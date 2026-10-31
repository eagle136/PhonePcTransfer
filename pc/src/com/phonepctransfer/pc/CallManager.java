package com.phonepctransfer.pc;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 通话状态机（PC 端，与 Android 端 voice/CallManager.kt 同构）。
 *
 * 全局**同一时刻最多一个通话**：新的呼出在已有通话时直接拒绝；新的呼入回 call_busy 静默拒绝。
 *
 * 音频：TALKING 状态下 VoiceEngine 采集编码经 Link 发出；收到对端音频交 VoiceEngine 播放。
 * 丢包重传一次再失败→静音跳过，由 VoiceEngine 抖动缓冲（按 seq 对时）保证时间轴不漂移。
 *
 * 视频：**PC 只收不发**（R2）。收到 video_cfg 后起一个最小化 ffmpeg 子进程解 HEVC/H.264
 * 裸流成 BGRA，交给 {@link VideoUi} 画到 {@link VideoWindow}。
 * 解码进程的启动/重启只在 ffmpeg 自己的 pp-video-feed 线程里发生 ——
 * 本类的 {@link #onVideoCfg}/{@link #onVideoFrame} 是**网络读线程**调用的，只能做入队。
 */
public final class CallManager {

    public static final long MAX_CALL_MS = 6L * 3600 * 1000;
    public static final long RING_TIMEOUT_MS = 60_000L;

    public enum State { IDLE, OUTGOING, INCOMING, TALKING }

    public interface Listener {
        void onStateChanged(State state, String peerIp, String peerName);

        /** @param video 本次来电是不是视频来电（决定弹窗标题与是否开视频窗） */
        void onIncomingCall(String peerIp, String peerName, boolean video);

        void onCallEnded(String peerIp, String reason);

        /**
         * 通话已结束（v2.7 起带通话形态与时长，供上层**以消息方式记录**）。
         * 默认实现回落到上面的三参版本，老实现方不会漏收结束事件。
         *
         * @param video      本次是否视频通话
         * @param durationMs 实际通话时长（未接通为 0）
         * @param outgoing   true = 本机发起
         */
        default void onCallEnded(String peerIp, String reason, boolean video,
                                 long durationMs, boolean outgoing) {
            onCallEnded(peerIp, reason);
        }

        /**
         * 2.9 / S23：通话中的**媒体侧提示**（"疑似被拦"这一类：只提示，不改变任何传输方式）。
         * default 空实现 ⇒ 老实现方不漏收；UI 侧要显示就覆盖它（本单由 ChatWindow 接到窗口副标题）。
         */
        default void onMediaNotice(String text) {
        }
    }

    /**
     * 视频画面出口（由 UI 层实现）。所有方法都可能被**非 EDT 线程**调用，
     * 实现方自己负责 marshal 到 EDT（见 PcMain 的实现）。
     */
    public interface VideoUi {
        /** 通话开始：创建并显示视频窗口 */
        void open(String peerLabel);

        /** 一帧画面（BGRA）。**在解码线程上调用**：必须同步转换，禁止阻塞。 */
        void onFrame(byte[] bgra, int w, int h);

        void setRotation(int rot);

        void setStatus(String s);

        /**
         * 注册画面盒监听：窗口尺寸/旋转变化时把**未旋转像素盒**报给解码器，
         * 让它按窗口大小出帧（BGRA 过管道的字节数是收端吞吐的瓶颈，见 FfmpegDecoder#boxW）。
         * 实现方需保证：{@code open} 里窗口还没建好时先记下，建好即补挂。
         */
        void setBoxListener(BoxListener l);

        /** 通话结束：关闭视频窗口 */
        void close();
    }

    /** 画面盒（未旋转的可用像素宽高）通知，EDT 调用。 */
    public interface BoxListener {
        void onBox(int w, int h);
    }

    private final Link link;
    private final Store store;
    private final VoiceEngine voice = new VoiceEngine();

    /**
     * 媒体传输模块（任务单 2.1；D2「一通电话一个媒体会话」）。
     * 呼叫一开（振铃期）就建，endCall 里关 —— 与安卓 voice/CallManager.kt 同一时机。
     * 上层只调 start / sendAudio / sendVideo / sendCtrl / stop 这五个方法；
     * 底下是五档里的哪一档由模块内部决定，通话层既不读取也不据此分支（D3 / I17）。
     */
    private volatile AvTransport av;

    /**
     * 定档时序已跑完（任务单 2.5 / §4.3：振铃期定一次）。每次 openTransport 复位，
     * 与 {@link #pendingStartTalking} 一起构成「接听」与「定档」两个到达顺序不定的事件的会合点。
     */
    private volatile boolean negotiated = false;

    /** true = 用户已接听/对端已应答，但定档还没跑完 ⇒ 由 {@link #onNegotiated} 补进 TALKING */
    private volatile boolean pendingStartTalking = false;

    /** 模块的收帧出口（只为本端状态判断服务；源地址过滤口径与改前逐字一致） */
    private final AvTransport.Sink avSink = new AvTransport.Sink() {
        @Override
        public void onAudio(String fromIp, int seq, long ts, byte[] data, int len) {
            handleInboundAudio(fromIp, seq, ts, data, len);
        }

        @Override
        public void onVideo(String fromIp, int seq, long ts, int flag, byte[] data, int len) {
            handleInboundVideo(fromIp, seq, ts, flag, data, len);
        }

        /**
         * 分片重组丢了半帧 ⇒ 参考链已断，交给解码器处置（停喂死链帧 + 要 IDR）。
         * ⚠ 这里不读 tier、不判档位、不自己发 video_req（I14/D3 同口径），只做一次转发；
         *   IDR 请求的限频与"要不要清队"都在 {@link FfmpegDecoder#notifyChainBroken} 里。
         */
        @Override
        public void onVideoChainBroken(boolean key) {
            FfmpegDecoder d = vdec;
            if (d != null && state == State.TALKING) {
                d.notifyChainBroken();
            }
        }

        /**
         * 2.9 / S23：模块判定"本档 1s 一条反馈都没收到"（**上升沿**，一秒最多一次）。
         * 处置口径是写死的：**只提示、不换档、不换端口、不重开探测**（I17），
         * 所以这里除把话交给 UI 以外不做任何动作 —— 通话层不许读 tier 做分支（D3）。
         */
        @Override
        public void onMediaSuspectedBlocked(String text) {
            Listener l = listener;
            if (l != null && state == State.TALKING) {
                l.onMediaNotice(text);
            }
        }

        /**
         * 2.9 / P10（2026-10-02 用户认可的默认处置）：媒体通道彻底无流达 10s ⇒ **自动挂断 + 提示**。
         * ⚠ "媒体存活由通话状态机决定"（I14）⇒ 挂断动作在这里，不在 AvTransport 里。
         * ⚠ 线程：由 pp-av-alive 调过来，与 pp-read / pp-call-cb 一样都是"非 EDT 但 endCall 已做成
         *   不阻塞调用者"的调用方；本端已有 state/IDLE 双检，迟到的回调不会复活上一通。
         */
        @Override
        public void onMediaNoStream() {
            String ip = peerIp;
            if (state != State.TALKING || ip.isEmpty()) {
                return;
            }
            Log.i("媒体通道无流达 " + AvTransport.NO_STREAM_HANGUP_MS + "ms ⇒ 按 P10 自动挂断");
            link.sendCallEnd(ip, "");
            endCall(ip, "呼叫中断：媒体通道无数据");
        }
    };

    /**
     * 建本次通话的媒体会话。2.5 起这一步就把**第 0 档 bind 好**（§4.3①「先 bind 出本档端口，
     * 再交换 mport/mudp/msid」——端口要随 invite/accept 出去），并由调用方紧接着发 invite/accept、
     * 再 {@link #startNegotiation()} 把七步时序跑完。
     *
     * @param caller true = 本端是主叫（媒体 TCP 档由主叫发起连接，见 AvTransport 2.5 块「我的推论③」）
     */
    private void openTransport(String ip, boolean caller) {
        AvTransport t = new AvTransport(link, avSink);
        // 三元组的本端一份（localMsid，每端各自生成 —— 2026-10-02 用户点）+ 对端一份
        // （主叫此刻还没有 ⇒ 0/false/空；被叫就是 invite 里带进来的那一份，2.4 的字段从本条起有读者）
        t.start(new AvTransport.Spec(ip, randomMsid(),
                caller ? "" : peerMsid, caller ? 0 : peerMport, caller ? false : peerMudp, caller));
        // 2.10 / P6 + S14：媒体入向的准入判据。三条件里的前三样在本机这几张表上，
        // msid 那一半在模块里（AvTransport.admitMediaInbound），两边**与**起来才放行。
        // ⚠ 这里读的是**当时的** volatile 状态（不是在 openTransport 时刻快照）：
        //   时序是在 pp-av-negotiate 上跑的，它跑到 ④ 时本通可能已经挂了 ⇒ ① 必须当场判。
        t.setMediaInboundAdmit(rip -> {
            if (state == State.IDLE || rip == null || rip.isEmpty() || store == null) {
                return false;   // ① 通话中（含振铃期 —— accept 本来就只可能在这时候发生）
            }
            if (BlocklistIpv6.isHighRisk(rip) || store.isBlockedAddr(rip)) {
                return false;   // 既有网段黑名单：高风险段 + 本机设备级标记（与 adopt 那道同源）
            }
            String pip = peerIp;
            if (store.isTrustedAddr(rip)) {
                return true;   // ② 已知设备（PC 这张表本来就按设备组键存，天然含"同组其它地址"）
            }
            // ② 的 D10 那一半：媒体连接可能来自对端**另一块网卡**（2.7 的教训）⇒
            // 只要这台设备已知、且这条连接与通话对端同组，就算已知设备。
            // ⚠ 不能写成 rip.equals(pip)：多地址设备的会话地址≠界面地址，那样会把正确连接判死。
            return !pip.isEmpty() && store.isTrustedAddr(pip)
                    && store.convKeyOf(rip).equals(store.convKeyOf(pip));
        });
        av = t;
        negotiated = false;
        pendingStartTalking = false;
    }

    /**
     * 本次通话的随机 token（串话隔离，S12；不是安全边界）。
     * 用 UUID ⇒ 不新增"token 长度/字符集"这类方案里没给的数字；36 字符塞进 probe 正文
     * 后整报 13+36=49B，仍在 §4.4 的 ctrl ≤64B 以内。
     */
    private static String randomMsid() {
        return java.util.UUID.randomUUID().toString();
    }

    /**
     * 在振铃期把 §4.3 的七步时序跑完（独立线程：每一步都可能按窗口阻塞，绝不能在 EDT 上跑）。
     * 结果只决定「定在哪一档 + 能不能进 TALKING」，不决定媒体帧今天怎么走（3.3 之前恒带内，
     * 见 {@link AvTransport#mediaUsesMainTcp()}）。
     */
    private void startNegotiation() {
        final AvTransport t = av;
        final String ip = peerIp;
        if (t == null || ip.isEmpty()) {
            return;
        }
        daemon(() -> {
            boolean ok;
            try {
                ok = t.negotiate();
            } catch (Throwable e) {
                ok = false;
            }
            onNegotiated(ip, ok);
        }, "pp-av-negotiate");
    }

    /** 时序跑完（或 ⑦ 判失败）。与 {@link #requestStartTalking()} 同一把锁，两边谁先到都不会漏。 */
    private synchronized void onNegotiated(String ip, boolean ok) {
        if (state == State.IDLE || !ip.equals(peerIp)) {
            return;   // 这一通已经结束了（挂断 / 超时 / 对端取消都会叫停时序），不要复活它
        }
        if (ok && mediaDowngraded()) {
            // 2.11 / §4.3⑥：UI **明示**降级（不是只写日志）。走 2.9 那条 onMediaNotice ——
            // 它本来就是"媒体侧提示"的通道，不再为降级另立第三个回调（D3：上层只读标志位，不读 tier）。
            // ⚠ 一次通话只跑一次时序（startNegotiation 的调用点是振铃期那两处）⇒ 这里天然不会重复提示，
            //   所以没有另设"提示过没有"的闩锁（1.6 的口径）。
            Listener l = listener;
            if (l != null) {
                l.onMediaNotice("本次通话媒体已降级：走消息端口的带内通道（第五档）。"
                        + "通话期间文件不发送、聚合恒关（D16 / I13）");
            }
        }
        negotiated = true;
        if (!ok) {
            // ⑦（U11②，2026-10-02 用户点）：带内也不成立 ⇒ 提示呼叫失败，**不进 TALKING**
            if (state == State.OUTGOING) {
                link.sendCallCancel(ip);
                endCall(ip, "无法与对方建立媒体通道，呼叫失败");
            } else if (state == State.INCOMING) {
                link.sendCallReject(ip);
                endCall(ip, "本机无法与对方建立媒体通道");
            } else {
                Log.i("媒体定档失败但已在通话中（state=" + state + "），只记录不切换");
            }
            return;
        }
        if (pendingStartTalking) {
            pendingStartTalking = false;
            startTalking();
        }
    }

    /**
     * 接听 / 对端应答 ⇒ 进 TALKING。⚠ §4.3 要求定档**全程在振铃期完成**，而 call_accept
     * 的语义就是「我接了」（收到即 startTalking）⇒ 本方法只把"要接听"这个意图记下来，
     * call_accept 由 {@link #acceptIncoming} / {@link #onAccept} 照发，进入 TALKING 这一步
     * 等定档结论：时序先跑完 ⇒ 就地进；用户先点接听 ⇒ 由 onNegotiated 补进（最坏等一个窗口）。
     */
    private synchronized void requestStartTalking() {
        if (negotiated) {
            startTalking();
        } else {
            pendingStartTalking = true;
        }
    }

    /**
     * 关本次通话的媒体会话（endCall 与"invite 都没发出去"这一条失败分支都走这里）。
     * 可重入：重复调用不出异常（任务单 2.9 的五条退出路径最终都汇聚到这一个 close）。
     */
    private void closeTransport() {
        AvTransport t = av;
        av = null;
        if (t != null) {
            t.stop();
        }
    }

    /**
     * 2.11：本次通话是否**落到带内档**（第五档 = 媒体跑在主 TCP 的消息端口上）。
     * 上层只读这一个标志，**不读 tier**（D3：五档互换时通话层零改动）；
     * UI 的降级文案（ChatWindow 的通话条）与 2.12 的文件闸门读的都是它。
     * ⚠ av 为 null（还没 openTransport / 已 closeTransport）时返回 false：
     *   那时本通根本没有媒体结论可展示，不能把"未定档"显示成"已降级"。
     */
    public boolean mediaDowngraded() {
        AvTransport t = av;
        return t != null && t.mediaDowngradedToInband();
    }

    private volatile Listener listener;
    private volatile VideoUi videoUi;
    private volatile State state = State.IDLE;
    private volatile String peerIp = "";
    private volatile String peerName = "";
    private volatile long talkStartMs = 0L;

    // ==================== 视频（只收） ====================

    /** 本次通话是否为视频通话（PC 侧只有"接听视频来电"这一种进入方式） */
    private volatile boolean videoMode = false;
    /** 本次通话档位（0=480p / 1=720p / 2=1080p）；接听时与呼叫方取 min */
    private volatile int level = 1;

    /**
     * 对端声明的**语音帧长**（ms，来自 `call_invite`/`call_accept` 的 `af` 字段）。
     * **0 = 对端是老版本**（没有这个字段）→ 本次通话用 200ms 兼容模式。
     */
    private volatile int pendingAfMs = 0;

    /**
     * 对端在 `call_invite`/`call_accept` 里声明的**媒体通道三元组**（任务单 2.4 / 方案 §4.2）。
     * 与 {@link #pendingAfMs} 同一生命周期：每次振铃/应答覆盖，不单独复位。
     * 缺省值 0 / false / 空 = 对端未声明（老端或老口径）⇒ 2.5 的定档时序只能落带内档。
     * ⚠ msid 只做**串话隔离**，不是安全边界（S12）；谁生成、谁回显由 2.5 定（本单只落 wire 字段）。
     */
    private volatile int peerMport = 0;
    private volatile boolean peerMudp = false;
    private volatile String peerMsid = "";

    /** 纯语音通话的码率自适应（64k ⇄ 32k）；只在 !videoMode 时启用 */
    private volatile VoiceBitrateController voiceQuality;
    private volatile Thread aqThread;

    /**
     * 6h 通话超时定时器：每次通话一个单线程 ScheduledExecutorService（daemon 线程）
     * + 已调度任务。endCall/dispose 里 future.cancel(false) 并 shutdown ——
     * 未触发的任务被取消，空闲工作线程立即退出（旧实现 Thread.sleep 会在提前
     * 挂断后继续睡满剩余时长）。
     */
    private volatile java.util.concurrent.ScheduledExecutorService callTimeoutExec;
    private volatile java.util.concurrent.ScheduledFuture<?> callTimeoutFuture;

    private volatile FfmpegDecoder vdec;
    /** 最近一次画面盒（未旋转像素）：跨解码器实例保留，见 startVideoReceive 的补挂 */
    private volatile int lastBoxW = 0;
    private volatile int lastBoxH = 0;
    private volatile boolean fbRunning = false;

    /** video_cfg 可能在 startVideoReceive 之前到达（握手顺序不保证）→ 先缓存 */
    private volatile String cfgCodec = "";
    private volatile int cfgW = 0;
    private volatile int cfgH = 0;
    private volatile int cfgFps = 0;
    private volatile int cfgBr = 0;
    /**
     * 远端画面的**默认显示基线角**：对端 `video_cfg.rot` + （对端用前置时）**180°**。
     *
     * 用户口径（2026-09-19）：「对方使用前置时，默认把我方显示对面的画面转 180°
     * （相当于默认就是点了两次旋转按钮的状态）」。把这 180 折进**基线**而不是
     * `VideoWindow.userRot`，是为了让用户此后点旋转钮仍是从这个默认再 +90，
     * 且与「每设备持久化的手调角」互不污染。
     */
    private volatile int cfgRot = 0;

    /** 接收侧健康统计（视频模式下每 2s 上报一次 video_fb） */
    private final AtomicLong vRxBytes = new AtomicLong();
    private final AtomicLong vRxFrames = new AtomicLong();
    private volatile int vLastSeq = -1;

    public CallManager(Link link, Store store) {
        this.link = link;
        this.store = store;
        this.level = store == null ? Store.VIDEO_MAX_LEVEL : store.videoMaxLevel();
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setVideoUi(VideoUi ui) {
        this.videoUi = ui;
    }

    public State state() {
        return state;
    }

    public boolean isBusy() {
        return state != State.IDLE;
    }

    public boolean isTalking() {
        return state == State.TALKING;
    }

    /** 本次通话是否为视频通话 */
    public boolean isVideoMode() {
        return videoMode;
    }

    public int level() {
        return level;
    }

    public String peerIp() {
        return peerIp;
    }

    public String peerName() {
        return peerName;
    }

    public long elapsedMs() {
        return (state == State.TALKING && talkStartMs > 0)
                ? System.currentTimeMillis() - talkStartMs : 0L;
    }

    // ==================== 呼出 ====================

    /** 发起语音呼叫（发给当前对话设备）。已有通话时静默拒绝。 */
    public boolean startOutgoing() {
        return startOutgoing(false);
    }

    /**
     * 发起呼叫。
     *
     * @param video 恒为 false —— PC 端**没有视频采集能力**（R2），传 true 直接拒绝并记日志。
     */
    public boolean startOutgoing(boolean video) {
        if (video) {
            Log.i("PC 端不支持发起视频通话（无摄像头采集），已拦截");
            return false;
        }
        if (isBusy()) {
            Log.i("已有通话进行中，拒绝新的呼出请求");
            return false;
        }
        // 用「实际可达地址」而不是 selectedIp：设备分组后用户可能把当前地址切到了
        // 同设备的另一个（热点/网卡），会话仍建在原地址上 —— 记错地址会导致
        // 对端的 call_accept / call_end 因源 IP 与 peerIp 不符被丢弃，呼叫永远接不通。
        String ip = link.currentPeerIp();
        if (ip.isEmpty()) {
            Log.i("尚未选择目标设备，无法发起通话");
            return false;
        }
        // 本机界面显示名（可能含**用户私有备注**）只留在本机；
        // 发到对端显示的必须是**本机设备名**（store.deviceName()）——
        // 备注是「仅自己可见」的（2026-09-16 R4），不能顺着 call_invite 泄给对方。
        String localLabel = link.selectedLabel();
        if (localLabel == null || localLabel.isEmpty()) {
            localLabel = ip;
        }
        String name = (store == null) ? "PC" : store.deviceName();
        // 媒体会话先建再发 invite：任务单 2.4/2.5 要在 invite 里带上本端 bind 出来的媒体端口，
        // 所以定档必须早于协商报文（振铃期完成，§4.3）。invite 发不出去 ⇒ 就地关掉，
        // 不留半个已 bind 的会话（2.9 的"呼叫失败也必须释放"从这条分支开始成立）。
        openTransport(ip, true);
        // 五参：显式声明本端支持语音低延迟（af=20）。此前用四参重载默认 af=0，
        // 导致「PC 呼出 → Android 接听」时两端帧长错配（Android 发 200ms、PC 发 20ms），
        // 双向完全无声（2026-09-15 语音低延迟改造引入的回归）。
        // 末尾三项 = 本端第 0 档的媒体通道三元组（2.4 的 wire 字段，2.5 起是**真值**：
        // openTransport 里已经 bind 好第 0 档；bind 不成立时这里就是 0/false + 仍然带 msid，
        // 对端据此认出"新端但本档不成立"⇒ 继续按 §4.3⑤ 换下一档）
        AvTransport t0 = av;
        int mport = t0 == null ? 0 : t0.mediaPort();
        boolean mudp = t0 != null && t0.mediaIsUdp();
        String msid = t0 == null ? "" : t0.localMsid();
        if (!link.sendCallInvite(name, Discovery.localIpv4(), false, 1,
                VoiceEngine.VOICE_FRAME_MS_LOWLAT, mport, mudp, msid)) {
            Log.i("设备 " + localLabel + " 未连接，无法发起通话");
            closeTransport();
            return false;
        }
        this.peerIp = ip;
        // 本机显示用私有备注名，对端看到的是上面发出的本机设备名（互不干扰）
        this.peerName = localLabel;
        this.videoMode = false;
        setState(State.OUTGOING);
        // §4.3 的七步在振铃期跑完（第 0 档已 bind 并已随 invite 广告出去）
        startNegotiation();
        final String rip = ip;
        daemon(() -> {
            try {
                Thread.sleep(RING_TIMEOUT_MS);
            } catch (InterruptedException ignore) {
                return;
            }
            if (state == State.OUTGOING && rip.equals(peerIp)) {
                link.sendCallCancel(rip);
                endCall(rip, "无人接听");
            }
        }, "pp-call-ring");
        return true;
    }

    public void cancelOutgoing() {
        if (state != State.OUTGOING) {
            return;
        }
        String ip = peerIp;
        link.sendCallCancel(ip);
        endCall(ip, "已取消");
    }

    // ==================== 呼入 ====================

    public void onIncoming(String peerIp, String name) {
        onIncoming(peerIp, name, false, 1, 0);
    }

    /**
     * 呼入（带视频协商 + 语音帧长协商）。
     *
     * @param vid 呼叫方希望视频
     * @param lv  呼叫方的起始档位
     * @param af  呼叫方期望的语音帧长（20 = 低延迟；0 = 老版本未声明）
     */
    public void onIncoming(String peerIp, String name, boolean vid, int lv, int af) {
        onIncoming(peerIp, name, vid, lv, af, 0, false, "");
    }

    /**
     * 呼入（带视频 / 语音帧长 / **媒体通道**协商）。
     *
     * @param mport 呼叫方的媒体端口；0 = 未声明 ⇒ 本端只能落带内档
     * @param mudp  呼叫方媒体端口是否 UDP
     * @param msid  呼叫方本次通话的随机 token；空 = 未声明
     */
    public void onIncoming(String peerIp, String name, boolean vid, int lv, int af,
                           int mport, boolean mudp, String msid) {
        if (isBusy()) {
            Log.i("忙：静默拒绝来自 " + peerIp + " 的呼入");
            link.sendCallBusy(peerIp);
            return;
        }
        this.peerIp = peerIp;
        // 本机显示优先用**本机对该设备的备注**（私有，仅自己可见）；
        // 没有备注才回退对端自报的设备名（2026-09-16 R4，与 Android 端语义一致）
        String localRemark = store == null ? "" : store.remarkOf(peerIp);
        this.peerName = (localRemark != null && !localRemark.isEmpty())
                ? localRemark
                : ((name == null || name.isEmpty()) ? peerIp : name);
        boolean canVideo = store == null || store.videoEnabled();
        this.videoMode = vid && canVideo;
        int maxLv = store == null ? Store.VIDEO_MAX_LEVEL : store.videoMaxLevel();
        this.level = Math.max(0, Math.min(lv, maxLv));
        this.pendingAfMs = af;
        this.peerMport = mport;
        this.peerMudp = mudp;
        this.peerMsid = msid == null ? "" : msid;
        // 被叫侧也在**振铃期**建媒体会话并起时序（任务单 2.5；原先这一步在 acceptIncoming 里，
        // 那时无条件回 call_accept ⇒ 端口只能广告"未声明"，七步时序没地方跑）。
        // 未接听的来电在 endCall（拒绝 / 对方取消 / 超时）里由 closeTransport 释放端口。
        openTransport(peerIp, false);
        if (vid && !canVideo) {
            Log.i("对端请求视频，但本机 videoEnabled=false → 以纯语音接听");
        }
        setState(State.INCOMING);
        startNegotiation();
        Listener l = listener;
        if (l != null) {
            l.onIncomingCall(peerIp, this.peerName, this.videoMode);
        }
    }

    public void acceptIncoming() {
        if (state != State.INCOMING) {
            return;
        }
        String ip = peerIp;
        // 媒体会话与七步时序在**振铃期**就起好了（onIncoming 里 openTransport + startNegotiation，
        // 原先这一步在本方法里 ⇒ 那时还没有任何端口可广告）。这里只把当前正在广告的那一档
        // 带进 call_accept；对端随后仍会由 call_media 收到更新后的三元组（时序可能还在往后档推进）。
        AvTransport t = av;
        int mport = (t == null) ? 0 : t.mediaPort();
        boolean mudp = t != null && t.mediaIsUdp();
        String msid = (t == null) ? "" : t.localMsid();
        // 回传本端可接受档位（对端取 min）与期望的语音帧长（双方都声明 20ms 才启用低延迟）
        link.sendCallAccept(ip, videoMode, level, VoiceEngine.VOICE_FRAME_MS_LOWLAT, mport, mudp, msid);
        requestStartTalking();
    }

    public void rejectIncoming() {
        if (state != State.INCOMING) {
            return;
        }
        String ip = peerIp;
        link.sendCallReject(ip);
        endCall(ip, "对方拒绝");
    }

    // ==================== 对端应答 ====================

    public void onAccept(String peerIp) {
        onAccept(peerIp, false, 1, 0);
    }

    public void onAccept(String peerIp, boolean vid, int lv) {
        onAccept(peerIp, vid, lv, 0);
    }

    /** 对端接听（带视频应答与语音帧长协商） */
    public void onAccept(String peerIp, boolean vid, int lv, int af) {
        onAccept(peerIp, vid, lv, af, 0, false, "");
    }

    /** 对端接听（带视频 / 语音帧长 / **媒体通道**协商），三项语义同 {@link #onIncoming} 的同名参数 */
    public void onAccept(String peerIp, boolean vid, int lv, int af,
                         int mport, boolean mudp, String msid) {
        if (state == State.OUTGOING && peerIp.equals(this.peerIp)) {
            // PC 呼出恒为语音；若对端回 vid=true 说明它愿意视频，但本端没有采集能力 →
            // 仍以语音进行（不该出现，防御性记录）
            if (vid) {
                Log.i("对端接受为视频通话，但本端无采集能力 → 以语音进行");
            }
            videoMode = false;
            pendingAfMs = af;
            peerMport = mport;
            peerMudp = mudp;
            peerMsid = msid == null ? "" : msid;
            // 主叫侧此刻才拿到被叫的三元组（invite 时还没有）⇒ 喂给媒体模块，
            // 振铃期时序后面每一档的 probe/媒体 TCP 连接都打这个端口。
            // accept 帧里**没有** mtier（§4.3① 之后由 call_media 逐档交换）⇒ 传 -1「未声明」。
            AvTransport t = av;
            if (t != null) {
                t.offerPeerTriple(mport, mudp, peerMsid, -1);
            }
            // §4.3：定档全程在振铃期完成，"进入通话"要等时序结论（见 requestStartTalking 的说明）
            requestStartTalking();
        }
    }

    /**
     * 对端在振铃期逐档交换过来的媒体三元组（任务单 2.5 / 方案 §4.3①，2026-10-02 用户点的 (a) 口径）。
     *
     * <p>这是**唯一**能在时序进行中更新「对端此刻听哪个端口」的通道：accept 只有一帧，
     * 而五档一轮一轮地推进，每一轮本端端口都会变（§4.1：逐档换端口、不复用）。
     * mtier 只进日志，不参与定档判断 —— 双端的档位清单长度不同（安卓滤掉两条 443 档），
     * 序号无法对齐，能对齐的是**协议类**（UDP 对 UDP、媒体 TCP 对媒体 TCP）。
     */
    public void onCallMedia(String peerIp, int mport, boolean mudp, String msid, int mtier) {
        if (state == State.IDLE || !peerIp.equals(this.peerIp)) {
            return;   // 不属于本通的协商帧（已挂断 / 串到了别的对端）直接丢
        }
        this.peerMport = mport;
        this.peerMudp = mudp;
        this.peerMsid = msid == null ? "" : msid;
        AvTransport t = av;
        if (t != null) {
            t.offerPeerTriple(mport, mudp, this.peerMsid, mtier);
        }
    }

    public void onReject(String peerIp) {
        if ((state == State.OUTGOING || state == State.INCOMING) && peerIp.equals(this.peerIp)) {
            endCall(peerIp, "对方拒绝");
        }
    }

    public void onBusy(String peerIp) {
        if (state == State.OUTGOING && peerIp.equals(this.peerIp)) {
            endCall(peerIp, "对方忙");
        }
    }

    public void onCancel(String peerIp) {
        if (state == State.INCOMING && peerIp.equals(this.peerIp)) {
            endCall(peerIp, "对方取消");
        }
    }

    // ==================== 挂断 ====================

    public void hangup() {
        // 呼出中（OUTGOING）点挂断 = 取消呼叫；通话中（TALKING）= 挂断
        if (state == State.OUTGOING) {
            String ip = peerIp;
            link.sendCallCancel(ip);
            endCall(ip, "已取消");
            return;
        }
        if (state != State.TALKING) {
            return;
        }
        String ip = peerIp;
        link.sendCallEnd(ip, "");
        endCall(ip, "已挂断");
    }

    public void onRemoteEnd(String peerIp, String reason) {
        if (peerIp.equals(this.peerIp)
                && (state == State.TALKING || state == State.OUTGOING)) {
            String text;
            if (reason == null || reason.isEmpty()) {
                text = "对方已挂断";
            } else if ("record_fail".equals(reason)) {
                text = "对方无法访问麦克风，通话被取消";
            } else if ("timeout".equals(reason)) {
                text = "对方通话已达时长上限";
            } else {
                text = reason;
            }
            endCall(peerIp, text);
        }
    }

    // ==================== 音频 ====================

    public void onAudio(String peerIp, int seq, long ts, byte[] data, int len) {
        AvTransport t = av;
        if (t != null) {
            t.deliverAudio(peerIp, seq, ts, data, len);
        }
    }

    /** 模块收帧回调（过滤口径与改前 onAudio 逐字一致：state==TALKING 且源地址＝本通话对端） */
    private void handleInboundAudio(String fromIp, int seq, long ts, byte[] data, int len) {
        if (state != State.TALKING || !fromIp.equals(this.peerIp)) {
            return;
        }
        voice.onAudioReceived(seq, ts, data, len);
    }

    /** 视频窗口的「静音」按钮 */
    public void setMuted(boolean muted) {
        voice.setMuted(muted);
    }

    public boolean isMuted() {
        return voice.isMuted();
    }

    // ==================== 视频（接收，均在非读线程或"只入队"语义下） ====================

    /**
     * 编码参数变更（**网络读线程**回调）。
     * 这里只落字段 + 让 ffmpeg 线程自己重启进程，绝不 spawn。
     */
    public void onVideoCfg(String peerIp, String codec, int w, int h, int fps, int br, int rot,
                           boolean front) {
        if (!videoMode || !peerIp.equals(this.peerIp)) {
            return;
        }
        // 2026-09-22 二期：Android 发送端 GL 已把数据转正（微信同款）→ rot 直接用，
        // 不再对前置额外 +180；PC 自身发送不走 video_cfg.cam（恒后置语义）
        int newRot = ((rot % 360) + 360) % 360;
        // 发送端 2026-09-22 起 1s 心跳重发 video_cfg（rot/cam 最终一致保障）：
        // 值全部没变时跳过日志与状态刷新，避免每秒刷屏；setFormat/setRotation 本身幂等照调。
        boolean unchanged = codec != null && codec.equals(cfgCodec) && w == cfgW && h == cfgH
                && fps == cfgFps && br == cfgBr && newRot == cfgRot;
        cfgCodec = codec == null ? "" : codec;
        cfgW = w;
        cfgH = h;
        cfgFps = fps;
        cfgBr = br;
        cfgRot = newRot;
        if (!unchanged) {
            Log.i("收到视频参数: " + cfgCodec + " " + w + "x" + h + " @" + fps
                    + "fps " + br + "k rot=" + rot + "° cam=" + (front ? "前置" : "后置")
                    + " → 默认显示基线 " + cfgRot + "°");
        }

        // 档位跟随码率推断（协议里 video_cfg 不带 lv；`br` 单位是 **kbps**，见 docs/PROTOCOL.md §4.5）
        // ⚠ 改前是 `br <= 500 ? 0 : 1` 两级：720P(1500k) 与 1080P(2000k) 全塌成 L1 ⇒ 本端显示的档位
        //   对 1080P 永远少一级（清单 3.3）。阈值取安卓 VideoLevel 表里的码率原值
        //   （500/1500/2000/5000k，10-03 加了 L3 超清 1080P/5M），不新设数字。
        //   语音码率仍只有两档（BITRATE_VIDEO_L0=32k / L1=64k），与安卓表里 L1~L3 同为
        //   64_000 bps 一致 ⇒ 塌缩只对显示是 bug，对码率从来不是。
        //   L3 与 L2 分辨率逐字节相同，解码器按 video_cfg 的 w/h 建，不受这里影响。
        int lv = br <= 500 ? 0 : (br <= 1500 ? 1 : (br <= 2000 ? 2 : 3));
        if (lv != level) {
            level = lv;
            voice.setBitrate(lv == 0 ? VoiceEngine.BITRATE_VIDEO_L0 : VoiceEngine.BITRATE_VIDEO_L1);
            Log.i("视频档位跟随调整为 L" + lv);
        }
        FfmpegDecoder d = vdec;
        if (d != null) {
            d.setFormat(cfgCodec, cfgW, cfgH);
        }
        VideoUi ui = videoUi;
        if (ui != null) {
            ui.setRotation(cfgRot);
            if (!unchanged) {
                ui.setStatus(cfgW + "x" + cfgH + " " + cfgFps + "fps " + cfgBr + "kbps"
                        + " cam:" + (front ? "front" : "back"));
            }
        }
    }

    /**
     * 收到一帧视频（**网络读线程**回调）→ 交给媒体模块分发（任务单 2.1）。
     * 模块回调再入队，立即返回；真正的解码在 ffmpeg 自己的 pp-video-feed / pp-video-read 线程。
     */
    public void onVideoFrame(String peerIp, int seq, long ts, int flag, byte[] data, int len) {
        AvTransport t = av;
        if (t != null) {
            t.deliverVideo(peerIp, seq, ts, flag, data, len);
        }
    }

    /** 模块收帧回调（守卫与计数与改前 onVideoFrame 逐字一致，只把参数名换成 fromIp） */
    private void handleInboundVideo(String fromIp, int seq, long ts, int flag, byte[] data, int len) {
        long t0 = System.nanoTime();
        if (!videoMode || state != State.TALKING || !fromIp.equals(this.peerIp)) {
            return;
        }
        // ⚠ 去重口径与安卓端对齐（视频收帧唯一的两处差异之一，另一处是 UDP 分片重组在 AvTransport）：
        //   「TCP 有序无重复」在 UDP 切片路径上不再成立 —— 一帧重组完成的先后与发送顺序无关，
        //   后发帧可能先完整重组出来。参数集 AU（CONFIG）本身可能整帧迟到并被这条单调判断吃掉，
        //   而 PC 解码器拿到半截流就没有 SPS/PPS ⇒ 永远黑屏。故 CONFIG 不受单调去重约束。
        boolean isConfig = (flag & Protocol.VIDEO_FLAG_CONFIG) != 0;
        if (!isConfig) {
            if (seq <= vLastSeq) {
                return;
            }
            vLastSeq = seq;
        }
        vRxFrames.incrementAndGet();
        vRxBytes.addAndGet(len);
        FfmpegDecoder d = vdec;
        if (d != null) {
            d.enqueue(data, len, flag, ts);
            SegMeter.get().record(SegMeter.V_IN, t0, System.nanoTime());
        }
    }

    /** 对端请求停/开视频（PC 作为接收方，这里只记日志） */
    public void onVideoReq(String peerIp, boolean on) {
        if (!videoMode || !peerIp.equals(this.peerIp)) {
            return;
        }
        Log.i("对端请求" + (on ? "开启" : "暂停") + "视频");
    }

    // ==================== 内部 ====================

    private void startTalking() {
        if (state != State.OUTGOING && state != State.INCOMING) {
            return;
        }
        // 媒体帧 ts 的零点（任务单 1.1：通话相对单调时钟，起呼=0）。必须与安卓端
        // voice/CallManager.startTalking 同位置——两端都在「进入 TALKING 前」置零。
        CallClock.reset();
        // 分段延迟计量起表（任务单 1.2）：每秒一行 p50/p95/max，挂断即停表
        SegMeter.get().start(Log::i);
        // 通话计数器清零（任务单 1.7）：计数口径 = 本次 TALKING 段，与 CallClock/SegMeter 同位置，
        // 双端同序。挂断时在 endCall 打唯一一行汇总。
        CallCounters.get().reset();
        talkStartMs = System.currentTimeMillis();
        vLastSeq = -1;
        vRxBytes.set(0);
        vRxFrames.set(0);
        setState(State.TALKING);
        // 2.12 / D16：进入 TALKING ⇒ "通话中"这一票投 1（你点的边界：**仅接通后**，振铃期不投，
        // 所以不是 isBusy()）。此后文件与目录一律不入网、在传的在分片/成员边界暂停。
        link.setFileVote(Link.VOTE_IN_CALL, true);
        final boolean vm = videoMode;
        final int lv = level;
        // ⚠ 音频设备初始化（AudioSystem.getLine / line.open）可能阻塞数百毫秒~数秒，
        // 必须放到独立线程：startTalking 在 PC 由**网络读线程**调用（见 PcMain 的
        // Link.Listener 转发），在那里阻塞会卡死整条会话（收不到对端帧 → 对端判死掉线；
        // 挂断时再抢 VoiceEngine 的锁就死锁、UI 无法关闭）。
        daemon(() -> {
            if (state != State.TALKING || peerIp.isEmpty()) {
                return;
            }
            // 音频参数必须在 startRecord/startPlay **之前**一次性设定（收发帧长硬对称）。
            // 语音帧长协商：**双方都声明 20ms 才用低延迟模式**，否则回落 200ms（老端）。
            final int afMs = (pendingAfMs >= 1 && pendingAfMs <= VoiceEngine.VOICE_FRAME_MS_LOWLAT)
                    ? VoiceEngine.VOICE_FRAME_MS_LOWLAT : VoiceEngine.VOICE_FRAME_MS;
            voice.configure(vm,
                    lv == 0 ? VoiceEngine.BITRATE_VIDEO_L0 : VoiceEngine.BITRATE_VIDEO_L1, afMs);
            if (!vm) {
                Log.i(afMs <= VoiceEngine.VOICE_FRAME_MS_LOWLAT
                        ? "语音低延迟模式：" + afMs + "ms/帧"
                        : "语音兼容模式：" + afMs + "ms/帧（对端版本较旧，未声明低延迟）");
            }
            if (vm) {
                startVideoReceive();
            }
            if (!voice.startRecord()) {
                Log.w("录音启动失败，强制挂断");
                if (state == State.TALKING) {
                    // 原因写进 call_end 帧，对端弹「对方无法访问麦克风」而不是无声消失
                    link.sendCallEnd(peerIp, "record_fail");
                    endCall(peerIp, "无法访问麦克风，请检查系统录音设备");
                }
                return;
            }
            if (state != State.TALKING) {
                voice.stopAll();
                return;
            }
            voice.startPlay();
            voice.setListener(frame -> {
                if (state == State.TALKING) {
                    // 发送耗时回传给码率自适应（TCP 写阻塞 = 链路拥塞的直接证据）
                    // ts = 通话相对毫秒（CallClock），不是墙钟：与视频帧共用同一零点
                    AvTransport t = av;
                    long w = (t == null) ? -1L
                            : t.sendAudio(frame.seq, CallClock.elapsedMs(),
                            frame.data, frame.data.length);
                    // aTx：编码完 → 已交给传输（含抢写锁与 socket 写；失败返回 -1 也计入）
                    SegMeter.get().record(SegMeter.A_TX, frame.encDoneNano, System.nanoTime());
                    VoiceBitrateController c = voiceQuality;
                    if (c != null) {
                        c.onFrameSent(w);
                    }
                }
            });
            if (!vm) {
                startVoiceAutoQuality();
            }
        }, "pp-call-audio");
        // 6h 超时：每次通话一个单线程 ScheduledExecutorService（daemon 线程）。
        // 旧实现 Thread.sleep：提前挂断后线程仍睡满剩余时长；现在 endCall 里
        // cancelCallTimeout() → future.cancel(false) + executor shutdown，
        // 未触发的任务被取消、空闲工作线程立即退出。
        final java.util.concurrent.ScheduledExecutorService timeoutExec =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "pp-call-timeout");
                    t.setDaemon(true);
                    return t;
                });
        callTimeoutExec = timeoutExec;
        callTimeoutFuture = timeoutExec.schedule(() -> {
            if (state == State.TALKING) {
                Log.i("通话超过 6 小时，强制挂断");
                String ip = peerIp;
                link.sendCallEnd(ip, "timeout");
                endCall(ip, "通话超时");
            }
        }, MAX_CALL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 起视频接收链路。**只能在 pp-call-audio 线程里调用**（会 spawn 进程 + 建窗口）。 */
    private void startVideoReceive() {
        if (vdec != null) {
            return;
        }
        VideoUi ui = videoUi;
        if (ui != null) {
            ui.open(peerName);
        }
        String bundled = FfmpegDecoder.bundledPath();
        String userPath = store == null ? "" : store.ffmpegPath();
        String hw = store == null ? "" : store.ffmpegHwaccel();
        FfmpegDecoder d = new FfmpegDecoder(bundled, userPath, hw, (bgra, w, h, len) -> {
            VideoUi u = videoUi;
            if (u != null) {
                u.onFrame(bgra, w, h);
            }
        });
        // 解码进程换代 = 新进程只能从一个 IDR 起解，而发送侧 GOP=1s ⇒ 不主动要一个关键帧，
        // 画面就要白等到下一个 IDR（用户看到的「接通后卡几秒」）。回调在 pp-video-feed 线程，
        // 这里只做一次非阻塞 execute；挂断/停视频之后状态不过，就不会再发。
        d.setReseedListener(() -> {
            if (state == State.TALKING && videoMode) {
                CallCounters.get().keyframeReq.inc();
                link.sendVideoKeyRequest(peerIp);
            }
        });
        vdec = d;
        // 画面盒：窗口一改版就把出帧尺寸贴上去；上一次通话的盒先补挂，
        // 免得每通开头都以源尺寸起进程、等首次布局再换一次进程（多要一个 IDR）。
        d.setDisplayBox(lastBoxW, lastBoxH);
        VideoUi bu = videoUi;
        if (bu != null) {
            bu.setBoxListener((bw, bh) -> {
                lastBoxW = bw;
                lastBoxH = bh;
                FfmpegDecoder x = vdec;
                if (x != null) {
                    x.setDisplayBox(bw, bh);
                }
            });
        }
        d.start();
        // 若 video_cfg 先于本方法到达，补一次格式设定（其余情况由 onVideoCfg 驱动）
        if (!cfgCodec.isEmpty() && cfgW > 0) {
            d.setFormat(cfgCodec, cfgW, cfgH);
            if (ui != null) {
                ui.setRotation(cfgRot);
                ui.setStatus(cfgW + "x" + cfgH + " " + cfgFps + "fps " + cfgBr + "kbps");
            }
        }
        startFbLoop();
        Log.i("视频接收已启动（档位 L" + level + "）");
    }

    /** 每 2s 上报一次接收健康度，供发送侧升降档判断（§9.2） */
    private void startFbLoop() {
        if (fbRunning) {
            return;
        }
        fbRunning = true;
        daemon(() -> {
            long lastDec = 0;
            long lastDrop = 0;
            long lastBytes = 0;
            while (fbRunning && state == State.TALKING) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                if (state != State.TALKING) {
                    break;
                }
                FfmpegDecoder d = vdec;
                if (d == null) {
                    continue;
                }
                long dec = d.takeDecodedCount();
                long drop = d.takeDroppedCount();
                long dDec = dec - lastDec;
                long dDrop = drop - lastDrop;
                lastDec = dec;
                lastDrop = drop;
                long deltaBytes = vRxBytes.get() - lastBytes;
                lastBytes = vRxBytes.get();
                int fps = (int) (dDec / 2);
                int kbps = (int) (deltaBytes * 8 / 2000);
                long tot = dDec + dDrop;
                int dropPct = tot <= 0 ? 0 : (int) (dDrop * 100 / tot);
                // lat = 真实排队时延（任务单 1.3：不再把丢帧期折成 -1 再夹 0），
                // 「正在丢帧」改由独立的 dropping 字段说
                link.sendVideoFb(peerIp, fps, dropPct, d.lastQueueDelayMs(), d.dropping(), kbps);
            }
            fbRunning = false;
        }, "pp-video-fb");
    }

    /**
     * 纯语音通话的 Opus 码率自适应采样（每 2s 一次）。
     *
     * 只做两件事：读统计（发送耗时 / 播放队列积压 / 缺帧增量）+ 调 Opus 码率。
     * **不碰 socket 写、不碰 UI、不碰解码器**，可安全跑在自己的 pp-voice-aq 线程上。
     * 退出条件：通话结束（state != TALKING）或本次通话变成视频通话。
     */
    private void startVoiceAutoQuality() {
        VoiceBitrateController ctrl = voiceQuality;
        if (ctrl == null) {
            ctrl = new VoiceBitrateController(b -> {
                voice.setBitrate(b);
                Log.i("语音码率自适应 → " + (b / 1000) + "k");
            });
            ctrl.reset();
            voiceQuality = ctrl;
        }
        Thread old = aqThread;
        if (old != null && old.isAlive()) {
            return;
        }
        final VoiceBitrateController c = ctrl;
        Thread t = new Thread(() -> {
            // 统计从零开始（在本线程内先重置再读基准，避免与接收线程抢跑）
            voice.resetStats();
            int lastReceived = voice.receivedFrames();
            int lastMissed = voice.missedFrames();
            while (state == State.TALKING && !videoMode) {
                try {
                    Thread.sleep(VoiceBitrateController.EVAL_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    return;
                }
                if (voiceQuality == null) {
                    return;
                }
                int rcv = voice.receivedFrames();
                int mis = voice.missedFrames();
                int dRcv = rcv - lastReceived;
                int dMis = mis - lastMissed;
                lastReceived = rcv;
                lastMissed = mis;
                // 任务单 1.5：积压口径 = 队列 ms + 线内未播 ms（只算队列虚好 40–43ms）。
                // 任务单 4：这个毫秒口径现在**同时是码率自适应的判据**（原来判据是「≥2 帧」，
                // 20ms 帧下 = 40ms，比接收端水位目标 80ms 还低，稳态必误判）。
                // 线内不可测（-1）时如实打"不可测"，不折成 0 少算。
                // 任务单 3.3：末尾带出向队列帧数 —— 发端只入队不入网的那一段，收端计数器看不见。
                long qMs = voice.backlogQueueMs();
                long wMs = voice.backlogWireMs();
                long backlogMs = qMs + (wMs < 0L ? 0L : wMs);
                AvTransport at = av;
                int outFrames = at == null ? 0 : at.pendingAudioDatagrams();
                Log.i("音频积压 " + backlogMs + "ms（队列 " + qMs + "ms/"
                        + voice.pendingFrames() + " 帧 + 线内 "
                        + (wMs < 0L ? "不可测" : wMs + "ms") + "）出向 " + outFrames + " 帧");
                c.evaluate(backlogMs, voice.frameMs(), dMis, dRcv);
            }
        }, "pp-voice-aq");
        t.setDaemon(true);
        aqThread = t;
        t.start();
        Log.i("语音码率自适应已启用（64k 起步，弱网降到 32k）");
    }

    private void stopVideoReceive() {
        fbRunning = false;
        FfmpegDecoder d = vdec;
        vdec = null;
        if (d != null) {
            // stop() 本身不阻塞（杀进程丢后台线程），可以放心在挂断路径上调用
            d.stop();
        }
        VideoUi ui = videoUi;
        if (ui != null) {
            ui.close();
        }
        cfgCodec = "";
        cfgW = 0;
        cfgH = 0;
    }

    /**
     * 会话层发现「通话对端的健康会话已消失」时调用（Link 掉线 → PcMain 转发）。
     *
     * 与 Android 端 {@code CallManager.onPeerDisconnected} **同名同义，两端必须对称**：
     * 链路掉线只走 {@code onState/onPeersChanged}，CallManager 不挂在那条链路上，
     * 不补这一下 state 会永远停在 TALKING（最坏 6h）—— 界面一直显示「正在通话」、
     * 视频窗口不会关、挂断之外的按钮全部无效。
     *
     * 线程：由 {@code callAsync} 切到 pp-call-cb 上执行，与其它通话回调一致；
     * {@link #endCall} 本身已做到「先摘 listener、释放丢后台」，不会卡住调用者。
     */
    public void onPeerDisconnected(String ip) {
        if (state == State.IDLE) {
            return;
        }
        if (ip == null || !ip.equals(peerIp)) {
            return;
        }
        String text = (state == State.OUTGOING) ? "设备已断开，呼叫已取消" : "连接已断开，通话结束";
        Log.i("通话对端 " + ip + " 会话已消失（state=" + state + "）→ 结束通话");
        endCall(ip, text);
    }

    /**
     * 取消并释放 6h 超时定时器：future.cancel(false)（不打断正在运行的任务；
     * 恰好 6h 触发的竞态由任务内 state==TALKING 检查兜底）+ executor shutdown，
     * 单线程 executor 空闲线程随即退出。
     */
    private void cancelCallTimeout() {
        java.util.concurrent.ScheduledFuture<?> f = callTimeoutFuture;
        callTimeoutFuture = null;
        if (f != null) {
            f.cancel(false);
        }
        java.util.concurrent.ScheduledExecutorService exec = callTimeoutExec;
        callTimeoutExec = null;
        if (exec != null) {
            exec.shutdown();
        }
    }

    private void endCall(String ip, String reason) {
        // 分段计量停表（只写一个标志，任何线程可调）；样本在下次 start 时清零
        SegMeter.get().stop();
        // 先取消 6h 超时定时器（未触发任务被取消、工作线程立即退出）
        cancelCallTimeout();
        boolean wasTalking = state == State.TALKING;
        boolean wasVideo = videoMode;
        boolean wasOutgoing = state == State.OUTGOING;
        // 时长必须在清 talkStartMs 之前算好（v2.7：上层要把它记成一条通话消息）
        long durationMs = (wasTalking && talkStartMs > 0)
                ? System.currentTimeMillis() - talkStartMs : 0L;
        // 先摘监听（立刻停止后续音频发送），音频设备释放放后台：
        // 若音频初始化线程仍持有 VoiceEngine 的锁（startRecord 阻塞中），同步 stopAll
        // 会把调用线程（网络读线程）一起卡住 —— 通话已结束但会话/UI 卡死。
        voice.setListener(null);
        voiceQuality = null;
        // 媒体会话关闭（任务单 2.1 建、此处收；2.9 会把"挂断/异常/对端消失/切后台/呼叫失败"
        // 五条路径全部汇聚到这一个 closeTransport）
        closeTransport();
        daemon(() -> {
            try {
                voice.stopAll();
            } catch (Throwable ignore) {
            }
        }, "pp-voice-stop");
        // 视频收尾同样先摘引用、再异步停（VideoUi.close 内部走 invokeLater）
        stopVideoReceive();
        videoMode = false;
        peerIp = "";
        peerName = "";
        talkStartMs = 0L;
        // 定档意图/结论随本通一起作废：closeTransport 已把协商线程叫停（stop() ⇒ negotiationStopped），
        // 残留的 onNegotiated 会被上面那句 IDLE 判断挡住，但标志本身不能留给下一通。
        negotiated = false;
        pendingStartTalking = false;
        setState(State.IDLE);
        // 2.12 / D16：挂断 ⇒ "通话中"这一票收回为 0。若三票全为 0，setFileVote 内部会自动
        // 续发一次待发队列（对端没应答就停在 PAUSED 等手动「继续」）。
        link.setFileVote(Link.VOTE_IN_CALL, false);
        Listener l = listener;
        if (l != null) {
            l.onCallEnded(ip, reason, wasVideo, durationMs, wasOutgoing);
        }
        if (wasTalking) {
            Log.i("通话结束（" + reason + "）");
            // 计数器唯一打印点（任务单 1.7）：一行 13 项，全 0 也要出现（缺行 = 没接上）
            CallCounters.get().logSummary(Log::i);
        }
    }

    private void setState(State s) {
        state = s;
        Listener l = listener;
        if (l != null) {
            l.onStateChanged(s, peerIp, peerName);
        }
    }

    /**
     * 2.9 的"切后台 / 实例销毁"这一条退出路径的落点。
     * ⚠ 这里必须先 {@link #closeTransport()}：媒体会话是振铃期就建的（{@link #openTransport}），
     *   本方法过去只收尾频与定时器 ⇒ 走这条路径退出会留下已 bind 的 UDP/TCP 端口、
     *   ④ 预建那条连接，以及 pp-av-alive 线程（"结束后记得关闭传输线程"正是防这个）。
     *   与 endCall 走的是**同一个** closeTransport ⇒ 可重入，重复调用不出异常。
     */
    public void dispose() {
        closeTransport();
        stopVideoReceive();
        voice.stopAll();
        cancelCallTimeout();
    }

    private static void daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.start();
    }
}
