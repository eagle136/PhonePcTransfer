package com.phonepctransfer.pc;

import java.io.BufferedInputStream;
import com.phonepctransfer.pc.LinkReceive.DirRx;
import com.phonepctransfer.pc.LinkReceive.Rx;
import com.phonepctransfer.pc.LinkReceive.VerifyState;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * PC 端 TCP 链路（与 Android 端 net/Link.kt 同协议、同去重规则）。
 *
 *  - 监听 28765（v2.1 起从 8765 迁移：MIUI scene-daemon 占 127.0.0.1:8765），
 *    同时按候选 IP 主动连手机（覆盖手机开热点的场景）
 *  - 只在收到 hello 后认定会话；重复会话按 deviceId 去重，收敛规则 preferOutgoing = (myId < peerId)
 *  - 15s ping，35s 无收包判死
 *
 * 传输性能（v2，2026-09-13）：
 *  - 文件分片 **1MB 裸二进制帧**（v1 是 256KB base64+JSON，33% 膨胀 + 大字符串编解码，
 *    实测只能跑出无线带宽 35% 左右）
 *  - 发送端 **双 4MB RAM 缓冲**交替读源（后台线程读盘，与网络发送重叠）
 *  - 接收端 **双 4MB RAM 缓冲**交替存放：一块填满切另一块继续收，满块交给独立落盘线程；
 *    传输结束剩余不满一块直接落盘
 *  - **断点续传**：开传前 file_query 询问对端半成品偏移，从偏移处继续
 *  - **文件夹传输**：dir_meta 声明目录，成员文件带 relPath/dirId，气泡聚合进度
 */
public final class Link {

    public enum State { STOPPED, IDLE, CONNECTING, CONNECTED }

    public interface Listener {
        void onState(State s, String peer);

        /** 已连接设备集合发生变化（连上/断开/去重）——设备列表据此重新打点 */
        default void onPeersChanged() {
        }

        void onMessageAdded(Msg m);

        void onMessageUpdated(Msg m);

        /**
         * 一条消息被移除（文件被任一方取消）。
         * 实现方应把该条从界面移除，并从本地历史/待发队列里删掉 —— 取消后两端都不再显示。
         */
        default void onMessageRemoved(String mid) {
        }

        /**
         * 历史同步（hsync）一批补齐完成：从对端补入了 count 条消息。
         * 实现方借此重排聊天区（补入的旧消息是按到达顺序追加的）。
         */
        default void onHsyncApplied(String ip, int count) {
        }

        /**
         * 一条消息在时间线上被移到「最新一条」（v2.7）。
         * 当前只有「数据流量被拒后重试成功」这一条路径：用户要求同意后把那条挪到最下面。
         */
        default void onMessageMoved(String mid) {
        }

        /** 手机推来的剪贴板正文，需要写入系统剪贴板 */
        void onClipToClipboard(String text);

        void onLog(String s);

        // ==================== 语音通话回调 ====================
        default void onCallInvite(String peerIp, String name) {
        }

        default void onCallAccept(String peerIp) {
        }

        default void onCallReject(String peerIp) {
        }

        default void onCallBusy(String peerIp) {
        }

        default void onCallEnd(String peerIp, String reason) {
        }

        default void onCallCancel(String peerIp) {
        }

        /**
         * 收到一帧对端音频（**网络读线程**回调）。
         *
         * @param ts 发送端的**通话相对**时间戳（ms，对端起呼=0）；只可对本端视频帧同轴比较，
         *           不可与本端墙钟比大小（两端各自零点，见 {@link CallClock}）
         */
        default void onCallAudio(String peerIp, int seq, long ts, byte[] data, int len) {
        }

        // ==================== 视频通话回调 ====================
        /**
         * 视频呼叫请求（带协商）。默认回落到纯语音版，避免实现方漏掉来电。
         *
         * @param af   对端期望的语音帧长（ms）；0 = 对端是老版本，未声明 → 用 200ms 兼容模式
         * @param mport 对端媒体端口（任务单 2.4 / 方案 §4.2）；0 = 未声明 → 只能走带内档
         * @param mudp  对端媒体端口是否 UDP；false = 未声明
         * @param msid  对端为本次通话带来的随机 token；空 = 未声明
         */
        default void onCallInviteVideo(String peerIp, String name, boolean vid, int lv, int af,
                                       int mport, boolean mudp, String msid) {
            onCallInvite(peerIp, name);
        }

        /** 视频接听应答（带档位、语音帧长与媒体通道协商），字段语义同 {@link #onCallInviteVideo} */
        default void onCallAcceptVideo(String peerIp, boolean vid, int lv, int af,
                                       int mport, boolean mudp, String msid) {
            onCallAccept(peerIp);
        }

        /**
         * 振铃期的**逐档媒体协商**（任务单 2.5 / 方案 §4.3① 的「交换」）：对端把它当前档的
         * 本端三元组带过来。默认实现什么都不做（老实现方不会因此漏掉任何通话事件）。
         *
         * @param msid 空 = 对端未声明（老端口径）⇒ 本端只能落带内档
         * @param mport 0 且 msid 非空 = 对端已落带内档（第五档），本端不必再试后面几档
         * @param mtier 对端当前档序号；⚠ 两端档位序列可能不同（安卓跳过两个 443 档），
         *              只用于日志，**不是**连通性判据
         */
        default void onCallMedia(String peerIp, int mport, boolean mudp, String msid, int mtier) {
        }

        /**
         * 收到一帧对端视频（**网络读线程**回调）。
         * 实现方只能入队，禁止在这里起动/重启 ffmpeg 进程 —— 会阻塞读线程 → 对端 35s 判死掉线。
         *
         * @param ts 与同通话音频帧同零点的**通话相对**毫秒（发送端侧）
         */
        default void onVideoFrame(String peerIp, int seq, long ts, int flag, byte[] data, int len) {
        }

        /**
         * 编码参数变更（codec/w/h 有变 → 重启 ffmpeg）。
         *
         * @param front 对端摄像头是否**前置**（`video_cfg.cam`，2026-09-19 加；
         *              老对端不带此键 = 后置）。仅影响**默认显示角**：
         *              对端前置时默认多转 180°（= 用户点两次旋转钮），见 {@code CallManager}。
         */
        default void onVideoCfg(String peerIp, String codec, int w, int h, int fps, int br, int rot,
                                boolean front) {
        }

        /**
         * 对端（视频接收方）发回的健康反馈。
         *
         * @param lat      对端「入队→喂解码器」的排队时延 ms（真实值）
         * @param dropping 对端此刻是否正处于丢帧期（任务单 1.3：与 lat 分开的独立标志）
         */
        default void onVideoFb(String peerIp, int fps, int drop, int lat, boolean dropping, int kbps) {
        }

        default void onVideoReq(String peerIp, boolean on) {
        }
    }

    /** 对端能力（hello 协商所得）。 */
    public static final class PeerCaps {
        volatile boolean vid;
        volatile boolean cam;
        /** 对端支持历史同步（hsync，v2.3 起）。老端不带该字段 → false → 不做历史补齐 */
        volatile boolean hs;
        /** 对端支持「消息删除同步」（msg_del，v2.4 起）。老端不带该字段 → false → 删除不跨端 */
        volatile boolean hd;
        /** 对端支持「文件夹完整性校验」（dir_meta.list / dir_end / dir_missing，v2.6 起） */
        volatile boolean dl;
        /** 对端支持「数据流量确认」（data_ask / data_ans，v2.7 起） */
        volatile boolean dm;
        volatile java.util.List<String> vcodec = new java.util.ArrayList<String>();

        public boolean vid() {
            return vid;
        }

        public boolean cam() {
            return cam;
        }

        public boolean hs() {
            return hs;
        }

        public boolean hd() {
            return hd;
        }

        /** 对端支持文件夹完整性校验（v2.6）：本端才敢发 dir_meta.list / dir_end */
        public boolean dl() {
            return dl;
        }

        /** 对端支持数据流量确认（v2.7）：本端才敢发 data_ask 并等它的 data_ans */
        public boolean dm() {
            return dm;
        }

        public java.util.List<String> vcodec() {
            return vcodec;
        }
    }

    /**
     * 本端能力（握手时广播）。PC 端**恒为「只收不发」**：
     * vid=true（能收视频）、cam=false（无摄像头、绝不主动发起视频）。
     */
    volatile boolean localVidCap = true;
    volatile java.util.List<String> localVCodecs = new java.util.ArrayList<String>();

    /** 由 PcMain 注入本机可解码的编码列表（取决于 ffmpeg 是否可用） */
    public void setLocalVideoCaps(java.util.List<String> vcodecs) {
        this.localVCodecs = vcodecs == null ? new java.util.ArrayList<String>() : vcodecs;
    }

    /**
     * 本端是否"能收视频"。由 PcMain 在 start() 之前设定：
     * ffmpeg 找不到、或用户关了 config.json.videoEnabled → false。
     *
     * 广播 vid=false 的效果是：Android 侧「视频」按钮直接置灰 ——
     * 比"能拨通但看不到画面"友好得多。**必须在 link.start() 之前设好**，
     * 因为 hello 在会话建立时就发出去了，之后改不会自动重发。
     */
    public void setLocalVidCap(boolean v) {
        this.localVidCap = v;
    }

    volatile Listener listener;
    private Clip clip;
    Store store;

    /**
     * 「窗口不可见（隐藏进托盘）」判据（PcMain 注入 = `() -> !win.isVisible()`，
     * 默认 false = 恒按前台处理）。
     * true 且对端**不是已知设备**（握手过 + 有消息/文件往来，见 Store.isTrustedAddr）
     * → adopt 拒接该入向连接（2026-10-01 与安卓端同规则，防托盘后台被陌生设备恶意连入）。
     */
    public volatile java.util.function.BooleanSupplier windowHidden = () -> false;

    // ==================== 2.12 / D16：文件发送的排他投票表 ====================

    /** 票 ①：通话中（**仅 TALKING**，接通后；由 CallManager 的状态迁移写） */
    public static final int VOTE_IN_CALL = 0;
    /** 票 ②：原本"没网络"（发送入口发现无会话时写，握手完成清） */
    public static final int VOTE_NO_NETWORK = 1;
    /** 票 ③：原本"被拒绝"（数据流量确认被拒时写，清零点 = 用户在气泡上手动重试） */
    public static final int VOTE_CONSENT_DENIED = 2;

    private volatile boolean voteInCall;
    private volatile boolean voteNoNetwork;
    private volatile boolean voteConsentDenied;

    /**
     * 2.12 / D16：文件与目录发送的**排他投票**，裁决 = 三票取最大值（任一为 1 即 1）。
     * 1 的时候：新条目**不入网**、改走既有待发台账排队；在传的那一条在下一个分片边界就地暂停。
     * ⚠ 与 2.11 的 {@code AvTransport.aggregationAllowed()} 是**两个不同的判据**（那条读媒体承载，
     *   本条读通话与发送状态），不许共用读口 —— 你点的 ② 里"通话中"这一票与五档无关，四档也投 1。
     */
    public boolean fileGateBlocked() {
        return voteInCall || voteNoNetwork || voteConsentDenied;
    }

    /**
     * 写一票。只在**裁决真的翻转**（有 1 ⇄ 全 0）时打一行日志（1.6 不周期打印），
     * 并且在全 0 的那一刻自动触发一次续发 —— 这就是你点的"结束拦截状态后，会自动触发一次续传请求"：
     * {@code flushPending → resendPendingFile → doSendFile} 顶部的 {@code negotiateResume}
     * 就是那**一次** file_query，对方应答就按回传的偏移续，不应答就退回暂停等手动"继续"。
     * ⚠ 票 ②（没网络）的解除点就是握手完成，而那里**本来就有** {@code flushPending(ip)}（既有行为）
     *   ⇒ 这一票解除时不再重复触发，免得同一条目排两次。
     */
    public synchronized void setFileVote(int which, boolean on) {
        boolean before = fileGateBlocked();
        switch (which) {
            case VOTE_IN_CALL:
                voteInCall = on;
                break;
            case VOTE_NO_NETWORK:
                voteNoNetwork = on;
                break;
            case VOTE_CONSENT_DENIED:
                voteConsentDenied = on;
                break;
            default:
                return;
        }
        boolean after = fileGateBlocked();
        if (before == after) {
            return;
        }
        log(after ? "[文件排他] 投票翻转 0→1 ⇒ 文件与目录不入网、在传的暂停（D16）"
                  : "[文件排他] 投票翻转 1→0 ⇒ 自动续发一次（D16）");
        if (!after && which != VOTE_NO_NETWORK) {
            // ⚠ 续发**不许跑在调用方的线程上**：本方法的调用点是 CallManager.endCall（挂断按钮
            //   = EDT），而 flushPending 会读 pending.jsonl（文件 I/O）并同步发文本/剪贴板帧。
            //   甩到既有的 UI 发送线程 = 与安卓端 flushPending() 的 io{} 同一口径。
            try {
                uiSendExec.execute(this::flushPending);
            } catch (Exception e) {
                log("解除文件排他后的自动续发被拒（多半是本机正在退出）：" + e);
            }
        }
    }

