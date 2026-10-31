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
// 文件/文件夹发送管线（v2.7 拆分自 Link.java）：发送、断点续传协商、
// 文件夹核对补发、md5 端到端核对。SourceException 转顶层包私有类。
// ==================================================================

final class LinkSend {

    final Link L;

    LinkSend(Link L) {
        this.L = L;
    }

    /**
     * 发文件（v2.7 起先过数据流量确认闸门）。
     *
     * 流程：出气泡(PENDING) → 若对端支持 `dm` 则发 data_ask 等它回 data_ans
     *（对端在数据网络下会先问它自己的用户）→ 同意才开传；
     * 对端拒绝 → 气泡留在聊天里置 DENIED（末尾红色叹号，可点重试）。
     *
     * @param preGranted true = 调用方已统一确认过（批量分享），不再重复询问
     */
    public void sendFile(File f, boolean preGranted) {
        Msg m = new Msg();
        m.mid = L.newMid();
        m.peerIp = L.selectedIp();
        m.dir = Msg.DIR_SELF;
        m.kind = Msg.KIND_FILE;
        m.fileName = f.getName();
        m.fileSize = f.length();
        m.filePath = f.getAbsolutePath();
        m.progress = 0;
        Session s = L.sessionByGroup(L.selectedIp());
        // 2.12 / D16：投票为 1（通话中 / 没网络 / 被拒）⇒ 一律**不入网**，改走既有的待发台账排队，
        //   拦截解除（全 0）那一刻由 setFileVote 自动触发一次续发。拦在**入口**而不是写者里
        //   （写者里拦＝字节已入队，等于没拦）。
        if (s == null || L.fileGateBlocked()) {
            m.status = Msg.ST_PENDING;
            L.emit(m);
            if (L.store != null) {
                L.store.addPending(m);
            }
            if (s == null) {
                L.setFileVote(Link.VOTE_NO_NETWORK, true);
                L.log("设备 " + (L.selectedIp().isEmpty() ? "（未选择）" : L.selectedIp())
                        + " 未连接，文件 " + m.fileName + " 进入待发队列");
            } else {
                L.log("[文件排他] 通话期不入网：文件 " + m.fileName + " 排队，结束后自动续发（D16）");
            }
            return;
        }
        m.peerIp = s.remoteIp();
        m.status = Msg.ST_PENDING;
        L.emit(m);
        final Msg item = m;
        final File file = f;
        // ⚠ 整段（确认 + 传输）进**串行队列**（2026-09-21 排队发送）：一次只传一个文件，
        //   前一个完成立刻进入下一个 —— 批量拖入几十个文件时按入队顺序逐个传，
        //   不再几十条线程同时抢写锁、一有异常各自关会话。
        L.enqueueFileSend("发送文件 " + item.fileName, () -> {
            int consent = preGranted ? DataConsent.OK
                    : L.consent.ensureDataConsent(item.peerIp, item.mid, DataConsent.KIND_FILE,
                    "发送文件", item.fileName, item.fileSize);
            if (consent != DataConsent.OK) {
                // NO_SESSION 归票 ②（握手完成自动清），只有真的"拒绝"才投票 ③
                denyFileByData(item, consent == DataConsent.REMOTE_REFUSED
                        ? DataConsent.peerRefusedText(DataConsent.KIND_FILE)
                        : "未同意使用数据流量", consent != DataConsent.NO_SESSION);
                return;
            }
            item.status = Msg.ST_SENDING;
            L.emit(item);
            doSendFile(file, item, "", "", null, false);
        });
    }


    /**
     * 把一条文件消息置为「数据流量未同意」：气泡留在聊天里，末尾显示红色叹号（可点重试）。
     * 备注写进 `text` —— 文件消息的 text 从不参与协议（只有 TEXT/图片会 hsync）。
     */
    void denyFileByData(Msg m, String note) {
        denyFileByData(m, note, true);
    }

