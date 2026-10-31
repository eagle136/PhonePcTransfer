package com.phonepctransfer.pc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * PC 端本地存储。**根目录 = 用户数据目录**（`%APPDATA%\PhonePcTransfer`，见 [defaultRoot]），
 * 与程序目录彻底分开 —— 升级 / 重装 MSI 不会碰数据：
 *  - <root>/config.json         配置（deviceId / 端口 / 设备列表 / 开关）
 *  - <root>/history/&lt;设备组键&gt;.jsonl  聊天历史（**按对端设备分文件**，行分隔 JSON，追加写）
 *  - <root>/pending.jsonl       待发队列（每条带 peer 字段，标明目的设备）
 *  - <root>/received/           收到的文件（用户可在设置里改保存位置）
 *  - <root>/logs/               运行日志
 *
 * 历史按对端分文件是"多设备互不干扰"的落点：A 的聊天记录物理上就不在 C 的文件里，
 * 切换会话只是换一个文件读，不存在把别人的内容带出来的可能。
 *
 * ⚠️ 2026-09-21 之前在**程序目录**（源码运行 = `pc/`；安装版 = `%LOCALAPPDATA%\手机电脑互传\`）。
 *    老数据首次启动会被复制一份过来（不删除老目录），见 [migrateLegacyData]。
 */
public final class Store {

    public final Path root;
    /** 默认接收目录（用户没自定义保存位置时用这个） */
    public final Path defaultReceivedDir;
    public final Path logDir;

    private final Path configFile;
    private final Path historyDir;

    private String deviceId = "";
    private String deviceName = "";
    private int tcpPort = Protocol.DEFAULT_TCP_PORT;
    private int udpPort = Protocol.DEFAULT_UDP_PORT;
    private boolean autoWriteClipboard = true;
    private boolean allowConnectOut = true;
    /** 用户自定义的接收目录；空字符串表示用默认目录（pc/received） */
    private String saveDir = "";
    /** 上次（自动或手动）连接的设备 IP —— 启动扫描后自动连接一次的目标依据 */
    private String lastIp = "";

    // ==================== 视频通话配置（PC 只收不发） ====================

    /** 全局视频开关。false = 完全走改造前的音频路径（回退开关，见 VIDEO_CALL_PLAN §13） */
    private boolean videoEnabled = true;

    /**
     * 档位表上限（= 安卓端 {@code VideoLevel.MAX.idx}，2026-10-03 由 2 加到 3：
     * 新增「超清 1080P/5M」）。PC 端没有档位表，只按码率阈值反推 L 数（见
     * {@code CallManager.onVideoCfg}），所以这里只需要一个数字上限；改档位表必须同步改它。
     */
    public static final int VIDEO_MAX_LEVEL = 3;

    /** 可接受的最高档位（0=流畅 480P / 1=标清 720P / 2=高清 1080P/2M / 3=超清 1080P/5M）。接听视频来电时与呼叫方取 min */
    private int videoMaxLevel = VIDEO_MAX_LEVEL;

    /** 用户指定的 ffmpeg 路径；空 = 自动探测（bin/ffmpeg.exe → PATH → C:\ffmpeg\bin → msys64） */
    private String ffmpegPath = "";

    /** ffmpeg 硬件加速方式；空 = 纯软解（实测 673 fps，软解余量已足够，默认关） */
    private String ffmpegHwaccel = "";

    // ==================== UI 缩放钉定（2026-09-27） ====================

    /**
     * 渲染缩放钉定值（config.json 的 {@code uiScale}，缺省 1.0）。
     *
     * <p>背景：JBR 的 Swing 是 DPI-aware，字体与像素都按系统缩放等比渲染 —— 界面**比例**
     * 在 100%~200% 下不会破，但「物理观感」跟着系统缩放变（200% 下固定窗口还会超出逻辑屏）。
     * 用户定调（2026-09-29）：把渲染缩放钉在 1.0（100%）—— 1 逻辑 px = 1 物理 px，
     * 组件坐标、边框、字体底边全部落整数像素网格，消除分数 DPI 的抗锯齿抖动；
     * 宋体小字号（12~16px）内嵌点阵位图可 1:1 命中，笔画最锐利（字体与字号改造见
     * {@code Fonts} 与 README §1.2）。由 {@code PcMain} 在任何 AWT 初始化之前
     * 设 {@code sun.java2d.uiScale}（实测 -D 与程序内 setProperty 都生效）。
     * {@code uiScale: 0} 或 {@code "system"} = 取消钉定，跟随系统缩放。
     */
    static final double DEFAULT_UI_SCALE = 1.0;

    /** 当前生效的钉定值；&le;0 表示跟随系统（save() 时写成 "system"） */
    private double uiScale = DEFAULT_UI_SCALE;

    // ==================== 主题（2026-09-27） ====================

    /** 深色主题开关（config.json 的 {@code darkTheme}，缺省 false = 浅色）；由左下角「主题」按钮切换 */
    private boolean darkTheme = false;

    /** 设备列表（备注 + IP）；旧配置里的纯 IP 会在 load() 里迁移进来 */
    private final List<Peer> peerList = new ArrayList<Peer>();

    // ==================== 设备身份与分组（2026-09-16，与 Android 端同构） ====================
    //
    // 一台设备可能有多个地址（WiFi / 热点 / 网卡），它们都会在握手里自报同一个 deviceId。
    // 于是「设备」才是会话的单位：备注、聊天记录、当前地址都挂在**组**（= deviceId）上。
    // 组键解析必须能在**任意线程**被调用（发送热路径），所以权威来源是一张
    // ConcurrentHashMap（[ckeys]），不去碰受锁保护的 peerList。

    /** 权威绑定表：ip → 对端 deviceId */
    private final Map<String, String> bindings = new LinkedHashMap<String, String>();
    /**
     * 被用户**明确删掉**的别名设备（设备身份 → "1"）。
     *
     * <p>这些是「同一 IP 上还有另一台设备」那种**别名行**：用户右键「删除（不再显示这台设备）」
     * 就记在这里。不记的话对方每 2s 一次广播，2 秒后这条别名行又回来了 —— 用户看到的就是
     * 「删不掉」。该设备**真的占到某个地址**（成为活动设备）时自动解除（见 {@link #unignoreDev}），
     * 那时它是正经设备，就该出主行。
     */
    private final Map<String, String> ignoredDevs = new LinkedHashMap<String, String>();

    /** 组备注：deviceId → 备注（仅本机可见，绝不上协议） */
    private final Map<String, String> deviceRemarks = new LinkedHashMap<String, String>();
    /** 组当前地址：deviceId → ip */
    private final Map<String, String> groupCurIp = new LinkedHashMap<String, String>();
    /**
     * 网段派生的设备标记：组键 → "1"。
     *
     * 一台设备的任一地址命中清单 → 整台设备（组内全部地址）都按命中处理，直到**删除该设备**才解除。
     * 值只有 "1"：不存原因、地址、时间，避免命中痕迹落到配置文件里。
     * 用并发表是为了让连接路径无锁读取（`save()` 持锁写文件，不该被它阻塞）。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> blockedGroups =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    /**
     * 「已知设备」名单（后台允许连入的白名单）：组键 → 标志位串。
     *
     * 与安卓端 TrustedPeers 同规则（2026-10-01 定稿）：**握手成功过（含 'c'）**且
     * **有过消息/文件往来（含 'f'）**两个位都置齐，才算 trusted。
     * 位序固定 "cf"；连接路径（adopt 闸门）无锁读，写入走 synchronized + save()。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, String> trustedPeers =
            new java.util.concurrent.ConcurrentHashMap<String, String>();

    /**
     * 每台设备的**视频画面旋转角**（组键 → 度）。
     *
     * 只记**用户手动叠加**的那部分（0/90/180/270），对端给的 `video_cfg.rot` 基线由
     * CallManager 每次通话重新下发 —— 记绝对值会和对端基线打架。
     * 与安卓端同规则：按组键（= 设备）分开记，只要不卸载客户端就一直沿用。
     */
    private final Map<String, String> rotRemote = new LinkedHashMap<String, String>();
    /** 组键缓存（线程安全；ip → deviceId），由 bindings / peer.lastId 维持 */
    private final java.util.concurrent.ConcurrentHashMap<String, String> ckeys =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    /** 已尝试过历史归并的地址（失败不重试） */
    private final java.util.Set<String> mergeTried =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private final Object histLock = new Object();

    public Store(Path root) throws IOException {
        this.root = root;
        this.defaultReceivedDir = root.resolve("received");
        this.logDir = root.resolve("logs");
        this.configFile = root.resolve("config.json");
        this.historyDir = root.resolve("history");
        Files.createDirectories(defaultReceivedDir);
        Files.createDirectories(logDir);
        Files.createDirectories(historyDir);
        migrateLegacyHistory();
    }

    /**
     * 老版本的 history.jsonl 是「所有设备混在一个文件里」。
     *
     * 分不出来源就没法按对端拆开，硬塞给某一台设备反而是编造数据，所以直接改名归档
     * （`history.legacy-<时间戳>.jsonl`，内容一字不改地留着），新版本从空历史开始。
     */
    private void migrateLegacyHistory() {
        Path legacy = root.resolve("history.jsonl");
        if (!Files.exists(legacy)) {
            return;
        }
        try {
            if (Files.size(legacy) == 0) {
                Files.delete(legacy);
                return;
            }
            Path target = root.resolve("history.legacy-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date())
                    + ".jsonl");
            Files.move(legacy, target);
            Log.i("检测到旧版合并历史（所有设备混在一个文件），已归档为 " + target.getFileName()
                    + "；新版本按设备分文件存放");
        } catch (Exception e) {
            Log.w("归档旧版历史失败: " + e);
        }
    }

    /**
     * 会话组键：对端 deviceId（已知时），否则回退该 IP。
     *
     * 聊天记录、待发队列、消息归属一律按它对齐（同一台设备的多个地址 = 同一份会话）。
     * **无锁实现**：读的是 ConcurrentHashMap，可被发送热路径任意线程调用；
     * 权威来源由 [bindDevice] / [upsertPeer] 维护，启动时从 peer.lastId 播种。
     */
    public String convKeyOf(String ip) {
        String v = ip == null ? "" : ip.trim();
        if (v.isEmpty()) {
            return "";
        }
        String k = ckeys.get(v);
        return (k == null || k.isEmpty()) ? v : k;
    }

    /**
     * 把某个地址绑定到对端设备 id 上（对端在握手 hello 里自报过它的 id）。
     * 同一 id 绑多个地址 = 同一台设备有多个地址 → 自动成组。
     *
     * @return true 表示绑定关系发生了变化
     */
    public synchronized boolean bindDevice(String ip, String deviceId) {
        String v = ip == null ? "" : ip.trim();
        String d = deviceId == null ? "" : deviceId.trim();
        if (v.isEmpty() || d.isEmpty()) {
            return false;
        }
        // 绑定**前**这个地址的组键：若它自成一组（= 地址自身，例如手工添加、还没连过的行），
        // 绑上之后这一组就要并进设备 d —— 组上挂的本机状态（备注 / 当前地址）要跟着搬。
        String oldKey = convKeyOf(v);
        boolean changed = false;
        if (!d.equals(bindings.get(v))) {
            bindings.put(v, d);
            changed = true;
        }
        // ⚠️ ckeys 一定要落（2026-09-21 修）：它是 convKeyOf 唯一的热路径判据（无锁读）。
        //    原来只在「bindings 变了」时才写 → 若 bindings 早就对上了（配置里存着）而 ckeys
        //    是空的（播种顺序 bug），这里会 early-skip，组键永远退回 IP，绑定等于没生效。
        boolean ckeyChanged = !d.equals(ckeys.get(v));
        if (ckeyChanged) {
            ckeys.put(v, d);
        }
        Peer p = findPeer(v);
        if (p != null && !d.equals(p.lastId)) {
            p.lastId = d;
            changed = true;
        }
        if (changed || ckeyChanged) {
            // 组键真的动了（含「之前退回 IP、这次才认出来」的修复路径）→ 把挂在旧组键上的
            // 本机状态（备注 / 当前地址 / 旋转角）搬过来，否则那些设置会取不出来
            migrateGroupState(oldKey, d);
        }
        // 用户手工把这个地址指给这台设备 = 明确要用它 → 作废「不再显示」标记
        boolean un = unignoreDev(d);
        if (changed || un) {
            save();
        }
        return changed;
    }

    /**
     * 握手 / 发现带来的**被动绑定**：只有当目标地址**还没有别的活动设备**（或那台已经离开）时才真的绑定。
     *
     * <p>为什么需要（2026-10-05 同 IP 多设备）：两台设备落在同一个 IP 上时，双方每次握手/广播都会
     * 自报自己的 id —— 若照旧无条件绑定，两个 id 会每 2s 轮流覆盖 `bindings[ip]`，组键来回漂移、
     * 备注/当前地址被反复搬运（用户看到的「切换不过去 + 卡」）。被动路径必须让**先来的那台守住地址**，
     * 后来者只登记成别名（列表里的「名字-2」那行），要不要换由用户点那行时确认
     *（见 ChatWindow.switchConflictDevice）。
     *
     * <p>用户手工声明「这个地址也属于某台设备」（ChatDevices.editPeer）仍走强绑定
     * {@link #bindDevice}——那是明确意图，不是被动发现。
     *
     * @return true 表示绑定关系发生了变化
     */
    public synchronized boolean bindDeviceIfFree(String ip, String deviceId) {
        String v = ip == null ? "" : ip.trim();
        String d = deviceId == null ? "" : deviceId.trim();
        if (v.isEmpty() || d.isEmpty()) {
            return false;
        }
        Peer p = findPeer(v);
        String active = (p == null || p.lastId == null) ? "" : p.lastId.trim();
        if (!active.isEmpty() && !active.equals(d) && !devGone(p, active)) {
            // 地址被一台**还在线**的设备占着 → 不抢绑定，只把它记进别名表（列表里多一行）
            if (p.noteDev(d, "", System.currentTimeMillis())) {
                save();
            }
            return false;
        }
        return bindDevice(v, d);
    }

    /**
     * 把一个组键上挂的本机状态搬到另一个组键（新地址连上后被并进已有设备时调用）。
     *
     * 场景：用户手工加过一个地址 X（当时它自成一组，组键 = X），之后 X 连上来、对端自报的
     * deviceId 是本机早就认识的设备 A —— 绑定表把 X 并进 A。若不做这一步，用户给 X 写过的
     * 备注、X 记的「当前地址」都会留在旧组键 X 上取不出来（表现：备注没了）。
     *
     * **目标组已有值时不覆盖**（A 的备注优先于 X 的备注），只补空缺。
     */
    private void migrateGroupState(String oldKey, String newKey) {
        if (oldKey == null || newKey == null || oldKey.isEmpty() || newKey.isEmpty()
                || oldKey.equals(newKey)) {
            return;
        }
        if (!deviceRemarks.containsKey(newKey)) {
            String oldRem = deviceRemarks.remove(oldKey);
            if (oldRem != null && !oldRem.isEmpty()) {
                deviceRemarks.put(newKey, oldRem);
            }
        } else {
            deviceRemarks.remove(oldKey);
        }
        if (!groupCurIp.containsKey(newKey)) {
            String oldCur = groupCurIp.remove(oldKey);
            if (oldCur != null && !oldCur.isEmpty()) {
                groupCurIp.put(newKey, oldCur);
            }
        } else {
            groupCurIp.remove(oldKey);
        }
        // 画面旋转角（按设备记，与安卓端 rotRemote/rotLocal 同口径）
        if (!rotRemote.containsKey(newKey)) {
            String oldRot = rotRemote.remove(oldKey);
            if (oldRot != null && !oldRot.isEmpty()) {
                rotRemote.put(newKey, oldRot);
            }
        } else {
            rotRemote.remove(oldKey);
        }
        // 设备标记同样按组键存：换绑不搬的话，这台设备一改地址就脱离标记（组内扩散失效）
        if (blockedGroups.remove(oldKey) != null && !blockedGroups.containsKey(newKey)) {
            blockedGroups.put(newKey, "1");
        }
        // 已知设备名单同口径迁移：合并两位标记（任一边见过就算见过），旧键删除
        String oldTrusted = trustedPeers.remove(oldKey);
        if (oldTrusted != null) {
            String cur = trustedPeers.get(newKey);
            String merged = (cur == null ? "" : cur);
            for (int i = 0; i < oldTrusted.length(); i++) {
                char fl = oldTrusted.charAt(i);
                if ((fl == 'c' || fl == 'f') && merged.indexOf(fl) < 0) {
                    merged += fl;
                }
            }
            if (!merged.isEmpty()) {
                trustedPeers.put(newKey, merged);
            }
        }
    }

    // ===== 网段派生的设备标记（标记只存组键 → "1"，不含原因/地址/时间）=====

    /** 连接路径调用：无锁读，绝不能被 save() 的写文件持锁卡住 */
    public boolean isBlockedAddr(String ip) {
        if (ip == null) {
            return false;
        }
        String k = convKeyOf(ip);
        return !k.isEmpty() && blockedGroups.containsKey(k);
    }

    /** 标记整台设备（组内全部地址）。已在表里就不重复落盘 */
    public synchronized void markBlockedGroup(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty() || blockedGroups.containsKey(k)) {
            return;
        }
        blockedGroups.put(k, "1");
        save();
    }

    /** 仅当该设备一个地址都不剩时由 removePeer 调用；顶栏「清空列表」不走这里 */
    private boolean unmarkBlockedGroup(String key) {
        return key != null && !key.isEmpty() && blockedGroups.remove(key) != null;
    }

    // ===== 已知设备名单（后台允许连入：握手过 + 有消息/文件往来）=====

    /** adopt 闸门调用：无锁读，绝不能被 save() 的写文件持锁卡住 */
    public boolean isTrustedAddr(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        String k = convKeyOf(ip);
        if (k.isEmpty()) {
            return false;
        }
        String v = trustedPeers.get(k);
        return v != null && v.indexOf('c') >= 0 && v.indexOf('f') >= 0;
    }

    /** 已知设备打点①：握手成功（含首次连上）。已置位则不重复落盘 */
    public synchronized void markTrustedConnected(String key) {
        markTrustedFlag(key, 'c');
    }

    /** 已知设备打点②：有过消息/文件往来（收发都算）。已置位则不重复落盘 */
    public synchronized void markTrustedContent(String key) {
        markTrustedFlag(key, 'f');
    }

    private void markTrustedFlag(String key, char flag) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return;
        }
        String cur = trustedPeers.get(k);
        if (cur != null && cur.indexOf(flag) >= 0) {
            return;
        }
        StringBuilder sb = new StringBuilder(cur == null ? "" : cur);
        sb.append(flag);
        trustedPeers.put(k, sb.toString());
        save();
    }

    /**
     * 升级播种：config 里从没有过 trustedPeers 这份名单时，把**已有聊天记录**的设备
     * 直接按「连接过+有往来」补齐 —— 否则老对端升级后第一次后台重连就被自己挡掉
     * （安卓端 2026-09-15 踩过的坑，这里同规则预防）。
     */
    private void seedTrustedFromHistory() {
        try {
            if (!Files.isDirectory(historyDir)) {
                return;
            }
            try (java.util.stream.Stream<Path> st = Files.list(historyDir)) {
                java.util.Iterator<Path> it = st.iterator();
                while (it.hasNext()) {
                    Path f = it.next();
                    String n = f.getFileName().toString();
                    if (!n.endsWith(".jsonl")) {
                        continue;
                    }
                    try {
                        if (Files.size(f) > 0) {
                            trustedPeers.put(n.substring(0, n.length() - ".jsonl".length()), "cf");
                        }
                    } catch (IOException ignore) {
                    }
                }
            }
        } catch (IOException e) {
            Log.w("已知设备名单播种失败（读 history 目录）: " + e);
        }
    }

    /** 该地址所属**设备**的备注（组备注优先，回退旧版的每 IP 备注） */
    public synchronized String remarkOf(String ip) {
        String v = ip == null ? "" : ip.trim();
        if (v.isEmpty()) {
            return "";
        }
        String k = convKeyOf(v);
        if (deviceRemarks.containsKey(k)) {
            String r = deviceRemarks.get(k);
            return r == null ? "" : r;
        }
        Peer p = findPeer(v);
        return p == null || p.remark == null ? "" : p.remark;
    }

    /** 写「对某设备」的备注（组级）；同组所有 peer 行一起同步，保证旧读法答案一致 */
    public synchronized void setGroupRemark(String key, String remark) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return;
        }
        String r = remark == null ? "" : remark.trim();
        deviceRemarks.put(k, r);
        for (Peer p : peerList) {
            if (p.ip != null && convKeyOf(p.ip).equals(k)) {
                p.remark = r;
            }
        }
        save();
    }

    /**
     * 该设备组当前使用的地址。
     *
     * <p>⚠️ 记忆里的地址必须**确实还属于这台设备**（`convKeyOf(c) == key`）才认，否则一律忽略。
     * 2026-10-05 修：旧版本只判「这个地址还在设备列表里」——设备换过 IP（或重装后换了 deviceId）
     * 之后，`groupCurIp` 里那条旧记忆会被另一台设备继承，于是**这台设备的行显示的是别人的地址**，
     * 点下去连的是别人的地址 → 一直 `Connect timed out`，用户看到的就是「打不开」
     *（实测数据：android-xxxxxxxx 真实在 192.168.1.210，却显示成 192.168.1.13）。
     *
     * <p>回退顺序：记住的地址（确属本设备）→ lastIp（确属本设备）→ 组内**最近出现过**的地址
     *（设备换 IP 后，最可能还活着的就是最后见到它的那个）。
     */
    public synchronized String curIpOf(String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return "";
        }
        String c = groupCurIp.get(k);
        if (c != null && !c.isEmpty() && findPeer(c) != null && k.equals(convKeyOf(c))) {
            return c;
        }
        if (lastIp != null && !lastIp.isEmpty() && findPeer(lastIp) != null
                && k.equals(convKeyOf(lastIp))) {
            return lastIp;
        }
        String best = "";
        long bestSeen = -1L;
        for (Peer p : peerList) {
            if (p.ip != null && !p.ip.isEmpty() && k.equals(convKeyOf(p.ip))
                    && p.lastSeenAt >= bestSeen) {
                bestSeen = p.lastSeenAt;
                best = p.ip;
            }
        }
        return best;
    }

    public synchronized void setGroupCurIp(String key, String ip) {
        String k = key == null ? "" : key.trim();
        String v = ip == null ? "" : ip.trim();
        if (k.isEmpty() || v.isEmpty()) {
            return;
        }
        if (v.equals(groupCurIp.get(k))) {
            return;
        }
        groupCurIp.put(k, v);
        save();
    }

    /**
     * 该设备上一次手动设置的**画面旋转角**（0/90/180/270；没记过 = 0）。
     *
     * @param key 组键（对端 deviceId；未知设备回退 IP）—— 与安卓端同一口径
     */
    public synchronized int rotRemoteOf(String key) {
        String k = key == null ? "" : key.trim();
        String v = k.isEmpty() ? null : rotRemote.get(k);
        if (v == null) {
            return 0;
        }
        try {
            int d = Integer.parseInt(v.trim());
            return ((d % 360) + 360) % 360;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 记下该设备手动设置的画面旋转角（0/90/180/270） */
    public synchronized void setRotRemoteOf(String key, int deg) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty()) {
            return;
        }
        int d = ((deg % 360) + 360) % 360;
        if (String.valueOf(d).equals(rotRemote.get(k))) {
            return;
        }
        rotRemote.put(k, String.valueOf(d));
        save();
    }

    /** 设备组（一台设备一条）：同一 deviceId 的多个地址合成一组 */
    public static final class Group {
        public String key = "";
        public String remark = "";
        public String name = "";
        public final List<String> ips = new ArrayList<String>();
        public String curIp = "";
        public long lastSeenAt = 0L;
        /**
         * 是否是「同 IP 多设备」里的**别名行**（2026-10-05）：这个 IP 当前的活动设备是别人，
         * 本行只是同一地址上出现过的另一台设备。点击时先弹 IP 冲突确认框，不直接连。
         */
        public boolean alt = false;
        /**
         * 去重后的列表显示名（同名设备自动加 `-2`/`-3`，见 {@link #dedupeNames}）。
         * 为空 = 用 remark/name/curIp 的常规回退；非空 = 强制用它（不动用户写的备注）。
         */
        public String shownName = "";
        /**
         * 该设备**共用**的地址（2026-10-05）：这些地址当前的活动设备是别人，本行只是
         * 「这台设备也在这个地址上出现过」。**只做提示** —— 不进 [ips]、不参与连接目标与
         * 地址切换菜单，也不算「本设备的地址」；想真正切过去要走 IP 冲突确认流程
         *（见 ChatWindow.switchConflictDevice）。
         */
        public final List<String> guestIps = new ArrayList<String>();

        /** 列表/顶栏显示名：去重名 > 备注 > 广播名 > 当前地址 */
        public String displayName() {
            if (shownName != null && !shownName.isEmpty()) {
                return shownName;
            }
            if (remark != null && !remark.isEmpty()) {
                return remark;
            }
            if (name != null && !name.isEmpty()) {
                return name;
            }
            return curIp == null ? "" : curIp;
        }

        /** 多地址（界面据此给出「切换地址」入口） */
        public boolean multi() {
            return ips.size() > 1;
        }
    }

    /**
     * 同名去重：列表里第 2/3… 台同名设备显示成 `名字-2`、`名字-3`。
     *
     * <p>只写 {@link Group#shownName}，不动 remark（用户备注）/ name（广播名）——
     * 备注修改、地址切换、气泡归属文案都读原始字段，不会被后缀污染。
     */
    private static void dedupeNames(List<Group> gs) {
        Map<String, Integer> used = new LinkedHashMap<String, Integer>();
        for (Group g : gs) {
            String base = g.displayName();
            if (base == null || base.isEmpty()) {
                continue;
            }
            Integer c = used.get(base);
            if (c == null) {
                used.put(base, Integer.valueOf(1));
                continue;
            }
            int n = c.intValue() + 1;
            String cand = base + "-" + n;
            while (used.containsKey(cand)) {
                n++;
                cand = base + "-" + n;
            }
            used.put(base, Integer.valueOf(n));
            used.put(cand, Integer.valueOf(1));
            g.shownName = cand;
        }
    }

    /** 全部设备组（按设备列表出现顺序） */
    public synchronized List<Group> groups() {
        LinkedHashMap<String, Group> byKey = new LinkedHashMap<String, Group>();
        for (Peer p : peerList) {
            if (p.ip == null || p.ip.isEmpty()) {
                continue;
            }
            String k = convKeyOf(p.ip);
            Group g = byKey.get(k);
            if (g == null) {
                g = new Group();
                g.key = k;
                byKey.put(k, g);
            }
            g.ips.add(p.ip);
            if (p.lastSeenAt >= g.lastSeenAt) {
                g.lastSeenAt = p.lastSeenAt;
            }
            if (p.lastName != null && !p.lastName.isEmpty()) {
                g.name = p.lastName;
            }
        }
        List<Group> out = new ArrayList<Group>(byKey.values());
        for (Group g : out) {
            if (deviceRemarks.containsKey(g.key)) {
                String r = deviceRemarks.get(g.key);
                g.remark = r == null ? "" : r;
            } else {
                // 回退旧版的每 IP 备注
                for (Peer p : peerList) {
                    if (p.ip != null && convKeyOf(p.ip).equals(g.key) && p.remark != null
                            && !p.remark.isEmpty()) {
                        g.remark = p.remark;
                        break;
                    }
                }
            }
            g.curIp = curIpOf(g.key);
            if (g.curIp.isEmpty() && !g.ips.isEmpty()) {
                g.curIp = g.ips.get(0);
            }
        }
        // ===== 同 IP 多设备（2026-10-05）：把每个地址上「非活动设备」的身份也列出来 =====
        // 硬约束：**一台设备只能出一行**。行键 = 设备身份；重复行键会让界面直接出问题
        //（安卓 LazyColumn 的 key 重复会抛异常，PC 列表会出现同一台设备两行、名字还被去重器加后缀）。
        //   · 这台设备在别的地址上已经是活动设备（**已有主行**）→ 它在这个地址上当「客人」的部分
        //     只记进那一行的 guestIps（纯提示，不参与连接 / 地址切换）；
        //   · 这台设备在所有地址上都是客人（一个地址都没占到）→ 才单独开一条别名行，
        //     点击时走 IP 冲突确认框（ChatWindow.switchConflictDevice）。
        // 于是：主行键 = 活动设备身份（未识别身份时是原始 IP），别名行键 = 一个地址都没占到的
        // 设备身份 —— 两类键**天然不可能重合**，唯一性由构造保证。
        LinkedHashMap<String, Group> owned = new LinkedHashMap<String, Group>();
        for (Group g : out) {
            owned.put(g.key, g);
        }
        LinkedHashMap<String, Group> alts = new LinkedHashMap<String, Group>();
        for (Peer p : peerList) {
            if (p.ip == null || p.ip.isEmpty()) {
                continue;
            }
            String active = p.lastId == null ? "" : p.lastId.trim();
            for (Peer.Dev d : p.devs) {
                if (d == null || d.id == null || d.id.isEmpty() || d.id.equals(active)) {
                    continue;
                }
                // 用户明确删过的（见 ignoredDevs）→ 不再列出来；等它真占到地址时自动解除
                if (ignoredDevs.containsKey(d.id)) {
                    continue;
                }
                // 太久没露面的别名（多半是换/重装前的旧身份）不再显示；它一旦再广播/握手，
                // 记录立刻变新鲜，这行会自己回来（见 DEV_ALIAS_SHOW_MS）
                if (devStaleForAlias(d.seenAt, DEV_ALIAS_SHOW_MS)) {
                    continue;
                }
                Group owner = owned.get(d.id);
                if (owner != null) {
                    // 已经有主行 → 只补一条「共用地址」提示，绝不开第二行
                    //（自己 ips 里已有的地址不算「共用」—— 那是配置里绑定与 peer.lastId 不一致时的兜底）
                    if (!owner.ips.contains(p.ip) && !owner.guestIps.contains(p.ip)) {
                        owner.guestIps.add(p.ip);
                    }
                    continue;
                }
                Group g = alts.get(d.id);
                if (g == null) {
                    g = new Group();
                    g.key = d.id;
                    g.alt = true;
                    g.name = d.name == null ? "" : d.name;
                    g.ips.add(p.ip);
                    g.curIp = p.ip;
                    g.lastSeenAt = d.seenAt;
                    String r = deviceRemarks.get(d.id);
                    g.remark = r == null ? "" : r;
                    alts.put(d.id, g);
                } else if (d.seenAt > g.lastSeenAt) {
                    // 这台「纯客人」设备出现在多个地址上：最近那个当主地址，旧的降为「共用地址」
                    if (!p.ip.equals(g.curIp)) {
                        if (!g.guestIps.contains(g.curIp)) {
                            g.guestIps.add(g.curIp);
                        }
                        g.ips.clear();
                        g.ips.add(p.ip);
                        g.curIp = p.ip;
                    }
                    g.lastSeenAt = d.seenAt;
                } else if (!p.ip.equals(g.curIp) && !g.guestIps.contains(p.ip)) {
                    g.guestIps.add(p.ip);
                }
            }
        }
        out.addAll(alts.values());
        dedupeNames(out);
        return out;
    }

    /** 某地址所属的设备组（找不到返回 null） */
    public synchronized Group groupOfIp(String ip) {
        String k = convKeyOf(ip);
        if (k.isEmpty()) {
            return null;
        }
        for (Group g : groups()) {
            if (g.key.equals(k)) {
                return g;
            }
        }
        return null;
    }

    /** 对端 IP → 安全的文件名（IP 本身很干净，这里只是防御性处理） */
    private static String safeKey(String peerIp) {
        String v = peerIp == null ? "" : peerIp.trim();
        if (v.isEmpty()) {
            return "_unknown";
        }
        return v.replaceAll("[^0-9A-Za-z._-]", "_");
    }

    private Path historyFile(String peerIp) {
        String ip = peerIp == null ? "" : peerIp.trim();
        String k = convKeyOf(ip);
        Path dest = historyDir.resolve(safeKey(k.isEmpty() ? ip : k) + ".jsonl");
        // 组键 ≠ IP（这台设备的身份已确认）→ 把旧版按 IP 存的历史并进组文件（一次性）
        if (!k.isEmpty() && !k.equals(ip)) {
            mergeLegacyHistory(ip, dest);
        }
        return dest;
    }

    /**
     * 把 `history/<ip>.jsonl` 并入 `history/<deviceId>.jsonl`。
     *
     * 只有写成功才删旧文件：写失败时旧文件留着，读历史仍能读到内容，
     * 不会出现「聊天记录凭空消失」。
     */
    private void mergeLegacyHistory(String ip, Path dest) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        Path legacy = historyDir.resolve(safeKey(ip) + ".jsonl");
        if (!Files.exists(legacy) || legacy.equals(dest)) {
            return;
        }
        try {
            if (Files.size(legacy) <= 0) {
                Files.deleteIfExists(legacy);
                return;
            }
        } catch (IOException ignore) {
            return;
        }
        if (!mergeTried.add(ip)) {
            return;
        }
        try {
            List<String> old = new ArrayList<String>();
            for (String ln : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
                if (ln != null && !ln.trim().isEmpty()) {
                    old.add(ln);
                }
            }
            if (old.isEmpty()) {
                Files.deleteIfExists(legacy);
                return;
            }
            List<String> cur = new ArrayList<String>();
            if (Files.exists(dest)) {
                for (String ln : Files.readAllLines(dest, StandardCharsets.UTF_8)) {
                    if (ln != null && !ln.trim().isEmpty()) {
                        cur.add(ln);
                    }
                }
            }
            StringBuilder sb = new StringBuilder();
            for (String ln : cur) {
                sb.append(ln).append('\n');
            }
            for (String ln : old) {
                sb.append(ln).append('\n');
            }
            Files.write(dest, sb.toString().getBytes(StandardCharsets.UTF_8));
            if (Files.deleteIfExists(legacy)) {
                Log.i("历史归并到设备文件：" + ip + " → " + dest.getFileName()
                        + "（" + old.size() + " 行）");
            }
        } catch (Exception e) {
            Log.w("历史归并失败 " + ip + ": " + e);
        }
    }

    // ==================== 配置 ====================

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        deviceName = defaultName();
        deviceId = "pc-" + UUID.randomUUID().toString().substring(0, 8);
        if (!Files.exists(configFile)) {
            peerList.add(new Peer("192.168.1.14"));
            save();
            return;
        }
        try {
            String s = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
            Map<String, Object> m = Json.parseObject(s);
            deviceId = Json.str(m, "deviceId", deviceId);
            deviceName = Json.str(m, "deviceName", deviceName);
            tcpPort = Json.integer(m, "tcpPort", tcpPort);
            udpPort = Json.integer(m, "udpPort", udpPort);
            // v2.1 端口迁移（8765/8766 → 28765/28766）：旧配置里若仍是旧默认值，
            // 视为「未自定义」，跟随新默认 —— 否则新代码会拿着旧端口继续撞 MIUI scene-daemon
            if (tcpPort == 8765) {
                tcpPort = Protocol.DEFAULT_TCP_PORT;
            }
            if (udpPort == 8766) {
                udpPort = Protocol.DEFAULT_UDP_PORT;
            }
            autoWriteClipboard = Json.bool(m, "autoWriteClipboard", autoWriteClipboard);
            allowConnectOut = Json.bool(m, "allowConnectOut", allowConnectOut);
            saveDir = Json.str(m, "saveDir", saveDir).trim();
            lastIp = Json.str(m, "lastIp", lastIp).trim();
            // 设备身份与分组（老配置没有这几个 key → 留空，行为与改造前一致：
            // 组键回退成 IP，一个地址一台设备）
            readStringMap(m, "bindings", bindings);
            readStringMap(m, "ignoredDevs", ignoredDevs);
            readStringMap(m, "deviceRemarks", deviceRemarks);
            readStringMap(m, "groupCurIp", groupCurIp);
            readStringMap(m, "rotRemote", rotRemote);
            Object marks = m.get("blockedGroups");
            if (marks instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) marks).entrySet()) {
                    if (e.getKey() != null) {
                        blockedGroups.put(String.valueOf(e.getKey()), "1");
                    }
                }
            }
            // 已知设备名单：没有这个 key = 从旧版升级 → 按聊天记录播种一次
            //（落盘推迟到 load 尾部：中途 save 会把还没加载完的字段以默认值写回去）
            boolean trustedSeeded = false;
            Object trusted = m.get("trustedPeers");
            if (trusted instanceof Map) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) trusted).entrySet()) {
                    if (e.getKey() == null) {
                        continue;
                    }
                    String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
                    String flags = (v.indexOf('c') >= 0 ? "c" : "") + (v.indexOf('f') >= 0 ? "f" : "");
                    if (!flags.isEmpty()) {
                        trustedPeers.put(String.valueOf(e.getKey()), flags);
                    }
                }
            } else {
                seedTrustedFromHistory();
                trustedSeeded = !trustedPeers.isEmpty();
            }
            // 视频配置：老 config.json 没有这几个 key → 取默认值（videoEnabled=true，
            // 即默认允许接收视频；真正能否收到视频还要看对端是否支持，见 Link 能力协商）
            videoEnabled = Json.bool(m, "videoEnabled", videoEnabled);
            videoMaxLevel = Json.integer(m, "videoMaxLevel", videoMaxLevel);
            ffmpegPath = Json.str(m, "ffmpegPath", ffmpegPath).trim();
            ffmpegHwaccel = Json.str(m, "ffmpegHwaccel", ffmpegHwaccel).trim();
            uiScale = parseUiScale(m.get("uiScale"));
            darkTheme = Json.bool(m, "darkTheme", darkTheme);
            // ⚠ 老 config.json 里存的是旧上限（本机 %APPDATA%\PhonePcTransfer\config.json 现在是 2）
            //    → 读档照样是 2，超清(3)会被夹回高清；由设置页那一排钮改（V-9）。
            videoMaxLevel = clampVideoMaxLevel(videoMaxLevel);

            peerList.clear();
            // ① 新格式：带备注的 peers 数组
            Object peersArr = m.get("peers");
            if (peersArr instanceof List) {
                for (Object o : (List<?>) peersArr) {
                    Map<String, Object> pm = Json.obj(o);
                    if (pm == null) {
                        continue;
                    }
                    Peer p = Peer.fromJson(pm);
                    p.ip = p.ip == null ? "" : p.ip.trim();
                    if (!p.ip.isEmpty() && findPeer(p.ip) == null) {
                        peerList.add(p);
                    }
                }
            }
            // ② 旧格式：纯 IP 集合，迁移成 Peer（备注留空）
            Object arr = m.get("peerIps");
            if (arr instanceof List) {
                for (Object o : (List<?>) arr) {
                    String ip = String.valueOf(o).trim();
                    if (!ip.isEmpty() && findPeer(ip) == null) {
                        peerList.add(new Peer(ip));
                    }
                }
            }
            // ⚠️ 播种必须**在 peerList 填好之后**（2026-09-21 修）：
            //    它遍历 peerList 取 lastId 填 ckeys，之前写在读 peers 数组**前面** ——
            //    那时 peerList 还是空的（上面刚 clear 过），于是 ckeys 永远是空的，
            //    convKeyOf() 把所有地址都退回「组键 = IP」：
            //      · 同一台设备的多个地址被拆成好几台（设备列表一台设备出现多行）；
            //      · config 里的 bindings 表形同废纸（bindDevice 见绑定没变就 early-return，
            //        连 ckeys 都不会补）。
            seedGroupKeys();
            if (trustedSeeded) {
                save();
            }
        } catch (Exception e) {
            Log.w("读取 config.json 失败，使用默认配置: " + e);
        }
    }

    /**
     * 从 peer.lastId 播种「ip → deviceId」缓存。
     * 老配置里没有 bindings 表，但发现/握手早就把对端 id 写进了 peer.lastId ——
     * 不播种的话升级后所有组键都会退回 IP，多地址设备会被拆成好几台。
     */
    private void seedGroupKeys() {
        for (Peer p : peerList) {
            if (p.ip == null || p.ip.isEmpty()) {
                continue;
            }
            String id = p.lastId == null ? "" : p.lastId.trim();
            if (id.isEmpty()) {
                continue;
            }
            String cur = bindings.get(p.ip);
            if (cur == null || cur.isEmpty()) {
                bindings.put(p.ip, id);
                cur = id;
            }
            ckeys.put(p.ip, cur);
        }
    }

    private static void readStringMap(Map<String, Object> m, String key, Map<String, String> out) {
        Object o = m.get(key);
        if (!(o instanceof Map)) {
            return;
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            out.put(String.valueOf(e.getKey()), e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
    }

    public synchronized void save() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("deviceId", deviceId);
        m.put("deviceName", deviceName);        m.put("tcpPort", Integer.valueOf(tcpPort));
        m.put("udpPort", Integer.valueOf(udpPort));
        m.put("autoWriteClipboard", Boolean.valueOf(autoWriteClipboard));
        m.put("allowConnectOut", Boolean.valueOf(allowConnectOut));
        m.put("saveDir", saveDir == null ? "" : saveDir);
        m.put("lastIp", lastIp == null ? "" : lastIp);
        m.put("videoEnabled", Boolean.valueOf(videoEnabled));
        m.put("videoMaxLevel", Integer.valueOf(videoMaxLevel));
        m.put("ffmpegPath", ffmpegPath == null ? "" : ffmpegPath);
        m.put("ffmpegHwaccel", ffmpegHwaccel == null ? "" : ffmpegHwaccel);
        // uiScale：&le;0 = 跟随系统（写成 "system" 便于人工阅读），数字 = 钉定渲染缩放
        if (uiScale <= 0) {
            m.put("uiScale", "system");
        } else {
            m.put("uiScale", Double.valueOf(uiScale));
        }
        m.put("darkTheme", Boolean.valueOf(darkTheme));
        // 新格式：带备注的设备列表；peerIps 同时写出，便于旧版本回读
        List<Object> peerMaps = new ArrayList<Object>();
        for (Peer p : peerList) {
            peerMaps.add(p.toJson());
        }
        m.put("peers", peerMaps);
        m.put("peerIps", new ArrayList<Object>(peerIps()));
        // 设备身份与分组：绑定表 / 组备注 / 组当前地址
        m.put("bindings", new LinkedHashMap<String, String>(bindings));
        m.put("ignoredDevs", new LinkedHashMap<String, String>(ignoredDevs));
        m.put("deviceRemarks", new LinkedHashMap<String, String>(deviceRemarks));
        m.put("groupCurIp", new LinkedHashMap<String, String>(groupCurIp));
        m.put("rotRemote", new LinkedHashMap<String, String>(rotRemote));
        m.put("blockedGroups", new LinkedHashMap<String, String>(blockedGroups));
        m.put("trustedPeers", new LinkedHashMap<String, String>(trustedPeers));
        try {
            Files.write(configFile, Json.stringify(m).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w("写入 config.json 失败: " + e);
        }
    }

    private static String defaultName() {
        String h = System.getenv("COMPUTERNAME");
        return (h == null || h.isEmpty()) ? "PC" : h;
    }

    public synchronized String deviceId() {
        return deviceId;
    }

    public synchronized String deviceName() {
        return deviceName;
    }

    public synchronized int tcpPort() {
        return tcpPort;
    }

    public synchronized int udpPort() {
        return udpPort;
    }

    public synchronized boolean autoWriteClipboard() {
        return autoWriteClipboard;
    }

    public synchronized void setAutoWriteClipboard(boolean v) {
        autoWriteClipboard = v;
        save();
    }

    public synchronized boolean allowConnectOut() {
        return allowConnectOut;
    }

    // ==================== 接收目录 ====================

    /** 用户自定义的保存位置（空字符串 = 用默认目录 pc/received） */
    public synchronized String saveDir() {
        return saveDir == null ? "" : saveDir;
    }

    public synchronized void setSaveDir(String dir) {
        saveDir = dir == null ? "" : dir.trim();
        save();
    }

    /** 上次连接的设备 IP（空 = 从未连接过） */
    public synchronized String lastIp() {
        return lastIp == null ? "" : lastIp;
    }

    public synchronized void setLastIp(String ip) {
        String v = ip == null ? "" : ip.trim();
        if (v.equals(lastIp)) {
            return;
        }
        lastIp = v;
        save();
    }

    // ==================== 视频通话（PC 只收不发） ====================

    /** 全局视频开关；false = 不接收视频，链路完全走音频路径 */
    public synchronized boolean videoEnabled() {
        return videoEnabled;
    }

    public synchronized void setVideoEnabled(boolean v) {
        if (v == videoEnabled) {
            return;
        }
        videoEnabled = v;
        save();
    }

    /** 可接受的最高档位（0=流畅 480P / 1=标清 720P / 2=高清 1080P/2M / 3=超清 1080P/5M） */
    public synchronized int videoMaxLevel() {
        return clampVideoMaxLevel(videoMaxLevel);
    }

    public synchronized void setVideoMaxLevel(int lv) {
        int v = clampVideoMaxLevel(lv);
        if (v == videoMaxLevel) {
            return;
        }
        videoMaxLevel = v;
        save();
    }

    /** 夹到 [0, {@link #VIDEO_MAX_LEVEL}]；写入与读取都过这里，免得哪一处又漏一个硬编码 2 */
    private static int clampVideoMaxLevel(int lv) {
        if (lv < 0) {
            return 0;
        }
        return Math.min(lv, VIDEO_MAX_LEVEL);
    }

    // ==================== 主题（2026-09-27） ====================

    /** 当前是否深色主题（启动时由 PcMain 应用到 Ui） */
    public synchronized boolean darkTheme() {
        return darkTheme;
    }

    /** 切换主题并落盘（左下角「主题」按钮触发） */
    public synchronized void setDarkTheme(boolean v) {
        if (v == darkTheme) {
            return;
        }
        darkTheme = v;
        save();
    }

    // ==================== UI 缩放钉定（2026-09-27） ====================

    /** 当前钉定缩放（&le;0 = 跟随系统）；仅供日志/诊断读 */
    public synchronized double uiScale() {
        return uiScale;
    }

    /**
     * 启动最早期读 uiScale（只做文件 IO，供 {@code PcMain.main} 在任何 AWT 初始化之前调用；
     * 此时 Log 可能还没 init，绝不能在这里打日志）。
     *
     * @return &gt;0 = 钉定的渲染缩放；0 = 跟随系统缩放
     */
    public static double readUiScaleOverride(Path cfg) {
        try {
            if (!Files.exists(cfg)) {
                return DEFAULT_UI_SCALE;
            }
            Map<String, Object> m = Json.parseObject(
                    new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8));
            return parseUiScale(m.get("uiScale"));
        } catch (Throwable t) {
            // 配置读不动就按默认钉定 —— 与「没有这个 key」同口径
            return DEFAULT_UI_SCALE;
        }
    }

    /**
     * 解析 uiScale 配置值：数字 = 钉定缩放（越界钳回默认）；0 / "system" = 跟随系统；非法 = 默认。
     *
     * <p>一次性迁移（2026-09-29）：旧钉定值 1.25 一律替换为 1.0 —— 当天默认值由 1.25 改为 1.0，
     * 老 config.json 里的 1.25 属历史遗留（与旧默认同值，无法区分「用户手设 1.25」），
     * 统一按新默认处理；load() 后下次 save() 会把 1.0 写回配置，迁移即完成。
     */
    static double parseUiScale(Object v) {
        if (v == null) {
            return DEFAULT_UI_SCALE;
        }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d > 0 && Math.abs(d - 1.25) < 1e-9) {
                return 1.0; // 旧版本遗留 1.25 → 替换为新默认
            }
            return d <= 0 ? 0.0 : clampUiScale(d);
        }
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty() || "system".equals(s)) {
            return 0.0;
        }
        try {
            double d = Double.parseDouble(s);
            if (d > 0 && Math.abs(d - 1.25) < 1e-9) {
                return 1.0; // 旧版本遗留 1.25（字符串形态）→ 替换为新默认
            }
            return d <= 0 ? 0.0 : clampUiScale(d);
        } catch (NumberFormatException e) {
            return DEFAULT_UI_SCALE;
        }
    }

    /** 合法范围 0.5~5，越界视为配置错误 → 回默认 1.0 */
    private static double clampUiScale(double d) {
        return (d < 0.5 || d > 5.0) ? DEFAULT_UI_SCALE : d;
    }

    /** 用户指定的 ffmpeg 可执行文件路径；空 = 自动探测 */
    public synchronized String ffmpegPath() {
        return ffmpegPath == null ? "" : ffmpegPath;
    }

    public synchronized void setFfmpegPath(String p) {
        String v = p == null ? "" : p.trim();
        if (v.equals(ffmpegPath)) {
            return;
        }
        ffmpegPath = v;
        save();
    }

    /** ffmpeg 硬件加速方式（如 d3d11va）；空 = 纯软解 */
    public synchronized String ffmpegHwaccel() {
        return ffmpegHwaccel == null ? "" : ffmpegHwaccel;
    }

    public synchronized void setFfmpegHwaccel(String v0) {
        String v = v0 == null ? "" : v0.trim();
        if (v.equals(ffmpegHwaccel)) {
            return;
        }
        ffmpegHwaccel = v;
        save();
    }

    /**
     * 当前生效的接收目录。
     *
     * 自定义目录不存在会自动创建；不可写（比如选了只读盘/U 盘拔了）时**回落到默认目录**，
     * 而不是让手机传过来的文件直接丢失。调用方拿到的永远是一个可写目录。
     */
    public Path receivedDir() {
        String d = saveDir();
        if (!d.isEmpty()) {
            try {
                Path p = Paths.get(d);
                Files.createDirectories(p);
                if (Files.isWritable(p)) {
                    return p;
                }
                Log.w("保存目录不可写，改用默认目录: " + p);
            } catch (Exception e) {
                Log.w("保存目录无效，改用默认目录: " + d + " — " + e);
            }
        }
        try {
            Files.createDirectories(defaultReceivedDir);
        } catch (IOException ignore) {
            // 默认目录在构造时已建好，这里只是兜底
        }
        return defaultReceivedDir;
    }

    /** 接收目录的绝对路径文本（给 UI 显示） */
    public String receivedDirText() {
        return receivedDir().toAbsolutePath().toString();
    }

    // ==================== 设备列表（备注 + IP） ====================

    public synchronized List<Peer> peers() {
        return new ArrayList<Peer>(peerList);
    }

    private synchronized Peer findPeer(String ip) {
        for (Peer p : peerList) {
            if (p.ip != null && p.ip.equals(ip)) {
                return p;
            }
        }
        return null;
    }

    /**
     * 新增或更新一台设备。
     *
     * @param remark   null = 不改备注；非 null（可含空串）= 覆盖
     * @param lastId   广播发现的 id；空串 = 不覆盖
     * @param lastName 广播发现的名字；空串 = 不覆盖
     * @return true 表示列表发生了变化
     */
    public synchronized boolean upsertPeer(String ip, String remark, String lastId,
                                          String lastName, long seenAt) {
        if (ip == null) {
            return false;
        }
        String v = ip.trim();
        if (v.isEmpty()) {
            return false;
        }
        String did = lastId == null ? "" : lastId.trim();
        String dn = lastName == null ? "" : lastName.trim();
        Peer exist = findPeer(v);
        if (exist == null) {
            Peer p = new Peer(v);
            p.remark = remark == null ? "" : remark.trim();
            p.lastId = did;
            p.lastName = dn;
            p.lastSeenAt = seenAt;
            if (!did.isEmpty()) {
                p.devs.add(new Peer.Dev(did, dn, seenAt));
            }
            peerList.add(p);
            if (!did.isEmpty()) {
                bindings.put(v, did);
                ckeys.put(v, did);
            }
            save();
            return true;
        }
        boolean changed = false;
        if (remark != null && !remark.trim().equals(exist.remark)) {
            exist.remark = remark.trim();
            changed = true;
        }
        String active = exist.lastId == null ? "" : exist.lastId.trim();
        // 别名表同时记**活动设备自己**：它「是否已经离开」只能看它**自己**最近一次
        // 广播/握手的时间，不能拿 peer.lastSeenAt —— 那会被同 IP 另一台设备的广播一直刷新，
        // 于是旧设备明明关机了也永远判不出「已离开」，新设备永远只能当别名。
        if (!did.isEmpty() && exist.noteDev(did, dn, seenAt)) {
            changed = true;
        }
        if (!did.isEmpty() && !did.equals(active)) {
            // ===== 同 IP 多设备（2026-10-05）=====
            // 这个地址上已经有**另一台**设备当活动设备了。旧行为是直接把 lastId 抢过来 ——
            // 两台设备的广播每 2s 交替到达，于是组键来回漂移、备注/当前地址被 migrateGroupState
            // 反复搬运并每次落盘（用户看到的「切换不过去 + 卡」）。现在改成：
            //   · 原活动设备**还在**（30s 内有过广播/握手）→ 后来者只登记成别名，
            //     绝不改 lastId / bindings / ckeys、不搬组状态（= 「后面直接跳过」）；
            //   · 原活动设备**已离开**（≥30s 没动静，通常是 IP 被路由重新分配）→ 允许接管，
            //     否则这个地址会永远钉在已经关机的那台设备上。
            if (active.isEmpty() || devGone(exist, active)) {
                String oldKey = convKeyOf(v);
                // 升级兼容：老配置 / 还没进别名表的旧活动设备补一条，否则接管后它就从列表里消失了。
                // 但只在它**最近还出现过**时才补（见 DEV_ALIAS_KEEP_MS）：几天没动静的旧身份
                // 多半是换/重装前的 deviceId，补进去只会留一条永远点不开、还删不干净的幽灵行。
                if (!active.isEmpty() && exist.dev(active) == null
                        && !devStaleForAlias(exist.lastSeenAt, DEV_ALIAS_KEEP_MS)) {
                    exist.noteDev(active, exist.lastName, exist.lastSeenAt);
                }
                // 它真的占到地址了 → 用户之前「不再显示」的标记作废
                unignoreDev(did);
                exist.lastId = did;
                // 身份绑定同步进组键缓存（同一 id 多个地址 = 同一台设备）
                if (!did.equals(bindings.get(v))) {
                    bindings.put(v, did);
                }
                ckeys.put(v, did);
                if (oldKey != null && !oldKey.isEmpty() && !oldKey.equals(did)) {
                    migrateGroupState(oldKey, did);
                }
                changed = true;
            }
        }
        // 广播名只跟着**活动设备**走：否则两台同 IP 设备的广播会把这个显示名来回改
        if (!dn.isEmpty() && (did.isEmpty() || did.equals(exist.lastId))
                && !dn.equals(exist.lastName)) {
            exist.lastName = dn;
            changed = true;
        }
        // ⚠️ lastSeenAt 每次收到广播都会变大，**不计入「列表有变化」**：
        // 对端每 2s 广播一次，若算作变化，状态栏就会每 2s 刷一条
        // 「发现设备 X，已加入设备列表」，看起来像在反复重新发现设备（与手机端同源问题）。
        // 拆开：在线时间静默落盘，返回值只表示「用户可见的结构有变化」。
        boolean touched = false;
        if (seenAt > exist.lastSeenAt) {
            exist.lastSeenAt = seenAt;
            touched = true;
        }
        if (changed || touched) {
            save();
        }
        return changed;
    }

    // ==================== 同 IP 多设备：活动设备 / 别名切换（2026-10-05） ====================

    /** 「活动设备」多久没动静就算离开了（广播/握手周期是 2s，30s 足够判定关机 / 换网） */
    private static final long DEV_TAKEOVER_MS = 30_000L;

    /**
     * 接管时「被顶下去的旧活动设备」还值不值得留一条别名行。
     *
     * <p>它已经超过这个时限没出现过（多半是换/重装前的旧 deviceId，永远不会再来）时**不记**——
     * 记了就是一条永远点不开、还删不干净的幽灵行（2026-10-05 用户实测）。
     * 10 分钟足够覆盖「A 网络抖了一下、B 抢到地址、A 待会回来」这种真需要能切回去的场景。
     */
    private static final long DEV_ALIAS_KEEP_MS = 10 * 60_000L;

    /** 旧活动设备是否已经超过 limitMs 没出现（不值得再留 / 再显示别名行） */
    private static boolean devStaleForAlias(long lastSeenAt, long limitMs) {
        return lastSeenAt <= 0L || System.currentTimeMillis() - lastSeenAt > limitMs;
    }

    /**
     * 别名行的**显示**时限：太久没露过面的「同一 IP 上的另一台设备」不再列出来。
     *
     * <p>为什么必须有（2026-10-05 用户实测）：同一台手机换/重装后 deviceId 变了，旧身份永远不会
     * 再广播，但它可能在别名表里躺着 —— 列表上就多一条「永远点不开的幽灵行」，点它还会弹
     * 「是否断开当前设备并切过去」（切过去只会把好好的地址抢给一个不存在的身份）。
     * 真正在场的设备每 2s 广播一次 / 握手一次，记录一直是新鲜的，所以这行不会误藏。
     */
    private static final long DEV_ALIAS_SHOW_MS = 60 * 60_000L;

    /**
     * 该地址上的活动设备是否已经离开。
     *
     * <p>只看**它自己**最近一次广播/握手的时间（别名表里那条），不看 peer.lastSeenAt ——
     * 后者会被同 IP 另一台设备的广播一直刷新，会让「旧设备已关机」永远判不出来。
     */
    private static boolean devGone(Peer p, String activeId) {
        if (p == null) {
            return true;
        }
        Peer.Dev d = p.dev(activeId);
        long seen = (d != null) ? d.seenAt : p.lastSeenAt;
        return seen <= 0L || System.currentTimeMillis() - seen > DEV_TAKEOVER_MS;
    }

    /** 该地址当前的**活动设备** id（未识别身份 / 地址不存在时为空串） */
    public synchronized String activeDevOf(String ip) {
        Peer p = findPeer(ip == null ? "" : ip.trim());
        return p == null || p.lastId == null ? "" : p.lastId.trim();
    }

    /**
     * 忘掉「这台设备在这个地址上出现过」的别名记录（别名行右键「删除（不再显示这台设备）」）。
     *
     * <p>注意必须**同时记进 {@link #ignoredDevs}**：别名行来自对方广播/握手时自报的 id，
     * 只删记录的话对方下一次广播（2s 后）就又把它加回来 —— 用户看到的是「怎么删都删不掉」。
     * 用户手工在「＋设备」里再加同一个地址、或这台设备**真的占到某个地址**时，忽略标记自动解除
     *（见 {@link #unignoreDev}）。
     *
     * @return true 表示设备列表/别名表发生了变化
     */
    public synchronized boolean forgetDev(String devId) {
        String d = devId == null ? "" : devId.trim();
        if (d.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Peer p : peerList) {
            if (p.removeDev(d)) {
                changed = true;
            }
        }
        if (!ignoredDevs.containsKey(d)) {
            ignoredDevs.put(d, "1");
            changed = true;
        }
        if (changed) {
            save();
        }
        return changed;
    }

    /** 某台设备真的占到地址（成为活动设备）→ 取消「用户不再显示」的标记 */
    private boolean unignoreDev(String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        return ignoredDevs.remove(id) != null;
    }

    /**
     * 把某个地址的**活动设备**切换成另一台（同 IP 多设备冲突：用户确认「断开之前的连接」后调用）。
     *
     * <p>为什么要显式切换：同一个 IP 在任一瞬间只能对应一台设备（`convKeyOf(ip)` 是热路径，
     * 只能给单值答案）。列表里的「别名行」点击时弹冲突确认框，用户点「是」之后就走这里：
     * 旧活动设备降级为别名（随时能再切回来）、新设备升为活动设备，并把挂在旧组键上的本机
     * 状态（备注 / 当前地址 / 旋转角 / 已知设备标记）搬到新组键上 —— 否则用户写完的备注、
     * 选过的地址会留在旧设备那边取不出来。
     *
     * @return true 表示切换成功（地址或身份不合法时返回 false）
     */
    public synchronized boolean promoteDev(String ip, String devId) {
        String v = ip == null ? "" : ip.trim();
        String d = devId == null ? "" : devId.trim();
        if (v.isEmpty() || d.isEmpty()) {
            return false;
        }
        Peer p = findPeer(v);
        if (p == null) {
            return false;
        }
        String old = convKeyOf(v);
        if (d.equals(old)) {
            // 已经是活动设备：补一条它自己的活跃记录即可
            if (p.dev(d) == null) {
                p.noteDev(d, p.lastName, System.currentTimeMillis());
            }
            unignoreDev(d);
            save();
            return true;
        }
        // 升级兼容：旧活动设备还没进别名表时补一条，否则切换后它就从列表里消失了
        if (old != null && !old.isEmpty() && !old.equals(v) && p.dev(old) == null) {
            p.noteDev(old, p.lastName, p.lastSeenAt);
        }
        p.lastId = d;
        if (p.dev(d) == null) {
            p.noteDev(d, "", System.currentTimeMillis());
        }
        bindings.put(v, d);
        ckeys.put(v, d);
        migrateGroupState(old, d);
        // 这台地址从现在起属于新设备（顶栏地址菜单 / 下次启动自动连接都读它）
        groupCurIp.put(d, v);
        unignoreDev(d);
        save();
        return true;
    }


    /** 修改一台设备的 IP 与备注（右键编辑）。oldIp 定位原条目，newIp 可改。 */
    public synchronized boolean updatePeer(String oldIp, String newIp, String remark) {
        Peer cur = findPeer(oldIp);
        if (cur == null) {
            return false;
        }
        String target = newIp == null ? "" : newIp.trim();
        if (target.isEmpty()) {
            target = oldIp;
        }
        if (!target.equals(oldIp)) {
            Peer dup = findPeer(target);
            if (dup != null) {
                // 目标 IP 已存在 → 合并（保留目标条目的发现信息）
                dup.remark = remark == null ? "" : remark.trim();
                peerList.remove(cur);
            } else {
                cur.ip = target;
                cur.remark = remark == null ? "" : remark.trim();
                // 身份绑定跟着走：否则改完 IP 后组键会退回新 IP，聊天记录断成两份
                String b = bindings.remove(oldIp);
                if (b != null && !b.isEmpty()) {
                    bindings.put(target, b);
                    ckeys.put(target, b);
                }
            }
        } else {
            cur.remark = remark == null ? "" : remark.trim();
        }
        save();
        return true;
    }

    /**
     * 从设备列表删掉一个地址，并**一并解除它的身份绑定**。
     *
     * 为什么必须解绑：绑定表是「ip → deviceId」的权威答案，[convKeyOf] 先查它。若只删设备行
     * 而留着绑定，用户之后手动把同一个地址加回来时，它**立刻又回到老那台设备的组里**
     * （只有等该地址自己广播/握手带上**另一个** id 才会被覆盖）——
     * 表现就是「明明加的是新设备，却并进了别人的聊天记录」。删地址 = 本端不再认为它属于那台设备。
     */
    public synchronized boolean removePeer(String ip) {
        Peer p = findPeer(ip);
        String v = ip == null ? "" : ip.trim();
        // 组键必须在解绑**之前**取：解绑后 convKeyOf 会退回 IP，就找不到这台设备的标记了
        String key = v.isEmpty() ? "" : convKeyOf(v);
        boolean unbound = false;
        if (!v.isEmpty()) {
            unbound = bindings.remove(v) != null;
            ckeys.remove(v);
            if (key.isEmpty()) {
                key = v;
            }
        }
        if (p != null) {
            peerList.remove(p);
        }
        // 标记只随「这台设备一个地址都不剩」解除：从组里摘掉一条地址不算删设备
        boolean lastAddr = !key.isEmpty() && !hasAddrOf(key);
        if (lastAddr) {
            unmarkBlockedGroup(key);
        }
        if (p != null || unbound || lastAddr) {
            save();
        }
        return p != null;
    }

    /** 该组键下是否还剩地址（解绑后 convKeyOf 会按剩余绑定/自身 IP 判定） */
    private boolean hasAddrOf(String key) {
        for (Peer q : peerList) {
            if (q.ip != null && key.equals(convKeyOf(q.ip))) {
                return true;
            }
        }
        return false;
    }

    /** 候选 IP 列表（从设备列表派生，供 Link 逐个尝试） */
    public synchronized List<String> peerIps() {
        List<String> out = new ArrayList<String>();
        for (Peer p : peerList) {
            if (p.ip != null && !p.ip.isEmpty()) {
                out.add(p.ip);
            }
        }
        return out;
    }

    /** @return true 表示新增成功（重复返回 false） */
    /**
     * 手工添加一个地址。
     *
     * 时间戳写「现在」：手工填的地址从未被广播/握手续过号（lastSeenAt 会停在 0），
     * 若写 0，[pruneStaleIps] 会把它当成「很旧」当场清掉 —— 用户刚加完、下次启动就没了。
     */
    public synchronized boolean addPeerIp(String ip) {
        return upsertPeer(ip, null, null, null, System.currentTimeMillis());
    }

    /**
     * 启动时清理「长期没用过的地址」（与 Android 端 Prefs.pruneStaleIps 同构）。
     *
     *  - 只清理**超过 keepMin 条地址的设备组**：只有两条（或更少）的组整体跳过；
     *  - 组内 lastSeenAt 早于 now - maxAgeDays 天的地址删掉；
     *  - **保底留最近使用的 keepMin 条**，外加组内当前使用的那个地址永不删 ——
     *    避免「很久没连过的设备被清成空列表」。
     *
     * 删除走 [removePeer]（连带解除 ip → deviceId 绑定），不留指向已删地址的绑定。
     *
     * @return 实际删掉的地址条数
     */
    public synchronized int pruneStaleIps(int maxAgeDays, int keepMin) {
        long cutoff = System.currentTimeMillis() - (long) maxAgeDays * 86_400_000L;
        LinkedHashMap<String, List<Peer>> byKey = new LinkedHashMap<String, List<Peer>>();
        for (Peer p : peerList) {
            if (p.ip == null || p.ip.isEmpty()) {
                continue;
            }
            String k = convKeyOf(p.ip);
            List<Peer> rows = byKey.get(k);
            if (rows == null) {
                rows = new ArrayList<Peer>();
                byKey.put(k, rows);
            }
            rows.add(p);
        }
        int removed = 0;
        for (Map.Entry<String, List<Peer>> en : byKey.entrySet()) {
            List<Peer> rows = en.getValue();
            if (rows.size() <= keepMin) {
                continue;
            }
            Set<String> keep = new HashSet<String>();
            String cur = curIpOf(en.getKey());
            if (cur != null && !cur.isEmpty()) {
                keep.add(cur);
            }
            List<Peer> sorted = new ArrayList<Peer>(rows);
            sorted.sort((a, b) -> Long.compare(b.lastSeenAt, a.lastSeenAt));
            for (int i = 0; i < Math.min(keepMin, sorted.size()); i++) {
                keep.add(sorted.get(i).ip);
            }
            for (Peer p : rows) {
                if (keep.contains(p.ip)) {
                    continue;
                }
                if (p.lastSeenAt > 0L && p.lastSeenAt >= cutoff) {
                    continue;
                }
                if (removePeer(p.ip)) {
                    removed++;
                }
            }
        }
        return removed;
    }

    public synchronized boolean removePeerIp(String ip) {
        return removePeer(ip);
    }

    // ==================== 历史（按对端设备分文件） ====================

    /**
     * 历史封顶（plan/15，2026-09-29）：单文件大小阈值（字节）。
     *
     * <p>进度帧会对同一条消息多次落盘，不封顶则文件只增不减，
     * hasMid / removeFromHistory / loadPendingRaw（全文件读入内存再写回）会越来越慢。
     * 超阈值即触发 {@link #capHistoryFile}：读 → 按 mid 合并 → 按 ts 留最近 2000 条 → 重写。
     */
    static final long HISTORY_MAX_BYTES = 8L * 1024 * 1024;
    /** 封顶后保留条数：按 ts 留最近 N 条（对齐 Android 端封顶思路；PC UI 窗口 200 条，留足余量） */
    static final int HISTORY_KEEP_ROWS = 2000;

    /**
     * 追加一条历史到「该消息所属设备」的文件里。
     *
     * <p>追加后检查文件大小（plan/15）：单文件超 {@link #HISTORY_MAX_BYTES} 立即
     * {@link #capHistoryFile} —— 读全部行、按 mid 合并（后行胜出）、按 ts 留最近
     * {@link #HISTORY_KEEP_ROWS} 条后整体重写。
     */
    public void appendHistory(String peerIp, Map<String, Object> record) {
        synchronized (histLock) {
            Path f = historyFile(peerIp);
            try {
                Files.write(f,
                        (Json.stringify(record) + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (Files.size(f) > HISTORY_MAX_BYTES) {
                    capHistoryFile(f);
                }
            } catch (IOException e) {
                Log.w("写历史失败(" + safeKey(peerIp) + "): " + e);
            }
        }
    }

    /**
     * 封顶单个历史文件（plan/15）：读全部行 → 解析（坏行丢弃）→ 按 mid 合并
     * （后行胜出，与 {@link #loadHistory} 同口径；无 mid 的行按独立条目保留）
     * → 按 ts 留最近 {@link #HISTORY_KEEP_ROWS} 条 → 序列化并按 **UTF-8 字节**计长，
     * 仍超 {@link #HISTORY_MAX_BYTES} 则从最旧继续丢（至少留 1 条）→ 按 ts 升序整体重写。
     *
     * <p>为什么按 ts 而不是行序：hsync 补齐的旧消息追加在文件尾部但 ts 旧，行序不可当时间序。
     * 合并后每个 mid 只有一行，行序不再影响 loadHistory 的合并结果；按 ts 升序落盘还让
     * loadHistory 大文件分支的「尾部 2MB」截尾读到更可能是最新消息。
     *
     * <p>坏行丢弃是安全的：loadHistory 本来就跳过解析失败的行（永不显示）。
     *
     * @return 是否实际重写了文件
     */
    private boolean capHistoryFile(Path f) {
        try {
            java.util.LinkedHashMap<String, Map<String, Object>> byMid =
                    new java.util.LinkedHashMap<String, Map<String, Object>>();
            int rawSeq = 0;
            for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = ln.trim();
                if (t.isEmpty()) {
                    continue;
                }
                Map<String, Object> row;
                try {
                    row = Json.parseObject(t);
                } catch (Exception ignore) {
                    continue; // 坏行 loadHistory 本来就跳过，封顶重写时无需保留
                }
                String mid = Json.str(row, "mid", "");
                String key = mid.isEmpty() ? "__raw_" + (rawSeq++) : mid;
                Map<String, Object> prev = byMid.get(key);
                byMid.put(key, prev == null ? row : mergeRow(prev, row));
            }
            List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(byMid.values());
            if (rows.isEmpty()) {
                return false; // 无一条可解析，不动原文件
            }
            // 最新在前（不能按行序，见方法注释）
            rows.sort((a, b) -> Long.compare(Json.lng(b, "ts", 0L), Json.lng(a, "ts", 0L)));
            if (rows.size() > HISTORY_KEEP_ROWS) {
                rows.subList(HISTORY_KEEP_ROWS, rows.size()).clear();
            }
            List<String> out = new ArrayList<String>(rows.size());
            long size = 0;
            for (Map<String, Object> r : rows) {
                String s = Json.stringify(r);
                out.add(s);
                size += s.getBytes(StandardCharsets.UTF_8).length + 1;
            }
            while (size > HISTORY_MAX_BYTES && out.size() > 1) {
                size -= out.remove(out.size() - 1).getBytes(StandardCharsets.UTF_8).length + 1;
            }
            StringBuilder sb = new StringBuilder((int) size);
            for (int i = out.size() - 1; i >= 0; i--) {
                sb.append(out.get(i)).append('\n');
            }
            Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
            Log.i("历史封顶: " + f.getFileName() + " → " + out.size() + " 条 / " + size + " 字节");
            return true;
        } catch (IOException e) {
            Log.w("历史封顶失败(" + f.getFileName() + "): " + e);
            return false;
        }
    }

    /**
     * 启动全扫封顶（plan/15）：遍历 history/*.jsonl，对超 {@link #HISTORY_MAX_BYTES}
     * 的文件执行 {@link #capHistoryFile}。
     *
     * @return 实际重写的文件数
     */
    public int capHistoryFiles() {
        int n = 0;
        synchronized (histLock) {
            try {
                if (!Files.isDirectory(historyDir)) {
                    return 0;
                }
                List<Path> files = new ArrayList<Path>();
                try (java.util.stream.Stream<Path> st = Files.list(historyDir)) {
                    st.filter(p -> p.getFileName().toString().endsWith(".jsonl")).forEach(files::add);
                }
                for (Path f : files) {
                    try {
                        if (Files.size(f) > HISTORY_MAX_BYTES && capHistoryFile(f)) {
                            n++;
                        }
                    } catch (IOException ignore) {
                    }
                }
            } catch (IOException e) {
                Log.w("历史封顶扫描失败: " + e);
            }
        }
        return n;
    }

    /**
     * 清理孤儿缩略图缓存（plan/15）：删掉 thumb_cache/ 下 mid 已不在任何设备历史、
     * 也不在待发队列里的缓存文件。
     *
     * <p>正常删消息会随消息一起清缓存（ChatWindow），但对端在本机离线期间删消息时
     * 缓存会留下 —— 启动时扫一遍兜住。缓存文件可再生，不是用户数据。
     *
     * @return 删除的文件数
     */
    public int pruneThumbCache() {
        Path dir = root.resolve("thumb_cache");
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        Set<String> live = new HashSet<String>();
        boolean histOk = true;
        synchronized (histLock) {
            try {
                if (Files.isDirectory(historyDir)) {
                    List<Path> files = new ArrayList<Path>();
                    try (java.util.stream.Stream<Path> st = Files.list(historyDir)) {
                        st.filter(p -> p.getFileName().toString().endsWith(".jsonl")).forEach(files::add);
                    }
                    for (Path f : files) {
                        for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                            String t = ln.trim();
                            if (t.isEmpty() || !t.contains("\"mid\":\"")) {
                                continue;
                            }
                            try {
                                String mid = Json.str(Json.parseObject(t), "mid", "");
                                if (!mid.isEmpty()) {
                                    live.add(mid);
                                }
                            } catch (Exception ignore) {
                                // 坏行没有 mid，不参与保留集
                            }
                        }
                    }
                }
            } catch (IOException e) {
                Log.w("读历史 mid 失败: " + e);
                histOk = false;
            }
        }
        // 待发队列的 mid 也算「活着」（未发出的图片消息可能已生成过缩略图）
        for (Msg m : loadPending()) {
            if (m.mid != null && !m.mid.isEmpty()) {
                live.add(m.mid);
            }
        }
        if (!histOk) {
            return 0; // 历史读取失败时 live 集不可信，一个都不删（防误删）
        }
        int n = 0;
        try {
            List<Path> thumbs = new ArrayList<Path>();
            try (java.util.stream.Stream<Path> st = Files.list(dir)) {
                st.filter(p -> p.getFileName().toString().endsWith(".jpg")).forEach(thumbs::add);
            }
            for (Path p : thumbs) {
                String name = p.getFileName().toString();
                String mid = name.substring(0, name.length() - ".jpg".length());
                if (!live.contains(mid)) {
                    Files.deleteIfExists(p);
                    n++;
                }
            }
        } catch (IOException e) {
            Log.w("清理缩略图缓存失败: " + e);
        }
        return n;
    }

    /**
     * 统一消息 id 查重（2026-09-16）：该设备的历史里是否已有这条 mid。
     * hsync 补齐与重复投递的判定**只认 mid**，与气泡、待发队列共用同一判据。
     */
    public boolean hasMid(String peerIp, String mid) {
        if (mid == null || mid.isEmpty()) {
            return false;
        }
        synchronized (histLock) {
            Path f = historyFile(peerIp);
            if (!Files.exists(f)) {
                return false;
            }
            try {
                for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String t = ln.trim();
                    if (t.isEmpty() || !t.contains("\"mid\":\"" + mid + "\"")) {
                        continue;
                    }
                    try {
                        if (mid.equals(Json.str(Json.parseObject(t), "mid", ""))) {
                            return true;
                        }
                    } catch (Exception ignore) {
                    }
                }
            } catch (IOException e) {
                Log.w("mid 查重失败(" + safeKey(peerIp) + "): " + e);
            }
            return false;
        }
    }

    /**
     * 该 mid 是否已作为**收到的文件**完整落盘过（2026-10-01 重复投递判据）。
     * 返回当初的落盘路径；空串 = 没收到过 / 记录不是成功态 / 文件已被用户删掉。
     *
     * <p>与 {@link #hasMid} 的区别：那个只认 mid、不分状态，历史里一条 SENDING 进度行
     * 或 FAILED 行也会命中 —— 拿它当「已存过」的判据会把该重传的文件永久跳过。
     * 这里要求 {@code dir=PEER + kind=FILE + isDir=false + status=OK}。
     *
     * <p>同一 mid 有多行（进度行 + 终态行）时按**后行胜出**，与 {@link #loadHistory}
     * / {@link #capHistoryFile} 同口径：先收成功、后来重传失败的设备，判据应跟最后一次。
     *
     * <p>文件必须仍在磁盘上：用户把收到的文件删了就该允许他让对端重发一次。
     */
    public String savedFileOf(String peerIp, String mid) {
        if (peerIp == null || peerIp.isEmpty() || mid == null || mid.isEmpty()) {
            return "";
        }
        String needle = "\"mid\":\"" + mid + "\"";
        Map<String, Object> row = null;
        synchronized (histLock) {
            Path f = historyFile(peerIp);
            if (!Files.exists(f)) {
                return "";
            }
            try {
                for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String t = ln.trim();
                    if (t.isEmpty() || !t.contains(needle)) {
                        continue;
                    }
                    try {
                        Map<String, Object> r = Json.parseObject(t);
                        if (mid.equals(Json.str(r, "mid", ""))) {
                            row = r;   // 后行胜出
                        }
                    } catch (Exception ignore) {
                    }
                }
            } catch (IOException e) {
                Log.w("已收妥查重失败(" + safeKey(peerIp) + "): " + e);
                return "";
            }
        }
        if (row == null || !"PEER".equals(Json.str(row, "dir", ""))
                || !Msg.KIND_FILE.equals(Json.str(row, "kind", ""))
                || Json.bool(row, "isDir", false)
                || !Msg.ST_OK.equals(Json.str(row, "status", ""))) {
            return "";
        }
        String path = Json.str(row, "filePath", "");
        if (path.isEmpty() || !Files.isRegularFile(Paths.get(path))) {
            return "";
        }
        return path;
    }

    /**
     * 按 mid 删除一条历史（用于「取消发送的文件」——取消后重载聊天记录也不应再出现）。
     * 只动该设备自己的文件：读出全部、滤掉该 mid、再整体写回。
     *
     * <p>批量场景（清空会话）请用 {@link #removeFromHistory(String, java.util.Collection)}，
     * 那个只做一次全文件往返。
     */
    public void removeFromHistory(String peerIp, String mid) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        synchronized (histLock) {
            Path f = historyFile(peerIp);
            if (!Files.exists(f)) {
                return;
            }
            try {
                StringBuilder sb = new StringBuilder();
                for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String t = ln.trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    boolean drop = false;
                    try {
                        drop = mid.equals(Json.str(Json.parseObject(t), "mid", ""));
                    } catch (Exception ignore) {
                        // 坏行保留
                    }
                    if (!drop) {
                        sb.append(ln).append('\n');
                    }
                }
                Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w("删历史失败: " + e);
            }
        }
    }

    /**
     * 按 mid 批量删除历史（「清空当前会话」逐条删除用）：**一次读、一次写**。
     *
     * <p>为什么不循环调 {@link #removeFromHistory(String, String)}：那个版本每删一条就把整个
     * 历史文件读一遍写一遍，清 N 条就是 N 次全文件往返（O(N²)）—— 会话一长（几百上千条）
     * 会明显卡顿。这里合并成一趟过滤，语义完全一致。
     *
     * @return 实际删掉的行数
     */
    public int removeFromHistory(String peerIp, java.util.Collection<String> mids) {
        if (mids == null || mids.isEmpty()) {
            return 0;
        }
        java.util.HashSet<String> drop = new java.util.HashSet<String>();
        for (String s : mids) {
            if (s != null && !s.isEmpty()) {
                drop.add(s);
            }
        }
        if (drop.isEmpty()) {
            return 0;
        }
        synchronized (histLock) {
            Path f = historyFile(peerIp);
            if (!Files.exists(f)) {
                return 0;
            }
            int removed = 0;
            try {
                StringBuilder sb = new StringBuilder();
                for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String t = ln.trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    boolean hit = false;
                    try {
                        hit = drop.contains(Json.str(Json.parseObject(t), "mid", ""));
                    } catch (Exception ignore) {
                        // 坏行保留
                    }
                    if (hit) {
                        removed++;
                    } else {
                        sb.append(ln).append('\n');
                    }
                }
                if (removed > 0) {
                    Files.write(f, sb.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.w("批量删历史失败(" + safeKey(peerIp) + "): " + e);
                return 0;
            }
            return removed;
        }
    }

    /**
     * 按 mid 删除一条历史，**在所有设备的历史文件里找**。
     *
     * 取消文件发送的通知里只带 mid、不带归属设备（协议里也不需要），而这里的历史是
     * 按对端分文件的，所以直接扫一遍目录最省事：mid 是全局唯一随机串，命中即该条。
     *
     * @return 被删掉该 mid 的文件数（0 = 本来就没有）
     */
    public int removeFromHistoryEverywhere(String mid) {
        if (mid == null || mid.isEmpty()) {
            return 0;
        }
        synchronized (histLock) {
            int n = 0;
            try {
                if (!Files.isDirectory(historyDir)) {
                    return 0;
                }
                List<Path> files = new ArrayList<Path>();
                try (java.util.stream.Stream<Path> st = Files.list(historyDir)) {
                    st.filter(p -> p.getFileName().toString().endsWith(".jsonl")).forEach(files::add);
                }
                for (Path f : files) {
                    String kept = filterOutMid(f, mid);
                    if (kept != null) {
                        Files.write(f, kept.getBytes(StandardCharsets.UTF_8));
                        n++;
                    }
                }
            } catch (IOException e) {
                Log.w("删历史失败: " + e);
            }
            return n;
        }
    }

    /** 读出文件、滤掉 mid 对应行；没有命中返回 null（调用方据此跳过写回） */
    private String filterOutMid(Path f, String mid) throws IOException {
        StringBuilder sb = new StringBuilder();
        boolean hit = false;
        for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            String t = ln.trim();
            if (t.isEmpty()) {
                continue;
            }
            boolean drop = false;
            try {
                drop = mid.equals(Json.str(Json.parseObject(t), "mid", ""));
            } catch (Exception ignore) {
                // 坏行保留
            }
            if (drop) {
                hit = true;
            } else {
                sb.append(ln).append('\n');
            }
        }
        return hit ? sb.toString() : null;
    }

    /**
     * 读取**指定设备**最近 max 条历史（文件过大时只读尾部 2MB）。
     *
     * **按 mid 合并去重（后行胜出）**：同一条消息可能有多行（file_meta 落一次、
     * 终态 updated 再补一次）—— 加载时合成一条，后行的非空字段覆盖前行。
     * 这同时治愈旧版本留下的重复行（v1 对端 file_done 会再 emit 一次 added）。
     */
    public List<Map<String, Object>> loadHistory(String peerIp, int max) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        synchronized (histLock) {
            Path f = historyFile(peerIp);
            if (!Files.exists(f)) {
                return out;
            }
            try {
                List<String> lines;
                long size = Files.size(f);
                if (size > 2 * 1024 * 1024) {
                    byte[] all = Files.readAllBytes(f);
                    String tail = new String(all, (int) (all.length - 2 * 1024 * 1024),
                            Math.min(2 * 1024 * 1024, all.length), StandardCharsets.UTF_8);
                    lines = java.util.Arrays.asList(tail.split("\n"));
                } else {
                    lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                }
                int from = Math.max(0, lines.size() - max);
                java.util.LinkedHashMap<String, Map<String, Object>> byMid =
                        new java.util.LinkedHashMap<String, Map<String, Object>>();
                int rawSeq = 0;
                for (int i = from; i < lines.size(); i++) {
                    String ln = lines.get(i).trim();
                    if (ln.isEmpty()) {
                        continue;
                    }
                    try {
                        Map<String, Object> row = Json.parseObject(ln);
                        String mid = Json.str(row, "mid", "");
                        String key = mid.isEmpty() ? "__raw_" + (rawSeq++) : mid;
                        Map<String, Object> prev = byMid.get(key);
                        byMid.put(key, prev == null ? row : mergeRow(prev, row));
                    } catch (Exception ignore) {
                        // 跳过损坏行
                    }
                }
                out.addAll(byMid.values());
            } catch (IOException e) {
                Log.w("读历史失败: " + e);
            }
        }
        return out;
    }

    /**
     * 历史行合并：后行的非空值覆盖前行（进度/状态/路径以最新的为准，其余保留）。
     * mid/dir/kind 是身份字段不覆盖 —— 裸状态帧（只有 mid+status）的 kind 默认 TEXT，
     * 若覆盖会把文件消息读成文本气泡。
     */
    private static Map<String, Object> mergeRow(Map<String, Object> old, Map<String, Object> neu) {
        for (Map.Entry<String, Object> e : neu.entrySet()) {
            String k = e.getKey();
            if ("mid".equals(k) || "dir".equals(k) || "kind".equals(k)) {
                continue;
            }
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof String && ((String) v).isEmpty()) {
                continue;
            }
            // fileSize/fileSize=0 / progress=-1 都是「未知/未赋值」，不得覆盖已有有效值
            //（裸状态帧里 fileSize 默认 0，会让文件气泡显示成 0B）
            if (("fileSize".equals(k) || "dirCount".equals(k)) && v instanceof Number
                    && ((Number) v).longValue() <= 0L) {
                continue;
            }
            old.put(k, v);
        }
        return old;
    }

    // ==================== 待发队列 ====================

    private Path pendingFile() {
        return root.resolve("pending.jsonl");
    }

    public synchronized void addPending(Msg m) {
        List<Map<String, Object>> cur = loadPendingRaw();
        for (Map<String, Object> r : cur) {
            if (m.mid.equals(Json.str(r, "mid", ""))) {
                return;
            }
        }
        m.status = Msg.ST_PENDING;
        cur.add(m.toJson());
        writePendingRaw(cur);
    }

    public synchronized void removePending(String mid) {
        List<Map<String, Object>> cur = loadPendingRaw();
        int before = cur.size();
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (mid.equals(Json.str(cur.get(i), "mid", ""))) {
                cur.remove(i);
            }
        }
        if (cur.size() != before) {
            writePendingRaw(cur);
        }
    }

    /**
     * 批量移除待发条目（「清空当前会话」用）：一趟过滤 + 一次落盘。
     * 与逐条调 {@link #removePending(String)} 语义相同，但避免 N 次全文件重写。
     *
     * @return 实际移除的条数
     */
    public synchronized int removePending(java.util.Collection<String> mids) {
        if (mids == null || mids.isEmpty()) {
            return 0;
        }
        java.util.HashSet<String> drop = new java.util.HashSet<String>();
        for (String s : mids) {
            if (s != null && !s.isEmpty()) {
                drop.add(s);
            }
        }
        if (drop.isEmpty()) {
            return 0;
        }
        List<Map<String, Object>> cur = loadPendingRaw();
        int before = cur.size();
        for (int i = cur.size() - 1; i >= 0; i--) {
            if (drop.contains(Json.str(cur.get(i), "mid", ""))) {
                cur.remove(i);
            }
        }
        int removed = before - cur.size();
        if (removed > 0) {
            writePendingRaw(cur);
        }
        return removed;
    }

    public synchronized void clearPending() {
        writePendingRaw(new ArrayList<Map<String, Object>>());
    }

    /**
     * 清空与某台设备的全部聊天/传输记录：历史文件整删 + 待发队列里属于该设备的条目清掉。
     * **只动记录，不动已传输落盘的实际文件**。
     *
     * <p>⚠ **不要用它实现「清空当前会话」**（2026-09-21 起）：它只删本机历史，对端那几条还在，
     * 对端一上线就会被 hsync 整段补回来。界面上的清空走
     * {@link ChatWindow#clearCurrentConversation()} —— 逐条 mid 删除 + `msg_del` 通知对端。
     * 这个方法仅留给「确实要整文件丢弃」的场景（当前 UI 已无调用点）。
     *
     * @return 清掉的记录数（历史文件算 1 + 待发条数），供 UI 提示；历史本来就没有时也把待发清掉
     */
    public int clearConversation(String peerIp) {
        if (peerIp == null || peerIp.isEmpty()) {
            return 0;
        }
        int n = 0;
        synchronized (histLock) {
            try {
                if (Files.deleteIfExists(historyFile(peerIp))) {
                    n++;
                }
            } catch (IOException e) {
                Log.w("清空历史失败(" + safeKey(peerIp) + "): " + e);
            }
        }
        synchronized (this) {
            List<Map<String, Object>> cur = loadPendingRaw();
            List<Map<String, Object>> keep = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> r : cur) {
                if (peerIp.equals(Json.str(r, "peer", ""))) {
                    n++;
                } else {
                    keep.add(r);
                }
            }
            if (keep.size() != cur.size()) {
                writePendingRaw(keep);
            }
        }
        return n;
    }

    @SuppressWarnings("unchecked")
    public synchronized List<Msg> loadPending() {
        List<Msg> out = new ArrayList<Msg>();
        for (Map<String, Object> r : loadPendingRaw()) {
            out.add(toMsg(r));
        }
        return out;
    }

    /**
     * 查某条待发消息是发给哪台设备的。
     *
     * 用于「取消发送」：取消通知里只有 mid，但要告诉对端撤哪个气泡、以及本端该从
     * 哪台设备的会话里摘掉，所以得回到队列里翻一眼这条 mid 的 `peer`。
     *
     * @return 目的设备 IP；查不到返回空串
     */
    public synchronized String pendingPeerIp(String mid) {
        if (mid == null || mid.isEmpty()) {
            return "";
        }
        for (Map<String, Object> r : loadPendingRaw()) {
            if (mid.equals(Json.str(r, "mid", ""))) {
                return Json.str(r, "peer", "");
            }
        }
        return "";
    }

    private List<Map<String, Object>> loadPendingRaw() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        Path f = pendingFile();
        if (!Files.exists(f)) {
            return out;
        }
        try {
            for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String t = ln.trim();
                if (t.isEmpty()) {
                    continue;
                }
                try {
                    out.add(Json.parseObject(t));
                } catch (Exception ignore) {
                    // 跳过坏行
                }
            }
        } catch (IOException e) {
            Log.w("读 pending.jsonl 失败: " + e);
        }
        return out;
    }

    private void writePendingRaw(List<Map<String, Object>> list) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> m : list) {
            sb.append(Json.stringify(m)).append('\n');
        }
        try {
            Files.write(pendingFile(), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w("写 pending.jsonl 失败: " + e);
        }
    }

    private static Msg toMsg(Map<String, Object> m) {
        Msg x = new Msg();
        x.mid = Json.str(m, "mid", "");
        x.peerIp = Json.str(m, "peer", "");
        x.dir = "SELF".equals(Json.str(m, "dir", "PEER")) ? Msg.DIR_SELF : Msg.DIR_PEER;
        x.kind = Json.str(m, "kind", Msg.KIND_TEXT);
        x.text = Json.str(m, "text", "");
        x.fileName = Json.str(m, "fileName", "");
        x.fileSize = Json.lng(m, "fileSize", 0L);
        x.filePath = Json.str(m, "filePath", "");
        x.ts = Json.lng(m, "ts", System.currentTimeMillis());
        x.status = Json.str(m, "status", Msg.ST_OK);
        x.isDir = Json.bool(m, "isDir", false);
        x.dirCount = Json.integer(m, "dirCount", 0);
        return x;
    }

    // ==================== 断点续传：半成品注册表 ====================

    /**
     * 正在接收/未接收完的文件登记（mid → path/name/size/received），持久化到 partials.json。
     * 发送端断线重连后发 file_query，接收端凭它找到半成品继续追加，而不是推倒重来。
     */
    public synchronized void putPartial(String mid, String path, String name, long size, long received) {
        if (mid == null || mid.isEmpty() || path == null || path.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> all = loadPartials();
            Map<String, Object> e = new LinkedHashMap<String, Object>();
            e.put("path", path);
            e.put("name", name);
            e.put("size", Long.valueOf(size));
            e.put("received", Long.valueOf(received));
            e.put("ts", Long.valueOf(System.currentTimeMillis()));
            all.put(mid, e);
            writePartials(all);
        } catch (Exception e) {
            Log.w("写半成品注册表失败: " + e);
        }
    }

    public synchronized Map<String, Object> getPartial(String mid) {
        try {
            return Json.obj(loadPartials().get(mid));
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void removePartial(String mid) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        try {
            Map<String, Object> all = loadPartials();
            if (all.remove(mid) != null) {
                writePartials(all);
            }
        } catch (Exception ignore) {
        }
    }

    /**
     * 清理孤儿半成品：发送端取消时取消帧丢失、或接收端重启后 mid 对不上，
     * 这类条目永远不会被 file_query 命中。启动时清掉「超过 maxAgeMs 未更新」的条目
     * （连同半成品文件一起删）；无 ts 的旧版条目一并视为过期。
     */
    public synchronized void purgeStalePartials(long maxAgeMs) {
        try {
            Map<String, Object> all = loadPartials();
            if (all.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            java.util.Iterator<Map.Entry<String, Object>> it = all.entrySet().iterator();
            int n = 0;
            while (it.hasNext()) {
                Map<String, Object> e = Json.obj(it.next().getValue());
                String path = e == null ? "" : Json.str(e, "path", "");
                long ts = e == null ? 0L : Json.lng(e, "ts", 0L);
                boolean stale = ts <= 0L || now - ts > maxAgeMs;
                if (!stale) {
                    continue;
                }
                if (!path.isEmpty()) {
                    new java.io.File(path).delete();
                }
                it.remove();
                n++;
            }
            if (n > 0) {
                writePartials(all);
                Log.i("清理过期半成品 " + n + " 项（含文件）");
            }
        } catch (Exception e) {
            Log.w("清理半成品注册表失败: " + e);
        }
    }

    private Path partialsFile() {
        return root.resolve("partials.json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadPartials() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        try {
            if (Files.exists(partialsFile())) {
                String s = new String(Files.readAllBytes(partialsFile()), StandardCharsets.UTF_8);
                Map<String, Object> m = Json.parseObject(s);
                if (m != null) {
                    out.putAll(m);
                }
            }
        } catch (Exception e) {
            Log.w("读 partials.json 失败: " + e);
        }
        return out;
    }

    private void writePartials(Map<String, Object> all) {
        try {
            Files.write(partialsFile(), Json.stringify(all).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w("写 partials.json 失败: " + e);
        }
    }

    /**
     * 数据根目录（2026-09-21 改版）——
     * **`%APPDATA%\PhonePcTransfer`**（Windows；其它平台回退 `~/.phonepctransfer`）。
     *
     * <p>为什么从「程序目录」搬到这里：以前数据就写在程序旁边（源码运行 = `pc/`，
     * MSI 安装版 = 安装根目录 `%LOCALAPPDATA%\手机电脑互传\`）。而 MSI 升级是
     * **卸旧装新**（jpackage 的 MajorUpgrade），安装目录连同 `config.json` / `history/` /
     * 待发队列一起被删干净 —— 升一次级设备列表与聊天记录就没了。放用户数据目录后，
     * 升级 / 重装 / 卸载都不碰它（卸载想清数据得用户自己删目录）。
     *
     * <p>覆盖方式（自检、多实例并存）：`-Dphonepctransfer.home=<dir>` 或环境变量
     * `PHONEPCTRANSFER_HOME`；给了就**不做**旧数据搬迁，完全按指定目录来。
     *
     * <p>首次运行会把老位置的数据**搬一份过来**（见 {@link #migrateLegacyData}）：
     * 老配置 + 设备列表 + 全部聊天历史，都是复制不是移动，老目录原样留着。
     */
    public static Path defaultRoot() {
        Path root = explicitRoot();
        if (root != null) {
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                Log.w("建数据目录失败: " + e);
            }
            return root;
        }
        String appdata = System.getenv("APPDATA");
        if (appdata != null && !appdata.trim().isEmpty()) {
            root = Paths.get(appdata.trim()).resolve("PhonePcTransfer");
        } else {
            // 非 Windows / APPDATA 被裁掉的环境
            root = Paths.get(System.getProperty("user.home", ".")).resolve(".phonepctransfer");
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            Log.w("建数据目录失败: " + e);
        }
        migrateLegacyData(root);
        return root;
    }

    /** `-Dphonepctransfer.home=` / `PHONEPCTRANSFER_HOME=` 指定的数据目录；没给返回 null */
    private static Path explicitRoot() {
        String p = System.getProperty("phonepctransfer.home");
        if (p == null || p.trim().isEmpty()) {
            p = System.getenv("PHONEPCTRANSFER_HOME");
        }
        return (p == null || p.trim().isEmpty()) ? null : Paths.get(p.trim()).toAbsolutePath();
    }

    /** 搬迁标记：写过一次就不再扫（新装用户也不必每次启动去翻旧目录） */
    private static final String MIGRATED_FLAG = ".migrated-into-appdata";

    /**
     * 把「程序目录时代」的数据搬进新数据目录（**只做一次**，且只复制不删除）。
     *
     * <p>旧位置候选：当前工作目录（源码运行 = `pc/`；MSI 安装版 = 安装根目录，实测就是
     * `%LOCALAPPDATA%\手机电脑互传\`）、`<cwd>/pc`、`%LOCALAPPDATA%\手机电脑互传`、`~/手机电脑互传`。
     *
     * <p>搬迁规则（以**最近用过**的那份配置为底，其余只补空缺）：
     * <ol>
     *   <li>配置：选 `peers[].lastSeenAt` 最大的那份当底（保留本机 deviceId / 端口 / 保存位置等设置），
     *       其余旧配置的**设备列表、绑定表、备注、组当前地址、旋转角**按「补空缺」并进来；</li>
     *   <li>历史：逐个旧目录的 `history/*.jsonl` 拷进来；目标已有同名文件时**按 mid 去重后追补**缺的行
     *       （不覆盖已有行，也不丢另一台机器上聊过的那几条）；</li>
     *   <li>待发队列 `pending.jsonl` 同样按 mid 去重合并；`partials.json`（断点续传进度）仅当目标没有时拷一份。</li>
     * </ol>
     *
     * <p>失败一律只记日志、不影响启动 —— 数据搬迁不该拦着用户用软件。
     */
    private static void migrateLegacyData(Path root) {
        try {
            Path flag = root.resolve(MIGRATED_FLAG);
            if (Files.exists(flag)) {
                return;
            }
            List<Path> legacy = new ArrayList<Path>();
            for (Path p : legacyRoots()) {
                if (p != null && !p.equals(root) && Files.isRegularFile(p.resolve("config.json"))) {
                    legacy.add(p);
                }
            }
            if (legacy.isEmpty()) {
                Files.write(flag, ("no legacy data; created "
                        + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()))
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            // ① 选底：最近用过的那份（peers 里最大的 lastSeenAt）
            Path base = legacy.get(0);
            long best = -1L;
            for (Path p : legacy) {
                long t = configLastSeen(p.resolve("config.json"));
                if (t > best) {
                    best = t;
                    base = p;
                }
            }
            Files.copy(base.resolve("config.json"), root.resolve("config.json"),
                    java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            Log.i("数据目录迁移：以 " + base + " 的配置为底（最后使用 "
                    + (best <= 0 ? "未知" : new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(best)))
                    + "），新数据目录 " + root);

            // ② 其余旧配置的设备/绑定/备注等「补空缺」并进新配置（复用 Store 自己的读写，
            //    不手搓 JSON：load() 认结构、save() 落盘，改一处两边都对）
            if (legacy.size() > 1) {
                Store tmp = new Store(root);
                tmp.load();
                for (Path p : legacy) {
                    if (!p.equals(base)) {
                        tmp.mergeConfigFrom(p.resolve("config.json"));
                    }
                }
                tmp.save();
            }

            // ③ 历史 / 待发 / 续传进度
            int files = 0;
            for (Path p : legacy) {
                files += copyHistoryDir(p.resolve("history"), root.resolve("history"));
                mergeJsonl(p.resolve("pending.jsonl"), root.resolve("pending.jsonl"));
                Path part = p.resolve("partials.json");
                if (Files.isRegularFile(part) && !Files.exists(root.resolve("partials.json"))) {
                    Files.copy(part, root.resolve("partials.json"));
                }
            }
            Files.write(flag, ("migrated from " + legacy + " at "
                    + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())
                    + "；history 文件 " + files + " 份").getBytes(StandardCharsets.UTF_8));
            Log.i("数据目录迁移完成：历史 " + files + " 份（老目录原样保留，可手动核对后删除）");
        } catch (Throwable t) {
            Log.w("数据目录迁移失败（不影响使用）: " + t);
        }
    }

    /** 旧数据目录候选（按「最可能」排序）：cwd → cwd/pc → %LOCALAPPDATA%\手机电脑互传 → ~/手机电脑互传 */
    private static List<Path> legacyRoots() {
        java.util.LinkedHashSet<Path> out = new java.util.LinkedHashSet<Path>();
        Path cwd = Paths.get("").toAbsolutePath();
        out.add(cwd);
        out.add(cwd.resolve("pc"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.trim().isEmpty()) {
            out.add(Paths.get(local.trim()).resolve("手机电脑互传"));
        }
        out.add(Paths.get(System.getProperty("user.home", ".")).resolve("手机电脑互传"));
        return new ArrayList<Path>(out);
    }

    /** 一份 config.json 里 peers 的最大 lastSeenAt（读不动就返回 0） */
    private static long configLastSeen(Path cfg) {
        try {
            Map<String, Object> m = Json.parseObject(
                    new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8));
            long best = 0L;
            Object peers = m.get("peers");
            if (peers instanceof List) {
                for (Object o : (List<?>) peers) {
                    Map<String, Object> pm = Json.obj(o);
                    if (pm != null) {
                        best = Math.max(best, Json.lng(pm, "lastSeenAt", 0L));
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 把另一份旧配置里「本机还缺」的设备与状态并进来（**只补空缺，不覆盖**）：
     * 设备列表按 IP、绑定表按 IP、备注/组当前地址/旋转角按组键。
     */
    private void mergeConfigFrom(Path cfg) {
        try {
            Map<String, Object> m = Json.parseObject(
                    new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8));
            Object peers = m.get("peers");
            if (peers instanceof List) {
                for (Object o : (List<?>) peers) {
                    Map<String, Object> pm = Json.obj(o);
                    if (pm == null) {
                        continue;
                    }
                    String ip = Json.str(pm, "ip", "").trim();
                    if (ip.isEmpty() || findPeer(ip) != null) {
                        continue;
                    }
                    Peer p = Peer.fromJson(pm);
                    peerList.add(p);
                    String id = p.lastId == null ? "" : p.lastId.trim();
                    if (!id.isEmpty() && !bindings.containsKey(ip)) {
                        bindings.put(ip, id);
                        ckeys.put(ip, id);
                    }
                }
            }
            mergeStringMap(m, "deviceRemarks", deviceRemarks);
            mergeStringMap(m, "groupCurIp", groupCurIp);
            mergeStringMap(m, "rotRemote", rotRemote);
            // 标量设置也「只补空缺」：底那份为空（用户没设过）而另一份设过 → 用设过的那份。
            // 典型：安装版没改过保存目录（saveDir=""），源码版设过 D:\Downloads —— 不能让用户
            // 升一次级就把接收位置丢了。底那份有值的一律不动（那是用户在那台机器上的选择）。
            if (saveDir().isEmpty()) {
                String v = Json.str(m, "saveDir", "").trim();
                if (!v.isEmpty()) {
                    setSaveDir(v);
                    Log.i("并入旧配置的保存位置: " + v);
                }
            }
            if (lastIp().isEmpty()) {
                String v = Json.str(m, "lastIp", "").trim();
                if (!v.isEmpty()) {
                    setLastIp(v);
                }
            }
            if (ffmpegPath().isEmpty()) {
                String v = Json.str(m, "ffmpegPath", "").trim();
                if (!v.isEmpty()) {
                    setFfmpegPath(v);
                }
            }
            Log.i("已并入旧配置的设备与设置：" + cfg);
        } catch (Throwable t) {
            Log.w("并入旧配置失败（跳过）: " + cfg + " " + t);
        }
    }

    private static void mergeStringMap(Map<String, Object> m, String key, Map<String, String> out) {
        Object o = m.get(key);
        if (!(o instanceof Map)) {
            return;
        }
        for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            String k = String.valueOf(e.getKey());
            String v = String.valueOf(e.getValue());
            if (!k.isEmpty() && !v.isEmpty() && !out.containsKey(k)) {
                out.put(k, v);
            }
        }
    }

    /** 把一个旧 history 目录里的 *.jsonl 拷进新目录；同名文件按 mid 去重追补。返回新拷的份数 */
    private static int copyHistoryDir(Path from, Path to) {
        if (!Files.isDirectory(from)) {
            return 0;
        }
        int copied = 0;
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(from, "*.jsonl")) {
            for (Path f : ds) {
                Path dest = to.resolve(f.getFileName().toString());
                if (!Files.exists(dest)) {
                    Files.copy(f, dest);
                    copied++;
                } else {
                    mergeJsonl(f, dest);
                }
            }
        } catch (IOException e) {
            Log.w("拷历史失败: " + from + " " + e);
        }
        return copied;
    }

    /** 行分隔 JSON 合并：目标里没有的 mid 才追加（避免同一台设备的记录两边各半、合并后重复） */
    private static void mergeJsonl(Path src, Path dest) {
        if (!Files.isRegularFile(src)) {
            return;
        }
        try {
            List<String> srcLines = Files.readAllLines(src, StandardCharsets.UTF_8);
            if (srcLines.isEmpty()) {
                return;
            }
            Set<String> have = midsIn(dest);
            StringBuilder add = new StringBuilder();
            for (String ln : srcLines) {
                String t = ln.trim();
                if (t.isEmpty()) {
                    continue;
                }
                String mid = midOf(t);
                if (!mid.isEmpty() && !have.add(mid)) {
                    continue;
                }
                add.append(t).append(System.lineSeparator());
            }
            if (add.length() > 0) {
                Files.write(dest, add.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (Throwable t) {
            Log.w("合并 jsonl 失败: " + src + " → " + dest + " " + t);
        }
    }

    private static Set<String> midsIn(Path f) {
        Set<String> out = new HashSet<String>();
        try {
            for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String mid = midOf(ln.trim());
                if (!mid.isEmpty()) {
                    out.add(mid);
                }
            }
        } catch (Throwable ignore) {
        }
        return out;
    }

    private static String midOf(String line) {
        int i = line.indexOf("\"mid\"");
        if (i < 0) {
            return "";
        }
        int a = line.indexOf('"', line.indexOf(':', i) + 1);
        int b = a < 0 ? -1 : line.indexOf('"', a + 1);
        return (a < 0 || b < 0) ? "" : line.substring(a + 1, b);
    }
}
