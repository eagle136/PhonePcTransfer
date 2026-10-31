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
// 语音/视频通话发送（v2.7 拆分自 Link.java）。
// ==================================================================

final class LinkCall {

    final Link L;

    LinkCall(Link L) {
        this.L = L;
    }

    // ==================== 语音通话发送 ====================

    /**
     * 向指定 IP 的设备发一帧音频。会话不存在/发送失败只记日志（通话层自行兜底静音跳过）。
     *
     * @return 写入耗时 ms；-1 = 未发送（无会话 / 异常）—— 语音码率自适应用
     */
    public long sendAudioTo(String ip, int seq, long ts, byte[] data, int len) {
        Session s = L.sessionOf(ip);
        if (s == null) {
            return -1L;
        }
        try {
            return s.sendAudio(seq, ts, data, 0, len);
        } catch (Throwable t) {
            L.log("音频帧发送失败: " + t);
            return -1L;
        }
    }

    /**
     * 语音通话控制帧（invite / accept / reject / busy / end / cancel）。
     *
     * ⚠ 六个入口都由 UI 回调（EDT）调用，必须走 L.uiSendExec：EDT 直连 socket 时，
     * 一旦对端读不动就会把界面卡住（Android 端更狠 —— 直接抛 NetworkOnMainThreadException，
     * 被 sendOrDrop 当成断链，点一下通话按钮就掉线，见 2026-09-15 日志）。
     * 与 Android 端 io{} 对称。
     *
     * 返回「有没有可用会话」而非「是否已写出」：写操作已入队，尽力而为。
     */
    public boolean sendCallInvite(String name, String ip) {
        return sendCallInvite(name, ip, false, 1, 0);
    }

    /**
     * 发起呼叫（带视频协商）。
     *
     * ⚠ PC 端**恒以 vid=false 发出**（R2：PC 没有采集能力），走这个入口只为与 Android 对称，
     * 真正的「PC 不能发起视频」在 {@link #startVideoOutgoing()} 处硬拒绝。
     */
    public boolean sendCallInvite(String name, String ip, boolean vid, int lv) {
        return sendCallInvite(name, ip, vid, lv, 0);
    }

    /** 发起呼叫（带视频协商 + 语音帧长协商）。af 语义见 {@link Protocol#callInvite}。 */
    public boolean sendCallInvite(String name, String ip, boolean vid, int lv, int af) {
        return sendCallInvite(name, ip, vid, lv, af, 0, false, "");
    }

    /**
     * 发起呼叫（带视频 / 语音帧长 / **媒体通道**协商）。
     *
     * mport / mudp / msid 是本端已 bind 的媒体端口与本通 token，语义与缺省回退口径见
     * {@link Protocol#callInvite(String, String, boolean, int, int, int, boolean, String)}；
     * 0 / false / 空 = 本端未声明 ⇒ 对端只能落带内档。
     */
    public boolean sendCallInvite(String name, String ip, boolean vid, int lv, int af,
                                  int mport, boolean mudp, String msid) {
        Session s = L.target();
        if (s == null) {
            return false;
        }
        final boolean v = vid;
        final int l = lv;
        final int a = af;
        final int mp = mport;
        final boolean mu = mudp;
        final String ms = msid;
        L.uiSendExec.execute(() ->
                L.sendOrDrop(s, Protocol.callInvite(name, ip, v, l, a, mp, mu, ms), "call_invite", false));
        return true;
    }

    public void sendCallAccept(String ip) {
        sendCallAccept(ip, false, 1, 0);
    }

    /** 接听应答（带视频协商）：vid=true 表示同意以视频通话进行，lv 为本端可接受档位 */
    public void sendCallAccept(String ip, boolean vid, int lv) {
        sendCallAccept(ip, vid, lv, 0);
    }

    /** 接听应答（带视频协商 + 语音帧长协商） */
    public void sendCallAccept(String ip, boolean vid, int lv, int af) {
        sendCallAccept(ip, vid, lv, af, 0, false, "");
    }

    /** 接听应答（带视频 / 语音帧长 / **媒体通道**协商），字段语义同 {@link #sendCallInvite} 的同名三项 */
    public void sendCallAccept(String ip, boolean vid, int lv, int af,
                               int mport, boolean mudp, String msid) {
        Session s = L.sessionOf(ip);
        if (s != null) {
            final boolean v = vid && L.localVidCap;
            final int l = lv;
            final int a = af;
            final int mp = mport;
            final boolean mu = mudp;
            final String ms = msid;
            L.uiSendExec.execute(() ->
                    L.sendOrDrop(s, Protocol.callAccept(v, l, a, mp, mu, ms), "call_accept", true));
        }
    }

