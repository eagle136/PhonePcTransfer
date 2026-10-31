package com.phonepctransfer.pc;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.nio.file.*;
import java.net.*;

import com.phonepctransfer.pc.Link.Session;
// ==================================================================
// hsync 历史同步 + 消息删除同步（v2.7 拆分自 Link.java）。
// 持 Link 引用访问其成员；Session 为 Link 包私有嵌套类。
// ==================================================================

final class LinkHsync {

    final Link L;

    LinkHsync(Link L) {
        this.L = L;
    }

    // ==================== 历史同步（hsync，v2.3）====================

    /** hsync：双方各扫描最近多少条历史参与对账 */
    static final int HSYNC_SCAN = 300;
    /** hsync：单次 pull 最多请求多少条（超出的等下次连接再补） */
    static final int HSYNC_PULL_MAX = 200;
    /** hsync：文件自动补齐的大小上限；更大的文件不补（避免误触发 GB 级重传） */
    static final long HSYNC_FILE_MAX = 64L * 1024 * 1024;

    /**
     * 以**统一消息 id（mid）为唯一判据**的双端聊天内容对齐（与安卓端同构）：
     *
     *   握手(hs=1) → 双方各发 hsync_mids（本机持有的该对端 mid 清单）
     *             → 各自算差集 → hsync_pull 请求缺失项
     *             → 对端补发：文本走 hsync_msg（方向按对端视角翻转）；
     *               文件沿用 file_meta/chunks/file_done 管线按**原 mid** 重发
     *             → 接收端按 mid 查重落库（历史/待发/气泡三处共用同一判据）
     *
     * 这样无论消息是「发了没送到」还是「一端清空/重装丢了」，下次连上自动补齐；
     * 重放/重发也不会产生重复气泡。只在双方 hello 广播 hs=1 时启用（老端不认识这些帧，
     * 走 default 只记日志，天然兼容）。
     */
    void startHsync(Session s) {
        if (!s.handshaked || s.closed || L.store == null) {
            return;
        }
        List<String> mids = new ArrayList<String>();
        for (Map<String, Object> r : L.store.loadHistory(s.remoteIp(), HSYNC_SCAN)) {
            if (!hsyncSyncable(r)) {
                continue;
            }
            String mid = Json.str(r, "mid", "");
            if (!mid.isEmpty()) {
                mids.add(mid);
            }
        }
        if (mids.isEmpty()) {
            return;
        }
        L.trySend(s, Protocol.hsyncMids(mids));
    }