    /**
     * @param voteDenied 2.12 / D16：这一条要不要替"被拒绝"那一票投 1（全局闸住文件管线）。
     *   只有**数据流量确认被拒**（含对端拒、本端拒）才投；"设备未连接"归票 ②（握手完成自动清）、
     *   "文件夹重试要重新选目录"归 UI 限制，两者都不该把整条管线闸到下一次手动重试为止。
     */
    void denyFileByData(Msg m, String note, boolean voteDenied) {
        if (L.store != null && m.mid != null && !m.mid.isEmpty()) {
            L.store.removePending(m.mid);
        }
        m.status = Msg.ST_DENIED;
        m.progress = -1;
        m.text = note;
        L.emit(m);
        L.log("文件「" + m.fileName + "」未发送：" + note);
        if (voteDenied) {
            // 2.12 / D16（你点的 ⑬-2"全局暂停，手动重试时清 0"）：被拒 ⇒ 这一票投 1，
            //   于是**整条文件管线**都闸住（新条目排队、在传的暂停），清零点 = 手动重试那一条。
            //   ⚠ 不改 DENIED 本体：条目仍然**移出**待发队列、仍然要人手动重试
            //     ⇒ resendPendingFile 那条"不会反复骚扰"的既有注释依旧成立（被拒的条目不在待发队列里，
            //     自动续发与解除拦截都不会重问它）。
            L.setFileVote(Link.VOTE_CONSENT_DENIED, true);
        }
    }

    /**
     * 待发队列的自动补发（{@link #flushPending} 甩到 daemon 专用线程跑）。
     * 与手动重试走**同一道数据流量闸门**：对端支持 dm 且在数据网络时会收到 data_ask 弹窗；
     * 拒绝 → 条目移出待发队列、气泡置 DENIED（红色叹号可再手动重试），不会反复骚扰；
     * 会话已断（NO_SESSION）→ 条目留在待发队列，下次握手再试。
     */
    void resendPendingFile(String peerIp, Msg p, File path,
                                   List<File> files, List<String> emptyDirs, int skipped) {
        if (skipped > 0) {
            L.log("注意：目录过深/过大或部分子树读取失败，" + skipped + " 个分支未包含在补发中");
        }
        int r = L.consent.ensureDataConsent(peerIp, p.mid, DataConsent.KIND_FILE,
                p.isDir ? "发送文件夹" : "发送文件", p.fileName, p.fileSize);
        if (r != DataConsent.OK) {
            if (r == DataConsent.REMOTE_REFUSED) {
                denyFileByData(p, DataConsent.peerRefusedText(DataConsent.KIND_FILE));
            } else if (r == DataConsent.LOCAL_REFUSED) {
                denyFileByData(p, "你拒绝了在数据流量下发送");
            }
            // NO_SESSION：条目留在待发队列等下次连接
            return;
        }
        p.status = Msg.ST_SENDING;
        p.progress = 0;
        if (p.isDir) {
            doSendFolder(path, p, files, emptyDirs, skipped);
        } else {
            doSendFile(path, p, "", "", null, false);
        }
    }

    /**
     * 重试一条「数据流量未同意」的文件（气泡末尾的红色叹号触发）。
     * 重新完整走一遍确认流程；同意 → 通知界面把这条挪到最新位置，然后开传；
     * 再次拒绝 → 保持 DENIED（与第一次拒绝后的表现完全一致）。
     */
    public void retryFileSend(Msg m) {
        if (m == null || m.mid == null || m.mid.isEmpty()) {
            return;
        }
        // 2.12 / D16（你点的 ⑬-2）：手动重试就是"被拒"这一票的清零点。
        // 放在入口而不是成功后 ⇒ 重试若再次被拒，denyFileByData 会重新投 1（与今天"每次拒绝都回 DENIED"一致）。
        L.setFileVote(Link.VOTE_CONSENT_DENIED, false);
        String ip = (m.peerIp == null || m.peerIp.isEmpty()) ? L.selectedIp() : m.peerIp;
        Session s = L.sessionByGroup(ip);
        if (s == null) {
            L.setFileVote(Link.VOTE_NO_NETWORK, true);   // 归票 ②：握手完成自动清，不靠下一次手动重试
            denyFileByData(m, "设备未连接，点设备重连后再试", false);
            return;
        }
        final Msg item = m;
        final Session fs = s;
        // 重试同样进串行队列：不许与在途传输并行（一次只有一个文件在传）
        L.enqueueFileSend("重试发送 " + item.fileName, () -> {
            int consent = L.consent.ensureDataConsent(fs.remoteIp(), item.mid, DataConsent.KIND_FILE,
                    item.isDir ? "发送文件夹" : "发送文件", item.fileName, item.fileSize);
            if (consent != DataConsent.OK) {
                // NO_SESSION 归票 ②（握手完成自动清），只有真的"拒绝"才投票 ③
                denyFileByData(item, consent == DataConsent.REMOTE_REFUSED
                        ? DataConsent.peerRefusedText(DataConsent.KIND_FILE)
                        : (consent == DataConsent.NO_SESSION ? "设备未连接" : L.LOCAL_DENY_TEXT),
                        consent != DataConsent.NO_SESSION);
                return;
            }
            Link.Listener l = L.listener;
            if (l != null) {
                l.onMessageMoved(item.mid);
            }
            item.peerIp = fs.remoteIp();
            item.status = Msg.ST_SENDING;
            item.progress = 0;
            if (L.store != null) {
                L.store.removePending(item.mid);
            }
            L.emit(item);
            File f = new File(item.filePath);
            if (item.isDir) {
                L.log("重试发送文件夹请重新选择目录（目录树需从 SAF/本地重新枚举）：" + item.fileName);
                denyFileByData(item, "请重新选择该文件夹发送", false);
                return;
            }
            doSendFile(f, item, "", "", null, false);
        });
    }