    /**
     * 振铃期逐档交换本端媒体三元组（任务单 2.5 / 方案 §4.3①）。
     * 字段语义见 {@link Protocol#callMedia(int, boolean, String, int)}。
     *
     * ⚠ 由 **pp-av-negotiate 线程**调用（振铃期的定档时序），不是 EDT；仍走 L.uiSendExec
     * 与其余通话控制帧同一条发送队列，行为一致。
     *
     * @return 有没有可用会话（写出已入队，尽力而为）；false = 主 TCP 已无会话 ⇒ 调用方按 ⑦ 处置
     */
    public boolean sendCallMedia(String ip, int mport, boolean mudp, String msid, int tierOrdinal) {
        Session s = L.sessionOf(ip);
        if (s == null) {
            return false;
        }
        L.uiSendExec.execute(() -> L.sendOrDrop(s,
                Protocol.callMedia(mport, mudp, msid, tierOrdinal), "call_media", true));
        return true;
    }

    public void sendCallReject(String ip) {
        Session s = L.sessionOf(ip);
        if (s != null) {
            L.uiSendExec.execute(() -> L.sendOrDrop(s, Protocol.callReject(), "call_reject", true));
        }
    }

    public void sendCallBusy(String ip) {
        Session s = L.sessionOf(ip);
        if (s != null) {
            L.uiSendExec.execute(() -> L.sendOrDrop(s, Protocol.callBusy(), "call_busy", true));
        }
    }

    public void sendCallEnd(String ip, String reason) {
        Session s = L.sessionOf(ip);
        if (s != null) {
            L.uiSendExec.execute(() -> L.sendOrDrop(s, Protocol.callEnd(reason), "call_end", true));
        }
    }

    public void sendCallCancel(String ip) {
        Session s = L.sessionOf(ip);
        if (s != null) {
            L.uiSendExec.execute(() -> L.sendOrDrop(s, Protocol.callCancel(), "call_cancel", true));
        }
    }

    // ==================== 视频通话（PC 端只收不发） ====================

    /** 该设备的对端能力（未握手/未连接返回 null） */
    public Link.PeerCaps peerCapsOf(String ip) {
        Session s = L.sessionOf(ip);
        return s == null ? null : s.caps;
    }

    /** 对端是否声明可接收视频 */
    public boolean peerVidCap(String ip) {
        Link.PeerCaps c = peerCapsOf(ip);
        return c != null && c.vid;
    }

    /**
     * PC 端发起视频：**恒拒绝**（R2）。
     * 留这个入口只为把「PC 不能发起视频」这件事收敛到一个可断言的点上。
     */
    public boolean startVideoOutgoing() {
        L.log("PC 端只接收视频、不发起视频（已拦截）");
        return false;
    }

    /**
     * 向指定 IP 的设备发一帧视频。
     *
     * ⚠ PC 端**不会**走到这里（cam=false、无编码器）；保留对称实现以便未来扩展与自检。
     *
     * @return 写入耗时 ms；-1 = 未发送
     */
    public long sendVideoTo(String ip, int seq, long ts, int flag, byte[] data, int len) {
        if (len > Protocol.MAX_VIDEO_FRAME) {
            L.log("视频帧超 128KB 闸门（" + len + "B），丢弃");
            // 与安卓同口径：被单帧闸门拦下**必须**计数并上报，否则这条静默丢就是下一个"lat 恒 0"。
            CallCounters.get().videoDroppedByGate.inc();
            return -1L;
        }
        Session s = L.sessionOf(ip);
        if (s == null || !s.caps.vid) {
            return -1L;
        }
        try {
            return s.sendVideo(seq, ts, flag, data, 0, len);
        } catch (Throwable t) {
            L.log("视频帧发送失败: " + t);
            return -1L;
        }
    }

    /** 编码参数变更通知 */
    public void sendVideoCfg(String ip, String codec, int w, int h, int fps, int br, int rot) {
        Session s = L.sessionOf(ip);
        if (s != null) {
            L.uiSendExec.execute(() -> L.sendOrDrop(s,
                    Protocol.videoCfg(codec, w, h, fps, br, rot), "video_cfg", true));
        }
    }

    /** 接收侧健康反馈（每 2s 一次）。{@code dropping}=接收端正处丢帧期（任务单 1.3） */
    public void sendVideoFb(String ip, int fps, int drop, int lat, boolean dropping, int kbps) {
        Session s = L.sessionOf(ip);
        if (s != null) {
            L.uiSendExec.execute(() -> L.sendOrDrop(s,
                    Protocol.videoFb(fps, drop, lat, dropping, kbps), "video_fb", true));
        }
    }

    /** 请求对端临时停/开视频 */
    public void sendVideoReq(String ip, boolean on) {
        Session s = L.sessionOf(ip);
        if (s != null) {
            L.uiSendExec.execute(() -> L.sendOrDrop(s, Protocol.videoReq(on), "video_req", true));
        }
    }

    /**
     * 请求对端**立刻给一个 IDR**（本端解码进程换代后用）。
     * 走主 TCP 既有的 video_req 帧 —— 控制帧的合法落点之一（I16），非阻塞：只 execute 进 uiSendExec。
     */
    public void sendVideoKeyRequest(String ip) {
        Session s = L.sessionOf(ip);
        if (s != null) {
            L.uiSendExec.execute(() -> L.sendOrDrop(
                    s, Protocol.videoReq(true, true), "video_req", true));
        }
    }

}
