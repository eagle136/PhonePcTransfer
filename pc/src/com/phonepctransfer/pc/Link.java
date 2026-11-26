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
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
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
 * PC端TCP链路(与Android端net/Link.kt同协议、同去重规则)
 * - 监听28765(MIUI scene-daemon占127.0.0.1:8765),同时按候选IP主动连手机(覆盖手机开热点)
 * - 只在收到hello后认定会话;重复会话按deviceId去重,收敛规则preferOutgoing=(myId<peerId)
 * - 15s ping,35s无收包判死
 * 传输性能:文件分片1MB裸二进制帧(base64+JSON有33%膨胀);收发端双4MB RAM缓冲交替读/存,满块交独立落盘线程;
 *  断点续传:开传前file_query问对端半成品偏移;文件夹传输:dir_meta声明目录,成员带relPath/dirId,气泡聚合进度
 */
public final class Link {

    public enum State { STOPPED, IDLE, CONNECTING, CONNECTED }

    public interface Listener {
        void onState(State s, String peer);

        /** 已连接设备集合发生变化(连上/断开/去重)——设备列表据此重新打点 */
        default void onPeersChanged() {
        }

        void onMessageAdded(Msg m);

        void onMessageUpdated(Msg m);

        /** 一条消息被移除(文件被任一方取消):实现方从界面移除+本地历史/待发队列删掉,取消后两端都不再显示 */
        default void onMessageRemoved(String mid) {
        }

        /** 历史同步(hsync)一批补齐完成:从对端补入了count条消息;实现方借此重排聊天区(补入的旧消息按到达顺序追加) */
        default void onHsyncApplied(String ip, int count) {
        }

        /** 一条消息在时间线上被移到"最新一条"(v2.7);目前只有"数据流量被拒后重试成功"这条路径:同意后把那条挪到最下面 */
        default void onMessageMoved(String mid) {
        }

        /** 手机推来的剪贴板正文，需要写入系统剪贴板 */
        void onClipToClipboard(String text);

        void onLog(String s);

        /**
         * 「必须让用户看见」的提示（发送前校验失败、空文件夹/命名不合规导致的取消等）。
         * 实现方负责切 UI 线程再弹对话框 —— 回调可能来自任意后台线程。
         */
        default void onNotice(String text) {
        }

        /** P4-5：配对覆盖层状态变化；null = 关掉覆盖层 */
        default void onPairState(PairUi u) {
        }

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

        /** 收到一帧对端音频(网络读线程回调);
         *  @param ts 发送端通话相对时间戳(ms,对端起呼=0);只可对本端视频帧同轴比较,不可与本端墙钟比大小(两端各自零点,见{@link CallClock}) */
        default void onCallAudio(String peerIp, int seq, long ts, byte[] data, int len) {
        }

        // ==================== 视频通话回调 ====================
        /** 视频呼叫请求(带协商)。默认回落到纯语音版,避免实现方漏掉来电;
         *  @param af 对端期望语音帧长(ms);0=老版本未声明→用200ms兼容模式
         *  @param mport 对端媒体端口(方案§4.2);0=未声明→只能走带内档
         *  @param mudp 对端媒体端口是否UDP;false=未声明
         *  @param msid 对端为本次通话带来的随机token;空=未声明 */
        default void onCallInviteVideo(String peerIp, String name, boolean vid, int lv, int af,
                                       int mport, boolean mudp, String msid) {
            onCallInvite(peerIp, name);
        }

        /** 视频接听应答（带档位、语音帧长与媒体通道协商），字段语义同 {@link #onCallInviteVideo} */
        default void onCallAcceptVideo(String peerIp, boolean vid, int lv, int af,
                                       int mport, boolean mudp, String msid) {
            onCallAccept(peerIp);
        }

        /** 振铃期的逐档媒体协商(方案§4.3①的"交换"):对端把它当前档的本端三元组带过来。默认实现什么都不做;
         *  @param msid 空=对端未声明(老端口径)⇒本端只能落带内档
         *  @param mport 0且msid非空=对端已落带内档(第五档),本端不必再试后面几档
         *  @param mtier 对端当前档序号;⚠两端档位序列可能不同(安卓跳过两个443档),只用于日志,不是连通性判据 */
        default void onCallMedia(String peerIp, int mport, boolean mudp, String msid, int mtier) {
        }

        /** 收到一帧对端视频(网络读线程回调);实现方只能入队,禁止在这里起动/重启ffmpeg进程——会阻塞读线程→对端35s判死掉线;
         *  @param ts 与同通话音频帧同零点的通话相对毫秒(发送端侧) */
        default void onVideoFrame(String peerIp, int seq, long ts, int flag, byte[] data, int len) {
        }

        /** 编码参数变更(codec/w/h有变→重启ffmpeg);
         *  @param front 对端摄像头是否前置(video_cfg.cam,老对端不带=后置);仅影响默认显示角:对端前置时默认多转180°(=用户点两次旋转钮),见{@code CallManager} */
        default void onVideoCfg(String peerIp, String codec, int w, int h, int fps, int br, int rot,
                                boolean front) {
        }

        /** 对端(视频接收方)发回的健康反馈;
         *  @param lat 对端"入队→喂解码器"的排队时延ms(真实值)
         *  @param dropping 对端此刻是否正处于丢帧期(与lat分开的独立标志) */
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

    /** 本端能力(握手时广播)。PC端恒为"只收不发":vid=true(能收视频)、cam=false(无摄像头、绝不主动发起视频) */
    volatile boolean localVidCap = true;
    volatile java.util.List<String> localVCodecs = new java.util.ArrayList<String>();

    /** 由PcMain注入本机可解码的编码列表(取决于ffmpeg是否可用) */
    public void setLocalVideoCaps(java.util.List<String> vcodecs) {
        this.localVCodecs = vcodecs == null ? new java.util.ArrayList<String>() : vcodecs;
    }

    /** 本端是否"能收视频"。由PcMain在start()之前设定:ffmpeg找不到、或用户关了config.json.videoEnabled→false;
     *  广播vid=false⇒Android侧"视频"按钮直接置灰;必须在link.start()之前设好(hello在会话建立时就发出去了,之后改不会自动重发) */
    public void setLocalVidCap(boolean v) {
        this.localVidCap = v;
    }

    volatile Listener listener;
    private Clip clip;
    Store store;

    /** 当前正在配对覆盖层上的设备 IP（空 = 没有覆盖层） */
    volatile String pairUiIp = "";

    /**
     * 用户点了设备但会话还没握手：等这次连接起来后按「用户主动发起」出示配对码。
     * 空 = 没有待办。带时间戳，超过 {@link #PAIR_TAP_INTENT_TTL_MS} 的连接不再补弹，避免几秒后的自动重连又把码弹出来。
     */
    private volatile String pendingPairIp = "";
    private volatile long pendingPairAt = 0L;

    /**
     * P4-5 配对覆盖层状态（给 UI 用）。
     * showCode=true → 本端用户点了这台设备，由本端显示 6 位码让对方读；
     * showCode=false → 对端点设备后本端切到数字键盘输入对方显示的码。
     * 两端都不再随连接自动弹。
     */
    public static final class PairUi {
        public final String ip;
        public final String name;
        public final boolean showCode;
        public final String code;
        public final int attemptsLeft;
        public final long ttlMs;
        public final String message;
        public final boolean done;
        public final boolean ok;

        public PairUi(String ip, String name, boolean showCode, String code,
                      int attemptsLeft, long ttlMs, String message, boolean done, boolean ok) {
            this.ip = ip;
            this.name = name;
            this.showCode = showCode;
            this.code = code;
            this.attemptsLeft = attemptsLeft;
            this.ttlMs = ttlMs;
            this.message = message;
            this.done = done;
            this.ok = ok;
        }

        public PairUi(String ip, String name, boolean showCode, String code,
                      int attemptsLeft, long ttlMs, String message) {
            this(ip, name, showCode, code, attemptsLeft, ttlMs, message, false, false);
        }
    }

    /** "窗口不可见(隐藏进托盘)"判据(PcMain注入=()=>!win.isVisible(),默认false=恒按前台处理);
     *  true且对端不是已知设备(握手过+有消息/文件往来,见Store.isTrustedAddr)⇒adopt拒接该入向连接(与安卓端同规则,防托盘后台被陌生设备恶意连入) */
    public volatile java.util.function.BooleanSupplier windowHidden = () -> false;

    // ==================== 文件发送的排他投票表 ====================

    /** 票①:通话中(仅TALKING,接通后;由CallManager的状态迁移写) */
    public static final int VOTE_IN_CALL = 0;
    /** 票 ②：原本"没网络"（发送入口发现无会话时写，握手完成清） */
    public static final int VOTE_NO_NETWORK = 1;
    /** 票③:原本"被拒绝"(数据流量确认被拒时写;作用域=该设备,10 分钟自动解除) */
    public static final int VOTE_CONSENT_DENIED = 2;

    /** 票③存活时长：到期自动解除，不再「拒一次永久发不出」 */
    static final long DENY_TTL_MS = 10L * 60 * 1000;

    /** 票③一条记录：只对这台设备生效 */
    private static final class DenyVote {
        final String peerIp;
        final long at;

        DenyVote(String peerIp) {
            this.peerIp = peerIp;
            this.at = System.currentTimeMillis();
        }
    }

    /** 票③登记表：设备组键 → 记录（同一台设备的多个地址用 convKeyOf 归并） */
    private final java.util.concurrent.ConcurrentHashMap<String, DenyVote> denyVotes =
            new java.util.concurrent.ConcurrentHashMap<String, DenyVote>();

    /** 票①：通话中的设备组键（按设备投——与 A 通话不再堵住发给 B 的文件） */
    private final java.util.Set<String> inCallKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 票②：原本"没网络"的设备组键（按设备投——A 离线不再堵住发给 B 的文件） */
    private final java.util.Set<String> offlineKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 文件与目录发送的排他投票,裁决=三票取或;1时新条目不入网走待发台账,在传的在下一分片边界暂停。
     *  票①/②按设备组键投，票③必须带 peerIp 才生效，避免「A 被拒/离线 ⇒ B 也发不出去」；
     *  ⚠与AvTransport.aggregationAllowed()是两个不同的判据(那条读媒体承载,本条读通话与发送状态),不许共用读口 */
    public boolean fileGateBlocked() {
        return !inCallKeys.isEmpty() || !offlineKeys.isEmpty();
    }

    public boolean fileGateBlocked(String peerIp) {
        String key = peerKeyOf(peerIp);
        if (key.isEmpty()) {
            return hasDenyVote(peerIp);
        }
        return inCallKeys.contains(key) || offlineKeys.contains(key) || hasDenyVote(peerIp);
    }

    /** 设备组键（与 sessionByGroup 同一判据；取不到时退回 IP 原串） */
    String peerKeyOf(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "";
        }
        if (store == null) {
            return ip;
        }
        String k = store.convKeyOf(ip);
        return (k == null || k.isEmpty()) ? ip : k;
    }

    /** 票③是否仍在拦这台设备（到期即清） */
    boolean hasDenyVote(String peerIp) {
        if (peerIp == null || peerIp.isEmpty() || denyVotes.isEmpty()) {
            return false;
        }
        String k = peerKeyOf(peerIp);
        DenyVote v = denyVotes.get(k);
        if (v == null) {
            return false;
        }
        if (System.currentTimeMillis() - v.at > DENY_TTL_MS) {
            denyVotes.remove(k, v);
            log("[文件排他] 票③到期自动解除（设备 " + v.peerIp + "）");
            return false;
        }
        return true;
    }

    /** 投票③：只登记这一台设备（PC 恒不在数据网，不存在「切网自动解除」，靠到期/手动恢复） */
    void denyConsent(String peerIp, String note) {
        String k = peerKeyOf(peerIp);
        if (k.isEmpty()) {
            return;
        }
        denyVotes.put(k, new DenyVote(peerIp));
        log("[文件排他] 票③投 1（设备 " + peerIp + "）：" + note + "；作用域=该设备，10 分钟后自动解除");
    }

    /** 清票③。flush=true 时只补发这台设备的待发条目（手动重试自己会发，传 false） */
    void clearDenyConsent(String peerIp, boolean flush) {
        String k = peerKeyOf(peerIp);
        boolean removed = denyVotes.remove(k) != null;
        if (!removed && peerIp != null && !peerIp.isEmpty()) {
            // 气泡重试常带的是断线前记下的 IP：按记录里的 peerIp 再兜底清一次
            for (java.util.Iterator<java.util.Map.Entry<String, DenyVote>> it =
                    denyVotes.entrySet().iterator(); it.hasNext(); ) {
                if (it.next().getValue().peerIp.equals(peerIp)) {
                    it.remove();
                    removed = true;
                }
            }
        }
        if (!removed) {
            return;
        }
        log("[文件排他] 票③清 0（设备 " + peerIp + "）");
        if (flush && !fileGateBlocked(peerIp)) {
            try {
                uiSendExec.execute(this::flushPending);
            } catch (Exception e) {
                log("恢复文件发送后的自动续发被拒（多半是本机正在退出）：" + e);
            }
        }
    }

    /** 显式恢复入口（菜单/错误横幅可调）：清该设备的票③并立刻补发它的待发条目 */
    public void resumeFileSend(String peerIp) {
        clearDenyConsent(peerIp, true);
    }

    /** 给入口日志用的真实原因（不再一律写「通话期不入网」） */
    String gateReason(String peerIp) {
        String key = peerKeyOf(peerIp);
        if (!key.isEmpty() && inCallKeys.contains(key)) {
            return "通话中不入网";
        }
        if (!key.isEmpty() && offlineKeys.contains(key)) {
            return "当前无连接";
        }
        if (hasDenyVote(peerIp)) {
            return "上次已拒绝使用数据流量（设备 " + peerIp + "）";
        }
        return "文件排他";
    }