    static boolean wireListFits(List<String> relPaths) {
        long n = 0;
        for (String p : relPaths) {
            n += p.length() + 4;
            if (n > Link.DIR_LIST_MAX_CHARS) {
                return false;
            }
        }
        return true;
    }

    void doSendFolder(File dir, Msg item, List<File> files, List<String> emptyDirs) {
        doSendFolder(dir, item, files, emptyDirs, 0);
    }

    void doSendFolder(File dir, Msg item, List<File> files, List<String> emptyDirs, int skipped) {
        // 开传前先入待发队列（防强杀）：整目录发送成功后才移除。
        // 注意 L.dirSendState 是内存态，重启后为空 —— 成员凭各自半成品（partials）续传，
        // 已完整送达的成员会整文件重传一遍（对端生成重复副本，可手动删，不影响正确性）。
        if (L.store != null) {
            L.store.addPending(item);
        }
        if (!L.inFlight.add(item.mid)) {
            return;
        }
        L.inFlightPeer.put(item.mid, item.peerIp);
        try {
            doSendFolderInner(dir, item, files, emptyDirs, skipped);
        } finally {
            L.inFlight.remove(item.mid);
            L.inFlightPeer.remove(item.mid);
            L.dirMissingBox.remove(item.mid);
        }
    }