    /** 已知设备打点：与该对端有过消息/文件往来（握手打点在 onHello，这里只打「往来」位） */
    void trustContent(String ip) {
        Store st = store;
        if (st != null) {
            st.markTrustedContent(st.convKeyOf(ip));
        }
    }

    private volatile State state = State.STOPPED;
    private volatile String peerLabel = "";
    private volatile boolean running = false;

    private volatile ServerSocket serverSocket;
    final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<Session>();
    /** 已握手会话：deviceId → 会话。只用于「同一台设备的双连接」去重，不同设备各留各的 */
    final ConcurrentHashMap<String, Session> byPeer =
            new ConcurrentHashMap<String, Session>();

    // ==================== 保活重连（2026-09-16） ====================

    /**
     * 连接成功过就要**保持长连接**（用户定调）：通话 / 视频 / 文件传输结束后会话不能掉。
     * 只对「本端主动发起成功」的会话自动重连 —— 能连上说明这个方向可达（对端可能有
     * 防火墙只放行单向来连）；对端发起的入向会话不重试（本方向可能被挡，SYN 长时间
     * 悬挂），由对端的保活负责。每 15s 一次直到连上 / 程序退出。
     */
    private final java.util.concurrent.ScheduledExecutorService keepAliveExec =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "pp-keepalive");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>> keepAliveJobs =
            new ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>>();

    /** 每台设备「连上就断」的连续次数（快速失败退避判定用；握手成功即清零） */
    private final ConcurrentHashMap<String, Integer> keepAliveFastFails =
            new ConcurrentHashMap<String, Integer>();

    /** 会话「快速失败」判据：连上不足这个时长就被断开 */
    static final long FAST_FAIL_MS = 10_000L;
    /** 连续这么多次快速失败 → 保活重连退避（15s → 60s），避免「连上就断」风暴 */
    static final int FAST_FAIL_LIMIT = 3;

    // ==================== 文件发送队列（2026-09-21） ====================

    /**
     * 文件 / 文件夹发送队列：**单线程串行** —— 一次只传一个文件，前一个结束立刻上下一个。
     *
     * 为什么不每条文件各起一条线程（老做法）：批量发几十个文件时会同时开出几十条发送线程，
     * 互相抢 socket 写锁、争带宽，一有异常还各自关会话 —— 传输互相踩踏。
     *
     * 为什么不共用 UI 发送线程（uiSendExec）：那条要处理消息 / 剪贴板 / ack，被一个大文件
     * 占住会让聊天消息排队卡住。
     *
     * 为什么不影响通话与消息：音频 / 视频帧由采集、编码线程直接调 `sendAudio` / `sendVideo`，
     * 消息与 ack 走 `sendOrDrop` —— **都不进这条队列**，只按【帧粒度】抢同一个 socket 的写锁，
     * 所以文件排队再长也不会让语音 / 视频 / 消息"排在文件后面"。
     */
    private final java.util.concurrent.ExecutorService fileSendExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pp-file-send");
                t.setDaemon(true);
                return t;
            });

    /** 排队等待中的文件 / 文件夹条数（不含正在传的那一个） */
    int fileSendQueueDepth() {
        return (fileSendExec instanceof java.util.concurrent.ThreadPoolExecutor)
                ? ((java.util.concurrent.ThreadPoolExecutor) fileSendExec).getQueue().size() : 0;
    }

    /**
     * 文件 / 文件夹发送的**唯一入队口**：入队顺序即发送顺序，一次只有一个在传。
     * 只给「顶层发送入口」用（用户发送 / 重试 / 待发补发 / hsync 补图）；
     * 文件夹成员在队列线程内由 {@link LinkSend#doSendFolderInner} 同步顺序调用，不再各自入队。
     */
    void enqueueFileSend(String tag, Runnable task) {
        int depth = fileSendQueueDepth();
        if (depth > 0) {
            log(tag + " 已排队（前面还有 " + depth + " 个在等）");
        }
        try {
            fileSendExec.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    log(tag + " 队列任务异常: " + t);
                } finally {
                    // 队列空了（最后一个任务跑完）就补跑一次超龄半成品回收（plan 07）：
                    // 「取消帧丢失 + 会话一直不断」的孤儿条目不必等到下次启动
                    if (fileSendQueueDepth() == 0 && store != null) {
                        store.purgeStalePartials(PARTIAL_MAX_AGE_MS);
                    }
                }
            });
        } catch (Throwable t) {
            log(tag + " 入队失败: " + t);
        }
    }

    private void scheduleKeepAlive(final String ip) {
        if (keepAliveJobs.containsKey(ip)) {
            return;
        }
        // 「连上就断」退避（2026-09-21）：连续 FAST_FAIL_LIMIT 次存活不足 FAST_FAIL_MS 就断，
        // 说明有东西在秒杀会话（对端本地源不可读反复补发失败、对端立刻踢连接等）。
        // 此时按 5s/15s 一直敲只会把日志、网络和界面刷成「不停断开重连」→ 退避到 60s；
        // 真正连上并活下来后计数清零（见 onHello），立刻恢复正常节奏。
        Integer ff = keepAliveFastFails.get(ip);
        int fails = ff == null ? 0 : ff.intValue();
        boolean backoff = fails >= FAST_FAIL_LIMIT;
        long first = backoff ? 20L : 5L;
        long delay = backoff ? 60L : 15L;
        keepAliveJobs.put(ip, keepAliveExec.scheduleWithFixedDelay(() -> {
            try {
                if (!running) {
                    cancelKeepAlive(ip);
                    return;
                }
                // Already connected (any direction, incl. another address of the same device)
                if (sessionByGroup(ip) != null) {
                    cancelKeepAlive(ip);
                    return;
                }
                // Multiple addresses: try the last one first, then the rest of the device
                connectTo(ip, true, true);
            } catch (Throwable t) {
                Log.w("保活重连异常: " + t);
            }
        }, first, delay, java.util.concurrent.TimeUnit.SECONDS));
        log("对端 " + ip + " 会话断开，已启动保活重连（每 " + delay + "s"
                + (backoff ? "，已连续 " + fails + " 次快速断开 → 已退避" : "") + "）");
    }

    /**
     * 会话断开时记一次账：存活不足 {@link #FAST_FAIL_MS} 算「快速失败」，供保活重连退避判定。
     * 只在**本端主动发起过**的会话断开处调用；握手成功（onHello）会把计数清零。
     */
    private void noteSessionDrop(Session s) {
        if (System.currentTimeMillis() - s.connectedAt < FAST_FAIL_MS) {
            keepAliveFastFails.merge(s.remoteIp(), Integer.valueOf(1),
                    (a, b) -> Integer.valueOf(a.intValue() + b.intValue()));
        }
    }

    private void cancelKeepAlive(String ip) {
        java.util.concurrent.ScheduledFuture<?> f = keepAliveJobs.remove(ip);
        if (f != null) {
            f.cancel(false);
        }
    }

    /**
     * 当前「对话中」的对端 IP —— 设备列表点哪台就是哪台。
     *
     * 它同时是**主动发送的唯一目标**：文本/文件/剪贴板都只发给它，别的设备收不到。
     * 接收侧不看它：收到谁的内容就记在谁名下（由 TCP 会话本身的身份决定）。
     */
    private volatile String selectedIp = "";

    /** 选中设备在列表里的显示名（未连接时也能显示备注/广播名） */
    private volatile String selectedLabel = "";

    /**
     * 用户点击设备后挂起的**一次性**连接请求队列（同一台设备的候选地址，按优先级排好）。
     *
     * 连接工人按顺序试：某个地址连上并握手成功 → 清空队列、不再试后面的；
     * 全部失败 → 清空队列、**不重试**（要重试只能等对端被动连过来，或用户再点一次设备）。
     */
    private volatile java.util.List<String> pendingConnectIps;

    /**
     * 「点的那个地址连不上、同设备的另一个地址连上了」的回调（请求地址 → 实际连上的地址）。
     *
     * 上层据此把「当前对话地址」跟到真正连上的那个地址上。
     * ⚠ 由 pp-connect 线程回调：**不得在这里做 Swing 操作**，上层自行切 EDT。
     */
    volatile java.util.function.BiConsumer<String, String> onGroupFallback;

    /**
     * 生成一台设备的**候选地址顺序**：
     *  ① 传入的那个地址（用户点的 / 上次选中的）排第一；
     *  ② 同设备（同组键 = 同一 deviceId）的其它地址，按最近在线时间倒序。
     *
     * 为什么要有 ②（2026-09-19）：一台设备可能有多个地址（WiFi / 热点 / 换网段），
     * 用户点的可能正是它当前不在线的那个 —— 只试一个地址就直接判「连不上」，
     * 而同设备的另一个地址此刻是通的。试通一个就停，绝不并发、绝不循环重试。
     */
    java.util.List<String> groupCandidates(String ip, boolean tryGroup) {
        String v = ip == null ? "" : ip.trim();
        if (v.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        if (!tryGroup || store == null) {
            return java.util.Collections.singletonList(v);
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        out.add(v);
        String k = store.convKeyOf(v);
        if (k == null || k.isEmpty() || k.equals(v)) {
            return new java.util.ArrayList<String>(out);
        }
        java.util.List<Peer> others = new java.util.ArrayList<Peer>();
        for (Peer p : store.peers()) {
            if (p == null || p.ip == null || p.ip.isEmpty() || p.ip.equals(v)) {
                continue;
            }
            if (k.equals(store.convKeyOf(p.ip))) {
                others.add(p);
            }
        }
        java.util.Collections.sort(others, (a, b) -> Long.compare(b.lastSeenAt, a.lastSeenAt));
        for (Peer p : others) {
            out.add(p.ip);
        }
        return new java.util.ArrayList<String>(out);
    }

    /** 被动连上 / 已连上：撤掉同设备还没轮到的候选地址（不再白试） */
    private void clearPendingFor(String ip) {
        java.util.List<String> q = pendingConnectIps;
        String v = ip == null ? "" : ip.trim();
        if (q == null || v.isEmpty()) {
            return;
        }
        String k = store == null ? v : store.convKeyOf(v);
        boolean hit = false;
        synchronized (q) {
            for (String c : q) {
                if (c.equals(v) || (k != null && !k.isEmpty() && k.equals(store.convKeyOf(c)))) {
                    hit = true;
                    break;
                }
            }
        }
        if (hit) {
            pendingConnectIps = null;
            log("Device " + v + " reachable, drop remaining candidates " + q);
        }
    }

    /** 正在连接中的目标 IP（仅用于状态展示） */
    private volatile String connectingIp;

    /** 状态栏附注（「连接失败，点设备重试」这类） */
    private volatile String stateNote = "";

    private final Object reconnectLock = new Object();
    private Thread acceptThread;
    private Thread connectThread;
    private Thread heartbeatThread;

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setClip(Clip c) {
        this.clip = c;
    }

    public void setStore(Store s) {
        this.store = s;
    }

    public State state() {
        return state;
    }

    public String peerLabel() {
        return peerLabel;
    }

    /** 当前对话的对端 IP；未选中为空串 */
    public String selectedIp() {
        return selectedIp == null ? "" : selectedIp;
    }

    /** 当前对话设备在列表里的名字 */
    public String selectedLabel() {
        return selectedLabel == null ? "" : selectedLabel;
    }

    /** 指定 IP 的设备当前是否已连接（设备列表打点用，与选中与否无关） */
    public boolean isConnectedIp(String ip) {
        return sessionOf(ip) != null;
    }

    /** 已经连上的设备 IP 集合 */
    public java.util.Set<String> connectedIps() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        for (Session s : sessions) {
            if (s.handshaked && !s.closed) {
                out.add(s.remoteIp());
            }
        }
        return out;
    }

    /**
     * 按 IP 找已握手且活着的会话。
     *
     * <p>同一个 IP 上可能同时挂着**两台**设备的会话（2026-10-05 同 IP 多设备：两台设备
     * 各自连进来，或者一端连入一端连出）。此时「按 IP 找」天然有歧义 —— 必须优先返回
     * **身份与这个 IP 的组键一致**的那条（即 Store 认定的活动设备），否则会出现
     * 「列表显示 A 在线、切过去却是 B 的会话」。
     */
    Session sessionOf(String ip) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        String want = (store == null) ? "" : store.convKeyOf(ip);
        Session fallback = null;
        for (Session s : sessions) {
            if (!s.handshaked || s.closed || !ip.equals(s.remoteIp())) {
                continue;
            }
            if (!want.isEmpty() && want.equals(s.peerId)) {
                return s;
            }
            if (fallback == null) {
                fallback = s;
            }
        }
        return fallback;
    }

    /** 指定 deviceId 的设备是否有健康会话（同 IP 多设备的别名行打点用） */
    public boolean isPeerConnected(String devId) {
        if (devId == null || devId.isEmpty()) {
            return false;
        }
        Session s = byPeer.get(devId);
        return s != null && s.handshaked && !s.closed;
    }

    /**
     * 该地址上正连着的、但**不是** keepDevId 这台设备的对端名（同 IP 多设备冲突判定用）。
     * 没有冲突（该地址没会话、或仅 keepDevId 自己的会话）返回 null。
     */
    String foreignPeerAt(String ip, String keepDevId) {
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        for (Session s : sessions) {
            if (!s.handshaked || s.closed || !ip.equals(s.remoteIp())) {
                continue;
            }
            if (keepDevId != null && !keepDevId.isEmpty() && keepDevId.equals(s.peerId)) {
                continue;
            }
            return s.peerName.isEmpty() ? s.peerId : s.peerName;
        }
        return null;
    }

    /**
     * 断开某个地址上的会话（同 IP 多设备冲突时「断开之前的连接」的落点）。
     *
     * <p>只断这个地址上的**旧设备**会话，别的设备（别的 IP）会话一条都不动 ——
     * 多设备独立长连接的语义不能破。
     *
     * @param keepDevId 非空时保留这条设备身份的会话（通常是即将切换过去的那台）
     * @return 实际断开的会话条数
     */
    int disconnectAt(String ip, String keepDevId) {
        if (ip == null || ip.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (Session s : sessions) {
            if (!ip.equals(s.remoteIp())) {
                continue;
            }
            if (keepDevId != null && !keepDevId.isEmpty() && keepDevId.equals(s.peerId)) {
                continue;
            }
            log("断开 " + ip + " 上的旧连接（"
                    + (s.peerName.isEmpty() ? s.peerId : s.peerName) + "，切换设备）");
            s.close();
            sessions.remove(s);
            if (!s.peerId.isEmpty()) {
                byPeer.remove(s.peerId, s);
            }
            n++;
        }
        if (n > 0) {
            notifyPeers();
            refreshState();
        }
        return n;
    }

    /**
     * 按**设备**找会话：先按 IP 精确匹配，找不到时回落到**同组键**（同一 deviceId）的
     * 任意健康会话。
     *
     * 设备分组的语义就是「这几个地址是同一台机器」：用户在顶栏切到该设备的另一个
     * 地址（换 WiFi/热点）而会话仍建在原地址上时，按 IP 精确找会「找不到会话」——
     * 消息进待发、通话发不出去，明明这台机器就在线（与 Android 端同源问题）。
     */
    Session sessionByGroup(String ip) {
        Session s = sessionOf(ip);
        if (s != null) {
            return s;
        }
        if (ip == null || ip.trim().isEmpty() || store == null) {
            return null;
        }
        String k = store.convKeyOf(ip);
        if (k == null || k.isEmpty()) {
            return null;
        }
        for (Session ses : sessions) {
            if (ses.handshaked && !ses.closed && k.equals(store.convKeyOf(ses.remoteIp()))) {
                return ses;
            }
        }
        return null;
    }

    /** 指定**设备**是否可达（组内任意地址有健康会话） */
    public boolean isDeviceConnected(String ip) {
        return sessionByGroup(ip) != null;
    }

    /** 当前会话（主动发送的目标）；未连接返回 null。
     *  选中地址没会话时回落到同设备的另一个地址（见 [sessionByGroup]）。 */
    Session target() {
        return sessionByGroup(selectedIp);
    }

    /**
     * 当前对话**实际可达**的对端地址；未选中返回空串。
     *
     * 优先返回会话的 remoteIp：设备分组后用户在顶栏切了地址但会话仍建在原地址时，
     * 通话应答帧的源地址必须与 CallManager 记的 peerIp 一致，否则呼叫永远接不通。
     */
    public String currentPeerIp() {
        Session s = target();
        return s != null ? s.remoteIp() : selectedIp();
    }

    /**
     * 设备列表点选一台设备 → 设为当前对话目标。
     * 只改目标、不碰别的会话：连着的其他设备**不受任何影响**（多设备互不干扰）。
     */
    public void selectTarget(String ip, String label) {
        if (ip == null || ip.trim().isEmpty()) {
            return;
        }
        selectedIp = ip.trim();
        if (label != null && !label.isEmpty()) {
            selectedLabel = label;
        }
        refreshState();
    }

    /**
     * 点选设备并按需连接：已连着就只是切过去（不动连接）；没连才发起**一次**连接尝试。
     * 失败不重试 —— 要重试就再点一次设备（这是「只有点击才触发重连」的落点）。
     */
    public void selectAndConnect(String ip, String label) {
        selectTarget(ip, label);
        connectTo(ip);
    }

    /**
     * 发起一次连接尝试（不重试）。
     * 若该设备已有健康会话则什么都不做。
     */
    public void connectTo(String ip) {
        connectTo(ip, false);
    }

    /** 同上；`quiet=true` 不打「请求连接」类日志（保活重连用，避免每 15s 刷屏） */
    /**
     * 发起一次连接尝试（不重试）。
     * 若该设备已有健康会话则什么都不做。
     *
     * @param tryGroup true = 该设备有多个地址时**依次**试（见 [groupCandidates]）：
     *                 点的那个先试，连上并握手成功即停，后面的地址不再试；
     *                 全试完仍失败 → 队列清空、不再重试，直到对端被动连过来或用户再点一次。
     */
    /** 同上；`quiet=true` 不打「请求连接」类日志（保活重连用，避免每 15s 刷屏） */
    public void connectTo(String ip, boolean quiet) {
        connectTo(ip, quiet, true);
    }

    public void connectTo(String ip, boolean quiet, boolean tryGroup) {
        if (ip == null) {
            return;
        }
        String v = ip.trim();
        if (v.isEmpty()) {
            return;
        }
        Session ex = sessionOf(v);
        if (ex != null) {
            // 已有会话 ≠ 健康：TCP 半开（对端进程已死 / 网络切换）时 socket 仍"连着"，
            // 但再也收不到任何帧 —— 表现为"点了没反应 / 连不上"。超过一轮心跳周期仍无
            // 任何收包且我们发过 ping 未得 pong，就判失效、废弃旧会话并重连。
            long rxIdle = System.currentTimeMillis() - ex.lastRx;
            if (!(ex.pingOutstanding && rxIdle > STALE_RX_MS)) {
                if (!quiet) {
                    log("已连接 " + v + "，无需重连");
                }
                refreshState();
                return;
            }
            log("会话 " + v + " 已 " + rxIdle + "ms 无收包（疑似半开），废弃后重连");
            ex.close();
            sessions.remove(ex);
            if (!ex.peerId.isEmpty()) {
                byPeer.remove(ex.peerId, ex);
            }
            notifyPeers();
        }
        // 同设备的另一个地址已经连着 → 这台设备已可达，不用再拨（见 sessionByGroup）
        if (sessionByGroup(v) != null) {
            if (!quiet) {
                log("Already connected to " + v + " via another address of the same device");
            }
            refreshState();
            return;
        }
        java.util.List<String> cands = groupCandidates(v, tryGroup);
        pendingConnectIps = java.util.Collections.synchronizedList(
                new java.util.ArrayList<String>(cands));
        stateNote = "";
        if (!quiet) {
            if (cands.size() > 1) {
                log("Request connect " + v + " — try " + String.join(" -> ", cands)
                        + " (stop at first success, no retry)");
            } else {
                log("Request connect " + v + " (manual, no auto retry)");
            }
        }
        wakeUp();
    }

    private String myId() {
        return store == null ? "pc" : store.deviceId();
    }

    private String myName() {
        return store == null ? "PC" : store.deviceName();
    }

    private int port() {
        return store == null ? Protocol.DEFAULT_TCP_PORT : store.tcpPort();
    }

    // ==================== 会话 ====================

final LinkHsync hsync = new LinkHsync(this);
final LinkReceive rx = new LinkReceive(this);
final LinkSend send = new LinkSend(this);
final LinkCall call = new LinkCall(this);
    final LinkConsent consent = new LinkConsent(this);

        final class Session {
        final Socket socket;
        final boolean outgoing;
        final InputStream input;
        final OutputStream output;
        /**
         * 写锁：保证帧不交错。用 ReentrantLock 而不是 synchronized ——
         * 心跳线程需要 tryLock：写锁被文件传输占用时跳过本轮 ping，
         * 否则一条阻塞的会话会把整个心跳循环（其他所有会话的判死）拖住。
         */
        final ReentrantLock writeLock = new ReentrantLock();
        /** 对端 IP，构造时定下（socket 关了也能读，用来做归属与状态判定） */
        final String remoteIp;

        volatile String peerId = "";
        volatile String peerName = "";
        volatile boolean handshaked = false;
        /** 对端能力（由 hello 协商得到）。握手前是"全 false"——因此默认不会发视频帧。 */
        final PeerCaps caps = new PeerCaps();
        volatile long lastRx = System.currentTimeMillis();
        volatile long lastTx = System.currentTimeMillis();
        /** 会话建立时刻：连上不到 {@link #FAST_FAIL_MS} 就被断 = 有东西在秒杀会话（保活退避用） */
        final long connectedAt = System.currentTimeMillis();
        /** 已发出 ping 但之后未收到任何帧（含 pong）时为 true —— 判死依据，防自身 ping 刷 lastTx 骗过看门狗 */
        volatile boolean pingOutstanding = false;
        volatile boolean closed = false;

        String remoteIp() {
            return remoteIp;
        }

        Session(Socket s, boolean out) throws Exception {
            this.socket = s;
            this.outgoing = out;
            InetAddress a = s.getInetAddress();
            this.remoteIp = a == null ? "" : a.getHostAddress();
            // 大一点的收发缓冲：分片 1MB 起，默认 8KB 太小会白白多走系统调用
            try {
                s.setSendBufferSize(1 << 20);
                s.setReceiveBufferSize(1 << 20);
                // 诊断（2026-10-30）：读回内核实际值。注意 TCP 窗口缩放是握手时按当时的缓冲定死的，
                // 这里（accept/connect 之后）只能改缓冲、改不了缩放因子 —— 读回值仅供对照。
                log("[sock] " + remoteIp + " snd=" + s.getSendBufferSize()
                        + " rcv=" + s.getReceiveBufferSize() + (out ? " (主动)" : " (被动)"));
            } catch (Exception ignore) {
            }
            this.input = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            this.output = new BufferedOutputStream(s.getOutputStream());
        }

        void send(Map<String, Object> m) throws Exception {
            writeLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("会话已关闭");
                }
                Protocol.write(output, m);
                lastTx = System.currentTimeMillis();
            } finally {
                writeLock.unlock();
            }
        }

        /** 二进制文件分片：与 JSON 控制帧共用写锁，保证帧不交错 */
        void sendChunk(String mid, int seq, byte[] buf, int off, int len) throws Exception {
            writeLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("会话已关闭");
                }
                Protocol.writeChunk(output, mid, seq, buf, off, len);
                lastTx = System.currentTimeMillis();
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * 语音音频帧：与 JSON 控制帧/文件分片共用写锁，保证帧不交错。
         *
         * @return 本次写入耗时（ms）—— 上层（语音码率自适应）据此判断链路是否拥塞
         */
        long sendAudio(int seq, long ts, byte[] buf, int off, int len) throws Exception {
            writeLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("会话已关闭");
                }
                long t0 = System.nanoTime();
                Protocol.writeAudio(output, seq, ts, buf, off, len);
                lastTx = System.currentTimeMillis();
                return (System.nanoTime() - t0) / 1_000_000L;
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * 视频帧：与音频/文件分片共用写锁。
         *
         * @return 本次写入耗时（ms）——上层据此做「100ms 发送截止期」统计。
         */
        long sendVideo(int seq, long ts, int flag, byte[] buf, int off, int len) throws Exception {
            writeLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("会话已关闭");
                }
                long t0 = System.nanoTime();
                Protocol.writeVideo(output, seq, ts, flag, buf, off, len);
                lastTx = System.currentTimeMillis();
                return (System.nanoTime() - t0) / 1_000_000L;
            } finally {
                writeLock.unlock();
            }
        }

        /**
         * 心跳专用：**tryLock** 发 ping。返回 false = 写锁被文件传输占用
         * （本端正在向对端发数据，链路显然活着，本轮跳过）；
         * 返回 true = ping 已发出（或会话已关/发送异常转判死流程）。
         */
        boolean tryPing() {
            if (!writeLock.tryLock()) {
                return false;
            }
            try {
                if (closed) {
                    return true;
                }
                Protocol.write(output, Protocol.ping());
                lastTx = System.currentTimeMillis();
                return true;
            } catch (Throwable t) {
                close();  // 与 sendOrDrop 同语义：发送失败即判死
                return true;
            } finally {
                writeLock.unlock();
            }
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                socket.close();
            } catch (Exception ignore) {
            }
        }
    }

    // ==================== 生命周期 ====================

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        if (store != null) {
            store.purgeStalePartials(PARTIAL_MAX_AGE_MS);
        }
        log("链路启动：本机 " + myId() + " / " + myName() + " 监听 " + port()
                + "（协议 v" + Protocol.PROTO_VER + "，多设备独立长连接）");
        acceptThread = daemon(this::acceptLoop, "pp-accept");
        connectThread = daemon(this::connectLoop, "pp-connect");
        heartbeatThread = daemon(this::heartbeatLoop, "pp-heartbeat");
        refreshState();
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        for (java.util.concurrent.ScheduledFuture<?> f : keepAliveJobs.values()) {
            f.cancel(false);
        }
        keepAliveJobs.clear();
        // 发送线程池一并停：停止后排队任务不得再对着已关的 socket 写
        keepAliveExec.shutdownNow();
        fileSendExec.shutdownNow();
        uiSendExec.shutdownNow();
        try {
            ServerSocket ss = serverSocket;
            if (ss != null) {
                ss.close();
            }
        } catch (Exception ignore) {
        }
        serverSocket = null;
        for (Session s : sessions) {
            s.close();
        }
        sessions.clear();
        byPeer.clear();
        // 停止链路前取消的 mid 不会被发送循环处理到，清掉避免残留到进程结束
        //（重启后不应再有「幽灵取消」命中新传输）
        cancelled.clear();
        pendingConnectIps = null;
        wakeUp();
        setState(State.STOPPED, "");
        log("链路已停止");
    }

    /** 当前选中设备是否已连接 */
    public boolean isConnected() {
        return target() != null;
    }

    /**
     * 依据「会话集合 + 选中目标」重新算一次对外状态。
     *
     * 状态一律**推导**而不是命令式赋值：去重会主动关掉多余的那条连接，
     * 若靠"关连接时顺手置位"，两条同 IP 的会话会互相踩。推导天然不受顺序影响。
     */
    void refreshState() {
        State st;
        String label;
        if (!running) {
            st = State.STOPPED;
            label = "";
        } else if (target() != null) {
            st = State.CONNECTED;
            label = selectedLabel().isEmpty() ? selectedIp() : selectedLabel();
        } else if (connectingIp != null) {
            st = State.CONNECTING;
            label = "";
        } else {
            st = State.IDLE;
            label = stateNote;
        }
        setState(st, label);
    }

    /** 会话集合变化（连上/断开）时通知 UI 刷设备列表 */
    void notifyPeers() {
        Listener l = listener;
        if (l != null) {
            l.onPeersChanged();
        }
    }

    static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ==================== 监听 / 连接 ====================

    private void acceptLoop() {
        // 绑定重试：与 Android 端对称。端口被占（旧实例退出中/TIME_WAIT 等）时
        // 一次失败就永久退出会让本机「谁都连不上」，每 3s 重试直到成功或停止。
        ServerSocket ss = null;
        int attempt = 0;
        while (running && ss == null) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(port()));
                ss = s;
            } catch (Throwable t) {
                attempt++;
                log("监听 " + port() + " 端口被占用（" + t.getMessage()
                        + "），3 秒后第 " + attempt + " 次重试");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
        if (ss == null) {
            return;
        }
        try {
            serverSocket = ss;
            log("开始监听 TCP " + port() + (attempt > 0 ? "（重试 " + attempt + " 次后成功）" : ""));
            while (running) {
                Socket sk = ss.accept();
                sk.setTcpNoDelay(true);
                adopt(sk, false);
            }
        } catch (Throwable t) {
            if (running) {
                log("监听退出: " + t);
            }
        }
    }

    /**
     * 连接工人：**只在有人点了设备时才动一次**，失败不重试。
     *
     * 旧版是个 while 轮询循环（连不上就 1s→2s→5s 一直敲），三端在线时会互相刷日志、
     * 互相挤连接。现在改成"点一次、试一次"：请求由 connectTo() 投递，处理完即清空，
     * 之后线程只是睡着，不再产生任何网络行为 —— 这也是"不主动频繁发包"的一部分。
     *
     * 2026-09-19「一台设备多个地址」：请求是**一串**候选地址（见 [groupCandidates]），
     * 这里串行逐个试：**某个地址连上并握手成功 → 立刻停**，后面同设备的地址一律不再试；
     * 全部失败 → 队列清空、**不再重试**，直到对端被动连过来或用户再点一次设备。
     */
    private void connectLoop() {
        while (running) {
            final java.util.List<String> queue = pendingConnectIps;
            if (queue == null || queue.isEmpty()) {
                waitFor(1500);
                continue;
            }
            final String requested = queue.isEmpty() ? "" : queue.get(0);
            boolean done = false;
            while (running && !done) {
                // Queue taken over (peer connected passively / user clicked another device) → yield
                if (pendingConnectIps != queue) {
                    break;
                }
                String target;
                synchronized (queue) {
                    target = queue.isEmpty() ? null : queue.remove(0);
                }
                if (target == null) {
                    break;
                }
                if (store != null && !store.allowConnectOut()) {
                    stateNote = "已关闭主动连接（设置里可开启）";
                    log("已禁用主动连接，跳过 " + target);
                    refreshState();
                    break;
                }
                // 设备层已可达（刚有会话连上 / 被动接纳）→ 剩余候选全部作废
                if (sessionByGroup(target) != null) {
                    pendingConnectIps = null;
                    refreshState();
                    break;
                }
                connectingIp = target;
                stateNote = "";
                refreshState();
                Session s = null;
                try {
                    Socket sk = new Socket();
                    sk.setTcpNoDelay(true);
                    try {
                        sk.setSendBufferSize(1 << 20);
                        sk.setReceiveBufferSize(1 << 20);
                    } catch (Exception ignore) {
                    }
                    sk.connect(new InetSocketAddress(target, port()), 3000);
                    log("主动连接 " + target + ":" + port() + " 成功");
                    s = adopt(sk, true);
                    // TCP 通 ≠ 这台设备在线：地址可能被别的主机/服务占用。
                    // 等一帧 hello 握手确认「对端确实是我们的对端」才算试通；否则换下一个地址。
                    if (s != null && waitHandshake(s, HANDSHAKE_WAIT_MS)) {
                        log("地址 " + target + " 握手成功，停止尝试其余地址");
                        pendingConnectIps = null;
                        if (!target.equals(requested) && onGroupFallback != null) {
                            log("请求的是 " + requested + "，实际连上的是同设备的 " + target);
                            try {
                                onGroupFallback.accept(requested, target);
                            } catch (Throwable ignore) {
                            }
                        }
                        done = true;
                    } else {
                        log("地址 " + target + " 已连通但未握手（非本应用 / 对端无响应），换下一个地址");
                        if (s != null) {
                            s.close();
                            sessions.remove(s);
                            refreshState();
                        }
                    }
                } catch (Throwable t) {
                    boolean last;
                    synchronized (queue) {
                        last = queue.isEmpty();
                    }
                    log("连接 " + target + ":" + port() + " 失败：" + t.getMessage()
                            + (last ? "（该设备全部地址均不可达，不自动重试）" : "（换下一个地址）"));
                    if (s != null) {
                        try {
                            s.close();
                        } catch (Exception ignore) {
                        }
                    }
                } finally {
                    connectingIp = null;
                    refreshState();
                }
            }
            boolean mine = (pendingConnectIps == queue);
            if (mine) {
                pendingConnectIps = null;
            }
            if (!done && mine) {
                // 全部候选都失败：如实提示，不做任何重试（重连只能靠用户再点一次设备）
                stateNote = "连接失败，点设备重试";
                refreshState();
            }
        }
    }

    /** 单地址握手确认窗口：局域网内足够，超时即换下一个地址 */
    private static final long HANDSHAKE_WAIT_MS = 2500L;

    /** 等这条会话完成握手；@return true = 已握手（该地址确实是我们的对端） */
    private boolean waitHandshake(Session s, long ms) {
        long t0 = System.currentTimeMillis();
        while (running && System.currentTimeMillis() - t0 < ms) {
            if (s.handshaked) {
                return true;
            }
            if (s.closed) {
                return false;
            }
            waitFor(100);
        }
        return s.handshaked;
    }

    /** @return 新建的会话（供调用方等握手确认）；创建/首帧失败返回 null */
    private Session adopt(Socket socket, boolean outgoing) {
        Session s;
        try {
            s = new Session(socket, outgoing);
        } catch (Exception e) {
            log("创建会话失败: " + e);
            try {
                socket.close();
            } catch (Exception ignore) {
            }
            return null;
        }
        // 网段判定只在这一处：命中 → 不进 sessions、不发 hello、不启读线程，立即 close 放掉 FD，
        // 返回 null 让调用方按普通「连不上」处理。不打日志（命中痕迹不落 pc.log）。
        String rip = s.remoteIp();
        if (BlocklistIpv6.isHighRisk(rip) || (store != null && store.isBlockedAddr(rip))) {
            String key = store == null ? rip : store.convKeyOf(rip);
            if (store != null) {
                store.markBlockedGroup(key);
            }
            blockDebugReport(key);
            s.close();
            return null;
        }
        // 后台入向闸门（2026-10-01，与安卓端 incomingGate 同规则）：窗口隐藏进托盘时
        // **只放行已知设备**（握手过 + 有消息/文件往来）；陌生设备直接 close，
        // 不发 hello、不进 sessions。前台不受限。
        if (!outgoing && !rip.isEmpty() && windowHidden.getAsBoolean()
                && (store == null || !store.isTrustedAddr(rip))) {
            log("后台拒接陌生设备入向连接：" + rip);
            s.close();
            return null;
        }
        sessions.add(s);
        log("接纳" + (outgoing ? "主动" : "被动") + "连接 " + s.remoteIp()
                + "（当前在线 " + sessions.size() + " 条）");
        try {
            s.send(Protocol.hello(myId(), myName(), "windows", localVidCap, false, localVCodecs));
        } catch (Exception e) {
            s.close();
            sessions.remove(s);
            return null;
        }
        daemon(() -> readLoop(s), "pp-read-" + s.remoteIp());
        return s;
    }

    private void readLoop(Session s) {
        try {
            while (running && !s.closed) {
                long rfT0 = System.nanoTime();
                Protocol.Frame f = Protocol.readFrame(s.input);
                TxStats.read(System.nanoTime() - rfT0);
                if (f == null) {
                    break;
                }
                s.lastRx = System.currentTimeMillis();
                s.pingOutstanding = false;
                if (f instanceof Protocol.Frame.JsonF) {
                    handleFrame(s, ((Protocol.Frame.JsonF) f).j);
                } else if (f instanceof Protocol.Frame.Chunk) {
                    Protocol.Frame.Chunk c = (Protocol.Frame.Chunk) f;
                    rx.onChunk(s, c.mid, c.seq, c.data, c.len);
                } else if (f instanceof Protocol.Frame.Audio) {
                    Protocol.Frame.Audio a = (Protocol.Frame.Audio) f;
                    Listener l = listener;
                    if (l != null) {
                        l.onCallAudio(s.remoteIp(), a.seq, a.ts, a.data, a.len);
                    }
                } else if (f instanceof Protocol.Frame.Video) {
                    Protocol.Frame.Video v = (Protocol.Frame.Video) f;
                    Listener l = listener;
                    if (l != null) {
                        // ⚠ 只入队。解码在独立线程（见 VideoConsumer）——这里禁止 spawn ffmpeg。
                        l.onVideoFrame(s.remoteIp(), v.seq, v.ts, v.flag, v.data, v.len);
                    }
                }
            }
        } catch (Throwable t) {
            if (running && !s.closed) {
                log("读循环异常: " + t);
            }
        } finally {
            s.close();
            sessions.remove(s);
            if (!s.peerId.isEmpty()) {
                // 只在映射确实指向这条会话时才摘，避免把去重后保留的那条挤掉
                byPeer.remove(s.peerId, s);
            }
            log("会话结束 " + s.remoteIp()
                    + " peer=" + (s.peerName.isEmpty() ? "?" : s.peerName)
                    + (s.handshaked ? "" : " (未握手)"));
            rx.abortRxsFor(s.remoteIp());
            notifyPeers();
            // 保活重连：本端发起成功过的会话断开后自动重连（保持长连接；
            // 单向防火墙场景由可达的一端负责重连）。入向会话不重试。
            if (running && s.handshaked && s.outgoing) {
                noteSessionDrop(s);          // 「连上就断」记账（保活退避判定用）
                scheduleKeepAlive(s.remoteIp());
            }
            refreshState();
        }
    }

    /**
     * 心跳循环（每 5s 一次，见 {@link #STALL_SCAN_MS}）：ping + 假连接判定 + 停滞看门狗。
     * 停滞看门狗每 tick 顺带跑（不新开线程）：接收侧 30s 无进度 / 发送侧 30s 无新字节
     * → 停成「已暂停」，半成品保留，重连后自动续传；另回收闲置 10 分钟的未完成目录。
     */
    private void heartbeatLoop() {
        while (running) {
            waitFor(STALL_SCAN_MS);
            if (!running) {
                return;
            }
            // 停滞看门狗（5s 周期）：先收后发，再回收闲置目录
            rx.scanStalledRx();
            scanStalledSends();
            rx.pruneDirs();
            for (Session s : sessions) {
                if (s.closed) {
                    continue;
                }
                if (!s.handshaked && System.currentTimeMillis() - s.lastRx > 8000) {
                    log("握手超时，关闭会话");
                    s.close();
                    continue;
                }
                // 判死只看收包：自身 ping 只会刷 lastTx（内核 ACK 即"成功"），
                // 不能作为对端存活证据。pingOutstanding=true 表示上一轮 ping
                // 之后 15s 内连 pong 都没等到，两轮即 35s+ 无任何收包 → 判死。
                long rxIdle = System.currentTimeMillis() - s.lastRx;
                if (s.handshaked && s.pingOutstanding && rxIdle > 35000) {
                    log("对端 35s 无响应（ping 无 pong），判定断链");
                    s.close();
                    continue;
                }
                // 只有 ping 真发出去了才置 pingOutstanding；tryLock 失败（正在传文件）
                // 就跳过本轮 —— 否则「想发没发成」会把活跃会话误判死
                if (s.tryPing()) {
                    s.pingOutstanding = true;
                }
            }
        }
    }

    // ==================== 删除（v2.4 删除同步 / 清空）====================

    /**
     * 本机删除一条消息后清掉本地历史/待发/界面；{@code syncable} = 是否同时通知对端
     * （只有**文本 + 图片**才通知，见协议 §4.7；文件/文件夹不发 msg_del）。
     */
    public void deleteMessage(String peerIp, String mid, boolean syncable) {
        hsync.deleteMessage(peerIp, mid, syncable);
    }

    /**
     * 批量删除（「清空当前会话」用）：按 mid 逐条清掉本地历史/待发/界面，
     * 并把其中**参与同步的那部分**（文本 + 图片）一次性通知对端。
     *
     * <p>为什么不循环调 {@link #deleteMessage}：那样每条都要把历史文件整读整写一遍
     * （N 条 → N 次全文件重写，条数一多就是 O(N²) 的磁盘往返）。这里合并成一次遍历 +
     * 一帧 msg_del（按 {@code 200} 条分片，避免单帧过大）。
     *
     * @param allMids      要清掉的**全部** mid（含文件/文件夹/剪贴板等不同步的类型）
     * @param syncableMids 其中「文本 + 图片」的 mid（要发给对端的那部分）
     * @return 实际请求对端删除的条数（0 = 对端离线或老版本，只清了本机）
     */
    public int deleteMessages(String peerIp, List<String> allMids, List<String> syncableMids) {
        return hsync.deleteMessages(peerIp, allMids, syncableMids);
    }

    /**
     * 对端此刻能不能收到删除命令（在线 + 握手完成 + 支持 hd）—— 只看不发。
     * 「清空」用它决定提示语（对方在线 = 会一起清；离线 = 只清本机，下次可能被同步回来）。
     */
    public boolean canSendMsgDel(String peerIp) {
        return hsync.canSendMsgDel(peerIp);
    }

    // ==================== 帧处理 ====================

    /**
     * 帧分发。
     *
     * **归属完全由会话决定**：协议帧里没有「发给谁」的字段（两端一致、无需改动），
     * 因为每条 TCP 长连接天然只服务一个对端 —— 从哪条会话读到就属于哪台设备。
     * 所有新建的 Msg 都打上 `peerIp = s.remoteIp()`，于是 A→B 的内容只会落在 B 的会话里。
     */
    private void handleFrame(Session s, Map<String, Object> j) {
        String type = Json.str(j, "type", "");
        // 剪贴板相关帧只在局域网链路生效：非局域网对端发来的直接忽略（不写本机剪贴板、不应答），
        // 与本端出向门控对称。判据口径与安卓端 LanGate 同源。
        if (type.startsWith("clip") && !LocalIps.isLanPeer(s.remoteIp())) {
            return;
        }
        switch (type) {
            case "hello":
                onHello(s, j);
                break;
            case "ping":
                trySend(s, Protocol.pong());
                break;
            case "pong":
                break;
            case "busy": {
                // 只可能来自**旧版本**对端：它还是"同一时刻只服务一个对端"。新版本不再发这个帧。
                // 收到就如实告诉用户（点了设备但对方被旧版本占着），不做退避轮询 —— 本端本来就不重试。
                String holder = Json.str(j, "holder", "");
                log("对端（旧版本）返回忙" + (holder.isEmpty() ? "" : "：正与 " + holder + " 会话")
                        + "，本端不自动重试，稍后再点一次设备");
                stateNote = "对端忙（旧版本同时只服务一个对端），稍后再点一次";
                s.close();
                break;
            }
            case "text": {
                trustContent(s.remoteIp());   // 已知设备打点②：收到文本
                String mid = Json.str(j, "mid", newMid());
                // 统一按 mid 查重（2026-09-16）：待发补发/hsync 补齐可能重放同一条，
                // 历史里已有的 mid 不再生成第二个气泡，但 ack 照回（对端要靠它出队）
                if (store != null && store.hasMid(s.remoteIp(), mid)) {
                    log("文本 mid=" + mid + " 本机已有（重复投递），跳过");
                } else {
                    Msg m = new Msg();
                    m.mid = mid;
                    m.peerIp = s.remoteIp();
                    m.dir = Msg.DIR_PEER;
                    m.kind = Msg.KIND_TEXT;
                    m.text = Json.str(j, "text", "");
                    m.ts = Json.lng(j, "ts", System.currentTimeMillis());
                    emit(m);
                }
                trySend(s, Protocol.ack(mid, true));
                break;
            }
            case "clip": {
                // 剪贴板同步不是「发消息」：只写本机剪贴板，不生成聊天记录
                String text = Json.str(j, "text", "");
                if (!text.isEmpty() && listener != null) {
                    listener.onClipToClipboard(text);
                }
                break;
            }
            case "clip_state": {
                String text = Json.str(j, "text", "");
                if (!text.isEmpty()) {
                    if (listener != null) {
                        listener.onClipToClipboard(text);
                    }
                    log("已接收手机剪贴板 " + text.length() + " 字");
                } else {
                    // 握手协商第二步：手机剪贴板缓冲为空 → 反过来把电脑这边的推过去。
                    // 电脑侧也没有内容时 pushLocalClipIfAny 自然什么都不做。
                    log("手机剪贴板为空，改为推送电脑剪贴板");
                    pushLocalClipIfAny();
                }
                break;
            }
            case "clip_pull": {
                String text = clip == null ? "" : clip.buffer();
                trySend(s, Protocol.clipState(text == null ? "" : text,
                        text == null ? "" : Clip.sha1(text)));
                log("手机拉取剪贴板缓冲（" + (text == null ? 0 : text.length()) + " 字）");
                break;
            }
            case "file_meta":
            case "file_done":
                trustContent(s.remoteIp());   // 已知设备打点③：收到文件
                rx.onFileFrame(s, j);
                break;
            case "file_query":
                rx.onFileQuery(s, j);
                break;
            case "file_resume":
                rx.onFileResume(j);
                break;
            case "file_verify":
                rx.onFileVerify(j);
                break;
            case "dir_meta":
                trustContent(s.remoteIp());   // 文件夹成员同样算文件往来
                rx.onDirMeta(s, j);
                break;
            case "dir_end":
                rx.onDirEnd(s, j);
                break;
            case "dir_missing":
                rx.onDirMissing(j);
                break;
            case "file_cancel":
                rx.onRemoteFileCancel(Json.str(j, "mid", ""));
                break;
            case "sync_req":
                // 只补发**这台设备**的待发内容，不波及其他会话
                flushPending(s.remoteIp());
                break;
            case "ack": {
                // 「对端确认收到」应答。文件/文本在本地发送成功路径（file_done / send 返回）已置 OK，
                // 这里再走 updated 只会拿一个 kind 默认 TEXT、fileSize=0 的裸帧重绘气泡 ——
                // 把文件气泡污染成空白文本、还把 0B 写进历史（2026-09-13 实测）。故只记日志，不 emit。
                log("对端已确认收到 mid=" + Json.str(j, "mid", ""));
                break;
            }
            case "call_invite": {
                Listener l = listener;
                if (l != null) {
                    // 视频呼叫带 vid/lv，语音帧长带 af，媒体通道带 mport/mudp/msid（任务单 2.4）。
                    // 三项**缺省即回退**：老对端不带 ⇒ 0 / false / 空 ⇒ 本端只能落带内档（§4.2 / I11）。
                    l.onCallInviteVideo(s.remoteIp(), Json.str(j, "name", ""),
                            Json.bool(j, "vid", false), Json.integer(j, "lv", 1),
                            Json.integer(j, "af", 0),
                            Json.integer(j, "mport", 0), Json.bool(j, "mudp", false),
                            Json.str(j, "msid", ""));
                }
                break;
            }
            case "call_accept": {
                Listener l = listener;
                if (l != null) {
                    l.onCallAcceptVideo(s.remoteIp(),
                            Json.bool(j, "vid", false), Json.integer(j, "lv", 1),
                            Json.integer(j, "af", 0),
                            Json.integer(j, "mport", 0), Json.bool(j, "mudp", false),
                            Json.str(j, "msid", ""));
                }
                break;
            }
            case "call_media": {
                // 振铃期逐档协商（任务单 2.5）。老对端不会发这条；本端读到缺省即「未声明」。
                Listener l = listener;
                if (l != null) {
                    l.onCallMedia(s.remoteIp(),
                            Json.integer(j, "mport", 0), Json.bool(j, "mudp", false),
                            Json.str(j, "msid", ""), Json.integer(j, "mtier", -1));
                }
                break;
            }
            case "call_reject": {
                Listener l = listener;
                if (l != null) {
                    l.onCallReject(s.remoteIp());
                }
                break;
            }
            case "call_busy": {
                Listener l = listener;
                if (l != null) {
                    l.onCallBusy(s.remoteIp());
                }
                break;
            }
            case "call_end": {
                Listener l = listener;
                if (l != null) {
                    l.onCallEnd(s.remoteIp(), Json.str(j, "reason", ""));
                }
                break;
            }
            case "call_cancel": {
                Listener l = listener;
                if (l != null) {
                    l.onCallCancel(s.remoteIp());
                }
                break;
            }
            // ==================== 视频控制帧 ====================
            // 老对端不认识这三个 type → 走 default 只记日志，天然忽略。
            case "video_cfg": {
                Listener l = listener;
                if (l != null) {
                    // ⚠ 这里在网络读线程上：实现方只允许落字段 + 让解码线程自己重启进程，
                    //   绝不能在这里 spawn ffmpeg（会阻塞读线程 → 对端 35s 判死掉线）。
                    l.onVideoCfg(s.remoteIp(), Json.str(j, "codec", ""),
                            Json.integer(j, "w", 0), Json.integer(j, "h", 0),
                            Json.integer(j, "fps", 0), Json.integer(j, "br", 0),
                            Json.integer(j, "rot", 0),
                            // cam：1 = 前置，0/缺省 = 后置（老对端不带此键 → 与改造前一致）
                            Json.integer(j, "cam", 0) != 0);
                }
                break;
            }
            case "video_fb": {
                Listener l = listener;
                if (l != null) {
                    l.onVideoFb(s.remoteIp(), Json.integer(j, "fps", 0),
                            Json.integer(j, "drop", 0), Json.integer(j, "lat", 0),
                            // dropping：老对端不带此键 = false（与改造前行为一致）
                            Json.bool(j, "dropping", false),
                            Json.integer(j, "kbps", 0));
                }
                break;
            }
            case "video_req": {
                Listener l = listener;
                if (l != null) {
                    l.onVideoReq(s.remoteIp(), Json.bool(j, "on", false));
                }
                break;
            }
            // ==================== 数据流量确认（v2.7）====================
            // PC 恒不在运营商数据网络（有线/无线局域网），所以收到询问一律**自动同意**，
            // 不需要打扰用户。data_ans 是发起方阻塞等待的唤醒信号 → 直接投信箱，不排队。
            case "data_ask": {
                String dmid = Json.str(j, "mid", "");
                if (!dmid.isEmpty()) {
                    consent.deliverDataAns(dmid, true);
                    consent.replyDataAsk(s.remoteIp(), dmid, true);
                    log("对端请求用数据流量传输（kind=" + Json.str(j, "kind", "file")
                            + "）：本机非数据网络，已自动同意");
                }
                break;
            }
            case "data_ans": {
                consent.deliverDataAns(Json.str(j, "mid", ""), Json.bool(j, "ok", false));
                break;
            }
            // ==================== 历史同步（hsync，v2.3）====================
            // 处理要扫 history 文件、重发文件，不能卡网络读线程 → 甩 pp-hsync 工作线程
            //（与 Android 端对称。2026-09-16 修复：此前漏接分发，手机发来的 hsync_mids
            //  落 default 记「未知帧类型」，历史补齐整条链路在 PC 侧是死的）。
            case "hsync_mids":
            case "hsync_pull":
            case "hsync_msg": {
                final Session fs = s;
                final Map<String, Object> fj = j;
                final String ftype = type;
                daemon(() -> {
                    if ("hsync_mids".equals(ftype)) {
                        hsync.onHsyncMids(fs, fj);
                    } else if ("hsync_pull".equals(ftype)) {
                        hsync.onHsyncPull(fs, fj);
                    } else {
                        hsync.onHsyncMsg(fs, fj);
                    }
                }, "pp-hsync");
                break;
            }
            // ==================== 消息删除同步（v2.4）====================

            // 清历史文件可能不快，不卡网络读线程 → 工作线程
            case "msg_del": {
                final Session fs = s;
                final Map<String, Object> fj = j;
                daemon(() -> hsync.onRemoteMsgDel(fs, fj), "pp-msgdel");
                break;
            }
            default:
                log("未知帧类型: " + type);
        }
    }

    /**
     * 握手：把这条连接登记到它那台设备名下。
     *
     * **不同设备一律照收**（这是"多设备独立长连接、互不干扰"的核心）：
     * 旧版在已有活跃会话时会回 busy 把新设备顶掉 —— 那正是"三端同时在线反复连接"的根源。
     * 现在每台设备各占一条 TCP，收发完全独立，谁也挤不掉谁。
     *
     * 只对**同一台设备**的双连接去重（两端同时点连接就会各连一条）：
     * 规则 preferOutgoing = (myId < peerId)，两端各自独立算出同一结论，避免各留一条。
     */
    private void onHello(Session s, Map<String, Object> j) {
        String pid = Json.str(j, "id", "");
        if (pid.isEmpty()) {
            s.close();
            return;
        }
        s.peerId = pid;
        s.peerName = Json.str(j, "name", pid);
        s.handshaked = true;
        // ===== 能力协商：老端不带这些字段 → vid=false → 本端永不发视频帧（向后兼容硬闸门）=====
        s.caps.vid = Json.bool(j, "vid", false);
        s.caps.cam = Json.bool(j, "cam", false);
        java.util.List<String> vc = new java.util.ArrayList<String>();
        Object arr = j.get("vcodec");
        if (arr instanceof java.util.List) {
            for (Object o : (java.util.List<?>) arr) {
                String v = String.valueOf(o);
                if (!v.isEmpty()) {
                    vc.add(v);
                }
            }
        }
        s.caps.vcodec = vc;
        s.caps.hs = Json.bool(j, "hs", false);
        s.caps.hd = Json.bool(j, "hd", false);
        s.caps.dl = Json.bool(j, "dl", false);
        // 数据流量确认（v2.7）：老端不带该位 → 不发 data_ask，退回老口径
        s.caps.dm = Json.bool(j, "dm", false);
        log("握手成功 ← " + s.peerName + "(" + pid + ") " + (s.outgoing ? "出向" : "入向")
                + " @ " + s.remoteIp()
                + " caps[vid=" + s.caps.vid + ", cam=" + s.caps.cam + ", vcodec=" + vc
                + ", hs=" + s.caps.hs + ", hd=" + s.caps.hd + ", dl=" + s.caps.dl + "]");

        Session exist = byPeer.get(pid);
        if (exist != null && exist != s && !exist.closed) {
            boolean preferOutgoing = myId().compareTo(pid) < 0;
            boolean keepNew = (s.outgoing == preferOutgoing);
            log("同设备重复连接去重（" + pid + "）：" + (keepNew ? "保留新连接" : "保留旧连接"));
            if (!keepNew) {
                s.close();
                sessions.remove(s);
                notifyPeers();
                refreshState();
                return;
            }
            byPeer.remove(pid, exist);
            exist.close();
            sessions.remove(exist);
        }
        byPeer.put(pid, s);

        // 入向连接的设备**自动登记**进设备列表（2026-09-16）：
        // 对端有防火墙只能单向主动发起时，这条握手是 PC 端唯一的设备来源——
        // 不登记的话对方发来的消息/通话记录没有归属，界面完全看不到。
        // remark 传 null = 不覆盖用户已设置的备注。
        if (store != null && store.upsertPeer(s.remoteIp(), null, pid, s.peerName,
                System.currentTimeMillis())) {
            notifyPeers();
        }
        // ===== 设备身份绑定（2026-09-16）=====
        // 对方每次握手都自报一次自己的 id → 绑到**当前这个地址**上。
        // 同一 id 绑了多个地址 = 同一台设备有多个地址 → 自动成组
        //（备注、聊天记录、当前地址都按设备共用一份）。必须早于 hsync / flushPending。
        if (store != null && store.bindDeviceIfFree(s.remoteIp(), pid)) {
            log("设备身份绑定：" + s.remoteIp() + " → " + pid);
            notifyPeers();
        }
        // 已知设备打点①：握手成功 = 「连接过」。必须排在 bindDevice 之后，
        // 组键才是 deviceId 而不是裸 IP（与安卓端 markConnected 同时机）。
        if (store != null) {
            store.markTrustedConnected(store.convKeyOf(s.remoteIp()));
        }
        // 连上了 → 保活重连任务完成使命；同时清掉「连上就断」计数（这次确实连上并握手了）
        cancelKeepAlive(s.remoteIp());
        keepAliveFastFails.remove(s.remoteIp());
        // 这台设备已可达 → 撤掉还没轮到的候选地址（可能是被动连上，也可能是对端抢先拨通）
        clearPendingFor(s.remoteIp());

        // 第一台连上来的设备默认成为「当前对话」；**当前选中目标没有健康会话时也跟过去**——
        // 否则 selectedIp 停在旧 IP / 点过但连不上的地址（如扫描列表里的 IPv6 条目）上：
        // 状态恒显「未连接」、发消息进待发队列收不到（2026-09-16 修复，与 Android 端对称）。
        if (selectedIp == null || selectedIp.isEmpty() || sessionOf(selectedIp) == null) {
            selectedIp = s.remoteIp();
            selectedLabel = s.peerName;
        }
        notifyPeers();
        refreshState();
        // 2.12 / D16：这台设备可达 ⇒ 撤掉"没网络"那一票；紧跟着下面那句既有的 flushPending 负责续发
        //（setFileVote 对这一票**不**再自动 flush，避免同一条目排两次）
        setFileVote(VOTE_NO_NETWORK, false);
        // 只补发属于这台设备的待发内容
        flushPending(s.remoteIp());
        // 剪贴板只跟「当前对话的那台」协商，不往别的设备推
        if (isSelected(s)) {
            pullPeerClip();
        }
        // 历史同步（hsync，v2.3）：双方都支持（hs=1）时互报 mid 清单、各取所需。
        // startHsync 要扫 history 文件，不能卡网络读线程 → 工作线程（与 Android 端对称）。
        if (s.caps.hs) {
            daemon(() -> hsync.startHsync(s), "pp-hsync");
        }
    }

    /** 这条会话是不是当前对话目标 */
    private boolean isSelected(Session s) {
        return s != null && s.remoteIp().equals(selectedIp);
    }

    void setState(State st, String peer) {
        if (state == st && peerLabel.equals(peer)) {
            return;
        }
        state = st;
        peerLabel = peer;
        if (listener != null) {
            listener.onState(st, peer);
        }
    }

    // ==================== 文件接收（双 4MB 缓冲 + 断点续传） ====================

    /**
     * 一个正在接收的文件。
     * 双缓冲：数据先进 bufA/bufB（各 4MB），一块填满就交给落盘线程、自己切到另一块继续填；
     * 两块都未落完时才阻塞（天然背压）。digest 覆盖整个文件（含续传前缀）。
     */

    final Map<String, Rx> rxMap = new HashMap<String, Rx>();
    final Map<String, DirRx> dirMap = new HashMap<String, DirRx>();

    /** 保留多少条「已收尾」的目录记录（供同一 mid 重发时复用目录名，见 pruneDirs） */
    static final int MAX_KEEP_DIRS = 32;

    /**
     * 已完整收妥的**普通单文件**：mid → 落盘路径（有界 LRU，容量 {@link #MAX_KEEP_SAVED}）。
     *
     * <p>rxMap 只记**在途**，落盘一完成就摘除；此时同一 mid 再被投一次（手机待发队列补发
     * 撞上 PC 发起的 hsync 补齐），PC 会把它当新文件走 {@code createUniqueFile} 存成
     * 「xxx (1)」并再弹一条「文件已保存」—— 用户报的「发 2 个文件落了 5 个」就是这么来的
     * （2026-10-01 根源修）。重复判据必须跨越「已收完」这个时刻，所以单独存一份。
     *
     * <p>跨进程重启的重复投递由 {@link Store#savedFileOf} 兜（读历史里那条 status=OK 记录）。
     */
    static final int MAX_KEEP_SAVED = 512;

    /** accessOrder=true → get 也会改链表顺序，必须整体同步（两条会话读线程会并发） */
    final Map<String, String> savedFiles = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_KEEP_SAVED;
                }
            });

    /**
     * 见过的**文件夹成员**：成员 mid → 所属目录 mid（有界 LRU，建 Rx 时登记）。
     *
     * <p>{@code file_query} 帧不带 dirId，单看这一帧无法分辨「这条 mid 是普通单文件，
     * 还是某个文件夹的成员」。有了这张表，重复投递闸门才不会把 dir_missing 的缺失成员
     * 补发一并拦掉 —— 拦了就是「文件夹里少了文件」永远补不回来。
     */
    final Map<String, String> memberOfMid = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_KEEP_SAVED;
                }
            });

    /** 发送端等「对端文件夹核对回执」用：dirMid → 容量 1 的信箱（onDirMissing 投递） */
    final ConcurrentHashMap<String, ArrayBlockingQueue<java.util.List<String>>> dirMissingBox =
            new ConcurrentHashMap<String, ArrayBlockingQueue<java.util.List<String>>>();

    /** 发送端等 file_resume 应答用：mid → 容量为 1 的信箱 */
    final ConcurrentHashMap<String, ArrayBlockingQueue<Long>> resumeWaiters =
            new ConcurrentHashMap<String, ArrayBlockingQueue<Long>>();

    /** 正在发送中的 mid（含文件夹整体）：防止握手补发与在途传输重复发同一条 */
    final java.util.Set<String> inFlight =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 在途发送的归属：mid → 对端 IP（清空会话前用来判断该设备是否还有传输在途） */
    final ConcurrentHashMap<String, String> inFlightPeer =
            new ConcurrentHashMap<String, String>();

    /** 在途发送：mid → 最近一次写出新字节的时刻（发送停滞看门狗用） */
    final ConcurrentHashMap<String, Long> sendLastAt =
            new ConcurrentHashMap<String, Long>();

    /**
     * 被停滞暂停的单文件 mid（v2.8）：下一个进度帧（file_meta 重到 / 续传首个 chunk）
     * 到达时气泡回「发送中」并清掉标记。目录成员不用标记 —— 其首个续传 chunk 本来
     * 就把目录气泡发成 SENDING。
     */
    final java.util.Set<String> pausedMids =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * 发送停滞看门狗：在途 mid 30s 没写出新字节（对端半死、socket 写阻塞）时，
     * 关掉对应会话强制解阻塞 → 发送循环抛异常 → 现有 catch → 进待发队列 → 重连后自动续传。
     * 由心跳线程每 {@link #STALL_SCAN_MS} 顺带调用（不新开线程）。
     *
     * 注：本地磁盘读停滞 30s+ 会被误判关会话 —— 概率极低，后果仅是重传（正确性无损）。
     */
    private void scanStalledSends() {
        long now = System.currentTimeMillis();
        java.util.Set<String> staleIps = new java.util.HashSet<String>();
        for (String mid : inFlight) {
            Long t = sendLastAt.get(mid);
            if (t == null) {
                continue;
            }
            if (now - t.longValue() > STALL_TIMEOUT_MS) {
                String ip = inFlightPeer.get(mid);
                if (ip != null && !ip.isEmpty()) {
                    staleIps.add(ip);
                }
            }
        }
        for (String ip : staleIps) {
            Session s = sessionByGroup(ip);
            if (s == null || s.closed) {
                continue;
            }
            log("发送停滞 30s（" + ip + "），关会话解阻塞；重连后自动续传");
            s.close();
        }
    }

    /** 是否有与该设备相关的传输在途（发送中或接收中）——清空会话记录前必须确认空闲 */
    public boolean hasActiveTransfer(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        if (inFlightPeer.containsValue(ip)) {
            return true;
        }
        synchronized (rxMap) {
            for (Rx rx : rxMap.values()) {
                if (ip.equals(rx.peerIp)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ==================== 续传完成 MD5 核对（端到端） ====================

    /** 一次续传核对的会话状态：两端 MD5 各自后台算，谁先就绪都不阻塞 */

    /** 发送端在途核对表（普通单文件续传才登记） */
    final ConcurrentHashMap<String, VerifyState> verifyMap =
            new ConcurrentHashMap<String, VerifyState>();

    /** 核对超时：大文件 MD5 双方都要读一遍全盘，给足 2 分钟；超时只记日志不改传输结果 */
    static final long VERIFY_TIMEOUT_MS = 120_000L;

    /** 半成品过期时限：3 天没人续传的孤儿半成品（取消帧丢失/对端重传换 mid）启动时回收 */
    static final long PARTIAL_MAX_AGE_MS = 3L * 24 * 3600 * 1000;

    /** 判「假连接（半开）」的收包空闲阈值：超过一轮心跳周期（5s）仍无收包才算 */
    private static final long STALE_RX_MS = 20_000L;

    /**
     * 接收/发送停滞判定阈值：30s 无任何真实进度（对端半死但 TCP 心跳仍正常）。
     * 心跳仍正常是因为对端进程活着、只是发送线程卡死/被杀 —— 心跳帧保不了业务帧。
     */
    static final long STALL_TIMEOUT_MS = 30_000L;

    /** 停滞看门狗扫描周期（= 心跳周期）：心跳线程每 5s 顺带扫一次，不新开线程 */
    static final long STALL_SCAN_MS = 5_000L;

    /** 未完成目录闲置回收时限：10 分钟无活动即回收（防「没走到 dir_end 的目录永留」） */
    static final long DIR_STALE_MS = 10L * 60 * 1000;

    /** 启动落盘线程：从 pending 取满块写盘，注册表同步刷新（供断点续传跨重启找回半成品） */


    // ==================== 发送（目标 = 当前选中的设备） ====================

    /**
     * UI 发起的小帧（文本/剪贴板/同步）统一丢到这里发：EDT 只入队，socket 写不占 UI 线程
     * —— 对端读不动时界面不再跟着卡（Android 端有 sendExec，PC 这里补齐）。
     * 单线程保序：同一线程提交的文本按原顺序到达对端。
     */
    final java.util.concurrent.ExecutorService uiSendExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pp-ui-send");
                t.setDaemon(true);
                return t;
            });

    public Msg sendText(String text) {
        trustContent(selectedIp());   // 已知设备打点④：发出文本
        Msg m = new Msg();
        m.mid = newMid();
        m.peerIp = selectedIp();
        m.dir = Msg.DIR_SELF;
        m.kind = Msg.KIND_TEXT;
        m.text = text;
        Session s = target();
        if (s != null) {
            final Session fs = s;
            final Msg fm = m;
            final String ftext = text;
            uiSendExec.execute(() -> {
                try {
                    fs.send(Protocol.text(fm.mid, ftext));
                    emit(fm);
                } catch (Exception e) {
                    log("发文本失败，转待发: " + e);
                    fm.status = Msg.ST_PENDING;
                    emit(fm);
                    if (store != null) {
                        store.addPending(fm);
                    }
                }
            });
            return m;
        }
        m.status = Msg.ST_PENDING;
        emit(m);
        if (store != null) {
            store.addPending(m);
        }
        log(selectedIp().isEmpty()
                ? "尚未选择设备，文本先入待发队列"
                : "设备 " + selectedIp() + " 未连接，文本先入待发队列");
        return m;
    }

    /**
     * 剪贴板正文广播：发给**所有已连接设备**（剪贴板是同步通道不是消息，
     * 不 emit 气泡、不进历史 —— 与「切客户端后 CLIP 不显示」的加载过滤保持一致，
     * 避免「发出时是消息、切换后消失」的错觉。2026-09-15）。
     * 无已连接设备时暂存待发，连上后由 flushPending 自动补发。
     */
    public boolean sendClip(String text, String sha1) {
        java.util.List<Session> targets = new java.util.ArrayList<Session>();
        for (String ip : connectedIps()) {
            Session s = sessionOf(ip);
            // 剪贴板只走局域网链路：非局域网对端不推（留在待发队列，等它变成局域网地址）
            if (s != null && LocalIps.isLanPeer(s.remoteIp())) {
                targets.add(s);
            }
        }
        if (targets.isEmpty()) {
            log("剪贴板暂存待发（当前无已连接设备，连上后自动补发）");
            Msg m = new Msg();
            m.mid = newMid();
            m.peerIp = selectedIp();
            m.dir = Msg.DIR_SELF;
            m.kind = Msg.KIND_CLIP;
            m.text = text;
            m.status = Msg.ST_PENDING;
            if (store != null) {
                store.addPending(m);
            }
            return false;
        }
        for (Session s : targets) {
            final Session fs = s;
            final String ftext = text;
            final String fsha = sha1;
            final String fmid = newMid();
            uiSendExec.execute(() -> {
                try {
                    fs.send(Protocol.clip(fmid, ftext, fsha));
                    log("已推送剪贴板到 " + fs.remoteIp() + "（" + ftext.length() + " 字）");
                } catch (Exception e) {
                    log("剪贴板推送失败(" + fs.remoteIp() + "): " + e);
                }
            });
        }
        return true;
    }

    /** 已被本端取消的文件 mid 集合：发送循环每轮检查，命中即中断 */
    final java.util.Set<String> cancelled =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 文件夹发送状态：dirMid → 已完成的成员 relPath（重发时跳过） */
    final ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> dirSendState =
            new ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>>();

    boolean isCancelled(String mid, String dirId) {
        if (cancelled.contains(mid)) {
            return true;
        }
        return dirId != null && !dirId.isEmpty() && cancelled.contains(dirId);
    }
    public void sendFile(File f) { trustContent(selectedIp()); send.sendFile(f, false); }

    public void sendFile(File f, boolean preGranted) { trustContent(selectedIp()); send.sendFile(f, preGranted); }

    public void retryFileSend(Msg m) { send.retryFileSend(m); }

    // ---- 通话/视频发送（实现移至 LinkCall，这里保持对外 API 不变） ----
    public long sendAudioTo(String ip, int seq, long ts, byte[] data, int len) { return call.sendAudioTo(ip, seq, ts, data, len); }

    public boolean sendCallInvite(String name, String ip) { return call.sendCallInvite(name, ip); }

    public boolean sendCallInvite(String name, String ip, boolean vid, int lv) { return call.sendCallInvite(name, ip, vid, lv); }

    public boolean sendCallInvite(String name, String ip, boolean vid, int lv, int af) { return call.sendCallInvite(name, ip, vid, lv, af); }

    public boolean sendCallInvite(String name, String ip, boolean vid, int lv, int af,
                                  int mport, boolean mudp, String msid) {
        return call.sendCallInvite(name, ip, vid, lv, af, mport, mudp, msid);
    }

    public void sendCallAccept(String ip) { call.sendCallAccept(ip); }

    public void sendCallAccept(String ip, boolean vid, int lv) { call.sendCallAccept(ip, vid, lv); }

    public void sendCallAccept(String ip, boolean vid, int lv, int af) { call.sendCallAccept(ip, vid, lv, af); }

    public void sendCallAccept(String ip, boolean vid, int lv, int af,
                               int mport, boolean mudp, String msid) {
        call.sendCallAccept(ip, vid, lv, af, mport, mudp, msid);
    }

    public boolean sendCallMedia(String ip, int mport, boolean mudp, String msid, int tierOrdinal) {
        return call.sendCallMedia(ip, mport, mudp, msid, tierOrdinal);
    }

    public void sendCallReject(String ip) { call.sendCallReject(ip); }

    public void sendCallBusy(String ip) { call.sendCallBusy(ip); }

    public void sendCallEnd(String ip, String reason) { call.sendCallEnd(ip, reason); }

    public void sendCallCancel(String ip) { call.sendCallCancel(ip); }

    public PeerCaps peerCapsOf(String ip) { return call.peerCapsOf(ip); }

    public boolean peerVidCap(String ip) { return call.peerVidCap(ip); }

    public boolean startVideoOutgoing() { return call.startVideoOutgoing(); }

    public long sendVideoTo(String ip, int seq, long ts, int flag, byte[] data, int len) { return call.sendVideoTo(ip, seq, ts, flag, data, len); }

    public void sendVideoCfg(String ip, String codec, int w, int h, int fps, int br, int rot) { call.sendVideoCfg(ip, codec, w, h, fps, br, rot); }

    public void sendVideoFb(String ip, int fps, int drop, int lat, boolean dropping, int kbps) {
        call.sendVideoFb(ip, fps, drop, lat, dropping, kbps);
    }

    public void sendVideoReq(String ip, boolean on) { call.sendVideoReq(ip, on); }

    /** 请求对端立刻给一个 IDR（本端解码进程换代后只能从关键帧起解） */
    public void sendVideoKeyRequest(String ip) { call.sendVideoKeyRequest(ip); }


    /** 本机拒绝用数据流量时的备注（PC 恒不在数据网络，实际只会是远端拒绝/设备未连接） */
    static final String LOCAL_DENY_TEXT = "未同意使用数据流量";



    public int ensureDataConsent(String peerIp, String mid, String kind, String label, String name, long size) {
        return consent.ensureDataConsent(peerIp, mid, kind, label, name, size);
    }

    public void replyDataAsk(String peerIp, String mid, boolean ok) { consent.replyDataAsk(peerIp, mid, ok); }

    public void ensureDataConsentAsync(String peerIp, String kind, String label, java.util.function.IntConsumer onDone) {
        consent.ensureDataConsentAsync(peerIp, kind, label, onDone);
    }

    public String newConsentMid() { return consent.newConsentMid(); }

    /** 主动发文件夹。空文件夹也照发（fileCount=0），不再静默拒绝 */
    public void sendFolder(File dir) {
        trustContent(selectedIp());   // 已知设备打点⑤：发出文件夹（成员各自也算）
        List<File> files = new ArrayList<File>();
        List<String> emptyDirs = new ArrayList<String>();
        int[] skipped = new int[1];
        LinkNames.listFilesRecursive(dir, "", files, emptyDirs, skipped, 0);
        if (skipped[0] > 0) {
            log("注意：目录过深/过大或部分子树读取失败，" + skipped[0] + " 个分支未包含在传输中");
        }
        // 一个文件都没读到、却又有整块读失败：这是「读不到」而不是「空的」。
        // 老逻辑会照发一个 count=0 的文件夹，用户以为传成功了，实际什么都没传。
        if (files.isEmpty() && skipped[0] > 0) {
            log("文件夹内容读取失败（" + skipped[0] + " 个分支未读到），已中止发送 —— 请确认该目录仍可访问");
            return;
        }
        Msg m = new Msg();
        m.mid = newMid();
        m.peerIp = selectedIp();
        m.dir = Msg.DIR_SELF;
        m.kind = Msg.KIND_FILE;
        m.fileName = dir.getName();
        m.isDir = true;
        m.dirCount = files.size();
        long total = 0;
        for (File f : files) {
            total += Math.max(0L, f.length());
        }
        m.fileSize = total;
        m.filePath = dir.getAbsolutePath();
        m.progress = 0;
        Session fs2 = sessionByGroup(selectedIp());
        // 2.12 / D16：投票为 1 时文件夹同样**不入网**，与单文件走同一条排队分支（拦在入口）
        if (fs2 != null && !fileGateBlocked()) {
            m.peerIp = fs2.remoteIp();
            m.status = Msg.ST_PENDING;
            emit(m);
            final Msg item = m;
            final List<File> fl = files;
            final List<String> ed = emptyDirs;
            final int sk = skipped[0];
            // ⚠ 整段（确认 + 传输）进**串行队列**（2026-09-21 排队发送）：
            //   一次只传一个文件/文件夹，前一个完成立刻进入下一个
            enqueueFileSend("发送文件夹 " + item.fileName, () -> {
                int dc = consent.ensureDataConsent(item.peerIp, item.mid, DataConsent.KIND_FILE,
                        "发送文件夹", item.fileName, item.fileSize);
                if (dc != DataConsent.OK) {
                    send.denyFileByData(item, dc == DataConsent.REMOTE_REFUSED
                            ? DataConsent.peerRefusedText(DataConsent.KIND_FILE) : LOCAL_DENY_TEXT);
                    return;
                }
                item.status = Msg.ST_SENDING;
                emit(item);
                send.doSendFolder(dir, item, fl, ed, sk);
            });
        } else {
            m.status = Msg.ST_PENDING;
            emit(m);
            if (store != null) {
                store.addPending(m);
            }
            if (fs2 == null) {
                setFileVote(VOTE_NO_NETWORK, true);
                log("设备 " + (selectedIp().isEmpty() ? "（未选择）" : selectedIp())
                        + " 未连接，文件夹 " + m.fileName + "（" + files.size() + " 个文件）进入待发队列");
            } else {
                log("[文件排他] 通话期不入网：文件夹 " + m.fileName + "（" + files.size()
                        + " 个文件）排队，结束后自动续发（D16）");
            }
        }
    }

    /** 对端报缺失后最多补发几轮；再缺说明是真发不过去，如实报失败而不是死循环 */
    static final int DIR_RESEND_ROUNDS = 2;
    /** 等 dir_missing 回执的时长：对端要先把本轮全部成员落盘、算校验和才核对 */
    static final long DIR_MISSING_WAIT_MS = 60_000L;
    /** 无清单时对端不会回执，等短一拍即可 */
    static final long DIR_MISSING_WAIT_SHORT_MS = 5_000L;
    /** dir_meta 成员清单在线上最多多少字符（超了就不带，退化为「只按数量核对」） */
    static final int DIR_LIST_MAX_CHARS = 2 * 1024 * 1024;


    /** 文件夹发送进度聚合：字节累加、按 % 变化刷新气泡 */
    final class DirProgress {
        private final Msg item;
        private final long total;
        private long done = 0;
        private int lastPct = -1;

        DirProgress(Msg item, long total) {
            this.item = item;
            this.total = total;
        }

        synchronized void add(long n) {
            if (total <= 0) {
                return;
            }
            done += n;
            // 文件夹气泡的速率用**聚合字节数**采样（成员各自算会互相打断基线）
            Speeds.sample(item.mid, done);
            int pct = (int) ((done * 100) / total);
            if (pct > 99) {
                pct = 99;
            }
            if (pct != lastPct) {
                lastPct = pct;
                Msg u = new Msg();
                u.mid = item.mid;
                u.peerIp = item.peerIp;   // 归属（2026-09-21）
                u.dir = Msg.DIR_SELF;
                u.kind = Msg.KIND_FILE;
                u.fileName = item.fileName;
                u.fileSize = item.fileSize;
                u.isDir = true;
                u.dirCount = item.dirCount;
                u.progress = pct;
                u.status = Msg.ST_SENDING;
                if (listener != null) {
                    listener.onMessageUpdated(u);
                }
            }
        }
    }

    /**
     * 断点续传协商：问对端有没有这个文件的半成品。
     * @return 对端已有字节数（已对齐分片边界；0 = 从头传）
     */

    boolean sendOrDrop(Session s, Map<String, Object> j, String what, boolean quiet) {
        try {
            s.send(j);
            return true;
        } catch (Throwable t) {
            if (!quiet) {
                log(what + " 发送失败(" + t + ")，关闭该设备连接（不自动重连）");
            }
            s.close();
            sessions.remove(s);
            if (!s.peerId.isEmpty()) {
                byPeer.remove(s.peerId, s);
            }
            notifyPeers();
            refreshState();
            return false;
        }
    }

    /**
     * 发送一个文件（1MB 二进制分片 + 双 4MB 缓冲读源 + 断点续传）。
     *
     * @param relPath     文件夹成员时为目录内相对路径
     * @param dirId       所属目录气泡 mid（空 = 普通单文件）
     * @param agg         文件夹进度聚合器（普通单文件为 null）
     * @param throwOnFail true = 成员失败向上抛（整目录标失败）；false = 记待发队列自动重试
     */
    /**
     * 发送入口（防强杀版）：**开传前**先把条目落进持久化待发队列，成功后才移除。
     * 这样任何时刻被强杀（包括传输中途），重启后连接上对端都会由握手自动补发，
     * 并凭同一个 mid 命中对端半成品注册表从断点续传 —— 不再依赖「失败 catch 补登记」。
     */

    /**
     * 取消一个正在发送 / 待发送的文件或文件夹。两端同步：
     * ① 本端把该条从界面移除，并从待发队列与历史里删掉；
     * ② 发一帧 `file_cancel`，对端收到后中止接收、删除半成品、同样移除展示。
     */
    public void cancelFile(String mid) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        cancelled.add(mid);
        dirSendState.remove(mid);
        // 这条内容原本发给谁，取消帧就发给谁（而不是"当前选中的设备"）
        String dest = store == null ? "" : store.pendingPeerIp(mid);
        if (store != null) {
            store.removePending(mid);
        }
        Listener l = listener;
        if (l != null) {
            l.onMessageRemoved(mid);
        }
        Session s = sessionOf(dest);
        if (s == null) {
            s = target();
        }
        if (s != null) {
            trySend(s, Protocol.fileCancel(mid));
        }
        log("已取消文件传输 " + mid);
    }

    /** 通知手机「电脑剪贴板有更新」——只发长度/哈希/预览，正文等手机来拉。只发给当前对话的那台。 */
    public void sendClipNotify(int len, String sha1, String preview) {
        final Session s = target();
        uiSendExec.execute(() -> {
            if (s == null) {
                log("电脑剪贴板已缓存（当前设备未连接，连上后可通过「同步」拉取）");
                return;
            }
            if (!LocalIps.isLanPeer(s.remoteIp())) {
                return;
            }
            trySend(s, Protocol.clipNotify(len, sha1, preview));
            log("已通知 " + selectedLabel() + "：电脑剪贴板更新 " + len + " 字");
        });
    }

    public void pullPeerClip() {
        final Session s = target();
        uiSendExec.execute(() -> {
            if (s == null) {
                log("当前设备未连接，无法拉取剪贴板");
                return;
            }
            if (!LocalIps.isLanPeer(s.remoteIp())) {
                return;
            }
            trySend(s, Protocol.clipPull());
            log("已请求 " + selectedLabel() + " 的剪贴板");
        });
    }


    /**
     * 握手协商第二步：把电脑这边的剪贴板缓冲推给当前对话的设备。
     * 电脑侧缓冲为空时什么都不做（对应「自己的也没有内容就什么都不做」）。
     */
    private void pushLocalClipIfAny() {
        Session s = target();
        if (s == null) {
            return;
        }
        String text = clip == null ? "" : clip.buffer();
        if (text == null || text.isEmpty()) {
            log("电脑剪贴板也为空，本次不做任何事");
            return;
        }
        trySend(s, Protocol.clip(newMid(), text, Clip.sha1(text)));
        log("已推送电脑剪贴板 " + text.length() + " 字");
    }

    public void syncNow() {
        log("手动同步");
        final Session s = target();
        uiSendExec.execute(() -> {
            if (s != null) {
                trySend(s, Protocol.syncReq());
                flushPending(s.remoteIp());
                pullPeerClip();
            } else {
                log("当前设备未连接（点左侧设备连接）");
            }
        });
    }

    /** 手机剪贴板缓冲内容（手机端拉取时由本端 clip_pull 应答提供） */
    public String clipBuffer() {
        return clip == null ? "" : clip.buffer();
    }

    // ==================== 待发队列 ====================

    /** 只补发「当前选中设备」的待发内容 */
    public void flushPending() {
        flushPending(selectedIp());
    }

    /**
     * 补发待发队列里**属于指定设备**的那部分。
     *
     * 每条待发记录都记着自己原本要发给谁（`peer` 字段），所以：
     *  - 别的设备的条目直接跳过（不误投；也不会因为某台没连上而卡住其他设备）；
     *  - 同一台设备的条目按原顺序发送，相对次序不变。
     */
    public void flushPending(String onlyIp) {
        if (store == null) {
            return;
        }
        List<Msg> pending = store.loadPending();
        if (pending.isEmpty()) {
            return;
        }
        boolean any = false;
        for (Msg p : pending) {
            String dest = (p.peerIp == null || p.peerIp.isEmpty()) ? onlyIp : p.peerIp;
            // 按**设备**找会话：条目可能是从这台设备的另一个地址排进去的（换 WiFi/热点）
            Session s = sessionByGroup(dest);
            if (s == null) {
                continue;
            }
            if (!any) {
                log("补发 " + dest + " 的待发内容");
                any = true;
            }
            if (Msg.KIND_TEXT.equals(p.kind)) {
                try {
                    s.send(Protocol.text(p.mid, p.text));
                    store.removePending(p.mid);
                } catch (Exception e) {
                    continue;
                }
            } else if (Msg.KIND_CLIP.equals(p.kind)) {
                // 剪贴板只走局域网链路：非局域网对端继续留在待发队列
                if (!LocalIps.isLanPeer(s.remoteIp())) {
                    continue;
                }
                try {
                    s.send(Protocol.clip(p.mid, p.text, Clip.sha1(p.text)));
                    store.removePending(p.mid);
                } catch (Exception e) {
                    continue;
                }
            } else if (Msg.KIND_FILE.equals(p.kind)) {
                if (cancelled.contains(p.mid)) {
                    store.removePending(p.mid);
                    continue;
                }
                final String destF = dest;
                File path = new File(p.filePath);
                final List<File> files = new ArrayList<File>();
                final List<String> emptyDirs = new ArrayList<String>();
                final int[] skipped = new int[1];
                if (p.isDir) {
                    if (path.isDirectory()) {
                        LinkNames.listFilesRecursive(path, "", files, emptyDirs, skipped, 0);
                    }
                    if (files.isEmpty() && emptyDirs.isEmpty()) {
                        store.removePending(p.mid);
                        continue;
                    }
                } else {
                    if (!path.exists()) {
                        store.removePending(p.mid);
                        continue;
                    }
                }
                // v2.7：**补发与手动发送走同一道数据流量闸门** —— 重连后自动补发同样
                // 「每次使用都提醒一次」，绝不因为用户没盯着屏幕就偷偷花流量。
                // 确认会阻塞等对端 data_ans（最长 90s），而本方法跑在网络读线程上 ——
                // 在那里阻塞会卡死整个会话 → 整条补发进【串行队列】的专用线程。
                // 条目仍留在待发队列，直到发送成功（doSendFile/doSendFolder 内部移除）；
                // 若同 mid 已在途，inFlight 守卫会让本次直接返回。
                // 2026-09-21：与手动发送共用同一条队列 —— 补发不许与在途传输并行，
                // 否则一次握手就能同时开出几十条补发流（"几十个文件一起发就乱"的来源之一）。
                final File fpath = path;
                enqueueFileSend("补发 " + p.fileName,
                        () -> send.resendPendingFile(destF, p, fpath, files, emptyDirs, skipped[0]));
            }
        }
    }


    // ==================== 二进制工具 ====================

    /**
     * 发送端读源：双 4MB 缓冲交替，后台线程读盘，与网络发送重叠。
     * 空闲缓冲从 free 领取、填满放回 ready；消费端发完归还。
     * len=-1 表示 EOF，len=-2 表示读源失败。
     */
    static final class BlockReader {
        static final class Next {
            final byte[] buf;
            final int len;

            Next(byte[] buf, int len) {
                this.buf = buf;
                this.len = len;
            }
        }

        private final LinkedBlockingQueue<Next> ready = new LinkedBlockingQueue<Next>();
        private final LinkedBlockingQueue<byte[]> free = new LinkedBlockingQueue<byte[]>();
        private final Thread thread;

        BlockReader(InputStream ins, int blockSize) {
            final byte[] a = new byte[blockSize];
            final byte[] b = new byte[blockSize];
            free.add(a);
            free.add(b);
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (true) {
                            byte[] buf = free.take();
                            int off = 0;
                            while (off < blockSize) {
                                int n = ins.read(buf, off, blockSize - off);
                                if (n < 0) {
                                    break;
                                }
                                off += n;
                            }
                            if (off == 0) {
                                ready.put(new Next(null, -1));
                                break;
                            }
                            ready.put(new Next(buf, off));
                            if (off < blockSize) {
                                ready.put(new Next(null, -1));
                                break;
                            }
                        }
                    } catch (Throwable t) {
                        ready.offer(new Next(null, -2));
                    } finally {
                        try {
                            ins.close();
                        } catch (Exception ignore) {
                        }
                    }
                }
            }, "pp-src-read");
            thread.setDaemon(true);
            thread.start();
        }

        /** 阻塞取下一块；len=-1 EOF，len=-2 读源失败 */
        Next next() throws InterruptedException {
            return ready.take();
        }

        /** 消费完一块后归还给 free 队列；双缓冲防覆写的关键一步（见消费端注释） */
        void recycle(byte[] buf) {
            if (buf != null) {
                free.offer(buf);
            }
        }

        /** 取消：中断读线程（消费端提前退出时调用） */
        void cancel() {
            thread.interrupt();
        }
    }

    // ==================== 工具 ====================

    void emit(Msg m) {
        if (listener != null) {
            listener.onMessageAdded(m);
        }
    }

    void trySend(Session s, Map<String, Object> m) {
        try {
            s.send(m);
        } catch (Exception e) {
            log("发送失败: " + e);
        }
    }

    /**
     * 统一消息 id（2026-09-16 定版）：两端同构的 32 位十六进制全局唯一串。
     * 接收查重、待发队列、历史合并、hsync 补齐**全部以它为唯一判据**。
     */
    static String newMid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", Byte.valueOf(x)));
        }
        return sb.toString();
    }

    private void waitFor(long ms) {
        synchronized (reconnectLock) {
            try {
                reconnectLock.wait(ms);
            } catch (InterruptedException ignore) {
            }
        }
    }

    private void wakeUp() {
        synchronized (reconnectLock) {
            reconnectLock.notifyAll();
        }
    }

    void log(String s) {
        Log.i(s);
        Listener l = listener;
        if (l != null) {
            l.onLog(s);
        }
    }

    // ===== 开发期调试出口（定稿时整块删除）=====
    // 只走标准输出，绝不进 log(...) → 不进 pc.log、不进界面。发布包保持 false。
    private static final boolean BLOCK_DEBUG = false;

    private static void blockDebugReport(String key) {
        if (!BLOCK_DEBUG) {
            return;
        }
        System.out.println("设备已标记 " + key);
    }
}