    /** 票③到期自动解除（心跳每 5s 顺带跑）；只清票、不补发（避免对端又收到确认弹窗） */
    void pruneDenyVotes() {
        if (denyVotes.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (java.util.Iterator<java.util.Map.Entry<String, DenyVote>> it =
                denyVotes.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<String, DenyVote> e = it.next();
            if (now - e.getValue().at <= DENY_TTL_MS) {
                continue;
            }
            it.remove();
            log("[文件排他] 票③到期自动解除（设备 " + e.getValue().peerIp + "）");
        }
    }

    /** 写一票（按设备）。只在裁决真的翻转时打一行日志，且该设备全 0 那一刻自动触发一次续发：
     *  ⚠票②(没网络)的解除点就是握手完成,那里本来就有flushPending(ip)⇒这一票解除时不再重复触发,免得同一条目排两次 */
    public synchronized void setFileVote(int which, boolean on) {
        setFileVote(which, on, "");
    }

    public synchronized void setFileVote(int which, boolean on, String peerIp) {
        String key = peerKeyOf(peerIp);
        if (key.isEmpty()) {
            return;
        }
        boolean before = fileGateBlocked(peerIp);
        switch (which) {
            case VOTE_IN_CALL:
                if (on) {
                    inCallKeys.add(key);
                } else {
                    inCallKeys.remove(key);
                }
                break;
            case VOTE_NO_NETWORK:
                if (on) {
                    offlineKeys.add(key);
                } else {
                    offlineKeys.remove(key);
                }
                break;
            // 票③改走 denyConsent / clearDenyConsent（按设备、限时），不再走这张票
            default:
                return;
        }
        boolean after = fileGateBlocked(peerIp);
        if (before == after) {
            return;
        }
        log(after ? "[文件排他] 投票翻转 0→1 ⇒ 该设备的文件与目录不入网、在传的暂停（D16，设备 " + peerIp + "）"
                  : "[文件排他] 投票翻转 1→0 ⇒ 该设备自动续发一次（D16，设备 " + peerIp + "）");
        if (!after && which == VOTE_IN_CALL) {
            // ⚠续发不许跑在调用方的线程上:调用点是CallManager.endCall(挂断按钮=EDT),而flushPending会读pending.jsonl(文件I/O)并同步发文本/剪贴板帧;
            //   甩到既有的UI发送线程=与安卓端flushPending()的io{}同一口径
            try {
                final String onlyIp = peerIp;
                uiSendExec.execute(() -> flushPending(onlyIp));
            } catch (Exception e) {
                log("解除文件排他后的自动续发被拒（多半是本机正在退出）：" + e);
            }
        }
    }

    /** 已知设备打点:与该对端有过消息/文件往来(握手打点在onHello,这里只打"往来"位) */
    void trustContent(String ip) {
        Store st = store;
        if (st != null) {
            st.markTrustedContent(st.convKeyOf(ip));
        }
    }

    private volatile State state = State.STOPPED;
    private volatile String peerLabel = "";
    private volatile boolean running = false;

    private volatile ServerSocketChannel serverSocket;
    final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<Session>();
    /** 已握手会话：deviceId → 会话。只用于「同一台设备的双连接」去重，不同设备各留各的 */
    final ConcurrentHashMap<String, Session> byPeer =
            new ConcurrentHashMap<String, Session>();

    /**
     * 2 期：数据通道（lane）连接池。key = 对端 IP，value = lane 号 → 会话。
     * lane 0 永远是主连接（{@link #byPeer} 里那条），这里只放 lane > 0 的纯数据连接；
     * 它们不注册 peer、不参与去重、不进 UI。
     */
    final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Session>> laneMap =
            new ConcurrentHashMap<String, ConcurrentHashMap<Integer, Session>>();

    /** 已声明走多通道、但 file_meta 还没到的 mid → 对端声明的通道数 */
    final ConcurrentHashMap<String, Integer> multiMids = new ConcurrentHashMap<String, Integer>();

    /**
     * 多通道发送「等接收端建好 Rx」的 latch（mid → latch）。发送端先登记、再发 xe_begin+file_meta，
     * 收到对端 xe_ready 才起数据通道；超时（{@link #XE_READY_WAIT_MS}）降级为主连接单通道发，不失败。
     * 根因：接收端只在解析到 file_meta 时才 createRx，早到的分片会因「未知 mid」被丢弃 →
     * 留下补不齐的单元缺口 → 重排环窗口卡死 30s。
     */
    final ConcurrentHashMap<String, java.util.concurrent.CountDownLatch> xeReadyLatches =
            new ConcurrentHashMap<String, java.util.concurrent.CountDownLatch>();
    /** 等 xe_ready 的上限：局域网往返是毫秒级，2s 只用于兜住「对端不支持/异常」→ 降级单通道 */
    static final long XE_READY_WAIT_MS = 2000L;

    void expectXeReady(String mid, java.util.concurrent.CountDownLatch latch) {
        xeReadyLatches.put(mid, latch);
    }

    void clearXeReady(String mid) {
        xeReadyLatches.remove(mid);
    }

    // ==================== 保活重连 ====================

    /** 连接成功过就要保持长连接:通话/视频/文件传输结束后会话不能掉;
     *  只对"本端主动发起成功"的会话自动重连(能连上说明这个方向可达,对端可能有防火墙只放行单向来连);
     *  对端发起的入向会话不重试(本方向可能被挡,SYN长时间悬挂),由对端的保活负责;每15s一次直到连上/程序退出 */
    private java.util.concurrent.ScheduledExecutorService keepAliveExec = newKeepAliveExec();

    /** P2-6：stop() 会 shutdownNow，start() 必须能重建（字段不能是 final） */
    private java.util.concurrent.ScheduledExecutorService newKeepAliveExec() {
        return java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pp-keepalive");
            t.setDaemon(true);
            return t;
        });
    }

    private final ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>> keepAliveJobs =
            new ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<?>>();

    /** 每台设备"连上就断"的连续次数(快速失败退避判定用;握手成功即清零) */
    private final ConcurrentHashMap<String, Integer> keepAliveFastFails =
            new ConcurrentHashMap<String, Integer>();

    /** 会话「快速失败」判据：连上不足这个时长就被断开 */
    static final long FAST_FAIL_MS = 10_000L;

    /**
     * 会话 socket 的收发缓冲（KB）。0 = 不显式设置，交给内核窗口自动调整（默认，P1-5 结论）。
     * A/B 时改成 1024 / 4096 即可切回固定缓冲，无需改别处。
     */
    static final int SOCK_BUF_KB = 0;

    /**
     * 并发数据通道数（含主连接）。**已回到单连接 = 1**：单通道续传实测 1.1GB 约 8s（140MB/s），
     * 第二条 TCP 连接没有实测收益却多一条断链路径；带宽改由自适应帧大小跑满（见 AdaptiveFrame）。
     * hello 用该值广播，对端 lanesFor 取 min 后不会再拨 lane。
     */
    static final int LANES = 1;
    /** lane 拨号超时（与主连接一致，3s 足够局域网/热点） */
    static final int LANE_CONNECT_TIMEOUT_MS = 3000;
    /** 等 lane 握手的上限；超时按该 lane 不可用处理，降级不失败 */
    static final long LANE_HANDSHAKE_WAIT_MS = 2500L;

    /**
     * 发送侧零拷贝开关（P1-4 A/B）。false 时文件一律走原来的流式分片路径。
     * Android 的 transferTo 走内核 sendfile；Windows 的 JDK 可能内部回落成拷贝循环，
     * 若 PC 端实测反而更慢，把它改成 false 即可。
     */
    static final boolean ZC_SEND = true;
    /** 连续这么多次快速失败→保活重连退避(15s→60s),避免"连上就断"风暴 */
    static final int FAST_FAIL_LIMIT = 3;

    // ==================== 文件发送队列 ====================

    /** 文件/文件夹发送队列:单线程串行——一次只传一个文件,前一个结束立刻上下一个;
     *  不每条文件各起一条线程:批量发几十个文件时会同时开出几十条发送线程,互相抢socket写锁、争带宽,一有异常还各自关会话;
     *  不共用UI发送线程(uiSendExec):那条要处理消息/剪贴板/ack,被大文件占住会让聊天消息排队卡住;
     *  不影响通话与消息:音视频帧由采集、编码线程直接调sendAudio/sendVideo,消息与ack走sendOrDrop,都不进这条队列,
     *  只按帧粒度抢同一个socket的写锁,文件排队再长也不会让语音/视频/消息"排在文件后面" */
    private java.util.concurrent.ExecutorService fileSendExec = newFileSendExec();

    /** P2-6：stop() 会 shutdownNow，start() 必须能重建 */
    private java.util.concurrent.ExecutorService newFileSendExec() {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pp-file-send");
            t.setDaemon(true);
            return t;
        });
    }

    /** 排队等待中的文件 / 文件夹条数（不含正在传的那一个） */
    int fileSendQueueDepth() {
        return (fileSendExec instanceof java.util.concurrent.ThreadPoolExecutor)
                ? ((java.util.concurrent.ThreadPoolExecutor) fileSendExec).getQueue().size() : 0;
    }

    /** 文件/文件夹发送的唯一入队口:入队顺序即发送顺序,一次只有一个在传;
     *  只给"顶层发送入口"用(用户发送/重试/待发补发/hsync补图);
     *  文件夹成员在队列线程内由{@link LinkSend#doSendFolderInner}同步顺序调用,不再各自入队 */
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
                    // 队列空了(最后一个任务跑完)就补跑一次超龄半成品回收:
                    // "取消帧丢失+会话一直不断"的孤儿条目不必等到下次启动
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
        // "连上就断"退避:连续FAST_FAIL_LIMIT次存活不足FAST_FAIL_MS就断,说明有东西在秒杀会话(对端本地源不可读反复补发失败、对端立刻踢连接等);
        // 按5s/15s一直敲只会把日志、网络和界面刷成"不停断开重连"→退避到60s;真正连上并活下来后计数清零(见onHello),立刻恢复正常节奏
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
                // 已连接(任意方向,含同设备另一地址)
                if (sessionByGroup(ip) != null) {
                    cancelKeepAlive(ip);
                    return;
                }
                // 多地址:先试最后一个,再试这台设备的其余地址
                connectTo(ip, true, true);
            } catch (Throwable t) {
                Log.w("保活重连异常: " + t);
            }
        }, first, delay, java.util.concurrent.TimeUnit.SECONDS));
        log("对端 " + ip + " 会话断开，已启动保活重连（每 " + delay + "s"
                + (backoff ? "，已连续 " + fails + " 次快速断开 → 已退避" : "") + "）");
    }

    /** 会话断开时记一次账:存活不足{@link #FAST_FAIL_MS}算"快速失败",供保活重连退避判定;
     *  只在本端主动发起过的会话断开处调用;握手成功(onHello)会把计数清零 */
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

    /** 当前"对话中"的对端IP——设备列表点哪台就是哪台;
     *  同时是主动发送的唯一目标:文本/文件/剪贴板都只发给它,别的设备收不到;
     *  接收侧不看它:收到谁的内容就记在谁名下(由TCP会话本身的身份决定) */
    private volatile String selectedIp = "";

    /** 选中设备在列表里的显示名（未连接时也能显示备注/广播名） */
    private volatile String selectedLabel = "";

    /** 用户点击设备后挂起的一次性连接请求队列(同一台设备的候选地址,按优先级排好);
     *  连接工人按顺序试:某个地址连上并握手成功⇒清空队列、不再试后面的;
     *  全部失败⇒清空队列、不重试(要重试只能等对端被动连过来,或用户再点一次设备) */
    private volatile java.util.List<String> pendingConnectIps;

    /** "点的那个地址连不上、同设备的另一个地址连上了"的回调(请求地址→实际连上的地址);
     *  上层据此把"当前对话地址"跟到真正连上的那个地址上;
     *  ⚠由pp-connect线程回调:不得在这里做Swing操作,上层自行切EDT */
    volatile java.util.function.BiConsumer<String, String> onGroupFallback;

    /** 生成一台设备的候选地址顺序:①传入的那个地址(用户点的/上次选中的)排第一;
     *  ②同设备(同组键=同一deviceId)的其它地址,按最近在线时间倒序;
     *  一台设备可能有多个地址(WiFi/热点/换网段),用户点的可能正是当前不在线的那个——
     *  只试一个地址就直接判"连不上"会误判,试通一个就停,绝不并发、绝不循环重试 */
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

    /** 指定IP的设备当前是否已连接(设备列表打点用,与选中与否无关) */
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

    /** 按IP找已握手且活着的会话。
     * <p>同一个IP上可能同时挂着两台设备的会话(同IP多设备:两台设备各自连进来,或一端连入一端连出),
     * "按IP找"天然有歧义——必须优先返回身份与这个IP的组键一致的那条(即Store认定的活动设备),
     * 否则会出现"列表显示A在线、切过去却是B的会话"。 */
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

    /** 指定deviceId的设备是否有健康会话(同IP多设备的别名行打点用) */
    public boolean isPeerConnected(String devId) {
        if (devId == null || devId.isEmpty()) {
            return false;
        }
        Session s = byPeer.get(devId);
        return s != null && s.handshaked && !s.closed;
    }

    /** 该地址上正连着的、但不是keepDevId这台设备的对端名(同IP多设备冲突判定用);
     *  没有冲突(该地址没会话、或仅keepDevId自己的会话)返回null */
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

    /** 断开某个地址上的会话(同IP多设备冲突时"断开之前的连接"的落点)。
     * <p>只断这个地址上的旧设备会话,别的设备(别的IP)会话一条都不动——多设备独立长连接的语义不能破。
     * @param keepDevId 非空时保留这条设备身份的会话(通常是即将切换过去的那台)
     * @return 实际断开的会话条数 */
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

    /** 按设备找会话:先按IP精确匹配,找不到时回落到同组键(同一deviceId)的任意健康会话;
     *  设备分组的语义就是"这几个地址是同一台机器":用户在顶栏切到该设备的另一个地址(换WiFi/热点)
     *  而会话仍建在原地址上时,按IP精确找会"找不到会话"——消息进待发、通话发不出去,明明这台机器就在线(与Android端同源问题) */
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

    /** 指定设备已连上但还没配对（设备列表提示「未配对，点一下配对」用；没连接 = false） */
    public boolean isDeviceUnpaired(String ip) {
        Session s = sessionByGroup(ip);
        return s != null && !s.paired;
    }

    /** 当前会话（主动发送的目标）；未连接返回 null。
     *  选中地址没会话时回落到同设备的另一个地址（见 [sessionByGroup]）。 */
    Session target() {
        return sessionByGroup(selectedIp);
    }

    /** 当前对话实际可达的对端地址;未选中返回空串。
     * 优先返回会话的remoteIp:设备分组后用户在顶栏切了地址但会话仍建在原地址时,
     * 通话应答帧的源地址必须与CallManager记的peerIp一致,否则呼叫永远接不通。 */
    public String currentPeerIp() {
        Session s = target();
        return s != null ? s.remoteIp() : selectedIp();
    }

    /** 设备列表点选一台设备→设为当前对话目标。
     * 只改目标、不碰别的会话:连着的其他设备不受任何影响(多设备互不干扰)。 */
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

    /** 点选设备并按需连接:已连着就只是切过去(不动连接);没连才发起一次连接尝试。
     * 失败不重试——要重试就再点一次设备(这是"只有点击才触发重连"的落点)。 */
    public void selectAndConnect(String ip, String label) {
        selectTarget(ip, label);
        connectTo(ip);
    }

    /** 发起一次连接尝试(不重试)。若该设备已有健康会话则什么都不做。 */
    public void connectTo(String ip) {
        connectTo(ip, false);
    }

    /** 同上;quiet=true不打"请求连接"类日志(保活重连用,避免每15s刷屏)
     * @param tryGroup true=该设备有多个地址时依次试(见groupCandidates):点的那个先试,连上并握手成功即停,后面的地址不再试;
     *                 全试完仍失败→队列清空、不再重试,直到对端被动连过来或用户再点一次。 */
    /** 同上;quiet=true不打"请求连接"类日志(保活重连用,避免每15s刷屏) */
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
            // 已有会话≠健康:TCP半开(对端进程已死/网络切换)时socket仍"连着",但再也收不到任何帧——表现为"点了没反应/连不上";
            // 超过一轮心跳周期仍无任何收包且我们发过ping未得pong,就判失效、废弃旧会话并重连
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

        /**
         * SocketChannel 直通输入流。
         *
         * **不能用 Channels.newInputStream**：它在阻塞模式下先 {@code synchronized(channel)} 再 read()，
         * 对端没数据时读线程一直抱着这把锁，同一条连接的写线程（控制帧/file_query/取消帧）全排在后面，
         * 只能等对端下一帧把读唤醒（实际是心跳 ping，实测卡 5s）。SocketChannel 本身保证
         * 「一个读线程 + 一个写线程」并发安全，读侧不需要额外互斥。
         */
        static final class ChannelIn extends InputStream {
            private final SocketChannel ch;
            private final ByteBuffer one = ByteBuffer.allocate(1);

            ChannelIn(SocketChannel ch) {
                this.ch = ch;
            }

            @Override
            public int read() throws IOException {
                one.clear();
                int n = ch.read(one);
                return n <= 0 ? -1 : (one.get(0) & 0xff);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return 0;
                }
                int n = ch.read(ByteBuffer.wrap(b, off, len));
                return n < 0 ? -1 : n;
            }

            @Override
            public int available() {
                return 0;
            }

            @Override
            public void close() {
                try {
                    ch.close();
                } catch (Exception ignore) {
                }
            }
        }

        /**
         * SocketChannel 直通输出流（理由见 {@link ChannelIn}）：直接 channel.write，不碰 channel 监视器锁；
         * 写与写的互斥由 {@link Session#writeLock} 负责（零拷贝分片也在同一把锁内）。
         */
        static final class ChannelOut extends OutputStream {
            private final SocketChannel ch;
            private final byte[] one = new byte[1];

            ChannelOut(SocketChannel ch) {
                this.ch = ch;
            }

            @Override
            public void write(int b) throws IOException {
                one[0] = (byte) b;
                write(one, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (len == 0) {
                    return;
                }
                ByteBuffer bb = ByteBuffer.wrap(b, off, len);
                while (bb.hasRemaining()) {
                    if (ch.write(bb) < 0) {
                        throw new IOException("会话已关闭");
                    }
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
                try {
                    ch.close();
                } catch (Exception ignore) {
                }
            }
        }

        final class Session {
        /**
         * 底层用 SocketChannel 而不是 Socket：文件分片的发送侧要靠
         * FileChannel.transferTo(..., SocketChannel) 走 sendfile/TransmitFile 零拷贝，
         * 目标必须是真的 SocketChannel（包一层流或 Socket 就会被 JDK 退化成普通拷贝）。
         * 控制帧/音视频帧走自带流式封装 {@link ChannelIn}/{@link ChannelOut}——**不能用
         * Channels.newInputStream/newOutputStream**（理由见 ChannelIn 注释：会在阻塞读时持锁，写帧被拖到下一次心跳）。
         */
        final SocketChannel channel;
        final Socket socket;
        final boolean outgoing;
        final InputStream input;
        final OutputStream output;
        /** 写锁:保证帧不交错。用ReentrantLock而不是synchronized——心跳线程需要tryLock:
         *  写锁被文件传输占用时跳过本轮ping,否则一条阻塞的会话会把整个心跳循环(其他所有会话的判死)拖住 */
        final ReentrantLock writeLock = new ReentrantLock();
        /** 对端IP,构造时定下(socket关了也能读,用来做归属与状态判定) */
        final String remoteIp;

        volatile String peerId = "";
        volatile String peerName = "";
        volatile boolean handshaked = false;
        /** 对端能力(由hello协商得到)。握手前是"全false"——因此默认不会发视频帧 */
        final PeerCaps caps = new PeerCaps();
        /** 2 期：本条连接的角色。0 = 主连接（唯一注册 peer 的那条）；>0 = 纯数据通道 */
        volatile int lane = 0;
        /** 2 期：对端声明的最大通道数。老端不带 lanes 字段 → 1（不支持多通道） */
        volatile int peerLanes = 1;
        /** 这条 lane 连接已握手、可写数据 */
        volatile boolean laneReady = false;
        volatile long lastRx = System.currentTimeMillis();
        volatile long lastTx = System.currentTimeMillis();
        /** 会话建立时刻:连上不到{@link #FAST_FAIL_MS}就被断=有东西在秒杀会话(保活退避用) */
        final long connectedAt = System.currentTimeMillis();
        /** 已发出ping但之后未收到任何帧(含pong)时为true——判死依据,防自身ping刷lastTx骗过看门狗 */
        volatile boolean pingOutstanding = false;
        volatile boolean closed = false;
        /**
         * 非文件帧（JSON/音频/视频/ping）**本次写出的开始时刻**，0 = 当前没有在写（P1-3 写看门狗）。
         * <p>Socket 没有写超时：对端半死（TCP 在但不读）时 write 会永久阻塞并占住 {@link #writeLock}，
         * 于是 tryPing 永远 tryLock 失败 → pingOutstanding 置不上 → 35s 判死永不触发 → 会话假活。
         * 心跳线程据此判死并 close()，让阻塞中的写立刻抛异常。文件分片不计入（大块传输本来就可能写很久）。
         */
        volatile long writeStartedAt = 0L;

        // ===== P4-5 配对状态 =====
        /** 内容通道是否已放行（配对通过或 token 命中） */
        volatile boolean paired = false;
        /** 0=无/发起方等待，1=接收方出示码，2=发起方输入码 */
        volatile int pairRole = 0;
        volatile String pairCode = "";
        volatile int pairAttemptsLeft = 0;
        volatile long pairDeadline = 0L;
        /** 本端在这条连接上实际带出去的配对 token（空=没带）；空 token 的 pair_ok 只认它 */
        volatile String pairTokenSent = "";
        /** 是否已经走完一次 onSessionReady（重发 hello 不再重复补发/hsync） */
        volatile boolean readyOnce = false;

        String remoteIp() {
            return remoteIp;
        }

        Session(SocketChannel ch, boolean out) throws Exception {
            this.channel = ch;
            this.socket = ch.socket();
            this.outgoing = out;
            InetAddress a = this.socket.getInetAddress();
            this.remoteIp = a == null ? "" : a.getHostAddress();
            // P1-5：**不再显式设置 SO_SNDBUF/SO_RCVBUF**。
            // 应用层一旦设值，内核就关闭发送/接收窗口的自动调整（Windows receive-window auto-tuning），
            // 在速率与 RTT 都在波动的无线链路上固定缓冲只会更差。A/B 时改 SOCK_BUF_KB 即可。
            try {
                this.socket.setTcpNoDelay(true);
                if (SOCK_BUF_KB > 0) {
                    this.socket.setSendBufferSize(SOCK_BUF_KB * 1024);
                    this.socket.setReceiveBufferSize(SOCK_BUF_KB * 1024);
                }
                log("[sock] " + remoteIp + " snd=" + this.socket.getSendBufferSize()
                        + " rcv=" + this.socket.getReceiveBufferSize() + (out ? " (主动)" : " (被动)"));
            } catch (Exception ignore) {
            }
            this.input = new DataInputStream(new BufferedInputStream(new ChannelIn(ch)));
            this.output = new BufferedOutputStream(new ChannelOut(ch));
        }

        void send(Map<String, Object> m) throws Exception {
            writeLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("会话已关闭");
                }
                writeStartedAt = System.currentTimeMillis();
                Protocol.write(output, m);
                lastTx = System.currentTimeMillis();
            } finally {
                writeStartedAt = 0L;
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
         * 零拷贝发送一块（P1-4）：帧头走 channel，载荷由 {@link FileChannel#transferTo} 直接从文件
         * 送进 socket（Linux sendfile / Windows TransmitFile），数据不经过用户态内存。
         * <p>transferTo 一旦半途失败就不可续写：已写出字节 &gt; 0 再报错就直接抛给上层断链重传；
         * 一个字节都没写就失败，才回落到普通拷贝（顺序仍然正确）。
         */
        void sendChunkFile(String mid, int seq, FileChannel src, long pos, int len) throws Exception {
            writeLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("会话已关闭");
                }
                // 控制帧/音视频帧可能还压在 8KB 缓冲里：必须先落地，否则顺序错乱
                output.flush();
                byte[] head = Protocol.chunkHeader(mid, seq, len);
                writeFully(ByteBuffer.wrap(head));
                long at = pos;
                int remain = len;
                long copied = 0L;
                while (remain > 0) {
                    long n;
                    try {
                        n = src.transferTo(at, remain, channel);
                    } catch (Throwable t) {
                        if (copied == 0L) {
                            TxStats.zcFallback();
                            copyRange(src, at, remain);
                            remain = 0;
                            break;
                        }
                        throw t;
                    }
                    if (n <= 0) {
                        if (copied == 0L) {
                            TxStats.zcFallback();
                            copyRange(src, at, remain);
                            remain = 0;
                            break;
                        }
                        throw new IOException("transferTo 无进展 n=" + n + " at=" + at + " remain=" + remain);
                    }
                    at += n;
                    remain -= (int) n;
                    copied += n;
                }
                if (copied > 0) {
                    TxStats.zeroCopy(copied);
                }
                lastTx = System.currentTimeMillis();
            } finally {
                writeLock.unlock();
            }
        }

        private void writeFully(ByteBuffer bb) throws IOException {
            while (bb.hasRemaining()) {
                int n = channel.write(bb);
                if (n <= 0) {
                    throw new IOException("channel closed while writing header");
                }
            }
        }

        /** 回落拷贝用的直接缓冲：每会话复用一个，避免每块都新申请 1MB 直接内存 */
        private ByteBuffer zcTmp;

        /** 零拷贝不可用时的块内回落：FileChannel → 直接缓冲 → channel（仍是一次内核拷贝） */
        private void copyRange(FileChannel src, long pos, int len) throws IOException {
            if (zcTmp == null) {
                zcTmp = ByteBuffer.allocateDirect(1 << 20);
            }
            ByteBuffer tmp = zcTmp;
            long at = pos;
            int remain = len;
            while (remain > 0) {
                tmp.clear();
                tmp.limit(Math.min(remain, tmp.capacity()));
                int r = src.read(tmp, at);
                if (r < 0) {
                    throw new java.io.EOFException("源文件在 " + at + " 处提前结束");
                }
                tmp.flip();
                writeFully(tmp);
                at += r;
                remain -= r;
            }
        }

        /** 语音音频帧:与JSON控制帧/文件分片共用写锁,保证帧不交错。
         * @return 本次写入耗时(ms)——上层(语音码率自适应)据此判断链路是否拥塞 */
        long sendAudio(int seq, long ts, byte[] buf, int off, int len) throws Exception {
            writeLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("会话已关闭");
                }
                long t0 = System.nanoTime();
                writeStartedAt = System.currentTimeMillis();
                Protocol.writeAudio(output, seq, ts, buf, off, len);
                lastTx = System.currentTimeMillis();
                return (System.nanoTime() - t0) / 1_000_000L;
            } finally {
                writeStartedAt = 0L;
                writeLock.unlock();
            }
        }

        /** 视频帧:与音频/文件分片共用写锁。
         * @return 本次写入耗时(ms)——上层据此做"100ms发送截止期"统计 */
        long sendVideo(int seq, long ts, int flag, byte[] buf, int off, int len) throws Exception {
            writeLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("会话已关闭");
                }
                long t0 = System.nanoTime();
                writeStartedAt = System.currentTimeMillis();
                Protocol.writeVideo(output, seq, ts, flag, buf, off, len);
                lastTx = System.currentTimeMillis();
                return (System.nanoTime() - t0) / 1_000_000L;
            } finally {
                writeStartedAt = 0L;
                writeLock.unlock();
            }
        }

        /** 心跳专用:tryLock发ping。返回false=写锁被文件传输占用(本端正在向对端发数据,链路显然活着,本轮跳过);
         *  返回true=ping已发出(或会话已关/发送异常转判死流程) */
        boolean tryPing() {
            if (!writeLock.tryLock()) {
                return false;
            }
            try {
                if (closed) {
                    return true;
                }
                writeStartedAt = System.currentTimeMillis();
                Protocol.write(output, Protocol.ping());
                lastTx = System.currentTimeMillis();
                return true;
            } catch (Throwable t) {
                close();  // 与 sendOrDrop 同语义：发送失败即判死
                return true;
            } finally {
                writeStartedAt = 0L;
                writeLock.unlock();
            }
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                channel.close();
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
        // P2-6：线程池是一次性的，stop() 已 shutdownNow；重启链路必须重建，否则第一个任务就 RejectedExecutionException
        if (keepAliveExec.isShutdown()) {
            keepAliveExec = newKeepAliveExec();
        }
        if (fileSendExec.isShutdown()) {
            fileSendExec = newFileSendExec();
        }
        if (uiSendExec.isShutdown()) {
            uiSendExec = newUiSendExec();
        }
        if (frameExec.isShutdown()) {
            frameExec = newFrameExec();
        }
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
        frameExec.shutdownNow();
        try {
            ServerSocketChannel ss = serverSocket;
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
        // 停止链路前取消的mid不会被发送循环处理到,清掉避免残留到进程结束(重启后不应再有"幽灵取消"命中新传输)
        cancelled.clear();
        // P2-6：运行时容器一并清。接收上下文先停写线程、关流（不删半成品，留给续传）再清表，
        // 否则下一次 start() 会命中上一轮的 Rx（旧进度/旧 sink）
        synchronized (rxMap) {
            for (Rx rx : new java.util.ArrayList<Rx>(rxMap.values())) {
                rx.writerAlive = false;
                synchronized (rx.flushLock) {
                    rx.flushLock.notifyAll();
                }
                try {
                    rx.sink.close();
                } catch (Exception ignore) {
                }
            }
            rxMap.clear();
        }
        synchronized (dirMap) {
            dirMap.clear();
        }
        pausedMids.clear();
        resumeWaiters.clear();
        blkAckWaiters.clear();
        resumeNeedBlk.clear();
        resumePrep.clear();
        memberOfMid.clear();
        dirHaveBox.clear();
        dirMemberAck.clear();
        consent.clearAll();
        // 回执登记同理：停机后不该再有等 ack 的条目（待发台账是持久的，重启照旧补发）
        send.awaitingAck.clear();
        send.rejectedAck.clear();
        pendingConnectIps = null;
        wakeUp();
        setState(State.STOPPED, "");
        log("链路已停止");
    }

    /** 当前选中设备是否已连接 */
    public boolean isConnected() {
        return target() != null;
    }

    /** 依据"会话集合+选中目标"重新算一次对外状态。
     * 状态一律推导而不是命令式赋值:去重会主动关掉多余的那条连接,若靠"关连接时顺手置位",两条同IP的会话会互相踩;推导天然不受顺序影响 */
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

    /** 连接工人是否还有请求在处理（未出队或在拨号中）——启动阶段等一次尝试出结果用 */
    boolean isDialing() {
        return pendingConnectIps != null || connectingIp != null;
    }

    static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    // ==================== 监听 / 连接 ====================

    private void acceptLoop() {
        // 绑定重试:与Android端对称。端口被占(旧实例退出中/TIME_WAIT等)时一次失败就永久退出会让本机"谁都连不上",每3s重试直到成功或停止
        ServerSocketChannel ss = null;
        int attempt = 0;
        while (running && ss == null) {
            try {
                ServerSocketChannel s = ServerSocketChannel.open();
                s.setOption(StandardSocketOptions.SO_REUSEADDR, Boolean.TRUE);
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
                SocketChannel sk = ss.accept();
                sk.socket().setTcpNoDelay(true);
                adopt(sk, false);
            }
        } catch (Throwable t) {
            if (running) {
                log("监听退出: " + t);
            }
        }
    }

    /** 连接工人:只在有人点了设备时才动一次,失败不重试。
     *  连不上就1s→2s→5s一直敲的话,三端在线时会互相刷日志、互相挤连接,所以
     *  "点一次、试一次":请求由connectTo()投递,处理完即清空,之后线程只是睡着,不再产生任何网络行为;
     *  一台设备多个地址:请求是一串候选地址(见groupCandidates),串行逐个试——某个地址连上并握手成功⇒立刻停,
     *  全部失败⇒队列清空、不再重试,直到对端被动连过来或用户再点一次设备 */
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
                // 队列已被接管(对端被动连上/用户点了另一台设备)⇒让出
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
                // 设备层已可达(刚有会话连上/被动接纳)⇒剩余候选全部作废
                if (sessionByGroup(target) != null) {
                    pendingConnectIps = null;
                    refreshState();
                    break;
                }
                connectingIp = target;
                stateNote = "";
                refreshState();
                Session s = null;
                // 首个候选（多半是上次成功地址）给足 3s；同设备的其它候选多是换网前的过期地址，
                // 给它 1.2s 就够（实测 .48 那种死地址每个吃满 3s，白白拖慢首连）
                final int connectTimeout = target.equals(requested) ? 3000 : 1200;
                try {
                    // SocketChannel 的 socket 适配器支持带超时的 connect；语义与原实现一致
                    SocketChannel sk = SocketChannel.open();
                    sk.socket().setTcpNoDelay(true);
                    sk.socket().connect(new InetSocketAddress(target, port()), connectTimeout);
                    log("主动连接 " + target + ":" + port() + " 成功");
                    s = adopt(sk, true);
                    // TCP通≠这台设备在线:地址可能被别的主机/服务占用;
                    // 等一帧hello握手确认"对端确实是我们的对端"才算试通,否则换下一个地址
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
    private Session adopt(SocketChannel socket, boolean outgoing) {
        return adopt(socket, outgoing, 0);
    }

    private Session adopt(SocketChannel socket, boolean outgoing, int lane) {
        Session s;
        try {
            s = new Session(socket, outgoing);
            s.lane = lane;
        } catch (Exception e) {
            log("创建会话失败: " + e);
            try {
                socket.close();
            } catch (Exception ignore) {
            }
            return null;
        }
        // 网段判定只在这一处:命中⇒不进sessions、不发hello、不启读线程,立即close放掉FD,返回null让调用方按普通"连不上"处理;不打日志(命中痕迹不落pc.log)
        String rip = s.remoteIp();
        if (BlocklistIpv6.isHighRisk(rip) || (store != null && store.isBlockedAddr(rip))) {
            String key = store == null ? rip : store.convKeyOf(rip);
            if (store != null) {
                store.markBlockedGroup(key);
            }
            // 调试出口已移除（发布包不留标准输出痕迹）
            s.close();
            return null;
        }
        // 后台入向闸门(与安卓端incomingGate同规则):窗口隐藏进托盘时只放行已知设备(握手过+有消息/文件往来),
        // 陌生设备直接close,不发hello、不进sessions;前台不受限
        if (!outgoing && !rip.isEmpty() && windowHidden.getAsBoolean()
                && (store == null || !store.isTrustedAddr(rip))) {
            log("后台拒接陌生设备入向连接：" + rip);
            s.close();
            return null;
        }
        sessions.add(s);
        log("接纳" + (outgoing ? "主动" : "被动") + "连接 " + s.remoteIp()
                + (lane > 0 ? "（数据通道 lane=" + lane + "）" : "")
                + "（当前在线 " + sessions.size() + " 条）");
        try {
            // token 按设备身份（deviceId）优先、IP 兜底：只认 IP 的话，对端换 IP/本机换了接入点就查空，
            // hello 不带 token ⇒ 对端把你当新设备拖去重新配对
            String helloTok = store == null ? "" : store.pairedTokenFor(s.remoteIp());
            s.pairTokenSent = helloTok;
            s.send(Protocol.hello(myId(), myName(), "windows", localVidCap, false, localVCodecs,
                    LANES, lane, helloTok));
        } catch (Exception e) {
            s.close();
            sessions.remove(s);
            return null;
        }
        daemon(() -> readLoop(s), (lane > 0 ? "pp-read-l" + lane : "pp-read-") + s.remoteIp());
        return s;
    }

    // ==================== 2 期：数据通道（lane）连接池 ====================

    /**
     * 取本次传输可用的通道集合（含主连接）。want 与对端能力取小；
     * 某条 lane 建不起来就按实际条数发，不因此失败（最差退化为 1 期单通道）。
     */
    java.util.List<Session> lanesFor(Session main, int want) {
        int n = Math.max(1, Math.min(want, main.peerLanes));
        java.util.ArrayList<Session> list = new java.util.ArrayList<Session>(n);
        list.add(main);
        if (n <= 1) {
            return list;
        }
        for (int l = 1; l < n; l++) {
            Session exist = laneMap.getOrDefault(main.remoteIp(), new ConcurrentHashMap<Integer, Session>()).get(Integer.valueOf(l));
            if (exist != null && !exist.closed && exist.laneReady) {
                list.add(exist);
                continue;
            }
            Session ns = null;
            try {
                ns = dialLane(main, l);
            } catch (Throwable ignore) {
            }
            if (ns == null) {
                break;
            }
            list.add(ns);
        }
        if (list.size() < n) {
            log("多通道：只建起 " + list.size() + "/" + n + " 条通道，按实际条数发送");
        }
        return list;
    }

    /** 拨一条数据通道：连上 → adopt(lane) → 等对端 hello 完成挂载；失败返回 null（调用方降级） */
    private Session dialLane(Session main, int lane) {
        String ip = main.remoteIp();
        if (ip.isEmpty()) {
            return null;
        }
        SocketChannel sk = null;
        try {
            sk = SocketChannel.open();
            sk.socket().setTcpNoDelay(true);
            sk.socket().connect(new InetSocketAddress(ip, port()), LANE_CONNECT_TIMEOUT_MS);
            Session ns = adopt(sk, true, lane);
            if (ns == null) {
                return null;
            }
            long t0 = System.currentTimeMillis();
            while (running && !ns.closed && System.currentTimeMillis() - t0 < LANE_HANDSHAKE_WAIT_MS) {
                if (ns.laneReady) {
                    laneMap.computeIfAbsent(ip, k -> new ConcurrentHashMap<Integer, Session>())
                            .put(Integer.valueOf(lane), ns);
                    log("数据通道 lane=" + lane + " 建立 " + ip);
                    return ns;
                }
                waitFor(50);
            }
            log("数据通道 lane=" + lane + " 握手超时（" + ip + "），降级不用它");
            ns.close();
            return null;
        } catch (Throwable t) {
            log("数据通道 lane=" + lane + " 连接失败（" + ip + "）：" + t.getMessage());
            if (sk != null) {
                try {
                    sk.close();
                } catch (Exception ignore) {
                }
            }
            return null;
        }
    }

    /** 主连接断开时顺手收掉它的所有 lane（避免半开连接堆积） */
    void closeLanes(String ip) {
        ConcurrentHashMap<Integer, Session> m = laneMap.remove(ip);
        if (m == null) {
            return;
        }
        for (java.util.Map.Entry<Integer, Session> e : m.entrySet()) {
            try {
                e.getValue().close();
            } catch (Exception ignore) {
            }
            sessions.remove(e.getValue());
            log("数据通道 lane=" + e.getKey() + " 随主连接关闭");
        }
    }

    private void readLoop(Session s) {
        try {
            while (running && !s.closed) {
                long rfT0 = System.nanoTime();
                // 快路径：解析到分片头就先问「这块落到哪」，能直读进 4MB 轮转缓冲就不分配临时数组
                Protocol.Frame f = Protocol.readFrameInto(s.input, new Protocol.ChunkSinkResolver() {
                    @Override
                    public Protocol.ChunkSink resolve(String mid, int seq, int len) {
                        return rx.reserveDirectChunk(mid, seq, len);
                    }
                });
                TxStats.read(System.nanoTime() - rfT0);
                if (f == null) {
                    break;
                }
                s.lastRx = System.currentTimeMillis();
                s.pingOutstanding = false;
                if (f instanceof Protocol.Frame.JsonF) {
                    handleFrame(s, ((Protocol.Frame.JsonF) f).j);
                } else if (!s.paired) {
                    // P4-5 闸门补全：二进制分片/音频/视频同样受配对约束（原来只拦 JSON，
                    // 跳过 hello 直灌二进制帧可绕过配对）；未配对静默丢弃
                    continue;
                } else if (f instanceof Protocol.Frame.Chunk) {
                    Protocol.Frame.Chunk c = (Protocol.Frame.Chunk) f;
                    rx.onChunk(s, c.mid, c.seq, c.data, c.len, c.off, c.direct);
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
                    + (s.handshaked ? "" : " (未握手)")
                    + (s.lane > 0 ? " (lane " + s.lane + ")" : ""));
            if (s.lane > 0) {
                // 数据通道断开：只摘自己，**不能**中止该对端的接收（主连接还在传）
                ConcurrentHashMap<Integer, Session> m = laneMap.get(s.remoteIp());
                if (m != null && m.get(Integer.valueOf(s.lane)) == s) {
                    m.remove(Integer.valueOf(s.lane));
                }
            } else {
                // 去重收尾保护：同设备已有另一条握手完成且未关闭的会话（本条是被淘汰/半旧的连接）时，
                // 不能按 IP 清扫接收与 lane，否则会把新会话上刚建立的传输误杀
                boolean newerAlive = false;
                for (Session o : sessions) {
                    if (o != s && !o.closed && o.handshaked && o.remoteIp().equals(s.remoteIp())) {
                        newerAlive = true;
                        break;
                    }
                }
                if (newerAlive) {
                    log("会话结束 " + s.remoteIp() + "：同 IP 已有新会话，跳过接收/通道清扫");
                } else {
                    rx.abortRxsFor(s.remoteIp());
                    // 单任务槽：断链兜底释放发送侧（接收侧已在 abortRxsFor 里按对端收口）
                    releaseTaskFor(s.remoteIp(), "send");
                    closeLanes(s.remoteIp());
                }
            }
            notifyPeers();
            // 保活重连:本端发起成功过的会话断开后自动重连(保持长连接;单向防火墙场景由可达的一端负责重连)。入向会话不重试
            if (running && s.lane == 0 && s.handshaked && s.outgoing) {
                noteSessionDrop(s);          // 「连上就断」记账（保活退避判定用）
                scheduleKeepAlive(s.remoteIp());
            }
            refreshState();
        }
    }

    /** 心跳循环(每5s一次,见{@link #STALL_SCAN_MS}):ping+假连接判定+停滞看门狗。
     *  停滞看门狗每tick顺带跑(不新开线程):接收侧30s无进度/发送侧30s无新字节→停成"已暂停",半成品保留,重连后自动续传;
     *  另回收闲置10分钟的未完成目录 */
    private void heartbeatLoop() {
        while (running) {
            waitFor(STALL_SCAN_MS);
            if (!running) {
                return;
            }
            // 停滞看门狗（5s 周期）：先收后发，再回收闲置目录；顺便撤掉 60s 没等到落盘回执的登记
            rx.scanStalledRx();
            scanStalledSends();
            send.scanAckTimeouts();
            pruneDenyVotes();     // 票③到期自动解除（P1-1）
            rx.pruneDirs();
            for (Session s : sessions) {
                if (s.closed) {
                    continue;
                }
                // 写看门狗（P1-3，与安卓同口径）：Socket 没有写超时，对端不读时 write 会一直阻塞并占着写锁；
                // 关会话让阻塞中的写立即抛异常，写线程自己清标记、上层按断链处理。
                // 只统计非文件帧（send/sendAudio/sendVideo/ping 写入期间置 writeStartedAt）
                long wAt = s.writeStartedAt;
                if (wAt > 0 && System.currentTimeMillis() - wAt > CONTROL_WRITE_TIMEOUT_MS) {
                    log("非文件帧写入超过 " + (CONTROL_WRITE_TIMEOUT_MS / 1000)
                            + "s 未完成（对端不读/半死），关闭会话解阻塞");
                    s.close();
                    continue;
                }
                if (!s.handshaked && System.currentTimeMillis() - s.lastRx > 8000) {
                    log("握手超时，关闭会话");
                    s.close();
                    continue;
                }
                // P4-5 配对超时：到点还没配好就关会话并提示，避免半开连接一直挂在列表里
                if (s.handshaked && !s.paired && s.pairDeadline > 0
                        && System.currentTimeMillis() > s.pairDeadline) {
                    log("配对超时（" + s.peerName + " " + s.remoteIp() + "），通知对端关弹窗并关闭会话");
                    // 先告诉对端「这次配对作废」：否则对端会一直停在出示码/输入键盘的界面上，
                    // 自己这边的超时提示关掉了，对端却像卡死一样（两端状态必须一起收）
                    if (!s.closed) {
                        trySend(s, Protocol.pairCancel());
                    }
                    if (pairUiMatches(s)) {
                        pairUiIp = "";
                        if (listener != null) {
                            listener.onPairState(new PairUi(s.remoteIp(), s.peerName,
                                    s.pairRole == 1, "", 0, 0,
                                    "配对超时，请重连后重新配对", true, false));
                        }
                    }
                    s.close();
                    continue;
                }
                // 判死只看收包:自身ping只会刷lastTx(内核ACK即"成功"),不能作为对端存活证据;
                // pingOutstanding=true表示上一轮ping之后15s内连pong都没等到,两轮即35s+无任何收包⇒判死
                long rxIdle = System.currentTimeMillis() - s.lastRx;
                if (s.handshaked && s.pingOutstanding && rxIdle > 35000) {
                    log("对端 35s 无响应（ping 无 pong），判定断链");
                    s.close();
                    continue;
                }
                // 只有ping真发出去了才置pingOutstanding;tryLock失败(正在传文件)就跳过本轮——否则"想发没发成"会把活跃会话误判死
                if (s.tryPing()) {
                    s.pingOutstanding = true;
                }
            }
        }
    }

    // ==================== 删除（v2.4 删除同步 / 清空）====================

    /** 本机删除一条消息后清掉本地历史/待发/界面;{@code syncable}=是否同时通知对端
     *  (只有文本+图片才通知,见协议§4.7;文件/文件夹不发msg_del) */
    public void deleteMessage(String peerIp, String mid, boolean syncable) {
        hsync.deleteMessage(peerIp, mid, syncable);
    }

    /** 批量删除("清空当前会话"用):按mid逐条清掉本地历史/待发/界面,并把其中参与同步的那部分(文本+图片)一次性通知对端;
     *  不循环调deleteMessage:那样每条都要把历史文件整读整写一遍(N条→N次全文件重写,O(N²)磁盘往返),这里合并成一次遍历+一帧msg_del(按200条分片);
     * @param allMids 要清掉的全部mid(含文件/文件夹/剪贴板等不同步的类型)
     * @param syncableMids 其中"文本+图片"的mid(要发给对端的那部分)
     * @return 实际请求对端删除的条数(0=对端离线或老版本,只清了本机) */
    public int deleteMessages(String peerIp, List<String> allMids, List<String> syncableMids) {
        return hsync.deleteMessages(peerIp, allMids, syncableMids);
    }

    /** 对端此刻能不能收到删除命令(在线+握手完成+支持hd)——只看不发。
     *  "清空"用它决定提示语(对方在线=会一起清;离线=只清本机,下次可能被同步回来) */
    public boolean canSendMsgDel(String peerIp) {
        return hsync.canSendMsgDel(peerIp);
    }

    // ==================== 帧处理 ====================

    /** 帧分发。
     *  归属完全由会话决定:协议帧里没有"发给谁"的字段(两端一致、无需改动),
     *  因为每条TCP长连接天然只服务一个对端——从哪条会话读到就属于哪台设备;
     *  所有新建的Msg都打上peerIp=s.remoteIp(),于是A→B的内容只会落在B的会话里 */
    private void handleFrame(Session s, Map<String, Object> j) {
        String type = Json.str(j, "type", "");
        // 剪贴板相关帧只在局域网链路生效:非局域网对端发来的直接忽略(不写本机剪贴板、不应答),
        // 与本端出向门控对称。判据口径与安卓端LanGate同源
        if (type.startsWith("clip") && !LocalIps.isLanPeer(s.remoteIp())) {
            return;
        }
        // ===== P4-5 闸门：未配对的会话只准走心跳与配对握手，任何内容帧直接丢 =====
        // 不再以 handshaked 为前提：TCP 连上后、hello 到达前同样要拦——
        // 否则跳过 hello 直发内容帧就能绕过配对往本机写消息/文件（首次连接必须先输配对码）
        if (s.lane == 0 && !s.paired && !PAIRED_ALLOWED_FRAMES.contains(type)) {
            log("未配对会话 " + s.remoteIp() + " 发来 " + type + "，已丢弃（内容通道要配对通过后才放行）");
            return;
        }
        // lane 会话只承载分片与通道控制帧，其余内容帧一律丢弃（防绕过配对闸门注水）
        if (s.lane > 0 && !LANE_ALLOWED_FRAMES.contains(type)) {
            log("数据通道 lane=" + s.lane + " 收到非通道帧 " + type + "，已丢弃");
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
            case "pair_need":
                runFrameTask("pair_need", () -> onPairNeed(s, j));
                break;
            case "pair_submit":
                runFrameTask("pair_submit", () -> onPairSubmit(s, j));
                break;
            case "pair_ok":
                runFrameTask("pair_ok", () -> onPairOk(s, j));
                break;
            case "pair_fail":
                runFrameTask("pair_fail", () -> onPairFail(s, j));
                break;
            case "pair_cancel":
                runFrameTask("pair_cancel", () -> onPairCancel(s));
                break;
            case "busy": {
                // 新协议：带 mid = 本机这一条被"单任务槽"回绝了。只暂缓该条目（不关会话），
                //   等对端空出来发的 sync_req 触发待发队列重试；
                // 不带 mid = 旧版本对端（"同一时刻只服务一个对端"），维持老逻辑：提示 + 断开会话
                String mid = Json.str(j, "mid", "");
                String name = Json.str(j, "name", "");
                String holder = Json.str(j, "holder", "");
                if (!mid.isEmpty()) {
                    markPeerBusy(mid, name.isEmpty() ? holder : name, s.remoteIp());
                    break;
                }
                log("对端（旧版本）返回忙" + (holder.isEmpty() ? "" : "：正与 " + holder + " 会话")
                        + "，本端不自动重试，稍后再点一次设备");
                stateNote = "对端忙（旧版本同时只服务一个对端），稍后再点一次";
                s.close();
                break;
            }
            case "text": {
                trustContent(s.remoteIp());   // 已知设备打点②：收到文本
                String mid = Json.str(j, "mid", newMid());
                // 统一按mid查重:待发补发/hsync补齐可能重放同一条,历史里已有的mid不再生成第二个气泡,但ack照回(对端要靠它出队)
                if (store != null && store.hasMid(s.remoteIp(), mid)) {
                    log("文本 mid=" + mid + " 本机已有（重复投递），跳过");
                } else {
                    Msg m = new Msg();
                    m.mid = mid;
                    m.peerIp = s.remoteIp();
                    m.dir = Msg.DIR_PEER;
                    m.kind = Msg.KIND_TEXT;
                    m.text = Protocol.limitText(Json.str(j, "text", ""));
                    m.ts = Protocol.saneTs(Json.lng(j, "ts", System.currentTimeMillis()));
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
                    // 握手协商第二步:手机剪贴板缓冲为空⇒反过来把电脑这边的推过去;电脑侧也没有内容时pushLocalClipIfAny自然什么都不做
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
                trustContent(s.remoteIp());   // 已知设备打点③：收到文件
                rx.onFileFrame(s, j);
                break;
            case "file_done": {
                trustContent(s.remoteIp());
                // 落盘收尾（最长 60s）不能坐在网络读线程上：期间读线程不读 socket ⇒ 心跳把自己判死、
                // 对端 ack 也等不到。投到单线程收尾执行器，同一文件夹的 file_done 一定先于 dir_end
                final Session fs = s;
                final Map<String, Object> fj = j;
                runRxFinalize("file_done", () -> rx.onFileFrame(fs, fj));
                break;
            }
            // ===== 2 期多通道：开传声明在 file_meta 之前到达，接收端据此把该 mid 建成重排环 =====
            case "xe_begin":
                rx.onXeBegin(s, j);
                break;
            case "xe_end": {
                String emid = Json.str(j, "mid", "");
                if (!emid.isEmpty()) {
                    multiMids.remove(emid);
                }
                break;
            }
            case "xe_ready": {
                // 接收端 Rx 已建好 → 放行发送端的多通道线程
                String rmid = Json.str(j, "mid", "");
                java.util.concurrent.CountDownLatch latch =
                        rmid.isEmpty() ? null : xeReadyLatches.get(rmid);
                if (latch != null) {
                    latch.countDown();
                }
                break;
            }
            case "lane_ok":
                log("数据通道 lane=" + Json.integer(j, "lane", 0) + " 对端已挂载");
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
            // 32MB 分块大小校验：核验请求 / 核验结果
            case "blk_ack":
                rx.onBlkAck(j);
                break;
            case "blk_verify":
                runFrameTask("blk_verify", () -> rx.onBlkVerify(s, j));
                break;
            case "dir_meta":
                trustContent(s.remoteIp());   // 文件夹成员同样算文件往来
                rx.onDirMeta(s, j);
                break;
            case "dir_have":
                rx.onDirHave(j);
                break;
            case "dir_end": {
                final Session fs = s;
                final Map<String, Object> fj = j;
                runRxFinalize("dir_end", () -> rx.onDirEnd(fs, fj));
                break;
            }
            case "dir_missing":
                rx.onDirMissing(j);
                break;
            case "file_cancel":
                rx.onRemoteFileCancel(Json.str(j, "mid", ""));
                break;
            case "sync_req":
                // 对端空出来了（它刚释放任务槽）：先清掉它名下的 busy 记号，再补发 ——
                // 否则那些被回绝的条目会一直等到 TTL 过期才重试
                clearPeerBusyFor(s.remoteIp());
                // 只补发**这台设备**的待发内容，不波及其他会话
                flushPending(s.remoteIp());
                break;
            case "ack": {
                // "对端确认收到"应答。**文件的落盘确认以它为准**：send.applyAck 用登记时留下的完整
                // 气泡快照(mid/peerIp/kind/fileName/fileSize)重绘，不会重犯"裸帧被当TEXT画成空白气泡、
                // 还把0B写进历史"的老坑；文本/其它无登记的 mid 返回 false，只记日志
                String ackMid = Json.str(j, "mid", "");
                boolean ackOk = Json.bool(j, "ok", true);
                // 文件夹成员的回执：只记账，要不要重发由 dir_end 的清单对账说了算
                if (onMemberAck(ackMid, ackOk)) {
                    break;
                }
                if (!send.applyAck(ackMid, ackOk)) {
                    log("对端回执 mid=" + ackMid + (ackOk ? " ok" : " fail") + "（无在途文件登记，仅记录）");
                }
                break;
            }
            case "call_invite": {
                Listener l = listener;
                if (l != null) {
                    // 视频呼叫带vid/lv,语音帧长带af,媒体通道带mport/mudp/msid;
                    // 三项缺省即回退:老对端不带⇒0/false/空⇒本端只能落带内档(§4.2)
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
                // 振铃期逐档协商。老对端不会发这条;本端读到缺省即"未声明"
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
            // 视频控制帧。老对端不认识这三个type→走default只记日志,天然忽略
            case "video_cfg": {
                Listener l = listener;
                if (l != null) {
                    // ⚠这里在网络读线程上:实现方只允许落字段+让解码线程自己重启进程,
                    //   绝不能在这里spawn ffmpeg(会阻塞读线程→对端35s判死掉线)
                    l.onVideoCfg(s.remoteIp(), Json.str(j, "codec", ""),
                            Json.integer(j, "w", 0), Json.integer(j, "h", 0),
                            Json.integer(j, "fps", 0), Json.integer(j, "br", 0),
                            Json.integer(j, "rot", 0),
                            // cam:1=前置,0/缺省=后置
                            Json.integer(j, "cam", 0) != 0);
                }
                break;
            }
            case "video_fb": {
                Listener l = listener;
                if (l != null) {
                    l.onVideoFb(s.remoteIp(), Json.integer(j, "fps", 0),
                            Json.integer(j, "drop", 0), Json.integer(j, "lat", 0),
                            // dropping：老对端不带此键 = false
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
            // 数据流量确认:v2.7。PC恒不在运营商数据网络(有线/无线局域网),所以收到询问一律自动同意,不需要打扰用户;
            //  只需回 data_ans 给发起方。data_ask 的 mid 由对端生成,与本机等待信箱的 mid 不可能相同,
            //  往本机信箱投递是空操作,已移除(本机信箱只由下方 data_ans 分支投递)。
            case "data_ask": {
                String dmid = Json.str(j, "mid", "");
                if (!dmid.isEmpty()) {
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
            // 历史同步(hsync,v2.3)。处理要扫history文件、重发文件,不能卡网络读线程⇒甩pp-hsync工作线程(与Android端对称);
            //  修复:此前漏接分发,手机发来的hsync_mids落default记"未知帧类型",历史补齐整条链路在PC侧是死的
            case "hsync_mids":
            case "hsync_pull":
            case "hsync_msg": {
                final Session fs = s;
                final Map<String, Object> fj = j;
                final String ftype = type;
                runFrameTask("hsync", () -> {
                    if ("hsync_mids".equals(ftype)) {
                        hsync.onHsyncMids(fs, fj);
                    } else if ("hsync_pull".equals(ftype)) {
                        hsync.onHsyncPull(fs, fj);
                    } else {
                        hsync.onHsyncMsg(fs, fj);
                    }
                });
                break;
            }
            // ==================== 消息删除同步（v2.4）====================

            // 清历史文件可能不快，不卡网络读线程 → 有界工作池
            case "msg_del": {
                final Session fs = s;
                final Map<String, Object> fj = j;
                runFrameTask("msg_del", () -> hsync.onRemoteMsgDel(fs, fj));
                break;
            }
            default:
                log("未知帧类型: " + type);
        }
    }

    /** 握手:把这条连接登记到它那台设备名下。
     *  不同设备一律照收(这是"多设备独立长连接、互不干扰"的核心):旧版在已有活跃会话时会回busy把新设备顶掉——那正是"三端同时在线反复连接"的根源;
     *  现在每台设备各占一条TCP,收发完全独立,谁也挤不掉谁。
     *  只对同一台设备的双连接去重(两端同时点连接就会各连一条):规则preferOutgoing=(myId<peerId),两端各自独立算出同一结论,避免各留一条 */
    private void onHello(Session s, Map<String, Object> j) {
        String pid = Json.str(j, "id", "");
        if (pid.isEmpty()) {
            s.close();
            return;
        }
        // 自连闸门：对端自称的 deviceId 就是本机 ⇒ 连到了自己（本机 IP / 环回被当设备填进了列表）。
        // 放进来会绑出「自己是自己的设备」的组，而且那一行 lastId=本机 id 在列表里被过滤掉、删不掉，
        // 之后每次启动都自连一轮。与安卓端同口径，必须在任何绑定/登记之前拒掉。
        if (pid.equals(myId())) {
            log("握手：对端自报 deviceId 与本机相同（" + s.remoteIp() + "），判定自连，关闭会话");
            s.close();
            sessions.remove(s);
            return;
        }
        // ===== 版本闸门（v4）：hello 必须自报协议版本，不一致直接拒连（与安卓同口径）=====
        int peerVer = Json.integer(j, "ver", 0);
        if (peerVer != Protocol.PROTO_VER) {
            log("对端协议 v" + peerVer + " 与本端 v" + Protocol.PROTO_VER
                    + " 不一致（缺 ver 按 0 计），拒绝连接；请把两端都升级到最新版本");
            stateNote = "对端版本过旧（协议 v" + peerVer + "），请同时升级两端";
            s.close();
            return;
        }
        // ===== 分片大小闸门（v3）：old 端不带 chunk → 按 1MB 处理，两端 offset 换算不一致会把文件写错位 =====
        int peerChunk = Json.integer(j, "chunk", 1024 * 1024);
        if (peerChunk != Protocol.CHUNK_SIZE) {
            log("对端分片 " + peerChunk + "B 与本端 " + Protocol.CHUNK_SIZE
                    + "B 不一致（旧版本），拒绝连接；请把两端都升级到最新版本");
            stateNote = "对端版本过旧（分片 " + peerChunk + "B），请同时升级两端";
            s.close();
            return;
        }
        // P4-1 32MB 分块校验 / P4-5 配对能力闸门：老端不带这两个字段 → 续传/配对都无法协商，直接拒
        int peerBlk = Json.integer(j, "blk", 0);
        boolean peerPairCap = Json.bool(j, "pr", false);
        if (peerBlk != Protocol.BLOCK_SIZE || !peerPairCap) {
            log("对端缺 32MB 分块校验/配对能力（blk=" + peerBlk + " pr=" + peerPairCap
                    + "），拒绝连接；请把两端都升级到最新版本");
            stateNote = "对端版本过旧（不支持分块校验/设备配对），请同时升级两端";
            s.close();
            return;
        }
        // ===== 2 期：数据通道（lane）连接只挂载，不注册 peer、不参与去重 =====
        s.peerLanes = Math.max(1, Json.integer(j, "lanes", 1));
        int jlane = Json.integer(j, "lane", 0);
        if (jlane > 0) {
            // 安全闸门：lane 必须从属于一条已握手、已配对的同设备主连接，否则不挂
            Session laneMain = byPeer.get(pid);
            if (laneMain == null || laneMain.closed || !laneMain.handshaked || !laneMain.paired) {
                log("拒绝挂载数据通道 lane=" + jlane + "：" + s.remoteIp()
                        + " 无已配对主连接（peer=" + pid + "）");
                s.close();
                sessions.remove(s);
                return;
            }
            s.lane = jlane;
            s.peerId = pid;
            s.peerName = Json.str(j, "name", pid);
            s.handshaked = true;
            s.laneReady = true;
            s.paired = true;   // 数据通道不做配对，主连接配对通过后才可能被拨
            laneMap.computeIfAbsent(s.remoteIp(), k -> new ConcurrentHashMap<Integer, Session>())
                    .put(Integer.valueOf(jlane), s);
            log("数据通道 lane=" + jlane + " 已挂载 " + s.remoteIp()
                    + "（对端 lanes=" + s.peerLanes + "）");
            try {
                s.send(Protocol.laneOk(jlane));
            } catch (Throwable ignore) {
            }
            return;
        }
        // 拨号方自己的 lane 会话：收到的是对端把自己当主连接发来的 hello（lane=0），同样不能走去重
        if (s.lane > 0) {
            Session laneMain = byPeer.get(pid);
            if (laneMain == null || laneMain.closed || !laneMain.handshaked || !laneMain.paired) {
                log("数据通道 lane=" + s.lane + " 主连接未就绪（peer=" + pid + "），关闭");
                s.close();
                sessions.remove(s);
                return;
            }
            s.peerId = pid;
            s.peerName = Json.str(j, "name", pid);
            s.handshaked = true;
            s.laneReady = true;
            s.paired = true;
            return;
        }
        s.peerId = pid;
        s.peerName = Json.str(j, "name", pid);
        s.handshaked = true;
        // ===== 能力协商:老端不带这些字段→vid=false→本端永不发视频帧(向后兼容硬闸门) =====
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
        // 数据流量确认(v2.7):老端不带该位→不发data_ask,退回老口径
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
            // 新连接顶掉旧连接时，把旧连接上**进行中的配对**迁移过来：
            // 不迁移的话，刚出示的码/正在输入的角色会随去重一起消失，对端提交的 pair_submit
            // 就没人核对（两端都停在配对界面，用户看到的就是「卡死」）
            if (!exist.paired && exist.pairRole != 0 && !s.paired) {
                s.pairRole = exist.pairRole;
                s.pairCode = exist.pairCode;
                s.pairAttemptsLeft = exist.pairAttemptsLeft;
                s.pairDeadline = exist.pairDeadline;
                log("配对：进行中的配对（role=" + s.pairRole + "）已迁移到新连接（" + s.remoteIp() + "）");
            }
            exist.close();
            sessions.remove(exist);
        }
        byPeer.put(pid, s);

        // 入向连接的设备自动登记进设备列表:对端有防火墙只能单向主动发起时,这条握手是PC端唯一的设备来源——
        // 不登记的话对方发来的消息/通话记录没有归属,界面完全看不到。remark传null=不覆盖用户已设置的备注
        if (store != null && store.upsertPeer(s.remoteIp(), null, pid, s.peerName,
                System.currentTimeMillis())) {
            notifyPeers();
        }
        // ===== 设备身份绑定 =====
        // 对方每次握手都自报一次自己的id→绑到当前这个地址上。
        // 同一id绑了多个地址=同一台设备有多个地址→自动成组(备注、聊天记录、当前地址都按设备共用一份)。必须早于hsync/flushPending
        if (store != null && store.bindDeviceIfFree(s.remoteIp(), pid)) {
            log("设备身份绑定：" + s.remoteIp() + " → " + pid);
            notifyPeers();
        }
        // 已知设备打点①:握手成功="连接过"。必须排在bindDevice之后,组键才是deviceId而不是裸IP(与安卓端markConnected同时机)
        if (store != null) {
            store.markTrustedConnected(store.convKeyOf(s.remoteIp()));
        }
        // 连上了⇒保活重连任务完成使命;同时清掉"连上就断"计数(这次确实连上并握手了)
        cancelKeepAlive(s.remoteIp());
        keepAliveFastFails.remove(s.remoteIp());
        // 这台设备已可达⇒撤掉还没轮到的候选地址(可能是被动连上,也可能是对端抢先拨通)
        clearPendingFor(s.remoteIp());

        // 同一台设备换了局域网地址：新的连上、旧的自动删 —— 这一步**要等身份验证通过**才做（onSessionReady）。
        // 握手只证明「对端自称是这个 id」（bindDeviceIfFree 在这里照旧做身份绑定），
        // 地址被 DHCP 复用给别的设备时不能凭自称就把这台设备的历史地址删掉。

        // 第一台连上来的设备默认成为"当前对话";当前选中目标没有健康会话时也跟过去——
        // 否则selectedIp停在旧IP/点过但连不上的地址(如扫描列表里的IPv6条目)上:状态恒显"未连接"、发消息进待发队列收不到(修复,与Android端对称)
        if (selectedIp == null || selectedIp.isEmpty() || sessionOf(selectedIp) == null) {
            selectedIp = s.remoteIp();
            selectedLabel = s.peerName;
        } else if (store != null && selectedIp.equals(s.remoteIp()) == false
                && store.convKeyOf(selectedIp).equals(pid)) {
            // 当前对话就是这台设备：这次是在它的新地址上握手的，选中地址跟过去，
            // 免得顶栏/聊天区停在一条马上要被验证通过的会话清掉的老 IP 上
            selectedIp = s.remoteIp();
            selectedLabel = s.peerName;
        }
        notifyPeers();
        refreshState();
        // ===== P4-5：配对状态机 =====
        // token 命中 → 直接放行内容通道；新设备 → 接收方出示 6 位码 / 发起方弹数字键盘。
        // 原来的 setFileVote/flushPending/pullPeerClip/hsync 全部搬进 onSessionReady，配对通过后才执行
        startPairingOrActivate(s, j);
    }

    /** 对单条会话重发 hello（配对自愈用：把按 deviceId 取到的权威 token 带过去） */
    private void resendHello(Session s, String tok) {
        if (s == null || s.closed) {
            return;
        }
        try {
            s.send(Protocol.hello(myId(), myName(), "windows", localVidCap, false, localVCodecs,
                    LANES, s.lane, tok));
        } catch (Exception e) {
            log("重发 hello 失败（" + s.remoteIp() + "）：" + e);
        }
    }

    // ==================== P4-5 配对状态机 ====================

    /**
     * 配对入口。
     * <ul>
     *   <li>双方都持有同一 token：发起方等 pair_ok；接收方回 pair_ok 后直接放行。</li>
     *   <li>用户点了设备：由点的那一端出示 6 位码（见 {@link #requestPair}），对端收到 pair_need 切到数字键盘。</li>
     *   <li>用户没点：**两端都不弹**。会话留在「已连接、未配对」，列表提示「未配对 — 单击这台设备出示配对码」。</li>
     * </ul>
     */
    private void startPairingOrActivate(Session s, Map<String, Object> j) {
        String pid = s.peerId;
        String peerTok = Json.str(j, "tok", "");
        String myTok = store == null ? "" : store.pairedTokenOf(pid);
        boolean tokenMatch = !myTok.isEmpty() && !peerTok.isEmpty()
                && MessageDigest.isEqual(myTok.getBytes(StandardCharsets.UTF_8),
                peerTok.getBytes(StandardCharsets.UTF_8));
        if (!tokenMatch && !myTok.isEmpty() && peerTok.isEmpty() && store != null) {
            // 对端这次没带 token ≠ 它清过数据：可能只是它换了 IP（按 IP 查表查空）、刚启动还没读到缓存、
            // 或者只更新了一端。**绝不能在这里删本机配对记录**——旧实现正是在这里 unpair，
            // 重启/换 DHCP 一次就要重配，而且两端互相删、再也回不去。保留记录，这次按"未配对"走 6 位码。
            log("配对：对端本次未带 token（" + s.remoteIp() + "），按未配对处理，保留本机配对记录");
            // 本机记录还在、只是**这次带过去的 token 不对**（按地址查空/地址被别的设备占了）⇒
            // 用按 deviceId 取到的权威 token 补发一次 hello。两端都这么做就能互相核对上，
            // 不用用户重新配对（这就是「连接过的设备反复要验证」的根因之一）。
            if (!myTok.equals(s.pairTokenSent)) {
                s.pairTokenSent = myTok;
                resendHello(s, myTok);
                log("配对：对端没带 token，已按 deviceId 补发一次带 token 的 hello（" + s.remoteIp() + "）");
            }
        }
        if (tokenMatch) {
            // 身份核对通过 ⇒ 刷新「设备 → 本次地址」缓存（换过 IP 的旧缓存会失效/被 DHCP 复用）
            if (store != null) {
                store.markPaired(pid, s.peerName, myTok, s.remoteIp());
            }
            // token 已经放行 → 用户之前点的配对意图自动作废
            if (matchesPendingPair(s)) {
                clearPendingPair();
            }
            if (!s.outgoing) {
                // 接收方：token 已核对通过，明确告诉发起方「不用配对」
                s.paired = true;
                trySend(s, Protocol.pairOk(""));
                onSessionReady(s);
            } else {
                // 已就绪会话重发 hello（能力更新）：保持配对态，不重走等待
                if (s.paired) {
                    log("配对：已就绪会话收到 hello 重发，保持配对（" + s.remoteIp() + "）");
                    return;
                }
                // 本机这次 hello 带过去的 token 不是权威值（按地址取，地址缓存被清过或被别的设备占了）⇒
                // 对端拿不到能对上号的 token，只能当我们没配对。用按 deviceId 取到的权威 token 补发一次 hello：
                // 对端重新核对，命中就直接回 pair_ok，不用用户重新配对。
                if (!myTok.equals(s.pairTokenSent)) {
                    s.pairTokenSent = myTok;
                    resendHello(s, myTok);
                    log("配对：本次 hello 的 token 与设备身份不符，已按 deviceId 补发一次（" + s.remoteIp() + "）");
                }
                // 发起方：token 已带过去，等 pair_ok 或 pair_need（由心跳看 pairDeadline）
                s.paired = false;
                s.pairRole = 0;
                s.pairDeadline = System.currentTimeMillis() + PAIR_TTL_MS;
                log("配对：本机持有 token，等待对端确认（" + s.remoteIp() + "）");
            }
            return;
        }
        // 已配对且内容通道已放行的会话，收到 hello 重发（能力更新/解码器枚举完）时 token 可能对不上
        // （本端换过 IP、或只更新了一端）——**不能因此把会话降级成未配对**：
        // 旧实现这里 s.paired=false，随后该连接上所有内容帧被配对闸门丢掉，用户看到的是连接忽然"半死"。
        if (s.paired) {
            log("配对：已就绪会话收到 token 对不上的 hello 重发，保持配对（" + s.remoteIp() + "）");
            return;
        }
        // 配对进行中（本端已出示码 / 正在输入码）时收到 hello 重发（例如视频解码器枚举完后的能力更新），
        // **绝不能把配对状态清掉**：旧实现会清空 pairRole/pairCode，对端随后提交的码就被当成
        // 「没有待核对的码」静默丢弃；若这里还重新生成一次码，用户照着旧码输永远不对 —— 两端都停在配对界面。
        if (s.pairRole != 0 || !s.pairCode.isEmpty()) {
            log("配对：配对进行中（role=" + s.pairRole + "），忽略 hello 重发对配对状态的重置（" + s.remoteIp() + "）");
            return;
        }
        s.paired = false;
        s.pairAttemptsLeft = PAIR_MAX_ATTEMPTS;
        s.pairRole = 0;
        s.pairCode = "";
        // 不设 pairDeadline：没弹窗就不该因为「配对超时」把一条好连接关掉
        s.pairDeadline = 0L;
        if (matchesPendingPair(s)) {
            clearPendingPair();
            log("配对：" + s.peerName + "(" + pid + ") 是用户刚点的设备，连接已建立，出示配对码");
            showPairAsCode(s);
        } else {
            // 本端是发起方、但用户没点这台设备（启动自动连接/扫描后自动连接）：**不自动出示**。
            // 自动弹与设计相反（列表与安卓端都写着「未配对，点一下配对」），
            // 现象就是「选了保留数据，一启动就被糊一脸 PIN 页」；用户点一下设备走 requestPair 即可。
            log("配对：" + s.peerName + "(" + pid + ") 已连接但未配对，等用户点设备再出示配对码（不自动弹）");
        }
    }

    /**
     * 用户主动点了一台设备：未配对就走「本端出示 6 位码 → 对端输入」。
     * 连接还没起来时只记下意图，握手完成后再出示（见 {@link #startPairingOrActivate}）。
     */
    public void requestPair(String ip) {
        if (ip == null) {
            return;
        }
        String v = ip.trim();
        if (v.isEmpty()) {
            return;
        }
        Session s = sessionByGroup(v);
        if (s == null || s.closed || !s.handshaked) {
            pendingPairIp = v;
            pendingPairAt = System.currentTimeMillis();
            log("配对：已记下用户配对意图（" + v + "），连接建立后出示配对码");
            return;
        }
        if (s.paired) {
            return;
        }
        // 已经在输入码（弹窗还开着）：保持现状，别把它切成「出示码」，否则两端都可能亮码、谁都没法输
        if (s.pairRole == 2) {
            return;
        }
        clearPendingPair();
        showPairAsCode(s);
    }

    /** 本端出示 6 位码并通知对端弹出数字键盘（只在用户主动点设备时调用，不再随连接自动弹） */
    private void showPairAsCode(Session s) {
        if (s == null || s.paired || s.closed) {
            return;
        }
        // 已经在出示码：沿用旧码，只把覆盖层重新推给界面。重新生成会让用户照着旧码输永远不对
        if (s.pairRole == 1 && !s.pairCode.isEmpty()) {
            showPairUi(new PairUi(s.remoteIp(), s.peerName, true, s.pairCode,
                    s.pairAttemptsLeft, PAIR_TTL_MS, ""));
            return;
        }
        String code = newPairCode();
        s.pairCode = code;
        s.pairRole = 1;
        s.pairAttemptsLeft = PAIR_MAX_ATTEMPTS;
        s.pairDeadline = System.currentTimeMillis() + PAIR_TTL_MS;
        log("配对：用户主动发起 " + s.peerName + "(" + s.peerId + ")，本端出示 6 位码，等待对方输入");
        showPairUi(new PairUi(s.remoteIp(), s.peerName, true, code,
                PAIR_MAX_ATTEMPTS, PAIR_TTL_MS, ""));
        // 点设备/握手回调可能就在 EDT 上：帧一律丢进 uiSendExec，别在 EDT 直接写 socket（写锁被文件占住时界面会卡死）
        uiSendExec.execute(() -> {
            if (!s.closed) {
                trySend(s, Protocol.pairNeed(PAIR_TTL_MS));
            }
        });
    }

    /** 用户点的地址是否就是这条会话的设备（同设备换地址也算同一台） */
    private boolean matchesPendingPair(Session s) {
        String want = pendingPairIp;
        if (want == null || want.isEmpty()) {
            return false;
        }
        if (System.currentTimeMillis() - pendingPairAt > PAIR_TAP_INTENT_TTL_MS) {
            return false;
        }
        if (want.equals(s.remoteIp())) {
            return true;
        }
        if (store == null) {
            return false;
        }
        return store.convKeyOf(want).equals(store.convKeyOf(s.remoteIp()));
    }

    private void clearPendingPair() {
        pendingPairIp = "";
        pendingPairAt = 0L;
    }

    /** 配对完成：内容通道正式可用（补发待发消息 + 历史同步 + 剪贴板协商） */
    private void onSessionReady(Session s) {
        if (s == null || s.closed) {
            return;
        }
        boolean first = !s.readyOnce;
        s.readyOnce = true;
        s.paired = true;
        s.pairRole = 0;
        s.pairCode = "";
        s.pairDeadline = 0L;
        // 配对成功即「已知设备」（与安卓端同口径）：配对码本身就是用户授权，
        // 不再要求先收发过消息/文件——否则配对完不发消息，托盘后台重连会被自己的白名单挡掉
        if (store != null) {
            store.markTrustedPaired(store.convKeyOf(s.remoteIp()));
            // 设备身份=deviceId+token：把 token 的地址缓存对齐到本次这条地址
            // （旧地址清理在握手阶段做，见 startPairingOrActivate）
            String tok = store.pairedTokenOf(s.peerId);
            if (tok != null && !tok.isEmpty()) {
                store.markPaired(s.peerId, s.peerName, tok, s.remoteIp());
            }
        }
        // ==== 身份验证通过之后，才替换这台设备的历史地址 ====
        // 先验证、再替换：握手阶段只做身份绑定（谁自称是谁），不删任何旧地址；
        // 只有 token 命中 / 6 位码配对成功（走到这里）才认定「同一台设备换了局域网地址」，
        // 新的连上、旧的自动删。否则 DHCP 把老地址分给别的设备时会误删这台设备的历史记录。
        // 判「旧地址还有没有会话」必须按精确 IP（isConnectedIp），不能按设备组判，否则新地址的会话
        // 会让旧地址一直算「连着的」，旧地址永远删不掉。
        if (store != null && !s.peerId.isEmpty()) {
            boolean selWasThisDevice = selectedIp != null && !selectedIp.isEmpty()
                    && store.convKeyOf(selectedIp).equals(s.peerId);
            if (store.syncGroupLanIp(s.peerId, s.remoteIp(), ip -> isConnectedIp(ip))) {
                log("设备换地址：" + s.peerName + "(" + s.peerId + ") 局域网地址已切到 " + s.remoteIp());
                // 当前对话就是这台设备：选中地址跟到刚验证通过的新地址上（旧地址马上被删）
                if (selWasThisDevice && !s.remoteIp().equals(selectedIp)) {
                    selectedIp = s.remoteIp();
                    selectedLabel = s.peerName;
                }
            }
        }
        if (matchesPendingPair(s)) {
            clearPendingPair();
        }
        if (pairUiMatches(s)) {
            clearPairUi();
        }
        notifyPeers();
        refreshState();
        if (!first) {
            // 重发 hello 触发的二次 ready：只刷新状态，不再重复补发/hsync
            return;
        }
        // 这台设备可达→撤掉它自己的"没网络"票；下面 flushPending 负责续发
        setFileVote(VOTE_NO_NETWORK, false, s.remoteIp());
        flushPending(s.remoteIp());
        // 剪贴板只跟「当前对话的那台」协商，不往别的设备推
        if (isSelected(s)) {
            // pair_ok 刚发出去、对端要收完这条才进 ready，它的配对闸门会丢掉早到的 clip_pull
            // （真机日志实测：「未配对会话 192.168.5.12 发来 clip_pull，已丢弃」= 每次重连第一次剪贴板同步丢失）。
            // 推迟一小段再拉，避开这个时序。
            scheduleClipPull(s);
        }
        if (s.caps.hs) {
            runFrameTask("hsync_start", () -> hsync.startHsync(s));
        }
    }

    private void showPairUi(PairUi u) {
        pairUiIp = u.ip;
        if (listener != null) {
            listener.onPairState(u);
        }
    }

    /**
     * 这条会话是不是覆盖层上那台设备。按**设备组键**比较（同设备换网卡/热点换地址时 raw IP 会变），
     * 否则配对完成/提交核对/取消都会因为地址不同而对不上，覆盖层就关不掉。
     */
    private boolean pairUiMatches(Session s) {
        if (s == null) {
            return false;
        }
        String cur = pairUiIp;
        String ip = s.remoteIp();
        if (cur.isEmpty() || ip.isEmpty()) {
            return false;
        }
        if (cur.equals(ip)) {
            return true;
        }
        return store != null && store.convKeyOf(cur).equals(store.convKeyOf(ip));
    }

    /** 关会话并从所有表里摘掉（配对取消/失败/超时共用） */
    private void dropSession(Session s) {
        if (s == null) {
            return;
        }
        s.close();
        sessions.remove(s);
        if (!s.peerId.isEmpty()) {
            byPeer.remove(s.peerId, s);
        }
        notifyPeers();
        refreshState();
    }

    /** 发起方用户提交 6 位码（覆盖层数字键盘「确认」调用；**在 EDT 上**，发送必须异步） */
    public void submitPairCode(String ip, String code) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < code.length() && digits.length() < PAIR_CODE_LEN; i++) {
            char c = code.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() != PAIR_CODE_LEN) {
            return;
        }
        Session s = sessionByGroup(ip);
        if (s == null || s.closed || s.pairRole != 2 || s.paired) {
            // 连接被重连替换 / 对端已取消 / 已超时：不能一声不响地什么都不做，
            // 否则键盘按了像卡死。给明确结果并收掉本端覆盖层，用户重新点设备即可再配。
            log("配对：提交被忽略（会话=" + (s == null ? ip : s.remoteIp())
                    + " role=" + (s == null ? -1 : s.pairRole) + "）");
            showPairUi(new PairUi(ip, s == null ? "" : s.peerName, false, "",
                    0, 0, "配对状态已失效，请关闭后重新点设备配对", true, false));
            return;
        }
        final String digitsFinal = digits.toString();
        // 先本地切「正在验证…」，再把帧交给 uiSendExec：
        // EDT 直接写 socket 会等写锁（文件分片可能正占着），对端读得慢时界面就卡在这里
        showPairUi(new PairUi(s.remoteIp(), s.peerName, false, "",
                s.pairAttemptsLeft, PAIR_TTL_MS, "正在验证…"));
        uiSendExec.execute(() -> {
            if (!s.closed) {
                trySend(s, Protocol.pairSubmit(digitsFinal));
            }
        });
    }

    /**
     * 放弃本次配对（覆盖层「取消」调用）：只关掉两端的配对弹窗，**保留连接**。
     * 之前是直接断会话，而两端启动时都会自动连，断完几秒又被连上、码又弹出来——用户根本取消不掉。
     * 现在连接留在「已连接但未配对」，用户下次点设备再重新出示码。
     */
    public void cancelPairing(String ip) {
        Session s = sessionByGroup(ip);
        if (s != null && s.paired) {
            // 已经配对成功：只把这层提示关掉
            clearPairUi();
            return;
        }
        log("配对：用户取消 " + (s == null ? ip : s.peerName + "(" + s.remoteIp() + ")")
                + "，保留连接");
        clearPendingPair();
        if (s != null) {
            s.pairRole = 0;
            s.pairCode = "";
            s.pairDeadline = 0L;
            if (!s.closed && s.handshaked) {
                // 通知对端把它那半边的配对弹窗也关掉（否则对端会一直停在输入框）；
                // 取消按钮在 EDT 上，发送一律走 uiSendExec，别让写锁把界面卡住
                uiSendExec.execute(() -> {
                    if (!s.closed) {
                        trySend(s, Protocol.pairCancel());
                    }
                });
            }
        }
        // 不管有没有会话都要关掉本端覆盖层：旧实现里会话已断时这里直接 return，弹窗会卡住关不掉
        clearPairUi();
    }

    private void clearPairUi() {
        pairUiIp = "";
        if (listener != null) {
            listener.onPairState(null);
        }
    }

    /** 对端在它那边点了「取消」：关掉本端弹窗，连接保留 */
    private void onPairCancel(Session s) {
        if (s.paired) {
            return;
        }
        log("配对：对端取消（" + s.remoteIp() + "）");
        s.pairRole = 0;
        s.pairCode = "";
        s.pairDeadline = 0L;
        if (pairUiMatches(s)) {
            clearPairUi();
        }
    }

    private void onPairSubmit(Session s, Map<String, Object> j) {
        if (s.paired) {
            return;
        }
        if (s.pairRole != 1 || s.pairCode.length() != PAIR_CODE_LEN) {
            // 本端此刻没有可核对的码（连接被重连替换 / 已取消 / 已超时）：
            // 明确回一帧 pair_cancel 让对端从「正在验证…」退出并关掉键盘，
            // 本端若还挂着覆盖层也一并收掉。旧实现直接 return ⇒ 两端都停在配对界面（用户看到的就是「卡死」）。
            log("配对：收到提交但本端没有待核对的码（role=" + s.pairRole + "），回 pair_cancel 让对端退出");
            if (!s.closed) {
                trySend(s, Protocol.pairCancel());
            }
            s.pairRole = 0;
            s.pairCode = "";
            s.pairDeadline = 0L;
            if (pairUiMatches(s)) {
                showPairUi(new PairUi(s.remoteIp(), s.peerName, false, "",
                        0, 0, "配对状态已失效，请关闭后重新点设备配对", true, false));
            }
            return;
        }
        String code = Json.str(j, "code", "");
        boolean ok = code.length() == PAIR_CODE_LEN
                && MessageDigest.isEqual(s.pairCode.getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8));
        if (ok) {
            String token = newPairToken();
            if (store != null) {
                store.markPaired(s.peerId, s.peerName, token, s.remoteIp());
            }
            trySend(s, Protocol.pairOk(token));
            log("配对成功：" + s.peerName + "(" + s.peerId + ")");
            onSessionReady(s);
        } else {
            s.pairAttemptsLeft -= 1;
            if (s.pairAttemptsLeft <= 0) {
                log("配对失败：" + s.peerName + " 连续输错，关闭会话");
                trySend(s, Protocol.pairFail(0));
                showPairUi(new PairUi(s.remoteIp(), s.peerName, true, "",
                        0, 0, "配对码错误次数过多，请重连后重新配对", true, false));
                dropSession(s);
            } else {
                trySend(s, Protocol.pairFail(s.pairAttemptsLeft));
                showPairUi(new PairUi(s.remoteIp(), s.peerName, true, s.pairCode,
                        s.pairAttemptsLeft, PAIR_TTL_MS,
                        "配对码不正确，还剩 " + s.pairAttemptsLeft + " 次"));
            }
        }
    }

    /**
     * 对端点设备、出示它的码，本端切到数字键盘。
     * 两端都可能主动点：本端此刻也在出示码时（同时发起），按 deviceId 定一方出示、一方输入，
     * 否则两边都亮码、谁都没法输入。比较口径两端对称（myId 与 peerId 互换），必然只有一方保留码。
     */
    private void onPairNeed(Session s, Map<String, Object> j) {
        if (s.paired) {
            return;
        }
        long ttl = Json.lng(j, "ttl", PAIR_TTL_MS);
        if (s.pairRole == 1 && !s.pairCode.isEmpty()) {
            if (myId().compareTo(s.peerId) <= 0) {
                log("配对：两端同时发起，本端继续出示配对码（" + s.remoteIp() + "）");
                return;
            }
            log("配对：两端同时发起，本端改为输入对端的配对码（" + s.remoteIp() + "）");
            s.pairCode = "";
        }
        s.pairRole = 2;
        s.pairAttemptsLeft = PAIR_MAX_ATTEMPTS;
        s.pairDeadline = System.currentTimeMillis() + ttl;
        showPairUi(new PairUi(s.remoteIp(), s.peerName, false, "",
                s.pairAttemptsLeft, ttl, "请输入对方设备上显示的 6 位配对码"));
    }

    private void onPairOk(Session s, Map<String, Object> j) {
        String token = Json.str(j, "token", "");
        if (!token.isEmpty()) {
            if (store != null) {
                store.markPaired(s.peerId, s.peerName, token, s.remoteIp());
            }
        } else if (store == null || store.pairedTokenOf(s.peerId).isEmpty()) {
            log("配对确认回来了但本机没有 token（会话=" + s.remoteIp() + "），按未配对处理");
            return;
        } else if (!s.outgoing || s.pairTokenSent.isEmpty()) {
            // 空 token 的 pair_ok 只对"确实带过 token 的发起方"有效，防伪造对端空口放行
            log("配对确认缺 token 且本端未带过 token（会话=" + s.remoteIp() + "），按未配对处理");
            return;
        }
        log("配对成功（token 校验）：" + s.peerName + "(" + s.peerId + ")");
        onSessionReady(s);
    }

    private void onPairFail(Session s, Map<String, Object> j) {
        int left = Math.max(0, Json.integer(j, "left", 0));
        s.pairAttemptsLeft = left;
        if (left <= 0) {
            showPairUi(new PairUi(s.remoteIp(), s.peerName, false, "",
                    0, 0, "配对码错误次数过多，请重连后重新配对", true, false));
            dropSession(s);
        } else {
            showPairUi(new PairUi(s.remoteIp(), s.peerName, false, "",
                    left, PAIR_TTL_MS, "配对码不正确，还剩 " + left + " 次"));
        }
    }

    private static String newPairCode() {
        return String.format(java.util.Locale.US, "%06d", PAIR_RANDOM.nextInt(1_000_000));
    }

    private static String newPairToken() {
        byte[] b = new byte[16];
        PAIR_RANDOM.nextBytes(b);
        return hex(b);
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

    // ==================== 文件接收（缓冲池 + 断点续传） ====================

    /**
     * 一个正在接收的文件。
     * 缓冲池：数据直读进当前块（8MB），攒满 1MB 即入队交落盘线程、自己取下一块空闲块继续填；
     * 没有空闲块时排队等（天然背压）；正在写盘的块绝不会再被读线程复用。见 LinkReceive.Rx。
     */

    final Map<String, Rx> rxMap = new HashMap<String, Rx>();
    final Map<String, DirRx> dirMap = new HashMap<String, DirRx>();

    /** 保留多少条「已收尾」的目录记录（供同一 mid 重发时复用目录名，见 pruneDirs） */
    static final int MAX_KEEP_DIRS = 32;

    /** 已完整收妥的普通单文件:mid→落盘路径(有界LRU,容量{@link #MAX_KEEP_SAVED});
     * <p>rxMap只记在途,落盘一完成就摘除;此时同一mid再被投一次(手机待发补发撞上PC发起的hsync补齐),
     * PC会把它当新文件走createUniqueFile存成"xxx (1)"并再弹一条"文件已保存"——用户报的"发2个文件落了5个"就是这么来的(根源修);
     * 重复判据必须跨越"已收完"这个时刻,所以单独存一份;
     * <p>跨进程重启的重复投递由{@link Store#savedFileOf}兜(读历史里那条status=OK记录) */
    static final int MAX_KEEP_SAVED = 512;

    /** accessOrder=true → get 也会改链表顺序，必须整体同步（两条会话读线程会并发） */
    final Map<String, String> savedFiles = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_KEEP_SAVED;
                }
            });

    /** 见过的文件夹成员:成员mid→所属目录mid(有界LRU,建Rx时登记);
     * <p>file_query帧不带dirId,单看这一帧无法分辨"这条mid是普通单文件,还是某个文件夹的成员";
     * 有了这张表,重复投递闸门才不会把dir_missing的缺失成员补发一并拦掉——拦了就是"文件夹里少了文件"永远补不回来 */
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

    // ===== 32MB 分块大小校验（不做 hash） =====
    /** 发送端等「大小核验 + 截断确认」：blk_ack 带回实际核验通过的偏移 */
    static final class BlkAckResult {
        final boolean ok;
        final long offset;

        BlkAckResult(boolean ok, long offset) {
            this.ok = ok;
            this.offset = offset;
        }
    }

    final ConcurrentHashMap<String, ArrayBlockingQueue<BlkAckResult>> blkAckWaiters =
            new ConcurrentHashMap<String, ArrayBlockingQueue<BlkAckResult>>();
    /** file_resume.needBlk=true */
    final ConcurrentHashMap<String, Boolean> resumeNeedBlk = new ConcurrentHashMap<String, Boolean>();
    /** 接收端待续传准备（file_query 命中半成品后等发送端选块起点） */
    static final class ResumePrep {
        final String mid, name, path;
        final long size;
        final String peerIp;

        ResumePrep(String mid, String name, long size, String peerIp, String path) {
            this.mid = mid;
            this.name = name;
            this.size = size;
            this.peerIp = peerIp;
            this.path = path;
        }
    }

    final ConcurrentHashMap<String, ResumePrep> resumePrep = new ConcurrentHashMap<String, ResumePrep>();

    /** 正在发送中的 mid（含文件夹整体）：防止握手补发与在途传输重复发同一条 */
    final java.util.Set<String> inFlight =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 在途发送的归属:mid→对端IP(清空会话前用来判断该设备是否还有传输在途) */
    final ConcurrentHashMap<String, String> inFlightPeer =
            new ConcurrentHashMap<String, String>();

    /** 在途发送：mid → 最近一次写出新字节的时刻（发送停滞看门狗用） */
    final ConcurrentHashMap<String, Long> sendLastAt =
            new ConcurrentHashMap<String, Long>();

    /** 被停滞暂停的单文件mid(v2.8):下一个进度帧(file_meta重到/续传首个chunk)到达时气泡回"发送中"并清掉标记;
     *  目录成员不用标记——其首个续传chunk本来就把目录气泡发成SENDING */
    final java.util.Set<String> pausedMids =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // ==================== 单任务槽（一个设备同一时刻只传一个任务） ====================
    /** 对端对某 mid 回过 busy 的时刻：本地发送在分片边界查它，命中就暂停该条目（不关会话），
     *  过期自动失效（对端可能已断线重连、session 换了） */
    static final long PEER_BUSY_TTL_MS = 120_000L;
    final ConcurrentHashMap<String, Long> peerBusyAt = new ConcurrentHashMap<String, Long>();
    /** 哪个 mid 是被哪台设备回绝的（对端发 sync_req=它空出来了 ⇒ 按 peer 一次性清，立刻重试） */
    final ConcurrentHashMap<String, String> peerBusyPeer = new ConcurrentHashMap<String, String>();

    /** 被回绝过的接收 mid/目录 id：其后续帧（成员 file_meta）直接回 busy，不走"隐式建目录"兜底 */
    final java.util.Set<String> busyIds =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 任务槽：taskMid 空=空闲；kind ∈ {"send","recv"}；文件夹整体算一个任务（mid=目录气泡 mid） */
    private final Object taskLock = new Object();
    private String taskMid, taskPeer, taskKind, taskName;

    /** 占用任务槽的文案（回 busy 时告诉对端"本机在忙什么"）；空闲返回 "" */
    String busyHolder() {
        synchronized (taskLock) {
            if (taskMid == null) {
                return "";
            }
            return (taskName == null || taskName.isEmpty()) ? taskKind + " " + taskMid : taskName;
        }
    }

    String taskMid() {
        synchronized (taskLock) {
            return taskMid;
        }
    }

    /** 占任务槽：同一 mid 幂等（文件夹成员/续传重入）；被别的任务占着返回 false */
    boolean claimTask(String mid, String peer, String kind, String name) {
        if (mid == null || mid.isEmpty()) {
            return true;
        }
        synchronized (taskLock) {
            if (taskMid == null) {
                taskMid = mid;
                taskPeer = peer;
                taskKind = kind;
                taskName = name;
                busyIds.remove(mid);
                return true;
            }
            return taskMid.equals(mid);
        }
    }

    /** 等空槽再开传（**只允许发送串行队列线程调用**，别的线程阻塞等会卡住读线程/UI） */
    boolean awaitTaskSlot(String mid, String peer, String kind, String name) throws InterruptedException {
        while (!claimTask(mid, peer, kind, name)) {
            if (sessionByGroup(peer) == null) {
                return false;   // 对端已断，别再空等
            }
            Thread.sleep(500L);
        }
        return true;
    }

    /** 释放任务槽；notifyPeer=true 时补一个 sync_req，让被 busy 拦下的条目立刻重试 */
    void releaseTask(String mid, boolean notifyPeer) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        String peer = null;
        synchronized (taskLock) {
            if (mid.equals(taskMid)) {
                peer = taskPeer;
                taskMid = null;
                taskPeer = null;
                taskKind = null;
                taskName = null;
            }
        }
        if (peer != null && notifyPeer) {
            notifyPeerIdle(peer);
        }
    }

    /** 断链时按"对端+方向"兜底释放（文件夹成员不逐条释放，只能这样收口） */
    void releaseTaskFor(String peerIp, String kind) {
        if (peerIp == null || peerIp.isEmpty()) {
            return;
        }
        synchronized (taskLock) {
            if (peerIp.equals(taskPeer) && (kind == null || kind.equals(taskKind))) {
                taskMid = null;
                taskPeer = null;
                taskKind = null;
                taskName = null;
            }
        }
    }

    /** 告诉对端"我这边空出来了，重新对一遍待发队列" */
    void notifyPeerIdle(String peerIp) {
        Session s = sessionByGroup(peerIp);
        if (s == null || s.closed || !s.handshaked) {
            return;
        }
        trySend(s, Protocol.syncReq());
    }

    void markPeerBusy(String mid, String name, String peer) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        peerBusyAt.put(mid, System.currentTimeMillis());
        if (peer != null && !peer.isEmpty()) {
            peerBusyPeer.put(mid, peer);
        }
        log("对端忙，暂缓 " + (name == null || name.isEmpty() ? mid : name) + "（mid=" + mid + "）");
        stateNote = "对端正在传输其它任务，本条暂停，对方空闲后自动重试";
    }

    boolean consumePeerBusy(String mid) {
        Long t = peerBusyAt.get(mid);
        if (t == null) {
            return false;
        }
        if (System.currentTimeMillis() - t.longValue() > PEER_BUSY_TTL_MS) {
            peerBusyAt.remove(mid);
            peerBusyPeer.remove(mid);
            return false;
        }
        return true;
    }

    /** 对端发来 sync_req = 它刚空出来：清掉它名下的 busy 记号，待发队列立刻重试 */
    void clearPeerBusyFor(String peerIp) {
        if (peerIp == null || peerIp.isEmpty()) {
            return;
        }
        java.util.Iterator<Map.Entry<String, String>> it = peerBusyPeer.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> e = it.next();
            if (peerIp.equals(e.getValue())) {
                peerBusyAt.remove(e.getKey());
                it.remove();
            }
        }
    }

    void markBusyId(String id) {
        if (id != null && !id.isEmpty()) {
            busyIds.add(id);
        }
    }

    boolean isBusyId(String id) {
        return id != null && !id.isEmpty() && busyIds.contains(id);
    }

    /** 发送停滞看门狗:在途mid30s没写出新字节(对端半死、socket写阻塞)时,
     *  关掉对应会话强制解阻塞⇒发送循环抛异常⇒现有catch⇒进待发队列⇒重连后自动续传;
     *  由心跳线程每{@link #STALL_SCAN_MS}顺带调用(不新开线程)。
     *  注:本地磁盘读停滞30s+会被误判关会话——概率极低,后果仅是重传(正确性无损) */
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
                if (ip == null || ip.isEmpty()) {
                    continue;
                }
                // 反向仍在正常收包 ⇒ 是「对端读得慢/被限速」的背压，不是链路死：交给自适应帧降速，
                // 别把整条会话（含反向正在收的那一份）一起关掉（互传时最容易踩）
                Session ses = sessionByGroup(ip);
                if (ses != null && !ses.closed && now - ses.lastRx < STALL_TIMEOUT_MS) {
                    log("发送停滞 " + (now - t.longValue()) + "ms（" + ip + "），但仍在收对端数据（背压），不关会话");
                    continue;
                }
                staleIps.add(ip);
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

    /** 是否有与该设备相关的传输在途(发送中或接收中)——清空会话记录前必须确认空闲 */
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

    // ==================== 续传完成大小核对（端到端，不做 hash） ====================

    /** 发送端在途核对表（普通单文件续传才登记） */
    final ConcurrentHashMap<String, VerifyState> verifyMap =
            new ConcurrentHashMap<String, VerifyState>();

    /** 核对超时：只 stat 文件大小不读内容，整文件按 10s 预算；超时只记日志不改传输结果 */
    static final long VERIFY_TIMEOUT_MS = 10_000L;

    /** 半成品过期时限:3天没人续传的孤儿半成品(取消帧丢失/对端重传换mid)启动时回收 */
    static final long PARTIAL_MAX_AGE_MS = 3L * 24 * 3600 * 1000;

    // ===== P4-5 配对 =====
    /** 配对码位数（6 位；输入长度、核对、生成三处统一用它） */
    static final int PAIR_CODE_LEN = 6;
    /** 配对码/等待输入的有效期（2 分钟） */
    static final long PAIR_TTL_MS = 120_000L;
    static final int PAIR_MAX_ATTEMPTS = 3;
    static final java.security.SecureRandom PAIR_RANDOM = new java.security.SecureRandom();

    /** 未配对会话仍允许的帧（心跳 + 配对握手本身） */
    static final java.util.Set<String> PAIRED_ALLOWED_FRAMES = new java.util.HashSet<String>(
            Arrays.asList("hello", "ping", "pong", "busy",
                    "pair_need", "pair_submit", "pair_ok", "pair_fail", "pair_cancel"));

    /** 用户点设备后等会话握手的最长时限：超过就不再补弹配对码（防几秒后的自动重连又弹出） */
    static final long PAIR_TAP_INTENT_TTL_MS = 30_000L;

    /** lane 数据通道允许的帧（分片与通道控制），其余内容帧一律不认 */
    static final java.util.Set<String> LANE_ALLOWED_FRAMES = new java.util.HashSet<String>(
            Arrays.asList("hello", "ping", "pong", "lane_ok", "xe_begin", "xe_end", "xe_ready", "busy"));

    /** 判"假连接(半开)"的收包空闲阈值:超过一轮心跳周期(5s)仍无收包才算 */
    private static final long STALE_RX_MS = 20_000L;

    /** 接收/发送停滞判定阈值:30s无任何真实进度(对端半死但TCP心跳仍正常);
     *  心跳仍正常是因为对端进程活着、只是发送线程卡死/被杀——心跳帧保不了业务帧 */
    /** 非文件帧写看门狗时限（P1-3，与安卓 Link.CONTROL_WRITE_TIMEOUT_MS 同值 15s） */
    static final long CONTROL_WRITE_TIMEOUT_MS = 15_000L;

    static final long STALL_TIMEOUT_MS = 30_000L;
    /** 停滞看门狗扫描周期（= 心跳周期）：心跳线程每 5s 顺带扫一次，不新开线程 */
    static final long STALL_SCAN_MS = 5_000L;

    /** 未完成目录闲置回收时限：10 分钟无活动即回收（防「没走到 dir_end 的目录永留」） */
    static final long DIR_STALE_MS = 10L * 60 * 1000;

    /** 启动落盘线程：从 pending 取满块写盘，注册表同步刷新（供断点续传跨重启找回半成品） */


    // ==================== 发送（目标 = 当前选中的设备） ====================

    /** UI发起的小帧(文本/剪贴板/同步)统一丢到这里发:EDT只入队,socket写不占UI线程——对端读不动时界面不再跟着卡(Android端有sendExec,PC这里补齐);
     *  单线程保序:同一线程提交的文本按原顺序到达对端 */
    java.util.concurrent.ExecutorService uiSendExec = newUiSendExec();

    /**
     * P4-4：hsync / msg_del / 配对核对等「按帧触发」任务统一走有界池（0 核 + 3 线程 + 128 队列，空闲 60s 回收）。
     * 不再每帧 new Thread；队列满丢弃并记日志。
     */
    private java.util.concurrent.ExecutorService frameExec = newFrameExec();

    /** 接收收尾执行器（单线程）：file_done / dir_end 的落盘与清单对账最长 60s，绝不能坐在网络读线程上
     *  （读线程不读 socket ⇒ 心跳把自己判死、对端等不到 ack）。单线程同时保证同一文件夹成员的
     *  file_done 一定先于 dir_end 执行，onDirEnd 不会把"还在写盘"的成员误判成缺件 */
    private final java.util.concurrent.ExecutorService rxFinalizeExec =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pp-rx-finalize");
                t.setDaemon(true);
                return t;
            });

    void runRxFinalize(String tag, Runnable r) {
        try {
            rxFinalizeExec.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    log("接收收尾失败(" + tag + "): " + t);
                }
            });
        } catch (Throwable t) {
            log("接收收尾入队失败(" + tag + "): " + t);
        }
    }

    private java.util.concurrent.ExecutorService newFrameExec() {
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(
                0, 3, 60L, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<Runnable>(128),
                r -> {
                    Thread t = new Thread(r, "pp-frame-task");
                    t.setDaemon(true);
                    return t;
                },
                new java.util.concurrent.ThreadPoolExecutor.DiscardPolicy());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    void runFrameTask(String tag, Runnable r) {
        try {
            if (frameExec.isShutdown()) {
                return;
            }
            frameExec.execute(() -> {
                try {
                    r.run();
                } catch (Throwable t) {
                    log("后台帧任务失败(" + tag + "): " + t);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException re) {
            log("后台帧任务过多，丢弃 " + tag + "（P4-4 限流）");
        }
    }

    /** P2-6：stop() 会 shutdownNow，start() 必须能重建 */
    java.util.concurrent.ExecutorService newUiSendExec() {
        return java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "pp-ui-send");
            t.setDaemon(true);
            return t;
        });
    }

    public Msg sendText(String text) {
        text = Protocol.limitText(text);
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

    /** 剪贴板正文广播:发给所有已连接设备(剪贴板是同步通道不是消息,不emit气泡、不进历史——与"切客户端后CLIP不显示"的加载过滤保持一致,避免"发出时是消息、切换后消失"的错觉);
     *  无已连接设备时暂存待发,连上后由flushPending自动补发 */
    public boolean sendClip(String text, String sha1) {
        java.util.List<Session> targets = new java.util.ArrayList<Session>();
        for (String ip : connectedIps()) {
            Session s = sessionOf(ip);
            // 剪贴板只走局域网链路:非局域网对端不推(留在待发队列,等它变成局域网地址)
            if (s != null && LocalIps.isLanPeer(s.remoteIp())) {
                targets.add(s);
            }
        }
        if (targets.isEmpty()) {
            // 无设备丢弃口径（S7，与安卓同口径）：连"选中设备"都没有就别暂存——
            // peerIp 空的 CLIP 条目只能靠 flushPending 的 onlyIp 兜底，容易变成长期积压
            String sel = selectedIp();
            if (sel == null || sel.isEmpty()) {
                log("剪贴板未推送：当前没有已连接设备、也没有选中设备（不暂存）");
                return false;
            }
            log("剪贴板暂存待发（选中设备 " + sel + " 未连接，连上后自动补发）");
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

    /** 已被本端取消的文件mid集合:发送循环每轮检查,命中即中断 */
    final java.util.Set<String> cancelled =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /**
     * 取消墓碑：用户点「取消发送」后把 mid **长期**记住（不随发送循环消费掉）。
     *
     * 为什么要有：[cancelled] 是「这次尝试到此为止」的一次性信号，发送循环读到就 remove。
     * 但同一条文件可能已经排进了串行发送队列（syncNow→flushPendingNow 的「待发补发」任务），
     * 循环一消费掉标记，排队的任务就会拿同一个 mid **重新开传**——取消完过几秒对端又冒出一个
     * 气泡继续收，本端气泡已移除、看不到进度。
     * 墓碑只在用户显式「继续/重试」（[forgetCanceled]）时清除。
     */
    private final java.util.Set<String> cancelTombs =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    /** 取消墓碑保留上限（只防内存无限增长） */
    private static final int CANCEL_TOMB_MAX = 256;

    /** 记一条取消墓碑（超出上限按最老的淘汰） */
    void markCanceled(String mid) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        synchronized (cancelTombs) {
            cancelTombs.add(mid);
            if (cancelTombs.size() > CANCEL_TOMB_MAX) {
                java.util.Iterator<String> it = cancelTombs.iterator();
                while (cancelTombs.size() > CANCEL_TOMB_MAX && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
    }

    /** 用户显式重试/继续：清墓碑，允许这一条重新发送 */
    void forgetCanceled(String mid) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        synchronized (cancelTombs) {
            cancelTombs.remove(mid);
        }
    }

    /** 这条（或它所属的文件夹）是否已被用户取消且还没被「继续」 */
    boolean isCanceled(String mid, String dirId) {
        if (mid != null && cancelTombs.contains(mid)) {
            return true;
        }
        return dirId != null && !dirId.isEmpty() && cancelTombs.contains(dirId);
    }

    /**
     * 文件夹发送时对端「已经在盘上」的成员清单（dir_have）信箱：dirMid → 容量 1 的信箱。
     * 断链重发整目录时对端先回报这份清单，发送端据此跳过，只补真正缺的文件（局域网，按文件粒度即可）。
     */
    final ConcurrentHashMap<String, ArrayBlockingQueue<java.util.List<String>>> dirHaveBox =
            new ConcurrentHashMap<String, ArrayBlockingQueue<java.util.List<String>>>();

    /** 成员 mid → 「目录 mid + \\u0001 + 成员 relPath」：成员 ack 到达时据此记账（S15/S24） */
    final ConcurrentHashMap<String, String> dirMemberAck = new ConcurrentHashMap<String, String>();

    /** 文件夹成员回执：只做记账/日志（要重发哪些由 dir_end 的清单对账决定，不再维护第二份完成表） */
    boolean onMemberAck(String mid, boolean ok) {
        if (mid == null || mid.isEmpty()) {
            return false;
        }
        String v = dirMemberAck.remove(mid);
        if (v == null) {
            return false;
        }
        int sep = v.indexOf('\u0001');
        if (sep <= 0) {
            return true;
        }
        String relPath = v.substring(sep + 1);
        if (!ok) {
            log("成员 " + relPath + " 对端回报未收妥，等 dir_end 清单对账后补发");
        }
        return true;
    }

    /** 整目录收尾/取消时清掉该目录的成员回执登记（防无界增长） */
    void clearMemberAcks(String dirId) {
        if (dirId == null || dirId.isEmpty()) {
            return;
        }
        java.util.Iterator<java.util.Map.Entry<String, String>> it = dirMemberAck.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().startsWith(dirId + "\u0001")) {
                it.remove();
            }
        }
    }

    boolean isCancelled(String mid, String dirId) {
        if (cancelled.contains(mid)) {
            return true;
        }
        if (dirId != null && !dirId.isEmpty() && cancelled.contains(dirId)) {
            return true;
        }
        return isCanceled(mid, dirId);
    }
    public void sendFile(File f) { trustContent(selectedIp()); send.sendFile(f, false); }

    public void sendFile(File f, boolean preGranted) { trustContent(selectedIp()); send.sendFile(f, preGranted); }

    public void retryFileSend(Msg m) { send.retryFileSend(m); }

    // ---- 通话/视频发送（实现移至 LinkCall，这里保持对外 API 不变） ----
    public long sendAudioTo(String ip, int seq, long ts, byte[] data, int len) { return call.sendAudioTo(ip, seq, ts, data, len); }

    public boolean sendCallInvite(String name, String ip) { return call.sendCallInvite(name, ip); }

    public boolean sendCallInvite(String name, String ip, boolean vid, int lv) { return call.sendCallInvite(name, ip, vid, lv); }

    public boolean sendCallInvite(String name, String ip, boolean vid, int lv, int af) { return call.sendCallInvite(name, ip, vid, lv, af); }

    public boolean sendCallInvite(String name, String ip, String peerIp, boolean vid, int lv, int af,
                                  int mport, boolean mudp, String msid) {
        return call.sendCallInvite(name, ip, peerIp, vid, lv, af, mport, mudp, msid);
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

    /** 请求对端立刻给一个IDR(本端解码进程换代后只能从关键帧起解) */
    public void sendVideoKeyRequest(String ip) { call.sendVideoKeyRequest(ip); }


    /** 本机拒绝用数据流量时的备注(PC恒不在数据网络,实际只会是远端拒绝/设备未连接) */
    static final String LOCAL_DENY_TEXT = "未同意使用数据流量";



    public int ensureDataConsent(String peerIp, String mid, String kind, String label, String name, long size) {
        return consent.ensureDataConsent(peerIp, mid, kind, label, name, size);
    }

    public void replyDataAsk(String peerIp, String mid, boolean ok) { consent.replyDataAsk(peerIp, mid, ok); }

    public void ensureDataConsentAsync(String peerIp, String kind, String label, java.util.function.IntConsumer onDone) {
        consent.ensureDataConsentAsync(peerIp, kind, label, onDone);
    }

    public String newConsentMid() { return consent.newConsentMid(); }

    /** 主动发文件夹。**空文件夹一律忽略**（不建气泡/不进待发/不发 dir_meta，双端同一规则） */
    public void sendFolder(File dir) {
        trustContent(selectedIp());   // 已知设备打点⑤：发出文件夹（成员各自也算）
        List<File> files = new ArrayList<File>();
        List<String> emptyDirs = new ArrayList<String>();
        int[] skipped = new int[1];
        LinkNames.listFilesRecursive(dir, "", files, emptyDirs, skipped, 0);
        if (skipped[0] > 0) {
            log("注意：目录过深/过大或部分子树读取失败，" + skipped[0] + " 个分支未包含在传输中");
        }
        // 空文件夹直接忽略：不记清单、不出气泡、不入待发
        if (files.isEmpty() && skipped[0] == 0) {
            log("空文件夹已忽略：" + dir.getName() + " 内没有任何文件，本次未发送");
            return;
        }
        // 一个文件都没读到、却又有整块读失败:这是"读不到"而不是"空的";
        // 照发一个count=0的文件夹会让用户以为传成功了,实际什么都没传
        if (files.isEmpty()) {
            log("文件夹内容读取失败（" + skipped[0] + " 个分支未读到），已中止发送 —— 请确认该目录仍可访问");
            return;
        }
        // 发送前名校验：文件夹名 + 每个成员相对路径；任一项不合格 → 提示并取消整次发送
        List<String> rels = new ArrayList<String>();
        for (File f : files) {
            rels.add(LinkNames.relPathOf(dir, f));
        }
        String fnameErr = LinkNames.folderError(dir.getName(), rels);
        if (fnameErr != null) {
            notifyUser(fnameErr + "，已取消发送");
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
        // 投票为1时文件夹同样不入网,与单文件走同一条排队分支(拦在入口)
        if (fs2 != null && !fileGateBlocked(m.peerIp)) {
            m.peerIp = fs2.remoteIp();
            m.status = Msg.ST_PENDING;
            emit(m);
            final Msg item = m;
            final List<File> fl = files;
            final List<String> ed = emptyDirs;
            final int sk = skipped[0];
            // ⚠整段(确认+传输)进串行队列(排队发送):一次只传一个文件/文件夹,前一个完成立刻进入下一个
            enqueueFileSend("发送文件夹 " + item.fileName, () -> {
                int dc = consent.ensureDataConsent(item.peerIp, item.mid, DataConsent.KIND_FILE,
                        "发送文件夹", item.fileName, item.fileSize);
                if (dc != DataConsent.OK) {
                    if (dc == DataConsent.REMOTE_TIMEOUT) {
                        send.pauseByDataTimeout(item, DataConsent.KIND_FILE);
                        return;
                    }
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
                setFileVote(VOTE_NO_NETWORK, true, m.peerIp);
                log("设备 " + (selectedIp().isEmpty() ? "（未选择）" : selectedIp())
                        + " 未连接，文件夹 " + m.fileName + "（" + files.size() + " 个文件）进入待发队列");
            } else {
                log("[文件排他] " + gateReason(m.peerIp) + "：文件夹 " + m.fileName + "（" + files.size()
                        + " 个文件）排队，恢复后自动续发（D16）");
            }
        }
    }

    /** 对端报缺失后最多补发几轮;再缺说明是真发不过去,如实报失败而不是死循环 */
    static final int DIR_RESEND_ROUNDS = 1;
    /** 等dir_missing回执的时长:对端要先把本轮全部成员落盘、算校验和才核对 */
    static final long DIR_MISSING_WAIT_MS = 60_000L;
    /** 无清单时对端不会回执，等短一拍即可 */
    static final long DIR_MISSING_WAIT_SHORT_MS = 5_000L;
    /** 等对端「已有成员清单」（dir_have）的时长：局域网同一事件循环内就回，1s 足够兜老端 */
    static final long DIR_HAVE_WAIT_MS = 1_000L;
    /** dir_meta成员清单在线上最多多少字符(超了就不带,退化为"只按数量核对") */
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
            // 文件夹气泡的速率用聚合字节数采样(成员各自算会互相打断基线)
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

    /** 断点续传协商:问对端有没有这个文件的半成品。
     * @return 对端已有字节数(已对齐分片边界;0=从头传) */

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

    /** 发送一个文件(1MB二进制分片+双4MB缓冲读源+断点续传)。
     * @param relPath 文件夹成员时为目录内相对路径
     * @param dirId 所属目录气泡mid(空=普通单文件)
     * @param agg 文件夹进度聚合器(普通单文件为null)
     * @param throwOnFail true=成员失败向上抛(整目录标失败);false=记待发队列自动重试 */
    /** 发送入口(防强杀版):开传前先把条目落进持久化待发队列,成功后才移除。
     *  这样任何时刻被强杀(包括传输中途),重启后连接上对端都会由握手自动补发,
     *  并凭同一个mid命中对端半成品注册表从断点续传——不再依赖"失败catch补登记" */

    /** 取消一个正在发送/待发送的文件或文件夹。两端同步:
     *  ①本端把该条从界面移除,并从待发队列与历史里删掉;
     *  ②发一帧file_cancel,对端收到后中止接收、删除半成品、同样移除展示 */
    public void cancelFile(String mid) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        cancelled.add(mid);
        markCanceled(mid);
        dirHaveBox.remove(mid);
        clearMemberAcks(mid);
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

    /** 通知手机"电脑剪贴板有更新"——只发长度/哈希/预览,正文等手机来拉。只发给当前对话的那台 */
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

    /**
     * 配对完成后推迟一点再拉对端剪贴板：pair_ok 还在路上，对端此刻尚未 ready，
     * 它的「未配对会话」闸门会把 clip_pull 丢掉（真机重连时实测每次都丢第一发）。
     */
    private void scheduleClipPull(final Session s) {
        daemon(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
                return;
            }
            if (s.closed || !s.paired) {
                return;
            }
            pullPeerClip();
        }, "pp-clip-pull");
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
            // 配对没走完就发 clip_pull 会被「未配对会话」闸门拦掉（CONNECTED 早于配对完成，
            // 重连那一刻必撞）。配对完成时 onSessionReady 会再拉一次，这里安静跳过即可
            if (!s.paired) {
                return;
            }
            trySend(s, Protocol.clipPull());
            log("已请求 " + selectedLabel() + " 的剪贴板");
        });
    }


    /** 握手协商第二步:把电脑这边的剪贴板缓冲推给当前对话的设备。电脑侧缓冲为空时什么都不做 */
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

    /** 补发待发队列里属于指定设备的那部分。
     *  每条待发记录都记着自己原本要发给谁(peer字段):别的设备的条目直接跳过(不误投,也不会因某台没连上而卡住其他设备);
     *  同一台设备的条目按原顺序发送,相对次序不变 */
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
            // 按设备找会话:条目可能是从这台设备的另一个地址排进去的(换WiFi/热点)
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
                // 已完整送达过这台设备 → 直接出队，不再与 hsync 补齐形成双发（S31）
                if (send.isFileDelivered(p.mid, dest)) {
                    store.removePending(p.mid);
                    log("待发条目 " + p.fileName + " 此前已完整送达，直接出队");
                    continue;
                }
                // 票①/②/③仍在拦这台设备：条目留在队列，解除后由 setFileVote/clearDenyConsent 触发补发。
                // 不能直接 enqueue：否则票③还在时会走 ensureDataConsent 反复弹确认框
                if (fileGateBlocked(dest)) {
                    continue;
                }
                // 取消墓碑：用户取消过的条目不补发（队列入口还有一道同样的闸）
                if (cancelled.contains(p.mid) || isCanceled(p.mid, null)) {
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
                    // 空文件夹一律忽略（双端统一规则）：没有任何成员 → 直接出队，绝不发 count=0 的 dir_meta
                    if (files.isEmpty() && skipped[0] == 0) {
                        store.removePending(p.mid);
                        log("待发文件夹 " + p.fileName + " 内没有任何文件，已忽略并出队");
                        continue;
                    }
                    if (files.isEmpty()) {
                        store.removePending(p.mid);
                        log("待发文件夹 " + p.fileName + " 已不可读，移出待发队列");
                        continue;
                    }
                    // 成员名校验：不合格 → 提示并取消该条（保持气泡为失败态，红叹号可再点）
                    List<String> rels = new ArrayList<String>();
                    for (File rf : files) {
                        rels.add(LinkNames.relPathOf(path, rf));
                    }
                    String nameErr = LinkNames.folderError(p.fileName, rels);
                    if (nameErr != null) {
                        notifyUser(nameErr + "，已取消发送");
                        send.denyFileByData(p, nameErr + "，已取消发送", false);
                        continue;
                    }
                } else {
                    if (!path.exists()) {
                        store.removePending(p.mid);
                        continue;
                    }
                    String nameErr = LinkNames.fileNameError(p.fileName);
                    if (nameErr != null) {
                        notifyUser(nameErr + "，已取消发送");
                        send.denyFileByData(p, nameErr + "，已取消发送", false);
                        continue;
                    }
                }
                // v2.7:补发与手动发送走同一道数据流量闸门——重连后自动补发同样"每次使用都提醒一次",绝不因为用户没盯着屏幕就偷偷花流量;
                //  确认会阻塞等对端data_ans(最长90s),而本方法跑在网络读线程上——在那里阻塞会卡死整个会话⇒整条补发进串行队列的专用线程;
                //  条目仍留在待发队列直到发送成功(doSendFile/doSendFolder内部移除);若同mid已在途,inFlight守卫会让本次直接返回;
                //  与手动发送共用同一条队列——补发不许与在途传输并行,否则一次握手就能同时开出几十条补发流("几十个文件一起发就乱"的来源之一)
                final File fpath = path;
                enqueueFileSend("补发 " + p.fileName,
                        () -> send.resendPendingFile(destF, p, fpath, files, emptyDirs, skipped[0]));
            }
        }
    }


    // ==================== 二进制工具 ====================

    /** 发送端读源:双4MB缓冲交替,后台线程读盘,与网络发送重叠;
     *  空闲缓冲从free领取、填满放回ready;消费端发完归还;
     *  len=-1表示EOF,len=-2表示读源失败 */
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

    /** 发一帧并如实回报是否真的交给 socket（墓碑确认、块校验兜底等需要按结果决定是否记账） */
    boolean trySend(Session s, Map<String, Object> m) {
        // ===== P4-5 发送侧闸门：未配对会话不允许发内容帧（配对帧/心跳在白名单里）；
        // 与接收侧同口径，不以 handshaked 为前提（握手前也拦） =====
        String frameType = Json.str(m, "type", "");
        if (s != null && s.lane == 0 && !s.paired
                && !PAIRED_ALLOWED_FRAMES.contains(frameType)) {
            log("未配对会话 " + s.remoteIp() + " 试图发送 " + frameType + "，已拦截（请先完成配对）");
            return false;
        }
        try {
            s.send(m);
            return true;
        } catch (Exception e) {
            log("发送失败: " + e);
            return false;
        }
    }

    /** 统一消息id:两端同构的32位十六进制全局唯一串。
     *  接收查重、待发队列、历史合并、hsync补齐全部以它为唯一判据 */
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

    /** 提示用户 + 记日志（校验失败/空文件夹忽略等）。可在任意线程调用，UI 层自己切线程 */
    void notifyUser(String s) {
        Log.i(s);
        Listener l = listener;
        if (l != null) {
            try {
                l.onNotice(s);
            } catch (Throwable ignore) {
            }
        }
    }

    // ===== 开发期调试出口已移除 =====
}
