package com.phonepctransfer.pc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单文件日志（同时输出到控制台，便于 javaw 场景下排查）。
 *
 * **异步化（任务单 1.6）**：调用线程只做「new 一条 Entry + 入有界队列」，
 * 时间戳格式化、System.out、磁盘追加写全在 pp-log-writer 线程上成批做。
 * 旧写法每行都在**调用线程**上做一次文件追加写，还带一把全局锁 —— 通话时缺帧类
 * 日志最高 ~33 行/s，磁盘一抖就把播放线程一起拖住（方案 §2.4㉒：日志自己是抖动源）。
 *
 * 队列满（磁盘卡住）时丢**新行并计数**，writer 在下一批最前面标出被丢的条数；
 * 丢日志只影响观测，绝不影响通话与传输。JVM 退出前尽力排空（shutdown hook，2s 上限）。
 */
public final class Log {

    /** 待写队列容量（条）：够磁盘卡 2s 时全速日志用，超出直接丢 */
    private static final int QUEUE_CAPACITY = 2560;

    /** 单批最多写多少条（一次 Files.write 的拼接上限） */
    private static final int BATCH_LINES = 256;

    /** 没日志时 writer 最长阻塞等待（ms）—— 只影响落盘延迟，不影响调用线程 */
    private static final long FLUSH_WAIT_MS = 200L;

    private static volatile Path file;
    private static volatile boolean console = true;

    private static final ArrayBlockingQueue<Entry> QUEUE =
            new ArrayBlockingQueue<Entry>(QUEUE_CAPACITY);
    private static final AtomicLong droppedLines = new AtomicLong(0L);

    /**
     * writer 正拿着一个批次（已从队列取走、还没落盘）。退出钩子必须连这个一起等，
     * 否则"队列已空"会让人在最后一批（常常正是挂断汇总那一行）写盘途中把 JVM 抽走。
     */
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static volatile boolean writerStarted = false;

    private Log() {
    }

    public static void init(Path logDir) {
        try {
            Files.createDirectories(logDir);
            file = logDir.resolve("pc.log");
        } catch (IOException e) {
            file = null;
        }
        startWriter();
    }

    public static void setConsole(boolean v) {
        console = v;
    }

    public static void i(String msg) {
        write("I", msg);
    }

    public static void w(String msg) {
        write("W", msg);
    }

    public static void e(String msg) {
        write("E", msg);
    }

    public static void e(String msg, Throwable t) {
        write("E", msg + " :: " + t);
        if (t != null) {
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) {
                write("E", "    at " + st[i]);
            }
        }
    }

    private static void write(String level, String msg) {
        startWriter();
        if (!QUEUE.offer(new Entry(System.currentTimeMillis(), level, msg))) {
            droppedLines.incrementAndGet();
        }
    }

    /** 懒启动：第一条日志（可能在 {@link #init} 之前）就把 writer 拉起来，双检锁只建一次 */
    private static void startWriter() {
        if (writerStarted) {
            return;
        }
        synchronized (Log.class) {
            if (writerStarted) {
                return;
            }
            writerStarted = true;
            Thread t = new Thread(Log::writerLoop, "pp-log-writer");
            t.setDaemon(true);
            t.start();
            try {
                Runtime.getRuntime().addShutdownHook(
                        new Thread(() -> flushQuietly(2000L), "pp-log-flush"));
            } catch (Throwable ignore) {
                // 拿不到 shutdown 权限（极少见）：daemon 线程随 JVM 退出，尾部最多丢 200ms 的批次
            }
        }
    }

    /** 退出时排空：只等队列清空，落盘仍由 writer 做（两个线程抢一个文件没意义） */
    private static void flushQuietly(long waitMs) {
        long deadline = System.currentTimeMillis() + waitMs;
        while ((!QUEUE.isEmpty() || inFlight.get()) && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void writerLoop() {
        SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");
        Date when = new Date();
        while (true) {
            try {
                Entry first = QUEUE.poll(FLUSH_WAIT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                inFlight.set(true);
                try {
                    List<Entry> batch = new ArrayList<Entry>(BATCH_LINES);
                    batch.add(first);
                    QUEUE.drainTo(batch, BATCH_LINES - 1);
                    emit(batch, fmt, when);
                } finally {
                    inFlight.set(false);
                }
            } catch (Throwable t) {
                // writer 一死日志就永久丢，所以整轮兜住：本类对调用方没有任何异常语义
            }
        }
    }

    /** 一批：拼成一大串，先控制台再文件，整批一次 Files.write（一次 open/append/close） */
    private static void emit(List<Entry> batch, SimpleDateFormat fmt, Date when) {
        long lost = droppedLines.getAndSet(0L);
        String nl = System.lineSeparator();
        StringBuilder sb = new StringBuilder(64 * (batch.size() + 1));
        if (lost > 0L) {
            // 汇总必须带上被丢条数（任务单 1.6 自查项），不许只留最后一条
            sb.append("[log] 队列已满，丢弃 ").append(lost).append(" 行").append(nl);
        }
        for (int i = 0; i < batch.size(); i++) {
            Entry e = batch.get(i);
            when.setTime(e.timeMs);
            sb.append(fmt.format(when)).append(" [").append(e.level)
                    .append("] ").append(e.msg).append(nl);
        }
        String text = sb.toString();
        if (console) {
            System.out.print(text);
        }
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            Files.write(f, text.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignore) {
            // 日志失败不影响主流程：这批丢掉，不重试（重试会把 writer 卡在磁盘上）
        }
    }

    /** 一条待写日志：调用线程只填这三个字段，格式化在 writer 线程 */
    private static final class Entry {
        final long timeMs;
        final String level;
        final String msg;

        Entry(long timeMs, String level, String msg) {
            this.timeMs = timeMs;
            this.level = level;
            this.msg = msg;
        }
    }
}
