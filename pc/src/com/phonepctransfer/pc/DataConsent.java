package com.phonepctransfer.pc;

/**
 * 「数据流量确认」（v2.7）的结果码与固定文案。
 *
 * <p>与 Android 端 {@code net/Protocol.kt} 里的 {@code DataConsent} / {@code DataConsentText}
 * 严格同构：两端用同一套 kind 取值与同一套提示文案，界面上不会出现两种说法。
 *
 * <p>触发判据是**两端各自的网络环境**，不是对端 IP 的网段：
 * <ul>
 *   <li>本机在运营商数据网络上 → 本机提醒一次（每次使用都提醒，不记忆）</li>
 *   <li>对端在运营商数据网络上 → 由 {@code data_ask} 让对端自己提醒它的用户</li>
 * </ul>
 * PC 端恒为「有线/无线局域网」，所以本机侧从不弹框，只会去问对端。
 */
public final class DataConsent {

    private DataConsent() {
    }

    /** 同意（本机不需要提醒，或本机与对端都同意） */
    public static final int OK = 0;

    /** 本机用户拒绝用数据流量（PC 端不会出现，保留常量与 Android 对齐） */
    public static final int LOCAL_REFUSED = 1;

    /** 对端拒绝用数据流量接收 */
    public static final int REMOTE_REFUSED = 2;

    /** 目标设备当前没有健康会话 */
    public static final int NO_SESSION = 3;

    /**
     * 已把 data_ask 发出去，但 90s 内没等到 data_ans（对端锁屏/界面忙/半死）。
     * <p>与 Android 端 {@code REMOTE_TIMEOUT} 同义：**不是拒绝**，不投「拒收」票、不关文件管线；
     * 条目留在待发队列，下次握手或用户点继续时再试。
     */
    public static final int REMOTE_TIMEOUT = 4;

    public static final String KIND_FILE = "file";
    public static final String KIND_VOICE = "voice";
    public static final String KIND_VIDEO = "video";

    /** 操作名（确认框里的「$label」） */
    public static String labelOf(String kind) {
        if (KIND_VOICE.equals(kind)) {
            return "语音通话";
        }
        if (KIND_VIDEO.equals(kind)) {
            return "视频通话";
        }
        return "文件";
    }

    /** 接收方确认框里的对象描述（文件带上名字，通话只说类型） */
    public static String incomingLabelOf(String kind, String name) {
        if (KIND_VOICE.equals(kind)) {
            return "语音通话";
        }
        if (KIND_VIDEO.equals(kind)) {
            return "视频通话";
        }
        if (name == null || name.isEmpty()) {
            return "文件";
        }
        return "文件 " + name;
    }

    /**
     * 发起方看到「对端拒绝用数据流量接收」时的提示文本，斜杠按三个场景分别取值：
     * 「对方拒绝了使用数据流量接受文件 / 接受语音通话 / 接受视频通话」。
     */
    /** 发起方看到「对端迟迟未确认」时的提示文本（可重试，不是拒绝） */
    public static String peerTimeoutText(String kind) {
        return "对方未确认（" + labelOf(kind) + "请求已保留，可重试）";
    }

    public static String peerRefusedText(String kind) {
        if (KIND_VOICE.equals(kind)) {
            return "对方拒绝了使用数据流量接受语音通话";
        }
        if (KIND_VIDEO.equals(kind)) {
            return "对方拒绝了使用数据流量接受视频通话";
        }
        return "对方拒绝了使用数据流量接受文件";
    }
}
