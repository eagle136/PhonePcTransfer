package com.phonepctransfer.pc;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
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

    /** 一次「字节已写出、等对端回报落盘」的登记：气泡快照 + 当时用的对端地址 + 登记时刻 */
    static final class AckWait {
        final Msg item;
        final String peerIp;
        final long at;

        AckWait(Msg item, String peerIp) {
            this.item = item;
            this.peerIp = peerIp;
            this.at = System.currentTimeMillis();
        }
    }

    /**
     * 已把全部分片和 `file_done` 写出、但**对端还没回报落盘结果**的普通单文件。
     * 为什么写完 file_done 不能就算送达：那一刻对端完全可能还在落盘、校验没过、或回 ack 前链路就断了，
     * 老写法当场置 OK 并出队 → 对端永远少这一份、本机气泡却报成功（漏传/假成功）。
     * 现在「置 OK + 出队」统一由对端的 `ack` 帧驱动（见 {@link #applyAck}）；等回执期间条目一直留在
     * 待发队列里，任何意外下次握手都会凭同一个 mid 问一遍对端「这条到没到」。
     */
    final ConcurrentHashMap<String, AckWait> awaitingAck = new ConcurrentHashMap<String, AckWait>();

    /**
     * 对端**提前回绝**（`ack(ok=false)` 在字节还没推完就到达）的 mid 记号：
     * 接收端在建接收通道失败时（存盘目录不可写/磁盘建不了文件）会在 `file_meta` 这一步立刻回 `ack false`，
     * 而 {@link #awaitingAck} 是写完 `file_done` 才登记的 —— 那一刻我方还在闷头推分片，回执没人认领，
     * 等推完再登记就永远等不到第二份 ack；有这个记号，收尾时就能按失败处理（气泡标红、条目留队下次重试）。
     */
    final Set<String> rejectedAck =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** 回执超时：只撤登记、不改气泡、不出队（条目留待发队列，下次握手 file_query 自愈） */
    static final long ACK_TIMEOUT_MS = 60_000L;

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
        // 发送前名校验：超 MAX_NAME_BYTES（200）/ 含 Linux-Windows 敏感字符 → 提示并取消本次发送（不建气泡）
        String nameErr = LinkNames.fileNameError(f.getName());
        if (nameErr != null) {
            L.notifyUser("【" + f.getName() + "】" + nameErr + "，已取消发送");
            return;
        }
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
        // 投票为 1（通话中 / 没网络 / 被拒）⇒ 一律**不入网**，改走既有的待发台账排队，
        //   拦截解除（全 0）那一刻由 setFileVote 自动触发一次续发。拦在**入口**而不是写者里
        //   （写者里拦＝字节已入队，等于没拦）。
        if (s == null || L.fileGateBlocked(m.peerIp)) {
            m.status = Msg.ST_PENDING;
            L.emit(m);
            if (L.store != null) {
                L.store.addPending(m);
            }
            if (s == null) {
                L.setFileVote(Link.VOTE_NO_NETWORK, true, m.peerIp);
                L.log("设备 " + (L.selectedIp().isEmpty() ? "（未选择）" : L.selectedIp())
                        + " 未连接，文件 " + m.fileName + " 进入待发队列");
            } else {
                L.log("[文件排他] " + L.gateReason(m.peerIp) + "：文件 " + m.fileName
                        + " 排队，恢复后自动续发（D16）");
            }
            return;
        }
        m.peerIp = s.remoteIp();
        m.status = Msg.ST_PENDING;
        L.emit(m);
        final Msg item = m;
        final File file = f;
        // ⚠ 整段（确认 + 传输）进**串行队列**：一次只传一个文件，
        //   前一个完成立刻进入下一个 —— 批量拖入几十个文件时按入队顺序逐个传，
        //   不再几十条线程同时抢写锁、一有异常各自关会话。
        L.enqueueFileSend("发送文件 " + item.fileName, () -> {
            int consent = preGranted ? DataConsent.OK
                    : L.consent.ensureDataConsent(item.peerIp, item.mid, DataConsent.KIND_FILE,
                    "发送文件", item.fileName, item.fileSize);
            if (consent != DataConsent.OK) {
                if (consent == DataConsent.REMOTE_TIMEOUT) {
                    pauseByDataTimeout(item, DataConsent.KIND_FILE);
                    return;
                }
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
     * 数据流量确认「超时未应答」：**不是拒绝**，不投拒收票、不闸整条管线；
     * 条目回待发队列并标 PAUSED，等下次握手 flushPending 或用户手动继续时再试（与 Android P2-1 同口径）。
     */
    void pauseByDataTimeout(Msg m, String kind) {
        if (m == null) {
            return;
        }
        if (L.store != null && m.mid != null && !m.mid.isEmpty()) {
            L.store.addPending(m);
        }
        m.status = Msg.ST_PAUSED;
        m.text = DataConsent.peerTimeoutText(kind);
        L.emit(m);
        L.log("数据流量确认超时（未投拒收票，留在待发队列）：" + m.fileName);
    }

    /**
     * @param voteDenied 这一条要不要替"被拒绝"那一票投 1（全局闸住文件管线）。
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
            // 被拒 ⇒ 票③，但作用域只到这台设备（按设备组键记，10 分钟自动解除），
            //   不再「整条文件管线闸到下一次手动重试」
            //   ⚠ 不改 DENIED 本体：条目仍然**移出**待发队列、仍然要人手动重试
            //     ⇒ resendPendingFile 那条「不会反复骚扰」的既有注释依旧成立
            String peer = (m.peerIp == null || m.peerIp.isEmpty()) ? L.selectedIp() : m.peerIp;
            L.denyConsent(peer, note);
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
        // 空文件夹忽略 + 名额校验：补发这条路径也必须拦（否则排进队列的老条目会绕过发送入口）
        if (p.isDir) {
            if (files.isEmpty()) {
                if (L.store != null) {
                    L.store.removePending(p.mid);
                }
                if (skipped > 0) {
                    denyFileByData(p, "文件夹已不可读", false);
                } else {
                    p.status = Msg.ST_FAILED;
                    p.text = "空文件夹已忽略（未发送）";
                    L.emit(p);
                    L.log("补发跳过（空文件夹已忽略）：" + p.fileName);
                }
                return;
            }
            List<String> rels = new ArrayList<String>();
            for (File rf : files) {
                rels.add(LinkNames.relPathOf(path, rf));
            }
            String dirErr = LinkNames.folderError(p.fileName, rels);
            if (dirErr != null) {
                L.notifyUser(dirErr + "，已取消发送");
                denyFileByData(p, dirErr + "，已取消发送", false);
                return;
            }
        } else {
            String fileErr = LinkNames.fileNameError(p.fileName);
            if (fileErr != null) {
                L.notifyUser(fileErr + "，已取消发送");
                denyFileByData(p, fileErr + "，已取消发送", false);
                return;
            }
        }
        int r = L.consent.ensureDataConsent(peerIp, p.mid, DataConsent.KIND_FILE,
                p.isDir ? "发送文件夹" : "发送文件", p.fileName, p.fileSize);
        if (r != DataConsent.OK) {
            if (r == DataConsent.REMOTE_REFUSED) {
                denyFileByData(p, DataConsent.peerRefusedText(DataConsent.KIND_FILE));
            } else if (r == DataConsent.LOCAL_REFUSED) {
                denyFileByData(p, "你拒绝了在数据流量下发送");
            } else if (r == DataConsent.REMOTE_TIMEOUT) {
                // 未确认≠拒绝：条目本来就留在待发队列，下次握手再试（不投拒收票）
                p.status = Msg.ST_PAUSED;
                p.text = DataConsent.peerTimeoutText(DataConsent.KIND_FILE);
                L.emit(p);
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
        // 发送前名校验：不合格 → 提示并把这条置失败/清待发（取消本次传输）
        String retryErr = m.isDir ? LinkNames.folderNameError(m.fileName) : LinkNames.fileNameError(m.fileName);
        if (retryErr != null) {
            L.notifyUser(retryErr + "，已取消发送");
            denyFileByData(m, retryErr + "，已取消发送", false);
            return;
        }
        // 用户显式「继续/重试」：清掉取消墓碑（否则队列入口会把它当成已取消直接跳过）
        L.forgetCanceled(m.mid);
        L.cancelled.remove(m.mid);
        String ip = (m.peerIp == null || m.peerIp.isEmpty()) ? L.selectedIp() : m.peerIp;
        // 手动重试是该设备票③的显式清零点；放在入口而不是成功后 ⇒ 重试若再次被拒，denyConsent 会重新投 1
        L.clearDenyConsent(ip, false);
        Session s = L.sessionByGroup(ip);
        if (s == null) {
            L.setFileVote(Link.VOTE_NO_NETWORK, true, ip);   // 归票 ②：握手完成自动清，不靠下一次手动重试
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
                if (consent == DataConsent.REMOTE_TIMEOUT) {
                    pauseByDataTimeout(item, DataConsent.KIND_FILE);
                    return;
                }
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
        // 单任务槽：一个设备同一时刻只传一个任务。本机在收（或收着别的）时这里排队等空槽 ——
        // 本方法只跑在发送串行队列线程上，阻塞等不会卡住网络读线程/UI
        boolean slot = L.claimTask(item.mid, item.peerIp, "send", "文件夹 " + item.fileName);
        try {
            if (!slot) {
                L.log("本机正在传其它任务（" + L.busyHolder() + "），文件夹 " + item.fileName + " 排队等空槽");
                slot = L.awaitTaskSlot(item.mid, item.peerIp, "send", "文件夹 " + item.fileName);
            }
            if (!slot) {
                L.log("等空槽期间对端已断开，文件夹 " + item.fileName + " 留在待发队列");
                return;
            }
            if (L.consumePeerBusy(item.mid)) {
                pauseDirForPeerBusy(item);
                return;
            }
            doSendFolderInner(dir, item, files, emptyDirs, skipped);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            L.log("等空槽被中断，文件夹 " + item.fileName + " 留在待发队列");
        } finally {
            if (slot) {
                L.releaseTask(item.mid, true);
            }
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
     * 为什么要有完整性闭环：
     *  - 只要 {@code file_done} 写进 socket 就当成功、**完全不等对端确认**的话，对端落盘/校验失败时
     *    发送端照样报「完成」，用户永远不知道少了哪些。
     *  - 成员失败即中止整目录会让一个读不了的文件挡住后面所有成员，所以本地源不可读只跳过该成员并继续，最后统一汇报。
     */
    void doSendFolderInner(File dir, Msg item, List<File> files, List<String> emptyDirs,
                                   int skipped) {
        // 与 doSendFile 同理：排在队列里的补发任务要认取消墓碑
        if (L.isCanceled(item.mid, null)) {
            L.log("文件夹 " + item.fileName + " 已被取消，跳过后到的发送任务");
            if (L.store != null) {
                L.store.removePending(item.mid);
            }
            return;
        }
        // 目的设备在创建这条消息时就定死了（item.peerIp），切换会话不会改变归属
        Session s = L.sessionOf(item.peerIp);
        if (s == null) {
            L.setFileVote(Link.VOTE_NO_NETWORK, true, item.peerIp);   // 「原本没网络」那一票（文件夹同一条口径）
            if (L.store != null) {
                L.store.addPending(item);
            }
            return;
        }
        if (L.cancelled.contains(item.mid)) {
            L.cancelled.remove(item.mid);
            return;
        }
        // 单任务槽：对端回过 busy（目录被回绝）⇒ 本轮不开，留待发队列等它空出来
        if (L.consumePeerBusy(item.mid)) {
            pauseDirForPeerBusy(item);
            return;
        }
        // 只有对端也支持（hello dl=1）才启用清单/收尾/核对；老端保持原行为，不引入未知帧
        boolean verifyPeer = s.caps.dl();        List<String> relPaths = new ArrayList<String>();
        for (File f : files) {
            relPaths.add(LinkNames.relPathOf(dir, f));
        }
        List<String> wireList = (verifyPeer && !relPaths.isEmpty() && wireListFits(relPaths))
                ? relPaths : new ArrayList<String>();
        // 清单大小（与 wireList 逐项对应）：先给清单（目录结构+大小），对端按清单存放并核对字节数
        List<Long> wireSizes = new ArrayList<Long>();
        if (!wireList.isEmpty()) {
            for (File f : files) {
                wireSizes.add(Long.valueOf(f.length()));
            }
        }
        if (verifyPeer && wireList.isEmpty() && !relPaths.isEmpty()) {
            L.log("成员清单过长（" + relPaths.size() + " 项），本次改用「按数量核对」完整性");
        }
        // 空文件夹一律忽略、不记清单：dir_meta 里的 dirs 恒为空（双端统一规则）
        List<String> emptyDirsWire = new ArrayList<String>();
        // ⚠ 信箱**必须先登记再发送**：对端收到 dir_meta 后立刻回 dir_have，
        //   先 send 后登记会与这条回执赛跑 —— 回执投进还不存在的信箱被丢掉，本端白等 1s、整目录重传
        ArrayBlockingQueue<java.util.List<String>> dirHaveBox =
                verifyPeer ? new ArrayBlockingQueue<java.util.List<String>>(1) : null;
        if (dirHaveBox != null) {
            L.dirHaveBox.put(item.mid, dirHaveBox);
        }
        try {
            s.send(Protocol.dirMeta(item.mid, item.fileName, files.size(), item.fileSize,
                    emptyDirsWire, wireList, skipped, wireSizes));
            Link.DirProgress agg = L.new DirProgress(item, item.fileSize);            // 对端「已经在盘上」的成员：断链重连后对端会立刻回报，跳过它们只补缺的
            java.util.Set<String> already = new java.util.HashSet<String>();
            if (dirHaveBox != null) {
                try {
                    java.util.List<String> have = dirHaveBox.poll(L.DIR_HAVE_WAIT_MS, TimeUnit.MILLISECONDS);
                    if (have != null) {
                        already.addAll(have);
                    }
                } catch (InterruptedException ie) {
                    // 等不到就按「对端没有」处理，整目录照发
                } finally {
                    L.dirHaveBox.remove(item.mid);
                }
                if (!already.isEmpty()) {
                    L.log("对端已有 " + already.size() + " 个成员，本次跳过，只补缺的（" + item.fileName + "）");
                }
            }
            // 本地不可读而跳过的成员（本地问题，补发也无用 —— 最后如实汇报）
            List<String> unreadable = new ArrayList<String>();
            List<File> pendingSet = files;
            int round = 0;
            List<String> peerMissing = new ArrayList<String>();
            boolean peerAllOk = true;
            boolean unconfirmed = false;
            while (true) {
                for (File f : pendingSet) {
                    if (L.cancelled.contains(item.mid)) {
                        L.cancelled.remove(item.mid);
                        L.clearMemberAcks(item.mid);
                        L.log("文件夹发送已取消 " + item.fileName);
                        return;
                    }
                    if (L.fileGateBlocked(item.peerIp)) {
                        // 文件夹在**成员边界**暂停；已发完的成员由对端 dir_have 回报，续发时自动跳过
                        pauseInFlight(item, "", null);
                        return;
                    }
                    if (L.consumePeerBusy(item.mid)) {
                        // 对端在传别的任务（单任务闸门）⇒ 成员边界整轮中止，等它空出来的 sync_req
                        pauseDirForPeerBusy(item);
                        return;
                    }
                    String rel = LinkNames.relPathOf(dir, f);
                    if (already.contains(rel)) {
                        // 对端已在盘上：不传，进度按已收补上
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
                        // **只跳过这一个成员**继续发其余（老逻辑会让整目录就此停摆）
                        unreadable.add(rel);
                        L.log("成员本地不可读，跳过继续：" + rel + "（" + se.getMessage() + "）");
                    } catch (BusyError be) {
                        // 对端忙（单任务闸门）：整轮中止，整目录留待发队列，等对方空出来自动重试
                        pauseDirForPeerBusy(item);
                        return;
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
                    // 无回执 ≠ 收齐：绝不默认成功。条目留在待发台账，下次握手可再核对/补发
                    L.log("对端未在 " + (waitMs / 1000) + "s 内回报文件夹核对结果，无法确认收齐，按失败处理（条目留在待发队列）");
                    peerAllOk = false;
                    unconfirmed = true;
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
                // 对端未回报（无法确认）时保留待发台账，让下次握手有机会再核对；明确失败才出队
                if (!unconfirmed) {
                    L.store.removePending(item.mid);
                }
            }
            // 整目录收尾
            L.dirHaveBox.remove(item.mid);
            L.clearMemberAcks(item.mid);
        } catch (Throwable t) {
            if (L.cancelled.contains(item.mid)) {
                L.cancelled.remove(item.mid);
                L.log("文件夹发送已取消 " + item.fileName);
                return;
            }
            // 失败收尾：重发走待发队列整目录补发，缺哪些由对端清单对账决定
            L.dirHaveBox.remove(item.mid);
            L.clearMemberAcks(item.mid);
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
            // 整目录速率采样器统一收尾：覆盖 while 内取消 / 循环后取消 /
            // 失败定稿 / catch 失败；成功定稿那次 end 保留，重入幂等。
            Speeds.end(item.mid);
        }
    }

    /**
     * 断点续传协商：一问（file_query）一答（file_resume），3s 等不到就算无应答。
     * 返回值口径：<b>&gt;0</b> = 对端已有这么多字节（按 CHUNK_SIZE 对齐后从这儿续）；
     * <b>0</b> = 对端答了"没有半成品"（从头发）；<b>-1</b> = **没发出问题或没收到应答**
     *（⇒ 调用方保持暂停，不许退化成从头重发）。
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
            // 接收端要求块校验 → 发 blk_verify 请它按 32MB 逐块核对大小，从它确认的偏移续发
            v = (v > size) ? size : v;
            if (Boolean.TRUE.equals(L.resumeNeedBlk.get(mid)) && v > 0) {
                v = verifyPrefixBlocks(s, mid, size, v);
            }
            return v;
        } catch (Throwable t) {
            return -1L;
        } finally {
            L.resumeWaiters.remove(mid);
            L.resumeNeedBlk.remove(mid);
        }
    }

    /**
     * 发送端前缀大小校验（不做 hash）：发 blk_verify{offset} 请接收端按 32MB 一块核对
     * [0, offset) 是否都在盘上；接收端截断到最后一个完整块边界、建好续传通道后回
     * blk_ack{ok, offset}，从该偏移续发。超时预算 = 块数 × 10s（单块 10s）；
     * 超时/失败一律补发 blk_verify{0} 让对端清掉续传准备，改从头重发。
     */
    long verifyPrefixBlocks(Session s, String mid, long size, long offset) {
        ArrayBlockingQueue<Link.BlkAckResult> aq = new ArrayBlockingQueue<Link.BlkAckResult>(1);
        L.blkAckWaiters.put(mid, aq);
        try {
            long blocks = (offset + Protocol.BLOCK_SIZE - 1) / Protocol.BLOCK_SIZE;
            long budgetMs = Math.max(1L, blocks) * 10_000L;
            L.trySend(s, Protocol.blkVerify(mid, offset));
            Link.BlkAckResult r = aq.poll(budgetMs, TimeUnit.MILLISECONDS);
            if (r == null) {
                L.log("大小校验：" + budgetMs + "ms 没等到对端核验确认（mid=" + mid + "），改从头重发");
                L.trySend(s, Protocol.blkVerify(mid, 0L));
                return 0L;
            }
            if (!r.ok) {
                L.log("大小校验：对端无法截断半成品，改从头重发");
                return 0L;
            }
            long target = Math.max(0L, Math.min(r.offset, offset));
            if (target != offset) {
                L.log("大小校验：对端核验通过到 " + target + "（请求 " + offset + "），从该点续传");
            } else {
                L.log("大小校验完成（" + blocks + " 块 / 32MB 按大小核验），从 " + target + " 续传");
            }
            return target;
        } catch (Throwable t) {
            // 异常同样不能沿用偏移：接收端没收到 blk_verify 就不会建续传通道，按 offset 推流会错位
            L.log("大小校验异常（" + t + "），改从头重发");
            L.trySend(s, Protocol.blkVerify(mid, 0L));
            return 0L;
        } finally {
            L.blkAckWaiters.remove(mid);
        }
    }

    /**
     * 对端回 busy（单任务闸门）时的就地暂停：**不是链路故障**，绝不当网络错误去关会话。
     * 单文件 → 落暂停态 + 留待发队列（对方空出来发 sync_req，本端 flushPending 自动重试）；
     * 文件夹成员 → 抛 BusyError 给 doSendFolderInner 整轮中止。
     */
    void pauseForPeerBusy(Msg item, String dirId, Link.DirProgress agg) {
        if (agg == null && (dirId == null || dirId.isEmpty())) {
            if (L.store != null) {
                L.store.addPending(item);
            }
            item.status = Msg.ST_PAUSED;
            L.emit(item);
            L.log("对端忙，暂停发送 " + item.fileName + "（对方空出来后自动重试）");
            return;
        }
        throw new BusyError("对端忙：" + item.fileName);
    }

    /** 整目录被对端回绝：转暂停并留在待发队列（成员不逐个记，重发时 dir_have 会跳过已到的） */
    void pauseDirForPeerBusy(Msg item) {
        if (L.store != null) {
            L.store.addPending(item);
        }
        item.status = Msg.ST_PAUSED;
        L.emit(item);
        L.log("对端忙，文件夹 " + item.fileName + " 本轮中止，等对方空出来后自动重试");
    }

    /**
     * 把一条**在传的**条目就地暂停 —— 结束本次尝试（不抛错、不进 cancelled、
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
     * 跳过源文件前 len 字节（续传时定位到续传起点），不发送、不算摘要。
     * 跳过失败返回 false，调用方改从头传。
     */
    static boolean skipPrefix(InputStream ins, long len) {
        try {
            byte[] buf = new byte[1 << 16];
            long left = len;
            while (left > 0) {
                int n = ins.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) {
                    return false;
                }
                left -= n;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 送达台账（S22/S31，与安卓 delivered.json 同口径） ====================

    /** 送达台账的键：mid + 对端设备组键（同一条消息发给两台设备不算重复；换 WiFi/热点仍算同一台） */
    String deliveryKey(String mid, String peerIp) {
        if (mid == null || mid.isEmpty()) {
            return "";
        }
        String k = "";
        try {
            k = L.store == null ? "" : L.store.convKeyOf(peerIp == null ? "" : peerIp);
        } catch (Throwable ignore) {
        }
        return mid + "@" + ((k == null || k.isEmpty()) ? (peerIp == null ? "" : peerIp) : k);
    }

    /** 单文件送达登记 —— 只在「对端回报这条已存妥」的路径调用（applyAck / 续传协商短路） */
    void markFileDelivered(String mid, String peerIp) {
        String k = deliveryKey(mid, peerIp);
        if (k.isEmpty()) {
            return;
        }
        awaitingAck.remove(mid);
        if (L.store != null) {
            L.store.markDelivered(k);
        }
    }

    /** 是否已把这条文件**完整送达**过这台设备（跨进程重启仍成立） */
    boolean isFileDelivered(String mid, String peerIp) {
        return L.store != null && L.store.isDelivered(deliveryKey(mid, peerIp));
    }

    void doSendFile(File f, Msg item, String relPath, String dirId,
                            Link.DirProgress agg, boolean throwOnFail) {
        // 队列里排着的任务（多为 syncNow→flushPendingNow 产生的「待发补发」）可能在用户取消之后才轮到：
        // 取消墓碑命中就直接跳过，不再入待发台账、不再开传（否则取消完过几秒同一个 mid 又被重发一遍）
        if (L.isCanceled(item.mid, dirId)) {
            L.log("文件 " + item.fileName + " 已被取消，跳过后到的发送任务");
            if (L.store != null) {
                L.store.removePending(item.mid);
            }
            return;
        }
        // 只有「普通单文件」由这里管待发队列；文件夹整体由 doSendFolder 管，成员不管
        if ((dirId == null || dirId.isEmpty()) && agg == null) {
            // 重复投递闸门（S31）：这条已经完整送达过该设备，连队都不排（待发补发与 hsync 补齐
            // 会同时往串行队列塞任务，前者跑完 inFlight 就空了，只判 inFlight 挡不住第二次）
            if (isFileDelivered(item.mid, item.peerIp)) {
                L.log("文件 " + item.fileName + " 本机已完整送达该设备，跳过重复发送");
                if (L.store != null) {
                    L.store.removePending(item.mid);
                }
                emitStatus(statusMsg(item, Msg.ST_OK, 100));
                return;
            }
            if (L.store != null) {
                L.store.addPending(item);
            }
        }
        if (!L.inFlight.add(item.mid)) {
            return;  // 已有同 mid 在途（握手补发撞上在途传输），让在途的那个继续
        }
        L.inFlightPeer.put(item.mid, item.peerIp);
        // 单任务槽：只有「普通单文件」自己占槽（文件夹成员跟着 doSendFolder 的整目录槽走）
        boolean ownSlot = (dirId == null || dirId.isEmpty()) && agg == null;
        boolean slot = ownSlot && L.claimTask(item.mid, item.peerIp, "send", item.fileName);
        try {
            if (ownSlot && !slot) {
                L.log("本机正在传其它任务（" + L.busyHolder() + "），文件 " + item.fileName + " 排队等空槽");
                slot = L.awaitTaskSlot(item.mid, item.peerIp, "send", item.fileName);
                if (!slot) {
                    L.log("等空槽期间对端已断开，文件 " + item.fileName + " 留在待发队列");
                    return;
                }
            }
            doSendFileInner(f, item, relPath, dirId, agg, throwOnFail);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            L.log("等空槽被中断，文件 " + item.fileName + " 留在待发队列");
        } finally {
            if (ownSlot && slot) {
                L.releaseTask(item.mid, true);
            }
            L.inFlight.remove(item.mid);
            L.inFlightPeer.remove(item.mid);
            L.sendLastAt.remove(item.mid);
            // 速率采样器统一收尾：一处覆盖 取消/失败/成功（含文件夹成员），
            // 成功路径里那次 end 只是提前收尾，这里重入幂等。
            Speeds.end(item.mid);
            // 文件夹成员不走「单文件等 ack」（整目录由 dir_end/dir_missing 收口）：
            // 对端仍会给每个成员回 ack，误记进 rejectedAck/awaitingAck 的条目在这里清掉，防大目录无界增长
            if (agg != null) {
                awaitingAck.remove(item.mid);
                rejectedAck.remove(item.mid);
            }
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
            L.setFileVote(Link.VOTE_NO_NETWORK, true, item.peerIp);   // 2.12：这就是"原本没网络"那一票
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
            // 单任务槽：对端回过 busy（它在传别的任务）→ 立刻停，别把字节倒进虚空
            if (L.consumePeerBusy(item.mid)) {
                pauseForPeerBusy(item, dirId, agg);
                return;   // 单文件已落暂停态；成员会在上面抛出，走 catch 的 BusyError 分支
            }
            // 首块计时起点：定位「链路已就绪」到「第一块数据真正出网」之间的空转
            long tTask = System.currentTimeMillis();
            // ① 断点续传协商：只给「普通单文件」用。文件夹成员一律从 0 整文件发 ——
            //   局域网下按文件粒度补发足够快，省掉每成员一次往返和一堆对账代码
            long offset = 0L;
            if (item.fileSize > 0 && (dirId == null || dirId.isEmpty()) && agg == null) {
                offset = negotiateResume(s, item.mid, item.fileName, item.fileSize);
                L.log("[首块计时] " + item.fileName + " 续传协商 "
                        + (System.currentTimeMillis() - tTask) + "ms → offset=" + offset);
                if (offset < 0) {
                    // 这是"解除拦截后自动发的那**一次**续传请求"没得到应答
                    // ⇒ 不从头重发、保持暂停，等气泡上的"继续"或下次握手
                    pauseInFlight(item, dirId, agg);
                    return;
                }
                // 对端报的偏移 = 文件全长 → 它已经有完整一份，就是送达确认：一个字节都不必再推，
                // 出队 + 记台账直接收尾（兜「ack 丢帧 / 对端回 ack 前掉线、但它其实已存好」这种假失败，S22）；
                // 文件夹成员不判（成员每次重发都用新 mid，提前 return 会打乱目录侧记账）
                if (offset == item.fileSize && (dirId == null || dirId.isEmpty()) && agg == null) {
                    L.log("文件 " + item.fileName + " 对端已存妥完整一份，无需重发，直接标记送达");
                    markFileDelivered(item.mid, s.remoteIp());
                    if (L.store != null) {
                        L.store.removePending(item.mid);
                    }
                    emitStatus(statusMsg(item, Msg.ST_OK, 100));
                    return;
                }
                if (offset > 0) {
                    L.log("断点续传 " + item.fileName + "：对端已有 " + offset + " B，校验后续传");
                }
            }
            // ①-b 多通道分支已停用（回到单连接，见 Link.LANES 注释）：doSendUnitized 保留但不再调用。
            // ①-c 零拷贝分支（P1-4）：非续传 + 源是真实文件 → 每块 transferTo 直送 socket，数据不进用户态内存。
            if (Link.ZC_SEND && offset == 0L && item.fileSize > 0) {
                FileChannel zc = null;
                try {
                    zc = new FileInputStream(f).getChannel();
                    L.log("零拷贝发送 " + item.fileName + "（" + item.fileSize + " B）"
                            + "｜入队到首块 " + (System.currentTimeMillis() - tTask) + "ms");
                    doSendFileZeroCopy(s, item, zc, relPath, dirId, agg);
                    return;
                } catch (java.io.FileNotFoundException e) {
                    throw new SourceException("打不开源文件: " + f, e);
                } finally {
                    if (zc != null) {
                        try {
                            zc.close();
                        } catch (Exception ignore) {
                        }
                    }
                }
            }
            FileInputStream fis = new FileInputStream(f);
            InputStream in = new BufferedInputStream(fis);
            if (offset > 0) {
                // ② 跳过前缀（把流推进到 offset，之后直接续着读，不用 reopen；不算摘要）
                if (!skipPrefix(in, offset)) {
                    offset = 0L;
                    L.log("前缀读取失败，改从头发送 " + item.fileName);
                    in.close();
                    fis = openSource(f);
                    in = new BufferedInputStream(fis);
                }
            }
            try {
                // ③ 双 8MB 缓冲交替读源（后台线程读盘，与网络发送重叠），分片长度由自适应帧决定
                s.send(Protocol.fileMeta(item.mid, item.fileName, item.fileSize,
                        "application/octet-stream", "", offset, relPath, dirId));
                Link.BlockReader reader = new Link.BlockReader(in, Protocol.RX_BUF_SIZE);
            // 自适应帧：1M 起步，0.5s 一评估，抖了降两档，顶档 8M（与安卓端同规则）
            AdaptiveFrame frame = new AdaptiveFrame(item.fileName);
                try {
                    // 帧序号只用于接收端按序校验，与文件偏移无关，续传也从 0 起
                    int seq = 0;
                    long sent = offset;
                    int lastPct = -1;
                    while (true) {
                        if (L.isCancelled(item.mid, dirId)) {
                            L.cancelled.remove(item.mid);
                            L.log("文件发送已取消 " + item.fileName + "（已发 " + sent + " B）");
                            return;
                        }
                        if (L.fileGateBlocked(item.peerIp)) {
                            // 在传的遇到投票=1 ⇒ 在**下一个分片边界**就地结束本次尝试
                            //   （不在已入队的字节中间动手 ⇒ 线上一条帧都不会被劈开）
                            pauseInFlight(item, dirId, agg);
                            return;
                        }
                        if (L.consumePeerBusy(item.mid)) {
                            // 对端在传别的任务（单任务闸门）⇒ 分片边界停手，等它空出来的 sync_req
                            pauseForPeerBusy(item, dirId, agg);
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
                            // 帧长自适应（1M 起步，0.5s 一评估，抖了降两档）：读块 8MB 是上限，档位再高也按块切
                            // 单帧整帧上限是 MAX_FRAME，帧头(6B+mid)也占额度 -> 载荷再夹一道上限，
                            // 否则顶档 8M 必然抛「分片帧过大」（与安卓 framePayloadCap 同构）
                            if (L.consumePeerBusy(item.mid)) {
                                // 分片边界：对端忙 ⇒ 停手（单文件落暂停态 / 成员抛给整目录）
                                pauseForPeerBusy(item, dirId, agg);
                                return;
                            }
                            if (rejectedAck.contains(item.mid)) {
                                // 对端建接收通道失败已提前回绝：立即停止推送，别把 GB 级字节倒进虚空
                                L.log("文件 " + item.fileName + " 对端提前拒收，停止推送（条目留在待发队列）");
                                Speeds.end(item.mid);
                                Msg rj = new Msg();
                                rj.mid = item.mid;
                                rj.peerIp = item.peerIp;
                                rj.dir = Msg.DIR_SELF;
                                rj.kind = Msg.KIND_FILE;
                                rj.fileName = item.fileName;
                                rj.fileSize = item.fileSize;
                                rj.status = Msg.ST_FAILED;
                                if (L.listener != null) {
                                    L.listener.onMessageUpdated(rj);
                                }
                                return;
                            }
                            int len = Math.min(Math.min(frame.size(), n(e) - off), framePayloadCap(item.mid));
                            long w0 = System.nanoTime();
                            s.sendChunk(item.mid, seq, e.buf, off, len);
                            frame.onFrame(len, System.nanoTime() - w0);
                            seq++;
                            off += len;
                            sent += len;
                            // 发送停滞看门狗（v2.8）：每写出一块刷新「最近进度时刻」
                            L.sendLastAt.put(item.mid, System.currentTimeMillis());
                            // 速率采样（字节级）：进度是整数百分比，大文件太粗 → 气泡速率靠这里
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
                        // 消费端卡死在 ready.take() —— 大文件必然卡住。
                        reader.recycle(e.buf);
                    }
                    // 源文件实际可读字节少于声明（云占位 / 被截断 / 提供方虚报）：
                    // 按本地源错误收尾（不重试、出队、通知对端清半成品），否则每轮「发完→对端判少→留队」永远出不去（S16）
                    if (item.fileSize > 0 && sent < item.fileSize) {
                        throw new SourceException("源文件实际可读 " + sent + " B 少于声明 "
                                + item.fileSize + " B（可能被截断或提供方虚报）");
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
            // 大小校验口径：不再算整文件摘要，sha 字段保留线上格式但恒为空串；
            // 完整性靠 file_done 的字节数核对 + 续传后的 file_verify(size)
            String sha = "";
            if ((dirId == null || dirId.isEmpty()) && agg == null) {
                // 普通单文件：登记回执 → 发 file_done → 气泡收尾（顺序不能颠倒，见 finishSingle）
                finishSingle(s, item, sha, offset);
            } else {
                // 先登记成员回执映射，再发 file_done：对端 ack(false) 到达时凭 mid 找回 (dirId, relPath) 记账
                if (dirId != null && !dirId.isEmpty()) {
                    L.dirMemberAck.put(item.mid, dirId + "\u0001" + relPath);
                }
                s.send(Protocol.fileDone(item.mid, sha));
                if (agg == null) {
                    // 文件夹成员：进度由聚合器反映，气泡状态在整目录收尾时更新
                    Speeds.end(item.mid);
                    emitStatus(statusMsg(item, Msg.ST_OK, 100));
                }
                L.log("文件发送完成 " + item.fileName + " " + sentTotal(item));
            }
        } catch (Throwable t) {
            L.cancelled.remove(item.mid);
            // 没走到「等 ack」就别留在待确认表里（条目按各自分支回待发/出队）
            awaitingAck.remove(item.mid);
            rejectedAck.remove(item.mid);
            if (t instanceof BusyError) {
                // 对端忙（单任务闸门）：链路是好的，**绝不能**按网络故障关会话
                L.log("对端忙，中止本轮发送 " + item.fileName + "（等对方空出来后自动重试）");
                if (throwOnFail) {
                    throw (BusyError) t;
                }
                return;
            }
            if (t instanceof SourceException) {
                // **本地**读不了源文件（被删 / 被占用 / 无权限）：链路本身是好的，
                // 因此绝不关连接（误杀连接会把后续所有传输一起带走），
                // 也不进待发队列（重试同样是失败，只会无限循环）。
                L.log("文件发送失败（本地源不可读）" + item.fileName + "：" + t.getMessage());
                // 对端可能已建好半成品：通知它删掉，免得留下残缺文件在「已接收」里
                L.trySend(s, Protocol.fileCancel(item.mid));
                if (throwOnFail) {
                    throw (SourceException) t;
                }
                // ⚠ 必须**出队**：doSendFile 入口已 addPending，不清掉的话
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
     * 2 期：本文件能否走多通道整单元发送 —— 需要本端开启多通道、对端宣告 lanes>=2、
     * 实际建起 >=2 条通道、且源是可 seek 的真实文件（否则无法随机偏移零拷贝）。
     */
    /**
     * 2 期：源是否可随机 seek（真实文件且能打开通道，且长度与 File 一致）。
     * 只有可 seek 才能按单元偏移零拷贝直发；不可 seek 的源回落 1 期单通道路径。
     */
    boolean sourceSeekable(File f) {
        if (!f.isFile() || f.length() <= 0) {
            return false;
        }
        FileChannel probe = null;
        try {
            probe = new FileInputStream(f).getChannel();
            return probe.size() == f.length();
        } catch (Throwable t) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /**
     * 2 期多通道发送：4MB 单元逐个交给「谁空谁领」的 N 条通道，每条通道一个线程 + 一个独立
     * FileChannel，整单元 transferTo 零拷贝直发 —— 发送端不占用户态缓冲。
     * 接收端 3 槽重排环乱序收、顺序落盘，所以单元到达可以乱序，但每个单元必须整块到达。
     */
    void doSendUnitized(Session s, File f, Msg item, String relPath, String dirId,
                        Link.DirProgress agg, java.util.List<Session> lanes) throws Exception {
        final int unit = Protocol.CHUNK_SIZE;
        final long total = item.fileSize;
        final int units = (int) ((total + unit - 1) / unit);
        final int declared = lanes.size();
        final java.util.ArrayList<FileChannel> chans = new java.util.ArrayList<>(declared);
        try {
            for (int i = 0; i < declared; i++) {
                chans.add(new FileInputStream(f).getChannel());
            }
            // 先登记「等就绪」的 latch，再发声明 + 元数据：接收端建好 Rx 会回 xe_ready，
            // 收到之后才允许起数据通道发分片。反过来的话，比 file_meta 更早到达的分片
            // 在接收端查不到 mid 会被丢弃 → 单元缺口补不齐 → 重排环窗口 30s 卡死。
            final java.util.concurrent.CountDownLatch ready =
                    new java.util.concurrent.CountDownLatch(1);
            L.expectXeReady(item.mid, ready);
            boolean readyOk = false;
            try {
                // 先声明后发元数据：接收端建 Rx 时要靠 xe_begin 决定用重排环还是 1 期双缓冲
                s.send(Protocol.xeBegin(item.mid, unit, declared, 0L));
                s.send(Protocol.fileMeta(item.mid, item.fileName, total,
                        "application/octet-stream", "", 0L, relPath, dirId));
                readyOk = ready.await(Link.XE_READY_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                readyOk = false;
            } finally {
                // 发送失败/中断也要摘掉登记，避免 map 里留一颗永不消费的 latch
                L.clearXeReady(item.mid);
            }
            // 超时（对端不支持/异常）→ 只用主连接按单元顺序发；接收端重排环照样按序收，功能不受影响
            if (!readyOk) {
                L.log("多通道：等对端接收上下文就绪超时（" + Link.XE_READY_WAIT_MS + "ms 未见 xe_ready），"
                        + item.fileName + " 降级为主连接单通道");
            }
            final java.util.List<Session> useLanes =
                    readyOk ? lanes : java.util.Collections.singletonList(s);
            final int n = useLanes.size();
            L.log("多通道发送 " + item.fileName + "（" + total + " B，" + n + " 条通道 × " + units + " 个单元）");

            final java.util.concurrent.atomic.AtomicInteger nextUnit =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            /** 真正写完的单元数（错误信息用真实完成数，别拿“已分配”当“已发完”） */
            final java.util.concurrent.atomic.AtomicInteger doneUnits =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            final java.util.concurrent.atomic.AtomicLong sentBytes =
                    new java.util.concurrent.atomic.AtomicLong(0L);
            final java.util.concurrent.atomic.AtomicInteger lastPct =
                    new java.util.concurrent.atomic.AtomicInteger(-1);
            final java.util.concurrent.atomic.AtomicBoolean stop =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            final Object errLock = new Object();
            final Throwable[] failed = new Throwable[1];
            final boolean[] cancelledHere = new boolean[1];
            final boolean[] pausedHere = new boolean[1];

            Thread[] threads = new Thread[n];
            for (int li = 0; li < n; li++) {
                final int idx = li;
                threads[li] = new Thread(() -> {
                    try {
                        while (!stop.get()) {
                            int u = nextUnit.getAndIncrement();
                            if (u >= units) {
                                break;
                            }
                            if (L.isCancelled(item.mid, dirId)) {
                                L.cancelled.remove(item.mid);
                                cancelledHere[0] = true;
                                stop.set(true);
                                return;
                            }
                            // 数据流量闸门在「单元边界」收手，已入队的字节不劈开
                            if (L.fileGateBlocked(item.peerIp)) {
                                pausedHere[0] = true;
                                stop.set(true);
                                return;
                            }
                            if (rejectedAck.contains(item.mid)) {
                                // 对端提前回绝：停止派发；rejectedAck 留着由收尾统一标失败
                                stop.set(true);
                                return;
                            }
                            long at = (long) u * unit;
                            int len = (int) Math.min((long) unit, total - at);
                            useLanes.get(idx).sendChunkFile(item.mid, u, chans.get(idx), at, len);
                            doneUnits.incrementAndGet();
                            L.sendLastAt.put(item.mid, System.currentTimeMillis());
                            long done = sentBytes.addAndGet(len);
                            Speeds.sample(item.mid, done);
                            if (agg != null) {
                                agg.add(len);
                            } else if (total > 0) {
                                int pct = (int) ((done * 100) / total);
                                if (lastPct.get() != pct && lastPct.getAndSet(pct) != pct) {
                                    Msg p = new Msg();
                                    p.mid = item.mid;
                                    p.peerIp = item.peerIp;
                                    p.dir = Msg.DIR_SELF;
                                    p.kind = Msg.KIND_FILE;
                                    p.fileName = item.fileName;
                                    p.fileSize = item.fileSize;
                                    p.progress = pct;
                                    p.status = Msg.ST_SENDING;
                                    if (L.listener != null) {
                                        L.listener.onMessageUpdated(p);
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        synchronized (errLock) {
                            if (failed[0] == null) {
                                failed[0] = t;
                            }
                        }
                        L.log("通道 lane=" + idx + " 发送中断：" + t.getMessage() + "（剩余单元交给其它通道）");
                        stop.set(true);
                    }
                }, "pp-send-l" + li);
                threads[li].setDaemon(true);
                threads[li].start();
            }
            for (int li = 0; li < n; li++) {
                try {
                    threads[li].join();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            if (cancelledHere[0] || L.isCancelled(item.mid, dirId)) {
                L.cancelled.remove(item.mid);
                L.log("多通道发送已取消 " + item.fileName + "（已发 " + sentBytes.get() + " B）");
                return;
            }
            if (pausedHere[0]) {
                pauseInFlight(item, dirId, agg);
                return;
            }
            int miss = units - Math.min(doneUnits.get(), units);
            Throwable t = failed[0];
            if (miss > 0 || t != null) {
                if (t instanceof SourceException) {
                    throw (SourceException) t;
                }
                throw new IOException("多通道发送中断（" + (units - miss) + "/" + units + " 个单元）："
                        + (t != null ? t.getMessage() : "通道全部退出"));
            }
            s.send(Protocol.xeEnd(item.mid));
            // 非续传：sha 发空串，接收端见空串即「对端未要求校验」
            if ((dirId == null || dirId.isEmpty()) && agg == null) {
                finishSingle(s, item, "", 0L);
            } else {
                if (dirId != null && !dirId.isEmpty()) {
                    L.dirMemberAck.put(item.mid, dirId + "\u0001" + relPath);
                }
                s.send(Protocol.fileDone(item.mid, ""));
                if (agg == null) {
                    Speeds.end(item.mid);
                    emitStatus(statusMsg(item, Msg.ST_OK, 100));
                }
                L.log("文件发送完成（多通道）" + item.fileName + " " + total + " B，等对端回报落盘");
            }
        } finally {
            for (FileChannel c : chans) {
                try {
                    c.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    /**
     * 零拷贝发送（P1-4）：按 4MB 块把源文件直接 transferTo 进 socket，数据不经过用户态内存。
     * 取消 / 数据流量闸门 / 进度 / 速率 / 收尾语义与流式路径逐条对齐；只用于非续传（offset=0），
     * 续传要先把源流推进到偏移（skipPrefix），仍走流式路径。
     */
    void doSendFileZeroCopy(Session s, Msg item, FileChannel src,
                            String relPath, String dirId, Link.DirProgress agg) throws Exception {
        long total = item.fileSize;
        // 源文件比声明小（云占位 / 已被截断）：直接按本地源错误收尾，
        // 别把半截文件当完整文件推完再让对端判少、留队无限重发（S16）
        if (total > 0) {
            long actual = src.size();
            if (actual < total) {
                throw new SourceException("源文件实际 " + actual + " B 少于声明 " + total
                        + " B（文件可能已被截断）");
            }
        }
        s.send(Protocol.fileMeta(item.mid, item.fileName, total,
                "application/octet-stream", "", 0L, relPath, dirId));
        int seq = 0;
        long sent = 0L;
        int lastPct = -1;
        // 自适应帧（与流式路径同规则）：1M 起步，0.5s 一评估，抖了降两档，顶档 8M
        AdaptiveFrame frame = new AdaptiveFrame(item.fileName);
        while (sent < total) {
            if (L.isCancelled(item.mid, dirId)) {
                L.cancelled.remove(item.mid);
                L.log("文件发送已取消 " + item.fileName + "（已发 " + sent + " B）");
                return;
            }
            // 在传的遇到数据流量投票 = 1 ⇒ 在下一个分片边界就地结束本次尝试
            if (L.fileGateBlocked(item.peerIp)) {
                pauseInFlight(item, dirId, agg);
                return;
            }
            if (L.consumePeerBusy(item.mid)) {
                pauseForPeerBusy(item, dirId, agg);
                return;
            }
            // 与流式路径同口径：对端建接收通道失败已提前回绝 → 立即停推，别把 GB 级字节倒进虚空（S14/S21）
            if (rejectedAck.contains(item.mid)) {
                L.log("文件 " + item.fileName + " 对端提前拒收，停止零拷贝推送（条目留在待发队列）");
                Speeds.end(item.mid);
                Msg rj = new Msg();
                rj.mid = item.mid;
                rj.peerIp = item.peerIp;
                rj.dir = Msg.DIR_SELF;
                rj.kind = Msg.KIND_FILE;
                rj.fileName = item.fileName;
                rj.fileSize = item.fileSize;
                rj.status = Msg.ST_FAILED;
                if (L.listener != null) {
                    L.listener.onMessageUpdated(rj);
                }
                return;
            }
            // 与流式路径同规则：8M 档的载荷要扣掉帧头(6B+mid)，见 framePayloadCap
            int len = (int) Math.min(total - sent, (long) Math.min(frame.size(), framePayloadCap(item.mid)));
            long w0 = System.nanoTime();
            s.sendChunkFile(item.mid, seq, src, sent, len);
            frame.onFrame(len, System.nanoTime() - w0);
            seq++;
            sent += len;
            // 发送停滞看门狗：每写出一块刷新「最近进度时刻」
            L.sendLastAt.put(item.mid, System.currentTimeMillis());
            Speeds.sample(item.mid, sent);
            if (agg != null) {
                agg.add(len);
            } else if (total > 0) {
                int pct = (int) ((sent * 100) / total);
                if (pct != lastPct) {
                    lastPct = pct;
                    Msg u = new Msg();
                    u.mid = item.mid;
                    u.peerIp = item.peerIp;
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
        if (L.isCancelled(item.mid, dirId)) {
            L.cancelled.remove(item.mid);
            L.log("文件发送已取消 " + item.fileName);
            return;
        }
        // 中途被截断的兜底（与流式路径同口径）：实际推出字节少于声明 → 按本地源错误收尾（S16）
        if (item.fileSize > 0 && sent < item.fileSize) {
            throw new SourceException("零拷贝实际推出 " + sent + " B 少于声明 " + item.fileSize
                    + " B（源文件被截断）");
        }
        // 非续传：sha 发空串，接收端见空串即「对端未要求校验」
        if ((dirId == null || dirId.isEmpty()) && agg == null) {
            // 普通单文件：登记回执 → 发 file_done → 气泡收尾（顺序不能颠倒，见 finishSingle）
            finishSingle(s, item, "", 0L);
        } else {
            if (dirId != null && !dirId.isEmpty()) {
                L.dirMemberAck.put(item.mid, dirId + "\u0001" + relPath);
            }
            s.send(Protocol.fileDone(item.mid, ""));
            if (agg == null) {
                Speeds.end(item.mid);
                emitStatus(statusMsg(item, Msg.ST_OK, 100));
            }
            L.log("文件发送完成 " + item.fileName + " " + sentTotal(item));
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

    /** 对端回 busy（单任务闸门）：**不是链路故障**，绝不能当网络错误去关会话 */
    static final class BusyError extends RuntimeException {
        BusyError(String msg) {
            super(msg);
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

    /**
     * 一个分片帧里**最多能装多少载荷字节**（与安卓 framePayloadCap 同构）。
     *
     * {@link Protocol#MAX_FRAME} 是整帧上限（4B 帧长字段的值 = 子类型 1B + midLen 1B + mid + seq 4B + 载荷），
     * 不是载荷上限：帧头固定占 {@code 6 + midLen} 字节（见 {@link Protocol#chunkHeader}）。
     * 自适应顶档 8M 若原样当载荷发，必然 8MB+6+midLen 越界 → 抛「分片帧过大」→ 关会话 → 重连再爬档再炸。
     * 所以发送侧按 mid 的真实 UTF-8 长度让位：一帧正好卡在 MAX_FRAME 上，不改协议常量、不降档。
     */
    static int framePayloadCap(String mid) {
        return Protocol.MAX_FRAME - 6 - mid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * 状态帧工厂：**必须带 mid/peerIp/kind/fileName/fileSize**。
     * 少了 kind 会被界面按 TEXT 重绘成空白气泡、还把 0B 写进历史（见 Link.java `case "ack"` 里的注释）。
     */
    static Msg statusMsg(Msg item, String status, int progress) {
        Msg u = new Msg();
        u.mid = item.mid;
        u.peerIp = item.peerIp;   // 归属：状态帧也要带，否则界面按"当前会话"画会串台
        u.dir = Msg.DIR_SELF;
        u.kind = Msg.KIND_FILE;
        u.fileName = item.fileName;
        u.fileSize = item.fileSize;
        u.progress = progress;
        u.status = status;
        return u;
    }

    void emitStatus(Msg u) {
        if (L.listener != null) {
            L.listener.onMessageUpdated(u);
        }
    }

    /**
     * 普通单文件收尾：**先登记回执 → 再发 `file_done` → 按「是否还在等」写气泡**。
     *
     * 顺序颠倒就会漏回执：局域网 RTT(0.2~1ms) 远小于一次「落盘+写历史」的耗时，对端 ack 常常
     * 早于登记到达 → {@link #applyAck} 查不到条目（ok=true 直接 return false）→
     * `removePending` 不执行：文件永久留守待发队列，界面还谎报成功。
     * 另外 ack(false) 抢跑时 applyAck 已把气泡改成失败并摘掉登记，这里再无条件写 OK 会把它盖回去。
     */
    void finishSingle(Session s, Msg item, String sha, long offset) throws Exception {
        if (rejectedAck.remove(item.mid)) {
            // 对端在推字节期间就回绝了（建接收通道失败：目录不可写/磁盘满）：不再发 file_done、也不登记
            Speeds.end(item.mid);
            emitStatus(statusMsg(item, Msg.ST_FAILED, 100));
            L.log("文件 " + item.fileName + " 对端提前拒收，留在待发队列等补发");
            return;
        }
        awaitingAck.put(item.mid, new AckWait(item, s.remoteIp()));   // ① 先登记，防 ack 抢跑
        s.send(Protocol.fileDone(item.mid, sha));                     // ② 再发
        Speeds.end(item.mid);
        // ③ 还没收到回执 → 停在「已推完、待确认」，绝不提前写成功（P2-2，与安卓同口径）：
        //    之前这里直接写 OK，ack(false) 到达才变红，用户先看到一次假的"已发送"；
        //    PcMain 只对 OK/FAILED 补写历史，SENDING 不会落成「已完成」。
        if (awaitingAck.containsKey(item.mid)) {
            emitStatus(statusMsg(item, Msg.ST_SENDING, 100));
        }
        L.log("文件发送完成 " + item.fileName + " " + sentTotal(item) + "，等对端回报落盘");
        // 触发过续传 → 等对端回发 MD5 做端到端核对（异步，不阻塞发送线程）
        if (offset > 0) {
            L.rx.startVerifyWatch(item, offset);
        }
    }

    /**
     * 处理对端的落盘回执；返回 true = 这是一条「正在等回执」的文件（调用方无需再按通用 ack 处理）。
     */
    boolean applyAck(String mid, boolean ok) {
        if (mid == null || mid.isEmpty()) {
            return false;
        }
        AckWait w = awaitingAck.remove(mid);
        if (w == null) {
            // 字节还在推 = 对端提前回绝（建接收通道就失败了）：记一笔，收尾时按失败处理
            if (!ok && L.inFlight.contains(mid)) {
                rejectedAck.add(mid);
                L.log("对端提前回绝接收 " + mid + "（多半是它那边存不了），本端收尾按失败处理");
                return true;
            }
            return false;
        }
        if (!ok) {
            // 对端明说没存好：**条目继续留在待发队列**（下次握手自动重发/续传），气泡改失败
            emitStatus(statusMsg(w.item, Msg.ST_FAILED, 100));
            L.log("对端回报文件未收妥 " + w.item.fileName + "（mid=" + mid + "），留在待发队列等补发");
            return true;
        }
        if (L.store != null) {
            L.store.removePending(mid);
        }
        // 记送达台账（S22/S31）：之后同一 mid 再被待发补发 / hsync 补齐点到时直接跳过
        markFileDelivered(mid, w.item.peerIp);
        emitStatus(statusMsg(w.item, Msg.ST_OK, 100));;
        L.log("对端已确认落盘 " + w.item.fileName + "，出待发队列");
        return true;
    }

    /**
     * 回执看门狗（心跳每 5s 调度）：登记超过 {@link #ACK_TIMEOUT_MS} 还没等到 ack 就撤掉登记。
     * **只清表**：不改气泡（对端若是不回 ack 的老构建，保持原来的「已发送」表现），也不出待发队列 ——
     * 下次握手 file_query 一问（offset==size）就会补 ack 自愈。
     */
    void scanAckTimeouts() {
        if (awaitingAck.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<String, AckWait> e : awaitingAck.entrySet()) {
            AckWait w = e.getValue();
            if (now - w.at < ACK_TIMEOUT_MS) {
                continue;
            }
            if (awaitingAck.remove(e.getKey(), w)) {
                L.log("等对端落盘回执超时 " + w.item.fileName + "（mid=" + e.getKey()
                        + "），条目留在待发队列，下次握手自动核对");
            }
        }
    }

}
