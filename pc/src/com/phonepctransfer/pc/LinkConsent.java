package com.phonepctransfer.pc;

import java.util.concurrent.ConcurrentHashMap;

// ==================================================================
// 数据流量确认（v2.7 拆分自 Link.java）：data_ask/data_ans 信箱与闸门。
// ==================================================================

final class LinkConsent {

    final Link L;

    LinkConsent(Link L) {
        this.L = L;
    }

    // ==================== 数据流量确认（v2.7）====================

    /** 等 data_ans 应答用：mid → 容量 1 的信箱（与 Android 端同构） */
    private final ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<Boolean>> dataAnsBoxes =
            new ConcurrentHashMap<String, java.util.concurrent.ArrayBlockingQueue<Boolean>>();

    static final long DATA_ANS_WAIT_MS = 90_000L;

    void deliverDataAns(String mid, boolean ok) {
        java.util.concurrent.ArrayBlockingQueue<Boolean> box = dataAnsBoxes.get(mid);
        if (box != null) {
            box.offer(Boolean.valueOf(ok));
        }
    }

    /**
     * 发起文件/通话前的数据流量确认。**会阻塞**调用线程。
     *
     * PC 端恒不在运营商数据网络 → 本机侧**永不弹框**，只需要问对端；
     * 对端不支持 `dm`（老版本）时直接放行，保持改造前的行为。
     *
     * ⚠ 只能在专用工作线程调用（daemon(...)），绝不能放在 UI 线程或读线程里。
     *
     * @return {@link DataConsent} 之一
     */
    public int ensureDataConsent(String peerIp, String mid, String kind, String label,
                                 String name, long size) {
        Link.Session s = L.sessionByGroup(peerIp);
        if (s == null) {
            return DataConsent.NO_SESSION;
        }
        if (!s.caps.dm) {
            return DataConsent.OK;
        }
        java.util.concurrent.ArrayBlockingQueue<Boolean> box =
                new java.util.concurrent.ArrayBlockingQueue<Boolean>(1);
        dataAnsBoxes.put(mid, box);
        try {
            if (!L.sendOrDrop(s, Protocol.dataAsk(mid, kind, name, size), "data_ask", true)) {
                L.log("数据流量确认：对端会话不可用，按未同意处理");
                return DataConsent.REMOTE_REFUSED;
            }
            Boolean r = box.poll(DATA_ANS_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (r == null) {
                L.log("数据流量确认：对端 " + (DATA_ANS_WAIT_MS / 1000) + "s 内未应答，按未同意处理");
                return DataConsent.REMOTE_REFUSED;
            }
            return r.booleanValue() ? DataConsent.OK : DataConsent.REMOTE_REFUSED;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return DataConsent.REMOTE_REFUSED;
        } finally {
            dataAnsBoxes.remove(mid);
        }
    }

    /** 应答对端的数据流量确认请求（PC 侧恒为同意，见 handleFrame 的 data_ask 分支） */
    public void replyDataAsk(String peerIp, String mid, boolean ok) {
        if (mid == null || mid.isEmpty()) {
            return;
        }
        final Link.Session fs = L.sessionByGroup(peerIp);
        if (fs == null) {
            return;
        }
        L.uiSendExec.execute(() -> L.sendOrDrop(fs, Protocol.dataAns(mid, ok), "data_ans", true));
    }

    /**
     * 异步版数据流量确认：后台线程跑 {@link #ensureDataConsent}，结果回到 EDT 回调。
     * **UI 线程可直接调用**（Swing 的按钮回调走这里，绝不能阻塞 EDT）。
     *
     * @param onDone 在 EDT 上收到 {@link DataConsent} 结果码
     */
    public void ensureDataConsentAsync(String peerIp, String kind, String label,
                                       java.util.function.IntConsumer onDone) {
        Link.daemon(() -> {
            final int r = ensureDataConsent(peerIp, L.newMid(), kind, label, "", 0L);
            if (onDone != null) {
                javax.swing.SwingUtilities.invokeLater(() -> onDone.accept(r));
            }
        }, "pp-consent");
    }

    /** 供上层为一次确认生成 mid */
    public String newConsentMid() {
        return L.newMid();
    }


}
