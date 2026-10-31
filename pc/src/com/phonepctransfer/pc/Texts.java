package com.phonepctransfer.pc;

/** 与 Android 端 data/MsgItem.kt 中 TextUtil 对齐的文本处理 */
public final class Texts {

    public static final int HEAD = 300;
    public static final int TAIL = 150;

    private Texts() {
    }

    public static boolean isTruncated(String s) {
        return s != null && s.length() > HEAD + TAIL;
    }

    public static String headTail(String s, boolean expanded) {
        if (s == null) {
            return "";
        }
        if (expanded || !isTruncated(s)) {
            return s;
        }
        int omitted = s.length() - HEAD - TAIL;
        return s.substring(0, HEAD) + "\n⋯（中间省略 " + omitted + " 字）⋯\n"
                + s.substring(s.length() - TAIL);
    }

    public static String humanSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", Double.valueOf(kb));
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", Double.valueOf(mb));
        }
        return String.format("%.2f GB", Double.valueOf(mb / 1024.0));
    }

    /** 长文件名中间省略：保留头部与尾部（含扩展名），中间以 … 代替（与 TextUtil.middleEllipsis 对齐） */
    public static String middleEllipsis(String name, int max) {
        if (name == null || name.length() <= max) {
            return name == null ? "" : name;
        }
        int dot = name.lastIndexOf('.');
        String ext = (dot > 0 && dot < name.length() - 1 && name.length() - dot <= 10)
                ? name.substring(dot) : "";
        String base = ext.isEmpty() ? name : name.substring(0, dot);
        int keep = Math.max(6, max - ext.length() - 1); // 1 = 省略号
        int head = (keep + 1) / 2;
        int tail = keep - head;
        return base.substring(0, head) + "…"
                + base.substring(base.length() - tail) + ext;
    }

    public static String preview(String s, int n) {
        if (s == null) {
            return "";
        }
        String flat = s.replace("\r", " ").replace("\n", " ");
        return flat.length() <= n ? flat : flat.substring(0, n) + "…";
    }
}