    /**
     * 发送一个文件夹（v2.6 起带完整性闭环）。
     *
     * 流程：{@code dir_meta}（带成员清单）→ 逐成员发送 → {@code dir_end} 收尾 → 等对端核对回执
     * （{@code dir_missing}）→ 有缺失就**只补发缺失项**（最多 {@link #L.DIR_RESEND_ROUNDS} 轮）→
     * 仍有缺件则如实标失败并把缺的文件名列进日志。
     *
     * 为什么必须这么改（2026-09-16 用户报「传文件夹会漏掉部分文件」）：
     *  - 老流程发送端只要 {@code file_done} 写进 socket 就当成功，**完全不等对端确认**。
     *    对端建文件失败 / 落盘失败 / 校验失败时只是本地记一行日志，发送端照样报
     *    「文件夹发送完成」→ 用户永远不知道少了哪些。
     *  - 成员失败即中止整目录：一个读不了的文件就能让**后面所有成员**一个都发不出去。
     *    现在本地源不可读只跳过该成员并继续，最后统一汇报。
     */
    void doSendFolderInner(File dir, Msg item, List<File> files, List<String> emptyDirs,
                                   int skipped) {
        // 目的设备在创建这条消息时就定死了（item.peerIp），切换会话不会改变归属
        Session s = L.sessionOf(item.peerIp);
        if (s == null) {
            L.setFileVote(Link.VOTE_NO_NETWORK, true);   // 2.12："原本没网络"那一票（文件夹同一条口径）
            if (L.store != null) {
                L.store.addPending(item);
            }
            return;
        }
        if (L.cancelled.contains(item.mid)) {
            L.cancelled.remove(item.mid);
            return;
        }
        // 只有对端也支持（hello dl=1）才启用清单/收尾/核对；老端保持原行为，不引入未知帧
        boolean verifyPeer = s.caps.dl();
        List<String> relPaths = new ArrayList<String>();
        for (File f : files) {
            relPaths.add(LinkNames.relPathOf(dir, f));
        }
        List<String> wireList = (verifyPeer && !relPaths.isEmpty() && wireListFits(relPaths))
                ? relPaths : new ArrayList<String>();
        if (verifyPeer && wireList.isEmpty() && !relPaths.isEmpty()) {
            L.log("成员清单过长（" + relPaths.size() + " 项），本次改用「按数量核对」完整性");
        }
        try {
            s.send(Protocol.dirMeta(item.mid, item.fileName, files.size(), item.fileSize,
                    emptyDirs, wireList, skipped));
            Link.DirProgress agg = L.new DirProgress(item, item.fileSize);
            ConcurrentHashMap<String, Boolean> state =
                    L.dirSendState.computeIfAbsent(item.mid, k -> new ConcurrentHashMap<String, Boolean>());
            // 本地不可读而跳过的成员（本地问题，补发也无用 —— 最后如实汇报）
            List<String> unreadable = new ArrayList<String>();
            List<File> pendingSet = files;
            int round = 0;
            List<String> peerMissing = new ArrayList<String>();
            boolean peerAllOk = true;
            while (true) {
                for (File f : pendingSet) {
                    if (L.cancelled.contains(item.mid)) {
                        L.cancelled.remove(item.mid);
                        // 防竞态残留：cancelFile 已 remove 后，成员完成钩子 getOrPut 可能重建 map
                        L.dirSendState.remove(item.mid);
                        L.log("文件夹发送已取消 " + item.fileName);
                        return;
                    }
                    if (L.fileGateBlocked()) {
                        // 2.12 / D16：文件夹在**成员边界**暂停（整目录条目本来就已入待发台账，
                        // dirSendState 里"已完成的成员"不清 ⇒ 续发时跳过它们，只补没发完的）
                        pauseInFlight(item, "", null);
                        return;
                    }
                    String rel = LinkNames.relPathOf(dir, f);
                    if (Boolean.TRUE.equals(state.get(rel))) {
                        // 重发时跳过已完成的成员（进度也要补上）
                        agg.add(Math.max(0L, f.length()));
                        continue;
                    }
                    Msg member = new Msg();
                    member.mid = L.newMid();
                    member.peerIp = item.peerIp;
                    member.dir = Msg.DIR_SELF;
                    member.kind = Msg.KIND_FILE;
                    member.fileName = f.getName();
                    member.fileSize = f.length();
                    member.filePath = f.getAbsolutePath();
                    member.progress = 0;
                    try {
                        doSendFile(f, member, rel, item.mid, agg, true);
                    } catch (SourceException se) {
                        // 本地读不了（文件被删 / 被占用 / 无权限）：
                        // **只跳过这一个成员**继续发其余 —— 老逻辑会让整目录就此停摆
                        unreadable.add(rel);
                        L.log("成员本地不可读，跳过继续：" + rel + "（" + se.getMessage() + "）");
                    }
                }
                if (!verifyPeer) {
                    break;
                }
                // 收尾：明确告诉对端「本轮发完了」。最后一轮 last=1 —— 对端无论缺不缺都定稿
                boolean last = round >= L.DIR_RESEND_ROUNDS;
                ArrayBlockingQueue<java.util.List<String>> box =
                        new ArrayBlockingQueue<java.util.List<String>>(1);
                L.dirMissingBox.put(item.mid, box);
                s.send(Protocol.dirEnd(item.mid, pendingSet.size(), last));
                long waitMs = wireList.isEmpty() ? L.DIR_MISSING_WAIT_SHORT_MS : L.DIR_MISSING_WAIT_MS;
                java.util.List<String> resp;
                try {
                    resp = box.poll(waitMs, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ie) {
                    resp = null;
                }
                if (resp == null) {
                    L.log("对端未在 " + (waitMs / 1000) + "s 内回报文件夹核对结果，按已发送处理");
                    peerAllOk = unreadable.isEmpty();
                    break;
                }
                if (resp.isEmpty()) {
                    break;   // 空清单 = 对端确认收齐
                }
                peerAllOk = false;
                peerMissing = resp;
                if (last) {
                    break;
                }
                java.util.Set<String> want = new java.util.HashSet<String>(resp);
                List<File> next = new ArrayList<File>();
                for (File f : files) {
                    if (want.contains(LinkNames.relPathOf(dir, f))) {
                        next.add(f);
                    }
                }
                if (next.isEmpty()) {
                    break;
                }
                round++;
                L.log("对端报告 " + resp.size() + " 个成员未到，第 " + round + " 轮补发");
                pendingSet = next;
            }
            if (L.cancelled.contains(item.mid)) {
                L.cancelled.remove(item.mid);
                L.log("文件夹发送已取消 " + item.fileName);
                return;
            }
            // 定稿：**任何一个成员没送达都要如实标失败并列出**，绝不无条件报「发送完成」
            List<String> bad = new ArrayList<String>();
            for (String p : unreadable) {
                if (!bad.contains(p)) {
                    bad.add(p);
                }
            }
            for (String p : peerMissing) {
                if (!bad.contains(p)) {
                    bad.add(p);
                }
            }
            Msg u = new Msg();
            u.mid = item.mid;
            u.peerIp = item.peerIp;   // 归属（2026-09-21）：状态帧也要带，否则界面归属不明会画进当前会话
            u.dir = Msg.DIR_SELF;
            u.kind = Msg.KIND_FILE;
            u.fileName = item.fileName;
            u.fileSize = item.fileSize;
            u.isDir = true;
            u.dirCount = item.dirCount;
            if (bad.isEmpty() && peerAllOk) {
                u.progress = 100;
                u.status = Msg.ST_OK;
                // 整目录收尾：丢掉速率采样器（气泡按「样本过期」自动不显示速率）
                Speeds.end(item.mid);
                if (L.listener != null) {
                    L.listener.onMessageUpdated(u);
                }
                L.log("文件夹发送完成 " + item.fileName + "（" + files.size() + " 个文件）");
            } else {
                u.status = Msg.ST_FAILED;
                if (L.listener != null) {
                    L.listener.onMessageUpdated(u);
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.min(20, bad.size()); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(bad.get(i));
                }
                L.log("文件夹发送不完整 " + item.fileName + "：应有 " + files.size()
                        + " 个，未送达 " + bad.size() + " 个 → " + sb
                        + (bad.size() > 20 ? " …（略）" : "")
                        + (unreadable.isEmpty() ? ""
                        : "（其中 " + unreadable.size() + " 个本地不可读，请检查文件是否已被删除/占用）"));
            }
            if (L.store != null) {
                L.store.removePending(item.mid);
            }
            // 整目录收尾：同步丢掉本 mid 的发送状态表（防 dirSendState 无界增长，2026-09-28）
            L.dirSendState.remove(item.mid);
        } catch (Throwable t) {
            if (L.cancelled.contains(item.mid)) {
                L.cancelled.remove(item.mid);
                L.log("文件夹发送已取消 " + item.fileName);
                return;
            }
            // 失败收尾：同样丢掉状态表（重发走待发队列整目录补发，不依赖 entry 残留）
            L.dirSendState.remove(item.mid);
            L.log("文件夹发送失败: " + t);
            Msg u = new Msg();
            u.mid = item.mid;
            u.peerIp = item.peerIp;   // 归属（2026-09-21）：状态帧也要带，否则界面归属不明会画进当前会话
            u.dir = Msg.DIR_SELF;
            u.isDir = true;
            u.status = Msg.ST_FAILED;
            if (L.listener != null) {
                L.listener.onMessageUpdated(u);
            }
        } finally {
            // 整目录速率采样器统一收尾（2026-09-29）：覆盖 while 内取消 / 循环后取消 /
            // 失败定稿 / catch 失败；成功定稿那次 end 保留，重入幂等。
            Speeds.end(item.mid);
        }
    }

    /**
     * 断点续传协商：一问（file_query）一答（file_resume），3s 等不到就算无应答。
     * 返回值口径（2.12 起）：<b>&gt;0</b> = 对端已有这么多字节（按 CHUNK_SIZE 对齐后从这儿续）；
     * <b>0</b> = 对端答了"没有半成品"（从头发）；<b>-1</b> = **没发出问题或没收到应答**
     *（⇒ 调用方保持暂停，不许退化成从头重发 —— 你点的"没收到就暂停，手动点继续才重试"）。
     */
    long negotiateResume(Session s, String mid, String name, long size) {
        ArrayBlockingQueue<Long> q = new ArrayBlockingQueue<Long>(1);
        L.resumeWaiters.put(mid, q);
        try {
            if (!L.sendOrDrop(s, Protocol.fileQuery(mid, name, size), "file_query", true)) {
                return -1L;
            }
            Long off = q.poll(3000, TimeUnit.MILLISECONDS);
            if (off == null) {
                return -1L;
            }
            if (off <= 0) {
                return 0L;
            }
            long v = off.longValue();
            return (v == size) ? v : v - (v % Protocol.CHUNK_SIZE);
        } catch (Throwable t) {
            return -1L;
        } finally {
            L.resumeWaiters.remove(mid);
        }
    }

    /**
     * 2.12 / D16：把一条**在传的**条目就地暂停 —— 结束本次尝试（不抛错、不进 cancelled、
     * 不把已入队的字节劈开），状态转 PAUSED 并留在/登入待发台账。
     * 拦截解除（投票全 0）那一刻 {@code setFileVote} 自动触发一次续发：
     * {@code flushPending → resendPendingFile → doSendFile} 顶部的 {@code negotiateResume}
     * 就是那**一次**续传请求 —— 对方应答就按回传偏移续，不应答就又回到本方法（停在 PAUSED）。
     * ⚠ 文件夹成员（agg != null）不单独入台账、也不单独置 PAUSED：UI 上那是整目录的一行，
     *   由 doSendFolder 的成员边界那条暂停负责，成员跟着本次尝试一起结束即可。
     */
    void pauseInFlight(Msg item, String dirId, Link.DirProgress agg) {
        if (agg == null && (dirId == null || dirId.isEmpty())) {
            if (L.store != null) {
                L.store.addPending(item);   // 按 mid 幂等（Store.java:1883 起：已存在就直接返回）
            }
            item.status = Msg.ST_PAUSED;
            L.emit(item);
        }
        L.log("[文件排他] 已暂停：" + item.fileName
                + "（投票=1，D16）⇒ 解除拦截后自动续发一次，没应答就停在这儿等手动「继续」");
    }

    /**
     * 读源文件前 offset 字节只做摘要（续传时补齐整文件 sha256 的前缀段），不发送。
     * 读失败返回 false，调用方改从头传。
     */
    static boolean digestPrefix(InputStream ins, long len, MessageDigest digest) {
        try {
            byte[] buf = new byte[1 << 16];
            long left = len;
            while (left > 0) {
                int n = ins.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) {
                    return false;
                }
                digest.update(buf, 0, n);
                left -= n;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    void doSendFile(File f, Msg item, String relPath, String dirId,
                            Link.DirProgress agg, boolean throwOnFail) {
        // 只有「普通单文件」由这里管待发队列；文件夹整体由 doSendFolder 管，成员不管
        if ((dirId == null || dirId.isEmpty()) && agg == null && L.store != null) {
            L.store.addPending(item);
        }
        if (!L.inFlight.add(item.mid)) {
            return;  // 已有同 mid 在途（握手补发撞上在途传输），让在途的那个继续
        }
        L.inFlightPeer.put(item.mid, item.peerIp);
        try {
            doSendFileInner(f, item, relPath, dirId, agg, throwOnFail);
        } finally {
            L.inFlight.remove(item.mid);
            L.inFlightPeer.remove(item.mid);
            L.sendLastAt.remove(item.mid);
            // 速率采样器统一收尾（2026-09-29）：一处覆盖 取消/失败/成功（含文件夹成员），
            // 成功路径里那次 end 只是提前收尾，这里重入幂等。
            Speeds.end(item.mid);
        }
    }

    void doSendFileInner(File f, Msg item, String relPath, String dirId,
                                 Link.DirProgress agg, boolean throwOnFail) {
        // 目的设备在创建这条消息时就定死了（item.peerIp），切换会话不会改变归属
        Session s = L.sessionOf(item.peerIp);
        if (s == null) {
            if (throwOnFail) {
                throw new IllegalStateException("未连接");
            }
            L.setFileVote(Link.VOTE_NO_NETWORK, true);   // 2.12：这就是"原本没网络"那一票
            if (L.store != null) {
                L.store.addPending(item);
            }
            return;
        }
        if (L.isCancelled(item.mid, dirId)) {
            L.cancelled.remove(item.mid);
            return;
        }
        try {
            // ① 断点续传协商：对端有半成品就从偏移处继续
            long offset = 0L;
            if (item.fileSize > 0) {
                offset = negotiateResume(s, item.mid, item.fileName, item.fileSize);
                if (offset < 0) {
                    // 2.12 / D16：这是"解除拦截后自动发的那**一次**续传请求"没得到应答
                    // ⇒ 不从头重发、保持暂停，等气泡上的"继续"或下次握手（你点的"没收到就暂停，手动才重试"）
                    pauseInFlight(item, dirId, agg);
                    return;
                }
                if (offset > 0) {
                    L.log("断点续传 " + item.fileName + "：对端已有 " + offset + " B，校验后续传");
                }
            }
            // 整文件 SHA-256 只服务「续传后复核」（设计口径）：一次性传完不再对每个字节做摘要。
            // offset 只在续传协商命中时才 > 0。
            MessageDigest digest = (offset > 0) ? MessageDigest.getInstance("SHA-256") : null;
            FileInputStream fis = new FileInputStream(f);
            InputStream in = new BufferedInputStream(fis);
            if (offset > 0) {
                // ② 前缀摘要（顺带把流推进到 offset，之后直接续着读，不用 reopen）
                if (!digestPrefix(in, offset, digest)) {
                    offset = 0L;
                    digest.reset();
                    L.log("前缀读取失败，改从头发送 " + item.fileName);
                    in.close();
                    fis = openSource(f);
                    in = new BufferedInputStream(fis);
                }
            }
            try {
                // ③ 双 4MB 缓冲交替读源（后台线程读盘，与网络发送重叠），1MB 二进制分片发出
                s.send(Protocol.fileMeta(item.mid, item.fileName, item.fileSize,
                        "application/octet-stream", "", offset, relPath, dirId));
                Link.BlockReader reader = new Link.BlockReader(in, Protocol.RX_BUF_SIZE);
                try {
                    int seq = (int) (offset / Protocol.CHUNK_SIZE);
                    long sent = offset;
                    int lastPct = -1;
                    while (true) {
                        if (L.isCancelled(item.mid, dirId)) {
                            L.cancelled.remove(item.mid);
                            L.log("文件发送已取消 " + item.fileName + "（已发 " + sent + " B）");
                            return;
                        }
                        if (L.fileGateBlocked()) {
                            // 2.12 / D16：在传的遇到投票=1 ⇒ 在**下一个分片边界**就地结束本次尝试
                            //   （不在已入队的字节中间动手 ⇒ 线上一条帧都不会被劈开）
                            pauseInFlight(item, dirId, agg);
                            return;
                        }
                        Link.BlockReader.Next e = reader.next();
                        if (n(e) == -1) {
                            break;
                        }
                        if (n(e) == -2 || e.buf == null) {
                            // 读源中途失败（磁盘错误 / 文件被截断 / 被删）同样是**本地**问题：
                            // 抛 SourceException，绝不能当网络故障去关会话（与 openSource 同语义）
                            throw new SourceException("读取源文件失败");
                        }
                        int off = 0;
                        while (off < n(e)) {
                            if (L.isCancelled(item.mid, dirId)) {
                                L.cancelled.remove(item.mid);
                                L.log("文件发送已取消 " + item.fileName + "（已发 " + sent + " B）");
                                return;
                            }
                            int len = Math.min(Protocol.CHUNK_SIZE, n(e) - off);
                            s.sendChunk(item.mid, seq, e.buf, off, len);
                            if (digest != null) {
                                digest.update(e.buf, off, len);
                            }
                            seq++;
                            off += len;
                            sent += len;
                            // 发送停滞看门狗（v2.8）：每写出一块刷新「最近进度时刻」
                            L.sendLastAt.put(item.mid, System.currentTimeMillis());
                            // 速率采样（字节级，2026-09-22）：进度是整数百分比，大文件太粗 → 气泡速率靠这里
                            Speeds.sample(item.mid, sent);
                            if (agg != null) {
                                agg.add(len);
                            } else if (item.fileSize > 0) {
                                int pct = (int) ((sent * 100) / item.fileSize);
                                if (pct != lastPct) {
                                    lastPct = pct;
                                    Msg u = new Msg();
                                    u.mid = item.mid;
                                    u.peerIp = item.peerIp;   // 归属（2026-09-21）：状态帧也要带，否则界面归属不明会画进当前会话
                                    u.dir = Msg.DIR_SELF;
                                    u.kind = Msg.KIND_FILE;
                                    u.fileName = item.fileName;
                                    u.fileSize = item.fileSize;
                                    u.progress = pct;
                                    u.status = Msg.ST_SENDING;
                                    if (L.listener != null) {
                                        L.listener.onMessageUpdated(u);
                                    }
                                }
                            }
                        }
                        // 这块发完了：**必须归还**给 free 队列，读线程才能继续填下一块。
                        // 只有 2 块缓冲，不还的话发完 2×4MB 后读线程永远阻塞在 free.take()，
                        // 消费端卡死在 ready.take() —— 大文件必然卡住（2026-09-13 实测踩坑）。
                        reader.recycle(e.buf);
                    }
                } finally {
                    reader.cancel();
                }
            } finally {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
            if (L.isCancelled(item.mid, dirId)) {
                L.cancelled.remove(item.mid);
                L.log("文件发送已取消 " + item.fileName);
                return;
            }
            // 没开摘要（非续传）时发空串：接收端见空串即「对端未要求校验」，不做比对
            s.send(Protocol.fileDone(item.mid, digest == null ? "" : L.hex(digest.digest())));
            if (agg == null) {
                Msg u = new Msg();
                u.mid = item.mid;
                u.peerIp = item.peerIp;   // 归属（2026-09-21）：状态帧也要带，否则界面归属不明会画进当前会话
                u.dir = Msg.DIR_SELF;
                u.kind = Msg.KIND_FILE;
                u.fileName = item.fileName;
                u.fileSize = item.fileSize;
                u.progress = 100;
                u.status = Msg.ST_OK;
                // 传完即丢掉速率采样器（气泡里的数字按「样本过期」隐藏，这里只是别留垃圾）
                Speeds.end(item.mid);
                if (L.listener != null) {
                    L.listener.onMessageUpdated(u);
                }
            }
            if (dirId != null && !dirId.isEmpty()) {
                L.dirSendState.computeIfAbsent(dirId, k -> new ConcurrentHashMap<String, Boolean>())
                        .put(relPath, Boolean.TRUE);
            }
            L.log("文件发送完成 " + item.fileName + " " + sentTotal(item) );
            if ((dirId == null || dirId.isEmpty()) && agg == null) {
                // 发送成功，退出待发队列（此前一直在队列里兜底防强杀）
                if (L.store != null) {
                    L.store.removePending(item.mid);
                }
                // 触发过续传 → 等对端回发 MD5 做端到端核对（异步，不阻塞发送线程）
                if (offset > 0) {
                    L.rx.startVerifyWatch(item, offset);
                }
            }
        } catch (Throwable t) {
            L.cancelled.remove(item.mid);
            if (t instanceof SourceException) {
                // **本地**读不了源文件（被删 / 被占用 / 无权限）：链路本身是好的，
                // 因此绝不关连接（老逻辑在这里误杀连接，把后续所有传输一起带走），
                // 也不进待发队列（重试同样是失败，只会无限循环）。
                L.log("文件发送失败（本地源不可读）" + item.fileName + "：" + t.getMessage());
                // 对端可能已建好半成品：通知它删掉，免得留下残缺文件在「已接收」里
                L.trySend(s, Protocol.fileCancel(item.mid));
                if (throwOnFail) {
                    throw (SourceException) t;
                }
                // ⚠ 必须**出队**（2026-09-21）：doSendFile 入口已 addPending，不清掉的话
                //   每次握手补发都会重放这条读不了的文件；几十条积压时就是反复雪崩。
                if ((dirId == null || dirId.isEmpty()) && agg == null && L.store != null) {
                    L.store.removePending(item.mid);
                }
                Msg u = new Msg();
                u.mid = item.mid;
                u.peerIp = item.peerIp;   // 归属（2026-09-21）：状态帧也要带，否则界面归属不明会画进当前会话
                u.dir = Msg.DIR_SELF;
                u.status = Msg.ST_FAILED;
                if (L.listener != null) {
                    L.listener.onMessageUpdated(u);
                }
                return;
            }
            L.log("文件发送失败: " + t);
            s.close();
            L.sessions.remove(s);
            if (!s.peerId.isEmpty()) {
                L.byPeer.remove(s.peerId, s);
            }
            L.notifyPeers();
            L.refreshState();
            if (throwOnFail) {
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
                throw new RuntimeException(t);
            }
            Msg u = new Msg();
            u.mid = item.mid;
            u.peerIp = item.peerIp;   // 归属（2026-09-21）：状态帧也要带，否则界面归属不明会画进当前会话
            u.dir = Msg.DIR_SELF;
            u.status = Msg.ST_PENDING;
            if (L.listener != null) {
                L.listener.onMessageUpdated(u);
            }
            if (L.store != null) {
                L.store.addPending(item);
            }
        }
    }

    /**
     * 本地源文件不可读（被删 / 被占用 / 权限不足）。
     *
     * 与网络故障**严格区分**：本地问题不该关连接、不该让整个文件夹停摆、也不该无限重试。
     * 声明成 RuntimeException 是为了能在 doSendFileInner 里原样上抛 —— checked 异常会强制
     * 方法签名带 throws，牵动一大片调用点。
     */
static final class SourceException extends RuntimeException {
        SourceException(String msg) {
            super(msg);
        }

        SourceException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** 打开本地源文件；失败一律包成 SourceException（本地问题，不当成网络故障处理） */
    static FileInputStream openSource(File f) {
        try {
            return new FileInputStream(f);
        } catch (Throwable t) {
            throw new SourceException("打不开源文件: " + f + "（" + t.getMessage() + "）");
        }
    }

    static int n(Link.BlockReader.Next e) {
        return e.len;
    }

    static long sentTotal(Msg item) {
        return item.fileSize;
    }

}
