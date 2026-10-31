package com.phonepctransfer.pc;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

/**
 * PC 端后台程序入口。
 *
 * 形态：常驻系统托盘，剪贴板监听 + TCP 收发始终在后台跑；点托盘图标才弹出聊天窗口。
 * 需要管理员权限（用于放行 Windows 防火墙入站规则），由 run_admin.bat 自提权启动。
 */
public final class PcMain {

    private static volatile boolean exiting = false;

    // ===== 单实例锁 =====
    // 每台机器只允许跑一个实例：先抢 127.0.0.1:8767 守卫端口，抢不到说明已有实例在跑，
    // 就连上它的守卫端口发一条 SHOW 让它把主窗口提到前台，然后本进程直接退出
    // （旧版会继续跑成一个「没有 TCP 监听」的残废实例，托盘图标多一个还互相打架）。
    private static final int GUARD_PORT = 8767;
    private static java.net.ServerSocket guardSocket;

    /** 已运行实例收到 SHOW 时执行的动作（主窗口建好之后才注册） */
    private static volatile Runnable onShowCommand = () -> { };

    /**
     * 尝试成为唯一实例。
     *
     * @return true = 锁已到手，继续启动；false = 已有实例（已发 SHOW 唤起），本进程应退出
     */
    private static boolean acquireSingleInstance() {
        try {
            guardSocket = new java.net.ServerSocket();
            guardSocket.bind(new java.net.InetSocketAddress(
                    java.net.InetAddress.getByName("127.0.0.1"), GUARD_PORT));
            return true;
        } catch (java.io.IOException e) {
            // 守卫端口被占 → 八成是已有实例：通知它弹出主窗口
            java.net.Socket s = null;
            try {
                s = new java.net.Socket("127.0.0.1", GUARD_PORT);
                s.getOutputStream().write("SHOW\n".getBytes("UTF-8"));
                s.getOutputStream().flush();
            } catch (java.io.IOException ignore) {
                // 端口被别的程序占着且不是本应用：只能提示用户手动处理
                Log.w("单实例端口 " + GUARD_PORT + " 被其它程序占用且联系不上已有实例");
            } finally {
                if (s != null) {
                    try { s.close(); } catch (java.io.IOException ignore) { }
                }
            }
            return false;
        }
    }