    void onHsyncMids(Session s, Map<String, Object> j) {
        if (L.store == null) {
            return;
        }
        java.util.Set<String> mine = new java.util.HashSet<String>();
        for (Map<String, Object> r : L.store.loadHistory(s.remoteIp(), HSYNC_SCAN)) {
            String mid = Json.str(r, "mid", "");
            if (!mid.isEmpty() && !Json.bool(r, "isDir", false)) {
                mine.add(mid);
            }
        }
        Object arr = j.get("mids");
        List<String> missing = new ArrayList<String>();
        if (arr instanceof List) {
            for (Object o : (List<?>) arr) {
                String mid = String.valueOf(o);
                if (!mid.isEmpty() && !mine.contains(mid)) {
                    missing.add(mid);
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        L.log("历史同步：对端持有 " + missing.size() + "+ 条缺失（清单共 "
                + ((List<?>) arr).size() + " 条），请求补齐");
        if (missing.size() > HSYNC_PULL_MAX) {
            missing = missing.subList(0, HSYNC_PULL_MAX);
        }
        L.trySend(s, Protocol.hsyncPull(missing));
    }

    void onHsyncPull(Session s, Map<String, Object> j) {
        if (L.store == null) {
            return;
        }
        Map<String, Map<String, Object>> byMid = new java.util.HashMap<String, Map<String, Object>>();
        for (Map<String, Object> r : L.store.loadHistory(s.remoteIp(), HSYNC_SCAN)) {
            String mid = Json.str(r, "mid", "");
            if (!mid.isEmpty()) {
                byMid.put(mid, r);
            }
        }
        Object arr = j.get("mids");
        List<Map<String, Object>> texts = new ArrayList<Map<String, Object>>();
        int nText = 0;
        int nFile = 0;
        if (arr instanceof List) {
            for (Object o : (List<?>) arr) {
                Map<String, Object> r = byMid.get(String.valueOf(o));
                if (r == null) {
                    continue;
                }
                String kind = Json.str(r, "kind", Msg.KIND_TEXT);
                if (Msg.KIND_TEXT.equals(kind) && !Json.str(r, "text", "").isEmpty()) {
                    Map<String, Object> e = new java.util.LinkedHashMap<String, Object>();
                    // 方向按**对端视角**翻转：本端持有的 SELF（我发的）在对端应显示为收到
                    e.put("mid", Json.str(r, "mid", ""));
                    e.put("dir", "SELF".equals(Json.str(r, "dir", "PEER")) ? "PEER" : "SELF");
                    e.put("kind", "TEXT");
                    e.put("text", Json.str(r, "text", ""));
                    e.put("ts", Long.valueOf(Json.lng(r, "ts", System.currentTimeMillis())));
                    texts.add(e);
                    nText++;
                } else if (Msg.KIND_FILE.equals(kind) && !Json.bool(r, "isDir", false)
                        && ChatBubbles.isImageName(Json.str(r, "fileName", ""))) {
                    // v2.4 收窄：hsync 只补文本与图片，其余文件/文件夹绝不跨端补齐
                    long size = Json.lng(r, "fileSize", 0L);
                    if (size > 0 && size <= HSYNC_FILE_MAX && resendHistoryFile(s, r)) {
                        nFile++;
                    }
                }
            }
        }
        if (nText > 0) {
            L.trySend(s, Protocol.hsyncMsg(texts));
        }
        if (nText + nFile > 0) {
            L.log("历史同步：向对端补发 " + nText + " 条文本、" + nFile + " 个文件");
        }
    }

    void onHsyncMsg(Session s, Map<String, Object> j) {
        if (L.store == null) {
            return;
        }
        Object arr = j.get("items");
        if (!(arr instanceof List)) {
            return;
        }
        int added = 0;
        for (Object o : (List<?>) arr) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> e = (Map<String, Object>) o;
            String mid = Json.str(e, "mid", "");
            String text = Json.str(e, "text", "");
            if (mid.isEmpty() || text.isEmpty()) {
                continue;
            }
            // 统一 mid 查重：本机已有该条就不重复入列
            if (L.store.hasMid(s.remoteIp(), mid)) {
                continue;
            }
            Msg m = new Msg();
            m.mid = mid;
            m.peerIp = s.remoteIp();
            m.dir = "SELF".equals(Json.str(e, "dir", "PEER")) ? Msg.DIR_SELF : Msg.DIR_PEER;
            m.kind = Msg.KIND_TEXT;
            m.text = text;
            m.ts = Json.lng(e, "ts", System.currentTimeMillis());
            m.status = Msg.ST_OK;
            L.emit(m);
            added++;
        }
        if (added > 0) {
            Link.Listener l = L.listener;
            if (l != null) {
                l.onHsyncApplied(s.remoteIp(), added);
            }
            L.log("历史同步：本机补入 " + added + " 条文本");
        }
    }

    /**
     * 一条历史记录是否参与 hsync 对账/补齐：**只有文本和图片**（v2.4 收窄）。
     * 文件与文件夹绝不跨端补齐 —— 未完成的传输走待发队列 + 断点续传，已完成的与消息状态无关。
     */
    static boolean hsyncSyncable(Map<String, Object> r) {
        String kind = Json.str(r, "kind", "");
        if (Msg.KIND_TEXT.equals(kind)) {
            return true;
        }
        return Msg.KIND_FILE.equals(kind) && !Json.bool(r, "isDir", false)
                && ChatBubbles.isImageName(Json.str(r, "fileName", ""));
    }

    // ==================== 消息删除同步（v2.4，仅双方 hd=1 时使用）====================

    /**
     * 找「能把 msg_del 送出去」的会话：该设备在线、握手完成、且对方支持删除同步（hd=1）。
     * 取不到返回 {@code null}（= 发不出去，调用方据此改提示语）。
     *
     * <p>先按 IP 精确找，找不到再**按组键回落** —— 同一台设备可能挂在同组的另一个地址上
     * （换网卡 / 开热点），删除通知要发给「那台设备」，不是发给某个字符串。
     */
    private Session delTarget(String peerIp) {
        String ip = peerIp == null ? "" : peerIp;
        Session s = L.sessionOf(ip);
        if (s == null || s.closed || !s.handshaked) {
            s = L.sessionByGroup(ip);
        }
        if (s == null || s.closed || !s.handshaked || !s.caps.hd) {
            return null;
        }
        return s;
    }

    /** 对端此刻能不能收到删除命令（在线 + hd=1）；给 UI 定提示语用，不发帧。 */
    public boolean canSendMsgDel(String peerIp) {
        return delTarget(peerIp) != null;
    }

    /**
     * 本机删除一条文本/图片后通知对端同步移除，并清掉本地的历史/待发/界面。
     *
     * <p>{@code syncable=false}（文件、文件夹、剪贴板、通话记录）时**不发 msg_del** ——
     * 协议 §4.7 只对文本/图片发；这些类型本来就不参与 hsync，不会被补回来，
     * 发过去反而会让对端删掉一条不该删的记录。老代码对所有 kind 都发，已对齐安卓端口径。
     *
     * <p>对端不在线或老版本（hd=0）时静默跳过发帧 —— 此时删除只影响本机，
     * 文本/图片下次连接会被 hsync 自动补回来（对端历史里还在，属预期行为）。
     */
    public void deleteMessage(String peerIp, String mid, boolean syncable) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        if (L.store != null) {
            L.store.removePending(mid);
            L.store.removeFromHistory(peerIp, mid);
        }
        Link.Listener l = L.listener;
        if (l != null) {
            l.onMessageRemoved(mid);
        }
        if (syncable) {
            Session s = delTarget(peerIp);
            if (s != null) {
                L.trySend(s, Protocol.msgDel(java.util.Collections.singletonList(mid)));
                L.log("已通知对端删除消息 mid=" + mid);
            }
        }
        L.log("已删除消息 mid=" + mid + (syncable ? "" : "（不同步类型，只删本机）"));
    }

    /** 单帧最多塞多少条 mid（清空时按此分片；msg_del 的 `mids[]` 没有协议上限，取个保守值） */
    static final int MSG_DEL_CHUNK = 200;

    /**
     * 批量删除（「清空当前会话」）：一次遍历清掉本地历史 + 待发 + 界面，
     * 并把其中**参与同步的部分**按 {@link #MSG_DEL_CHUNK} 条分片通知对端。
     *
     * <p>这是「清空」的正确做法（2026-09-21 用户要求）：**逐条执行删除命令**，
     * 而不是把历史文件整删 —— 只删本机的话，对端历史里那几条还在，
     * 它下次一连上就通过 hsync 把整段聊天补回来（用户报的正是这个）。
     *
     * <p>对端离线时无法通知，本机照清，返回 0（调用方据此改提示语）。
     *
     * @return 实际发出去的 mid 条数（0 = 没发，对端离线/老版本/没有可同步的条目）
     */
    public int deleteMessages(String peerIp, List<String> allMids, List<String> syncableMids) {
        if (L.store == null) {
            return 0;
        }
        if (allMids != null && !allMids.isEmpty()) {
            L.store.removePending(allMids);
            L.store.removeFromHistory(peerIp, allMids);
        }
        int sent = 0;
        if (syncableMids == null || syncableMids.isEmpty()) {
            return 0;
        }
        Session s = delTarget(peerIp);
        if (s == null) {
            L.log("清空：对端不在线或老版本（hd=0），" + syncableMids.size()
                    + " 条文本/图片只清了本机，下次连接可能被 hsync 补回");
            return 0;
        }
        for (int i = 0; i < syncableMids.size(); i += MSG_DEL_CHUNK) {
            List<String> chunk = syncableMids.subList(i,
                    Math.min(i + MSG_DEL_CHUNK, syncableMids.size()));
            // trySend 自己吞异常并落日志（发送失败只记不抛），这里按「已发出」计数足矣
            L.trySend(s, Protocol.msgDel(new ArrayList<String>(chunk)));
            sent += chunk.size();
        }
        L.log("清空：已逐条通知对端删除 " + sent + " 条（文本/图片），本机共清 "
                + (allMids == null ? 0 : allMids.size()) + " 条");
        return sent;
    }

    /**
     * 对端删除了若干文本/图片：本端把界面气泡、历史、待发三处一起移除。
     * 只处理本地，**不再回传** msg_del —— 无回环。对未知 mid 幂等（nothing to do）。
     */
    void onRemoteMsgDel(Session s, Map<String, Object> j) {
        if (L.store == null) {
            return;
        }
        Object arr = j.get("mids");
        int n = 0;
        if (arr instanceof List) {
            for (Object o : (List<?>) arr) {
                String mid = String.valueOf(o);
                if (mid.isEmpty()) {
                    continue;
                }
                L.store.removePending(mid);
                L.store.removeFromHistory(s.remoteIp(), mid);
                Link.Listener l = L.listener;
                if (l != null) {
                    l.onMessageRemoved(mid);
                }
                n++;
            }
        }
        if (n > 0) {
            L.log("对端删除了 " + n + " 条消息，本端同步移除");
        }
    }

    /**
     * 按原 mid 重发一条历史文件消息（hsync 补齐用）。
     * 沿用 doSendFile 管线：断点续传、sha256 校验、待发兜底全部复用；
     * 接收端凭 file_meta 里的原 mid 生成气泡 —— 内容与 mid 双双对齐。
     */
    boolean resendHistoryFile(Session s, Map<String, Object> r) {
        String mid = Json.str(r, "mid", "");
        String name = Json.str(r, "fileName", "");
        if (mid.isEmpty() || L.inFlight.contains(mid)) {
            return false;
        }
        File f = new File(Json.str(r, "filePath", ""));
        if (!f.exists() && !name.isEmpty()) {
            // 收到的文件按落盘目录兜底（历史里的 filePath 可能是展示文案）
            File alt = new File(L.store.receivedDir().toFile(), name);
            if (alt.exists()) {
                f = alt;
            }
        }
        if (!f.exists() || f.length() <= 0) {
            return false;
        }
        Msg item = new Msg();
        item.mid = mid;
        item.peerIp = s.remoteIp();
        item.dir = Msg.DIR_SELF;
        item.kind = Msg.KIND_FILE;
        item.fileName = name.isEmpty() ? f.getName() : name;
        item.fileSize = f.length();
        item.filePath = f.getAbsolutePath();
        item.progress = 0;
        item.status = Msg.ST_SENDING;
        // 与其它文件发送共用同一条**串行队列**（2026-09-21 排队发送）：hsync 补图也不许与在途传输并行。
        // 入队即返回 true（= 已受理）；真正的失败在队列线程里记日志并清掉待发兜底。
        final Msg fitem = item;
        final File ff = f;
        L.enqueueFileSend("历史补齐 " + item.fileName, () -> {
            try {
                // 气泡本端本来就有（历史里那条），进度更新按 mid 合并；成功/失败都留在原状态
                L.send.doSendFile(ff, fitem, "", "", null, false);
            } catch (Throwable t) {
                L.log("历史同步：文件 " + fitem.fileName + " 重发失败(" + t.getMessage() + ")");
                L.store.removePending(mid);
            }
        });
        return true;
    }
}