    /** 守卫端口常驻监听：收到 SHOW → 把主窗口提到前台 */
    private static void startGuardListener() {
        if (guardSocket == null) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!exiting) {
                    java.net.Socket s = null;
                    try {
                        s = guardSocket.accept();
                        byte[] b = new byte[64];
                        int n = s.getInputStream().read(b);
                        String cmd = n > 0
                                ? new String(b, 0, n, "UTF-8").trim() : "";
                        if (cmd.contains("SHOW")) {
                            Log.i("收到二次启动的唤起请求，弹出主窗口");
                            javax.swing.SwingUtilities.invokeLater(
                                    () -> onShowCommand.run());
                        }
                    } catch (Throwable t2) {
                        if (!exiting) {
                            Log.w("单实例守卫异常: " + t2);
                        }
                        break;
                    } finally {
                        if (s != null) {
                            try { s.close(); } catch (java.io.IOException ignore) { }
                        }
                    }
                }
            }
        }, "pp-guard");
        t.setDaemon(true);
        t.start();
    }

    /**
     * mid → 归属对端 IP。updated 帧（进度/完成/失败）不带 peer，落历史时回这里查；
     * LRU 封顶 2000 条，终态更新总是紧跟 added 不远，正常不会被挤掉。
     */
    private static final java.util.Map<String, String> midPeer =
            new java.util.LinkedHashMap<String, String>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) {
                    return size() > 2000;
                }
            };

    private static void rememberPeer(String mid, String peerIp) {
        if (mid == null || mid.isEmpty() || peerIp == null || peerIp.isEmpty()) {
            return;
        }
        synchronized (midPeer) {
            midPeer.put(mid, peerIp);
        }
    }

    private static String peerOf(String mid) {
        if (mid == null || mid.isEmpty()) {
            return "";
        }
        synchronized (midPeer) {
            String v = midPeer.get(mid);
            return v == null ? "" : v;
        }
    }

    /**
     * 通话回调从**网络读线程**转移到独立线程执行：
     * 建/挂通话会做音频设备初始化（javax.sound 的 getLine/open 可能阻塞数秒），
     * 若直接在读线程里执行会卡死整条会话（收不到对端帧 → 对端判死掉线；
     * 挂断时再抢 VoiceEngine 的锁就死锁、UI 卡死无法关闭）。
     */
    private static void callAsync(Runnable r) {
        Thread t = new Thread(r, "pp-call-cb");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 装配视频画面出口：把 {@link CallManager.VideoUi} 的回调落到 Swing 的 {@link VideoWindow} 上。
     *
     * 线程约定：
     *  - {@code open/close} 可能在任意线程被调用 → 一律 {@code invokeLater} 到 EDT 建/销窗口；
     *  - {@code onFrame} 在 ffmpeg 的解码线程上高频调用 → **不 marshal**，
     *    直接交给 VideoWindow 做"最新帧优先"（它自己负责只在 EDT 重绘）；
     *  - 窗口按钮（挂断/静音）回调丢到独立线程，避免在 EDT 上做通话状态机动作。
     */
    private static void installVideoUi(final CallManager call, final Store store) {
        call.setVideoUi(new CallManager.VideoUi() {

            private volatile VideoWindow win;
            /** 窗口是在 EDT 上异步建的，监听可能先于窗口到达 → 先记下，建好即补挂 */
            private volatile CallManager.BoxListener pendingBox;

            @Override
            public void open(String peerLabel) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (win == null) {
                        VideoWindow w = VideoWindow.open(null, peerLabel);
                        // 这台设备上次手调的画面角度（按组键记；没记过 = 0）
                        w.setUserRotation(store.rotRemoteOf(store.convKeyOf(call.peerIp())));
                        w.setActions(new VideoWindow.Actions() {
                            @Override
                            public void onHangup() {
                                callAsync(call::hangup);
                            }

                            @Override
                            public void onToggleMute(boolean muted) {
                                callAsync(() -> call.setMuted(muted));
                            }

                            @Override
                            public void onRotate(int deg) {
                                // 记住这台设备的角度：只要不卸载客户端就一直沿用（同安卓端规则）
                                store.setRotRemoteOf(store.convKeyOf(call.peerIp()), deg);
                            }
                        });
                        win = w;
                    }
                    CallManager.BoxListener b = pendingBox;
                    if (b != null) {
                        win.setBoxListener(b);
                    }
                });
            }

            @Override
            public void onFrame(byte[] bgra, int w, int h) {
                VideoWindow x = win;
                if (x != null) {
                    x.onFrame(bgra, w, h);
                }
            }

            @Override
            public void setRotation(int rot) {
                VideoWindow x = win;
                if (x != null) {
                    x.setRotation(rot);
                }
            }

            @Override
            public void setStatus(String s) {
                VideoWindow x = win;
                if (x != null) {
                    x.setStatus(s);
                }
            }

            @Override
            public void setBoxListener(CallManager.BoxListener l) {
                pendingBox = l;
                VideoWindow x = win;
                if (x != null) {
                    x.setBoxListener(l);
                }
            }

            @Override
            public void close() {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    VideoWindow x = win;
                    win = null;
                    if (x != null) {
                        x.close();
                    }
                });
            }
        });
    }

    /**
     * 检测本进程是否以管理员（High Mandatory Level）运行。
     * Windows UIPI 会**静默拦截**从普通权限的 Explorer 向提权进程的文件拖放 ——
     * 拖拽功能看起来「完全没反应」，所以必须在日志里点破这件事。
     * （S-1-16-12288 = 高完整性；正常桌面进程是 S-1-16-8192 中完整性。）
     */
    private static void logIfElevated() {
        try {
            Process p = new ProcessBuilder("whoami", "/groups", "/fo", "csv", "/nh")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.Charset.forName("GBK")).toLowerCase();
            p.waitFor();
            if (out.contains("s-1-16-12288")) {
                Log.w("本进程以管理员权限运行：Windows 将拦截资源管理器的文件拖放"
                        + "（拖拽会毫无反应）。请改用非管理员方式启动（run.bat），"
                        + "防火墙规则只需用 run_admin.bat 加一次。");
            }
        } catch (Throwable ignore) {
            // 检测失败不影响启动（个别精简系统没有 whoami）
        }
    }

    public static void main(String[] args) {
        try {
            // ===== UI 缩放钉定（2026-09-29：1.25 → 1.0，必须是 main 里最先做的事）=====
            // 需求：按 100% 渲染 —— 1 逻辑 px = 1 物理 px，组件坐标/边框/字体底边全部落整数像素，
            // 消除分数 DPI 的抗锯齿抖动；宋体小字号（12~16px）内嵌点阵 1:1 命中，笔画最锐利。
            // Swing 本身 DPI-aware，会拿渲染缩放乘一切（字体/像素都是逻辑单位，比例不破，
            // 但物理观感跟着缩放变）→ 把渲染缩放钉在 1.0。
            // 读 config.json 的 uiScale（缺省 1.0；0/"system" = 跟随系统；旧值 1.25 自动迁移为 1.0），设
            // sun.java2d.uiScale —— ⚠ setProperty 必须发生在任何 AWT/Swing 类初始化之前
            // 才生效（JBR 25 实测：-D 与程序内 setProperty 均验证有效，见 tools/DpiProbe.java）。
            Path root = Store.defaultRoot();
            double uiScale = Store.readUiScaleOverride(root.resolve("config.json"));
            if (uiScale > 0) {
                System.setProperty("sun.java2d.uiScale", String.valueOf(uiScale));
            }
            // 文本抗锯齿：关（2026-09-29，与 100%+宋体清晰度改造配套）—— 小字号走宋体内嵌点阵，
            // 笔画锐利无灰边；大字号（>18px）走 outline，那几处单独显式开 AA（见 Ui.AALabel）。
            // ⚠ 同 uiScale，必须在任何 AWT/Swing 类初始化之前设置才生效。
            System.setProperty("awt.useSystemAAFontSettings", "off");
            System.setProperty("swing.aatext", "false");
            // 现代扁平视觉风格（圆角按钮/输入框/统一字体），必须在创建任何窗口前装
            Ui.install();
            Log.init(root.resolve("logs"));
            Log.i("==================== 手机电脑互传 PC 端启动 ====================");
            // 数据目录 = %APPDATA%\PhonePcTransfer（升级/重装不受影响）；程序目录另算。
            // 两个都打出来，排查「配置怎么没生效」「ffmpeg 找不到」时一眼看清。
            Log.i("数据目录: " + root.toAbsolutePath());
            Log.i("工作目录: " + Paths.get("").toAbsolutePath());
            Log.i("UI 缩放钉定: " + (uiScale > 0
                    ? "固定 " + uiScale + "（100% 整数像素渲染 + 宋体点阵；config.json uiScale=system 可改为跟随系统）"
                    : "跟随系统缩放"));
            logIfElevated();

            // ===== 单实例闸门：抢不到锁 = 已有实例在跑，唤起它然后退出 =====
            if (!acquireSingleInstance()) {
                Log.i("已有实例正在运行，已请求其弹出主窗口，本进程退出");
                return;
            }

            // ===== 桌面/开始菜单中文快捷方式（安装版才有；PowerShell 起进程要几百毫秒，
            // 放后台线程，别拖慢托盘图标出现。细节见 ensureDesktopShortcut）=====
            Thread shortcut = new Thread(PcMain::ensureDesktopShortcut, "pp-shortcut");
            shortcut.setDaemon(true);
            shortcut.start();

            final Store store = new Store(root);
            store.load();
            Log.i("本机标识: " + store.deviceId() + " / " + store.deviceName()
                    + "  TCP " + store.tcpPort() + "  UDP " + store.udpPort());
            Log.i("候选 IP: " + store.peerIps());

            // 主题（2026-09-27）：窗口还没建，只需换调色板 + UIManager 默认值；
            // 必须在 new ChatWindow 之前，否则首帧按浅色画完再切换会闪一下
            Ui.applyDark(store.darkTheme());
            Log.i("主题: " + (store.darkTheme() ? "深色" : "浅色"));

            // 启动清理：删掉 5 天没用过、且该设备还有别的地址可用的陈旧地址。
            // 只有两条地址的设备整体跳过；保底留最近使用的两条 + 当前使用的那条
            //（见 Store.pruneStaleIps，与 Android 端 Prefs.pruneStaleIps 同规则）。
            int pruned = store.pruneStaleIps(5, 2);
            if (pruned > 0) {
                Log.i("已清理 " + pruned + " 个 5 天未使用的设备地址");
            }

            // 历史封顶（plan/15）：单设备历史文件超 8MB 时读-合并-按 ts 留最近 2000 条重写；
            // 同时清掉孤儿缩略图缓存（mid 已不在任何设备历史 / 待发队列里的）
            int capped = store.capHistoryFiles();
            if (capped > 0) {
                Log.i("已封顶 " + capped + " 个超 8MB 的历史文件");
            }
            int orphanThumbs = store.pruneThumbCache();
            if (orphanThumbs > 0) {
                Log.i("已清理 " + orphanThumbs + " 个孤儿缩略图缓存");
            }

            final Link link = new Link();
            link.setStore(store);

            // ===== 视频能力协商（PC 端**只收不发**，R2）=====
            // 能不能收视频取决于有没有可用的 ffmpeg：没有就广播 vid=false。
            // 手机端据 hello.vid 决定「敢不敢发 0x03 视频帧」——闸门在 Link.sendVideoTo()，
            // 对老端/无能力端一个视频帧都不会发出去（否则对端 readFrame 抛 IOException 断链）。
            // 手机端「视频」按钮**不再按能力置灰**（灰按钮点了没反应，用户无法判断原因）：
            // 点下去由 toast 说明具体原因，不支持的会退回语音。
            final String ffPath = FfmpegDecoder.probe(store.ffmpegPath(),
                    FfmpegDecoder.bundledPath());
            final boolean videoOk = ffPath != null && store.videoEnabled();
            if (ffPath == null) {
                Log.w("未找到 ffmpeg：PC 端将无法接收视频画面。"
                        + "请把最小化 ffmpeg.exe 放到程序目录的 bin/ 下（安装版 = app/bin/），"
                        + "或在 config.json 的 ffmpegPath 指定路径");
            } else if (!store.videoEnabled()) {
                Log.i("config.json videoEnabled=false → 本端不接收视频");
            } else {
                Log.i("视频接收可用，ffmpeg = " + ffPath);
            }
            link.setLocalVidCap(videoOk);
            link.setLocalVideoCaps(videoOk
                    ? java.util.Arrays.asList("hevc", "h264")
                    : java.util.Collections.<String>emptyList());

            final ChatWindow win = new ChatWindow(link, store);

            // 后台入向闸门判据（2026-10-01，与安卓端 incomingGate 同规则）：
            // 窗口隐藏进托盘 = 后台 → 陌生设备的 TCP 连入一律拒接，已知设备放行；
            // 窗口可见 = 前台 → 不限。isVisible() 线程安全（pp-accept 线程直接读）。
            link.windowHidden = () -> !win.isVisible();

            // ===== 同设备多地址回落（2026-09-19，与安卓端对齐）=====
            // 点的是 A 地址、实际连上的是同一台设备的 B 地址 → 把「当前对话地址」跟到 B，
            // 否则顶栏/地址菜单回显的还是那个不通的 A。回调在 pp-connect 线程 → 转 EDT。
            link.onGroupFallback = (req, actual) ->
                    javax.swing.SwingUtilities.invokeLater(() -> win.onGroupFallback(req, actual));

            final CallManager call = new CallManager(link, store);
            win.setCallManager(call);
            installVideoUi(call, store);

            // 本机剪贴板变化：直接把正文推给手机（手机端为纯被动接收，不再主动拉取；
            // 2026-09-15 从 clip_notify「通知+手机来拉」改为 push 正文，配合安卓端新语义）
            final Clip clip = new Clip(new Clip.Listener() {
                @Override
                public void onClipChanged(String text, String sha1) {
                    win.setClipBuffer(text);
                    link.sendClip(text, sha1);
                }
            });
            link.setClip(clip);

            link.setListener(new Link.Listener() {
                @Override
                public void onState(Link.State s, String peer) {
                    String label;
                    if (s == Link.State.CONNECTED) {
                        label = "● 已连接 " + peer;
                        // 握手协商：连上就先拉一次手机剪贴板；
                        // 手机缓冲为空时它会回空 clip_state，我们再把电脑这边的推过去。
                        link.pullPeerClip();
                    } else if (s == Link.State.CONNECTING) {
                        label = "○ 正在连接…";
                    } else if (s == Link.State.IDLE) {
                        label = "○ 未连接（点左侧设备连接 · 监听 " + store.tcpPort() + "）";
                    } else {
                        label = "○ 未运行";
                    }
                    // 左栏底部只剩「接收目录」一行（2026-09-21 用户定调）：在线数 / 候选 IP
                    // 这类诊断信息不再上界面，所以这里不再拼 sub 文案（接收目录由左栏自己显示）。
                    win.setState(label, "");
                    Tray.setTooltip(label);
                }

                @Override
                public void onPeersChanged() {
                    // 会话集合变化（某台连上/断开）→ 只刷设备列表；聊天区不动
                    win.refreshDevices();
                    // 通话对端掉线 → 必须结束通话（与 Android 端 onPeerDisconnected 对称）。
                    // 链路掉线只走 onState/onPeersChanged，CallManager 不在那条链路上：
                    // 不补这一下 state 会永远停在 TALKING（最坏 6h）—— 界面一直显示「正在通话」、
                    // 视频窗口不关、挂断之外的按钮全部无效。
                    String cp = call.peerIp();
                    if (cp != null && !cp.isEmpty() && !link.isConnectedIp(cp)) {
                        final String cpf = cp;
                        callAsync(() -> call.onPeerDisconnected(cpf));
                    }
                }

                @Override
                public void onMessageAdded(Msg m) {
                    // 接收侧文本命中名单 → 当作没收到：不上屏、不写历史、不记归属。
                    // ack 由 Link 在 emit 之后照回（对端要靠它出队），与本处判定无关。
                    if (m.dir == Msg.DIR_PEER && Msg.KIND_TEXT.equals(m.kind)
                            && TransferKeywords.isSilentlyFiltered(m.text)) {
                        return;
                    }
                    // 归属兜底（2026-09-21）：帧里没带 peer 的，先按 mid 反查一次再往界面上送。
                    // 不补这一步的话，界面的归属判据只能把这条件不明的消息**丢掉**（宁可不显示，
                    // 也不能让它串到别的设备的会话里）。
                    if (m.peerIp == null || m.peerIp.isEmpty()) {
                        String p = peerOf(m.mid);
                        if (!p.isEmpty()) {
                            m.peerIp = p;
                        }
                    }
                    win.onMessageAdded(m);
                    if (!Msg.KIND_CLIP.equals(m.kind)) {
                        // 历史对**自己发的消息也落盘**（旧版只落收到的 → 切走再切回/重启
                        // 后自己发的内容全部凭空消失，聊天区看起来是空白的）。
                        // CLIP 不是消息，照旧不落；peerIp 归属不到不落（防写 _unknown.jsonl）。
                        if (m.peerIp != null && !m.peerIp.isEmpty()) {
                            rememberPeer(m.mid, m.peerIp);
                            store.appendHistory(m.peerIp, m.toJson());
                        }
                    }
                }

                @Override
                public void onMessageUpdated(Msg m) {
                    // 同上：状态帧（进度/完成/失败）历史上就不带 peer，先反查补上，界面才能判定它属于谁
                    if (m.peerIp == null || m.peerIp.isEmpty()) {
                        String p = peerOf(m.mid);
                        if (!p.isEmpty()) {
                            m.peerIp = p;
                        }
                    }
                    win.onMessageUpdated(m);
                    // 终态补写历史：进度/完成/失败都走 updated（帧里不带 peer），
                    // 不落盘的话自己发的文件在重载后永远停在 SENDING 或直接消失。
                    // 只落「带实质内容」的帧：裸状态帧（fileName/text 都空，如对端 ack）只更新 UI，不写历史。
                    if (!Msg.KIND_CLIP.equals(m.kind)
                            && (Msg.ST_OK.equals(m.status) || Msg.ST_FAILED.equals(m.status))
                            && ((m.fileName != null && !m.fileName.isEmpty())
                                 || (m.text != null && !m.text.isEmpty()))) {
                        String peer = (m.peerIp == null || m.peerIp.isEmpty())
                                ? peerOf(m.mid) : m.peerIp;
                        if (peer != null && !peer.isEmpty()) {
                            store.appendHistory(peer, m.toJson());
                        }
                    }
                    // 收到文件**落盘完成**才弹托盘提示（2026-09-30）：以前挂在 added 上，
                    // 而接收 file_meta 被误标 ST_OK → 传输一开始就弹「文件已保存」，
                    // 完成这个真终态反而没有提示。
                    if (!m.isSelf() && Msg.KIND_FILE.equals(m.kind) && Msg.ST_OK.equals(m.status)
                            && m.fileName != null && !m.fileName.isEmpty()
                            && m.filePath != null && !m.filePath.isEmpty()) {
                        javax.swing.SwingUtilities.invokeLater(() -> Tray.messageThrottled(
                                "文件已保存到 " + dirShort(store), m.fileName
                                        + "\n" + m.filePath));
                    }
                }

                @Override
                public void onMessageRemoved(String mid) {
                    // 文件被任一方取消：两端同步，移除展示 + 删除本地历史（重载也不复现）。
                    // 取消通知只带 mid，历史按设备分文件，所以全目录扫一遍删。
                    store.removeFromHistoryEverywhere(mid);
                    win.onMessageRemoved(mid);
                }

                @Override
                public void onHsyncApplied(String ip, int count) {
                    win.onHsyncApplied(ip, count);
                }

                @Override
                public void onClipToClipboard(String text) {
                    if (store.autoWriteClipboard()) {
                        clip.setClipboard(text);
                        win.setSub("已把手机发来的 " + text.length() + " 字写入本机剪贴板");
                    } else {
                        win.setSub("收到手机剪贴板 " + text.length() + " 字（自动写入已关闭）");
                    }
                }

                @Override
                public void onLog(String s) {
                    // 日志已落盘，UI 只在状态栏给最近一条
                }

                @Override
                public void onCallInvite(String peerIp, String name) {
                    callAsync(() -> call.onIncoming(peerIp, name));
                }

                @Override
                public void onCallAccept(String peerIp) {
                    callAsync(() -> call.onAccept(peerIp));
                }

                @Override
                public void onCallReject(String peerIp) {
                    callAsync(() -> call.onReject(peerIp));
                }

                @Override
                public void onCallBusy(String peerIp) {
                    callAsync(() -> call.onBusy(peerIp));
                }

                @Override
                public void onCallEnd(String peerIp, String reason) {
                    callAsync(() -> call.onRemoteEnd(peerIp, reason));
                }

                @Override
                public void onCallCancel(String peerIp) {
                    callAsync(() -> call.onCancel(peerIp));
                }

                @Override
                public void onCallAudio(String peerIp, int seq, long ts, byte[] data, int len) {
                    // 音频帧只做入队（极快），保持同步以免打乱时序/积压
                    call.onAudio(peerIp, seq, ts, data, len);
                }

                // ==================== 视频接收（PC 端只收） ====================

                @Override
                public void onCallInviteVideo(String peerIp, String name, boolean vid, int lv, int af,
                                              int mport, boolean mudp, String msid) {
                    // 状态机改动丢独立线程；这里不阻塞读线程
                    callAsync(() -> call.onIncoming(peerIp, name, vid, lv, af, mport, mudp, msid));
                }

                @Override
                public void onCallAcceptVideo(String peerIp, boolean vid, int lv, int af,
                                              int mport, boolean mudp, String msid) {
                    callAsync(() -> call.onAccept(peerIp, vid, lv, af, mport, mudp, msid));
                }

                /**
                 * 振铃期逐档媒体协商（任务单 2.5）：交通话层转给媒体模块。
                 * 与其它通话回调同一条独立线程，不占网络读线程。
                 */
                @Override
                public void onCallMedia(String peerIp, int mport, boolean mudp, String msid, int mtier) {
                    callAsync(() -> call.onCallMedia(peerIp, mport, mudp, msid, mtier));
                }

                /**
                 * 视频帧：**只入队**。
                 *
                 * 这是网络读线程的回调，绝不能在这里 spawn/重启 ffmpeg（可能阻塞数百 ms，
                 * 会让读线程停摆 → 心跳收包归零 → 对端 35s 判死掉线）。
                 * 真正的解码在 FfmpegDecoder 自己的 pp-video-feed / pp-video-read 线程。
                 */
                @Override
                public void onVideoFrame(String peerIp, int seq, long ts, int flag, byte[] data, int len) {
                    call.onVideoFrame(peerIp, seq, ts, flag, data, len);
                }

                @Override
                public void onVideoCfg(String peerIp, String codec, int w, int h, int fps, int br,
                                       int rot, boolean front) {
                    // 同上：只落字段，进程重启交给 ffmpeg 线程
                    call.onVideoCfg(peerIp, codec, w, h, fps, br, rot, front);
                }

                @Override
                public void onVideoFb(String peerIp, int fps, int drop, int lat,
                                      boolean dropping, int kbps) {
                    // PC 不发送视频，对端的接收反馈与我们无关（仅记录）
                }

                @Override
                public void onVideoReq(String peerIp, boolean on) {
                    call.onVideoReq(peerIp, on);
                }
            });

            clip.start();
            link.start();

            final Discovery disc = new Discovery(
                    store.deviceId(), store.deviceName(), store.tcpPort(), store.udpPort(),
                    new Discovery.Listener() {
                        @Override
                        public void onPeer(String ip, String name, String id, int port) {
                            // 自动发现：既有设备刷新发现信息，新设备直接进左侧设备列表
                            if (store.upsertPeer(ip, null, id, name, System.currentTimeMillis())) {
                                win.setSub("发现设备 " + name + " @ " + ip + "，已加入设备列表");
                                Log.i("发现设备 " + name + " @ " + ip + "，已加入设备列表");
                                win.refreshDevices();
                            }
                        }
                    });
            disc.start();

            // ===== 启动扫一次局域网（只此一次）=====
            // 闸门（2026-09-21 起）：本机 IP 必须落在**私网段**（10/8、172.16/12、192.168/16）
            // 才真的扫 —— 公网 / 链路本地地址没有「本网段 /24」可扫，逐个扫 254 个地址既没意义，
            // 也容易被对端网络当成端口扫描。
            // 扫描窗口 = 广播询问 + **本网段 /24 逐地址单播询问**（本机 10.1.2.3 → 只扫 10.1.2.1-254）。
            // 窗口内**回应**的设备会经由 Discovery.Listener → store.upsertPeer 自动进入设备列表，
            // 这里只负责汇报结果：有回应 →「发现 N 台设备」，一个都没有 →「无新设备」。
            // 此后不再主动扫：靠 Discovery 接收线程常驻，被动接收对端广播并刷新设备列表。
            final Runnable startupScan = new Runnable() {
                @Override
                public void run() {
                    final String myIp = Discovery.localIpv4();
                    final String prefix = Discovery.subnetPrefixOf(myIp);
                    if (prefix == null) {
                        final String shown = (myIp == null || myIp.isEmpty()) ? "未知" : myIp;
                        win.setSub("本机不在局域网私有网段（" + shown + "），已跳过扫描");
                        Log.i("跳过局域网扫描：本机 " + shown + " 不属于私网段（10/172.16-31/192.168）");
                        return;
                    }
                    win.setSub("正在扫描 " + prefix + ".1-254…");
                    Log.i("开始扫描局域网（本机 " + myIp + "，本网段 " + prefix + ".1-254）");
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            java.util.List<String> found = disc.scanOnce(3000, myIp);
                            if (found.isEmpty()) {
                                win.setSub("无新设备");
                                Log.i("扫描结束：无新设备");
                            } else {
                                win.setSub("发现 " + found.size() + " 台设备");
                                Log.i("扫描结束：发现 " + found.size() + " 台设备 " + found);
                            }
                            // 启动扫描后自动连接**全部扫描到的设备**（多设备独立会话，互不冲突；
                            // 只试一次，失败不重试，重连仍以用户点击设备为准）。
                            // 扫描空手时兜底：lastIp 仍在设备列表则单独试它。
                            try {
                                if (!found.isEmpty()) {
                                    for (String ip : found) {
                                        if (link.isConnectedIp(ip)) {
                                            Log.i("自动连接目标 " + ip + " 已连接，无需连接");
                                            continue;
                                        }
                                        String label = null;
                                        for (Peer p : store.peers()) {
                                            if (ip.equals(p.ip)) {
                                                label = p.displayName();
                                                break;
                                            }
                                        }
                                        Log.i("启动扫描后自动连接 " + ip
                                                + (label != null ? "（" + label + "）" : ""));
                                        link.selectAndConnect(ip, label);
                                        store.setLastIp(ip);
                                    }
                                    win.setSub("自动连接 " + found.size() + " 台设备…");
                                } else {
                                    String last = store.lastIp();
                                    boolean inList = false;
                                    if (!last.isEmpty()) {
                                        for (Peer p : store.peers()) {
                                            if (last.equals(p.ip)) {
                                                inList = true;
                                                break;
                                            }
                                        }
                                    }
                                    if (inList && !link.isConnectedIp(last)) {
                                        Log.i("本轮扫描无回应，兜底自动连接上次设备 " + last);
                                        link.selectAndConnect(last, null);
                                    } else {
                                        Log.i("跳过自动连接：本轮无扫描结果"
                                                + (last.isEmpty() ? "，且无历史连接目标" : "，上次设备 " + last
                                                        + (inList ? " 已连接" : " 不在设备列表")));
                                    }
                                }
                            } catch (Throwable t) {
                                Log.w("自动连接失败: " + t);
                            }
                            win.refreshDevices();
                        }
                    }, "pp-scan");
                    t.setDaemon(true);
                    t.start();
                }
            };
            // 只扫这一次：之后不再主动广播扫描，改为纯被动接收对端广播
            //（Discovery 的接收线程常驻，收到谁就把谁 upsert 进设备列表）。
            startupScan.run();

            // 托盘常驻
            Tray.init(win, link, clip, disc, store);
            // 注册主题监听：运行期切主题时重刷托盘菜单（菜单只在 init 建一次，颜色建时烤走，根因③）
            Ui.addThemeListener(Tray::refreshTheme);

            // 主窗口就绪 → 注册二次启动的唤起动作，并开始守卫监听
            onShowCommand = win::showWindow;
            startGuardListener();

            // 每 3s 刷新一次托盘提示
            Thread ticker = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!exiting) {
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (link.isConnected()) {
                            Tray.setTooltip("● " + link.selectedLabel());
                        } else {
                            int online = link.connectedIps().size();
                            Tray.setTooltip(online > 0
                                    ? "○ 未选中（" + online + " 台在线）"
                                    : "○ 未连接（点设备连接 · 监听 " + store.tcpPort() + "）");
                        }
                    }
                }
            }, "pp-ticker");
            ticker.setDaemon(true);
            ticker.start();

            // 默认前台打开聊天窗口；--hide 才缩到托盘
            boolean hideToTray = false;
            for (String a : args) {
                if ("--hide".equals(a)) {
                    hideToTray = true;
                }
            }
            if (!hideToTray || !SystemTray.isSupported()) {
                win.showWindow();
            } else {
                Tray.message("已在后台运行", "双击托盘图标打开聊天窗口");
                Log.i("已最小化到托盘（双击托盘图标打开聊天窗口）");
            }
        } catch (Throwable t) {
            Log.e("启动失败", t);
            javax.swing.JOptionPane.showMessageDialog(null,
                    "启动失败：" + t + "\n详见 logs/pc.log");
            System.exit(1);
        }
    }

    static void markExiting() {
        exiting = true;
    }

    /**
     * 安装版启动时把中文快捷方式「手机电脑互传」铺到**桌面**和**开始菜单**。
     *
     * <p>为什么中文名不在 MSI 里做：安装器语言固定 zh-CN（数据库 codepage 936，写中文本来可以），
     * 但 exe、安装目录、开始菜单组都统一用 ASCII 的 PhonePcTransfer，避免路径进脚本；
     * 所以安装器只落 ASCII 名的 PhonePcTransfer.lnk，中文名由这里用 PowerShell 造 .lnk ——
     * .lnk 是文件系统对象，和 MSI 的 codepage 无关。
     *
     * <p>只在**安装版**里做：源码版（run.bat / IDE）的 command 是 JDK 的 java.exe，
     * 照着它造出来的 .lnk 双击只会起一个不带参数的 JVM，桌面上就是多出一个永远打不开
     * 应用的坏快捷方式（2026-09-30「装了 MSI 却没有桌面快捷方式」里真正的成因）。
     */
    private static void ensureDesktopShortcut() {
        String exe = installedLauncherExe();
        if (exe == null) {
            Log.i("非安装版启动（run.bat/IDE），不创建桌面快捷方式");
            return;
        }
        ensureShortcutsFor(exe);
    }

    /** 建/修「手机电脑互传.lnk」（桌面 + 开始菜单），成功后才删安装器留下的英文名。 */
    static void ensureShortcutsFor(String exe) {
        try {
            String ps = """
                    $ErrorActionPreference='SilentlyContinue'
                    $ProgressPreference='SilentlyContinue'
                    [Console]::OutputEncoding=[Text.Encoding]::UTF8
                    $exe='__EXE__'
                    $work=Split-Path $exe -Parent
                    $shell=New-Object -ComObject WScript.Shell
                    $dirs=@([Environment]::GetFolderPath('Desktop'),
                            (Join-Path ([Environment]::GetFolderPath('StartMenu')) 'Programs\\PhonePcTransfer'))
                    foreach ($d in $dirs) {
                        if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
                        $p=Join-Path $d '手机电脑互传.lnk'
                        $s=$shell.CreateShortcut($p)
                        if ($s.TargetPath -ne $exe) {
                            $s.TargetPath=$exe; $s.WorkingDirectory=$work
                            $s.IconLocation="$exe,0"; $s.Description='手机电脑互传'; $s.Save()
                            Write-Output ("REFRESH " + $p)
                        } else {
                            Write-Output ("OK " + $p)
                        }
                    }
                    foreach ($d in $dirs) {
                        if (-not (Test-Path (Join-Path $d '手机电脑互传.lnk'))) { continue }
                        $en=Join-Path $d 'PhonePcTransfer.lnk'
                        if (Test-Path $en) { Remove-Item -Force $en; Write-Output ("REMOVED " + $en) }
                    }
                    """;
            // 单引号转义：用户名里带 '（O'Brien 之类）会让 PS 的字符串字面量直接语法炸掉
            for (String line : runPowerShell(
                    ps.replace("__EXE__", exe.replace("'", "''"))).split("\n")) {
                if (!line.isBlank()) {
                    Log.i("快捷方式: " + line.trim());
                }
            }
        } catch (Throwable t) {
            Log.w("创建桌面快捷方式失败（不影响运行）: " + t);
        }
    }

    /**
     * 取安装版启动器 exe 的绝对路径，**非安装版返回 null**。
     * jpackage 的 app-image 布局是 {@code <安装根>/Xxx.exe + <安装根>/app/ + <安装根>/runtime/}，
     * 三个一起才算数（JDK 的 bin\ 里也有 exe，但它旁边没有 app\，天然被排除）。
     */
    static String installedLauncherExe() {
        String cmd = null;
        try {
            java.util.Optional<String> c = ProcessHandle.current().info().command();
            if (c.isPresent()) {
                cmd = c.get();
            }
        } catch (Throwable t) {
            Log.w("installedLauncherExe ProcessHandle 失败: " + t);
        }
        if (cmd == null || !cmd.toLowerCase().endsWith(".exe")) {
            return null;
        }
        java.io.File exe = new java.io.File(cmd);
        java.io.File dir = exe.getParentFile();
        if (dir == null) {
            return null;
        }
        return new java.io.File(dir, "app").isDirectory()
                && new java.io.File(dir, "runtime").isDirectory()
                        ? exe.getAbsolutePath() : null;
    }

    /**
     * 跑一段 PowerShell 并返回 stdout。
     * ⚠ 必须用 {@code -EncodedCommand}（UTF-16LE 的 base64）：脚本里有中文（快捷方式名/
     * 描述），而 powershell 5.1 解析 -Command 参数时按系统 ANSI(GBK) 取字，中文会糊成乱码，
     * 造出来的 .lnk 名字就是坏字符。-EncodedCommand 走宽字符，什么语言都稳。
     * ⚠ stderr 不能 redirectErrorStream 合并：powershell 会把 stderr 包成 CLIXML 文档
     * 混进 stdout（日志里一大坨 {@code <Objs Version=...}）；这里单独丢弃，脚本本身
     * {@code $ErrorActionPreference='SilentlyContinue'}，返回值 rc 仍会报出失败。
     */
    static String runPowerShell(String script) throws Exception {
        String b64 = java.util.Base64.getEncoder().encodeToString(
                script.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", b64);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) {
            Log.w("PowerShell 返回 " + rc);
        }
        return out;
    }

    /** 状态栏用的短目录文本 */
    private static String dirShort(Store store) {
        String d = store.saveDir();
        return (d == null || d.isEmpty()) ? "pc/received（默认）" : d;
    }

    /** 托盘图标与菜单 */
    private static final class Tray {
        private static TrayIcon icon;
        private static SystemTray tray;
        private static JPopupMenu menu;

        static void init(final ChatWindow win, final Link link, final Clip clip,
                         final Discovery disc, final Store store) {
            if (!SystemTray.isSupported()) {
                Log.w("当前系统不支持系统托盘，改为直接显示窗口");
                return;
            }
            try {
                tray = SystemTray.getSystemTray();

                // ⚠️ 必须用 Swing 的 JPopupMenu，不要换回 AWT 的 PopupMenu。
                // AWT 托盘菜单交给 Windows 原生菜单渲染：实测 MenuItem.getFont() 恒为 null，
                // 字体由系统菜单字体决定、Java 侧既读不到也改不了，中文会整条渲染成方块。
                // JPopupMenu 走 Java2D 绘制，和应用其余界面（聊天窗）共用同一套字体回退链，中文正常。
                menu = new JPopupMenu();
                // 托盘菜单，用户定调不参与字号统一（保持 14）
                Font menuFont = new Font(Fonts.FAMILY, Font.PLAIN, 14);

                item(menu, menuFont, "打开聊天窗口", win::showWindow);
                item(menu, menuFont, "手动同步", link::syncNow);
                item(menu, menuFont, "推送本机剪贴板到手机", () -> {
                    String b = clip.buffer();
                    if (b != null && !b.isEmpty()) {
                        link.sendClip(b, Clip.sha1(b));
                    }
                });

                menu.addSeparator();
                item(menu, menuFont, "打开接收目录", () -> {
                    try {
                        java.io.File f = new java.io.File(store.receivedDirText());
                        if (!f.isDirectory()) {
                            f.mkdirs();
                        }
                        java.awt.Desktop.getDesktop().open(f);
                    } catch (Throwable t) {
                        Log.w("打开接收目录失败: " + t);
                    }
                });

                menu.addSeparator();
                item(menu, menuFont, "退出", () -> {
                    markExiting();
                    clip.stop();
                    link.stop();
                    disc.stop();
                    tray.remove(icon);
                    Log.i("程序退出");
                    System.exit(0);
                });

                // 收尾套一遍当前主题：菜单只在 init 里建一次，颜色是建时烤走的（根因③）
                Ui.theme(menu);

                icon = new TrayIcon(makeIcon(), "手机电脑互传");
                icon.setImageAutoSize(true);
                icon.addActionListener(e -> win.showWindow());
                icon.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        maybeShowMenu(e);
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {
                        maybeShowMenu(e);
                    }
                });
                tray.add(icon);
                Log.i("系统托盘图标已就绪");
            } catch (AWTException e) {
                Log.w("托盘初始化失败: " + e);
            }
        }

        /** 造一个菜单项并挂上动作；字体显式给，不跟随系统默认（详见 init 里的说明） */
        private static void item(JPopupMenu pm, Font font, String text, Runnable action) {
            JMenuItem mi = new JMenuItem(text);
            mi.setFont(font);
            mi.addActionListener(e -> action.run());
            pm.add(mi);
        }

        /**
         * 主题切换时重刷托盘菜单配色 —— 菜单只在 init 里建一次，颜色是建时被烤走的（根因③）。
         * 托盘菜单是**重型弹窗**：刷新前先 setVisible(false)，否则可能在屏幕上留下半张旧图。
         */
        static void refreshTheme() {
            if (menu == null) {
                return;
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (menu.isVisible()) {
                    menu.setVisible(false);
                }
                Ui.theme(menu);
            });
        }

        /**
         * 右键弹出托盘菜单。
         *
         * ⚠️ 坐标有两套，别混用（2026-09-29：程序钉定 1.0，系统仍是 125%）：
         *   - Swing 的组件/popup 用「逻辑坐标」：钉定 1.0 后逻辑 = 物理（本机 2560x1440）
         *   - TrayIcon 的 MouseEvent 给的可能是「设备像素」：仅当系统缩放 ≠ 钉定值时两套才分离
         * 拿设备像素直接 setLocation，落点会跑到屏外，被 popup 机制挤走 —— 表现就是
         * 「托盘菜单不在图标附近」。所以这里统一先把点换算成 Swing 坐标系（见 toLogicalScreen），
         * 再贴着图标并夹进可用区。
         *
         * 托盘图标不是 Swing 组件、没有天然的 invoker，而 JPopupMenu 必须要一个 invoker
         * 才能建出 popup 窗口并在点击别处时自动收起 —— 用 menu 自己当 invoker 是常规做法，
         * popup 位置取 menu 自己的 getLocation()，所以 setLocation 给的值就是屏幕位置。
         */
        private static void maybeShowMenu(MouseEvent e) {
            if (!e.isPopupTrigger() || menu == null) return;
            // 优先 MouseInfo：它跟 Swing 同一套逻辑坐标系，最准；
            // 取不到再退回事件的 x,y（TrayIcon 事件的坐标本身就是屏幕坐标），
            // 最后统一过一遍 toLogicalScreen：已经是逻辑坐标就原样返回，是设备像素才换算。
            Point at = pointerOnScreen();
            if (at == null) {
                at = new Point(e.getX(), e.getY());
            }
            showMenuAt(toLogicalScreen(at));
        }

        /** 当前指针位置（AWT 逻辑屏幕坐标）；不可用时返回 null */
        private static Point pointerOnScreen() {
            try {
                PointerInfo pi = MouseInfo.getPointerInfo();
                return pi == null ? null : pi.getLocation();
            } catch (Throwable t) {
                return null;
            }
        }

        /**
         * 若坐标不在逻辑屏内，说明给来的是设备像素 —— 换算回 Swing 坐标系。
         *
         * <p>换算因子必须取「Swing 实际使用的渲染缩放」（main 设的 sun.java2d.uiScale），
         * 而不是 gc.getDefaultTransform()（那是**系统**缩放）：2026-09-29 起程序钉 1.0 而
         * 系统仍 125%，若按系统 1.25 除会得到「逻辑坐标」，但 Swing 此时要的是设备坐标 → 菜单错位。
         * 未钉定（跟随系统）时两者相同，退回系统 transform 即旧行为。
         */
        private static Point toLogicalScreen(Point raw) {
            GraphicsConfiguration gc = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            Rectangle bounds = gc.getBounds();
            if (bounds.contains(raw)) {
                return raw;
            }
            double sx = pinnedUiScale();
            double sy = sx;
            if (sx <= 0) {
                // 未钉定（跟随系统）：此时系统 transform 就是 Swing 实际缩放
                AffineTransform tr = gc.getDefaultTransform();
                sx = tr.getScaleX();
                sy = tr.getScaleY();
            }
            if (sx <= 0 || sy <= 0) {
                return raw;
            }
            Point q = new Point((int) Math.round(raw.x / sx), (int) Math.round(raw.y / sy));
            Log.i("托盘坐标 " + raw.x + "," + raw.y + " 落在逻辑屏 " + bounds
                    + " 之外，按缩放 " + sx + " 换算为 " + q.x + "," + q.y);
            return q;
        }

        /** main 钉定的渲染缩放（sun.java2d.uiScale）；0 = 未钉定/未设置（跟随系统） */
        private static double pinnedUiScale() {
            String v = System.getProperty("sun.java2d.uiScale");
            if (v == null) {
                return 0.0;
            }
            try {
                double d = Double.parseDouble(v);
                return d > 0 ? d : 0.0;
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }

        /** 在指定屏幕位置弹出菜单：贴住点击点，并整体夹进「去掉任务栏的可用区」内 */
        private static void showMenuAt(Point at) {
            Rectangle safe = safeBoundsAt(at);
            Dimension size = menu.getPreferredSize();
            int w = Math.max(0, size.width);
            int h = Math.max(0, size.height);
            int x = at.x;
            int y = at.y;
            // 右下角的托盘：先向右下溢出必然越界，于是菜单自动翻到图标的左上侧（Windows 原生行为）
            if (w > 0 && x + w > safe.x + safe.width) {
                x = safe.x + safe.width - w;
            }
            if (h > 0 && y + h > safe.y + safe.height) {
                y = safe.y + safe.height - h;
            }
            if (x < safe.x) {
                x = safe.x;
            }
            if (y < safe.y) {
                y = safe.y;
            }
            menu.setInvoker(menu);
            menu.setLocation(x, y);
            menu.setVisible(true);
            Log.i("托盘菜单弹出 @ " + x + "," + y + "（点击 " + at.x + "," + at.y
                    + "，菜单 " + w + "x" + h + "，可用区 " + safe.x + "," + safe.y
                    + " " + safe.width + "x" + safe.height + "）");
        }

        /** 点所在那块屏幕去掉任务栏后的可用区域（逻辑坐标）；多屏时取点所在屏 */
        private static Rectangle safeBoundsAt(Point at) {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice dev = null;
            for (GraphicsDevice d : ge.getScreenDevices()) {
                if (d.getDefaultConfiguration().getBounds().contains(at)) {
                    dev = d;
                    break;
                }
            }
            if (dev == null) {
                dev = ge.getDefaultScreenDevice();
            }
            Rectangle r = new Rectangle(dev.getDefaultConfiguration().getBounds());
            Insets in = Toolkit.getDefaultToolkit().getScreenInsets(dev.getDefaultConfiguration());
            r.x += in.left;
            r.y += in.top;
            r.width -= (in.left + in.right);
            r.height -= (in.top + in.bottom);
            return r;
        }

        static void setTooltip(String s) {
            if (icon != null) {
                icon.setToolTip("手机电脑互传 — " + s);
            }
        }

        static void message(String title, String body) {
            if (icon != null) {
                try {
                    icon.displayMessage(title, body, TrayIcon.MessageType.INFO);
                } catch (Throwable ignore) {
                }
            }
        }

        /** 「文件已保存」通知的最小间隔：连发时只弹第一条，其余落日志 */
        private static final long FILE_MSG_GAP_MS = 15_000L;
        /** 上一条文件保存通知的时刻；只在 EDT 上读写，不需要 volatile */
        private static long lastFileMsgAt = 0L;

        /**
         * {@link #message} 的限流版：15s 内只弹一条，其余只记日志。
         * 连投 / 历史补齐会在几百毫秒内落多个文件，逐条弹会把通知区刷成一串重复气泡。
         */
        static void messageThrottled(String title, String body) {
            long now = System.currentTimeMillis();
            if (now - lastFileMsgAt < FILE_MSG_GAP_MS) {
                Log.i("托盘通知 15s 内已弹过，本次抑制: " + body.replace('\n', ' '));
                return;
            }
            lastFileMsgAt = now;
            message(title, body);
        }

        /**
         * 托盘图标。
         *
         * 2026-09-15：改为**与安卓端 launcher 图标同源** —— 之前这里是手绘的一套近似图形
         * （蓝底 + 屏 + 手机 + 箭头）与手机端不一致；现在统一走 {@link AppIcon}（几何 1:1
         * 复刻 {@code android/.../drawable/ic_launcher_foreground.xml}），安装包/快捷方式/
         * 托盘/窗口标题栏 四处同一张图。32px = 托盘标准尺寸。
         */
        private static Image makeIcon() {
            return AppIcon.render(32);
        }
    }
}
