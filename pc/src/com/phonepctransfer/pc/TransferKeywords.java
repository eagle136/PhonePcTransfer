package com.phonepctransfer.pc;

import java.util.Locale;

// 由 tools/ipdata/GenBlocklist.java 生成，勿手工编辑（词表在 GenBlocklist.WORDS 里维护）。
// 判定口径（写死，顺序不可调）：命中词表 且 codePointCount < 30 且
// 去掉「数字与数字之间」的空格（含全角）后最长连续数字段 >= 12。
// 只作用于接收侧文本消息；剪贴板、文件备注、通话不过滤。

public final class TransferKeywords {

    /** 词表条数（A 组 26 + B 组 4） */
    public static final int WORD_COUNT = 30;
    /** 词表指纹：按数组顺序逐行 sha256 */
    public static final String WORDS_SHA256 = "a3ba69b19efb4adffb27f786e6459661ea32de7f1fa9f0c5622c88fe7ca413ed";
    /** 与名单同批生成（勿手工编辑本文件；词表在 GenBlocklist.WORDS 里维护） */
    public static final String GENERATED_AT = "2026-10-01 02:54:11";
    public static final String SOURCE_FILE = "highrisk_ipv6_blocklist.txt";
    public static final int LIST_SEGMENT_COUNT = 4362;
    public static final String LIST_SHA256 = "b57ece2543d97de95836e26729d2a9d60f17a1d281ba9b3ecbb59b7593b1945a";

    private static final String[] WORDS = {
        "炒股",
        "荐股",
        "带你炒",
        "个股推荐",
        "虚拟币",
        "虚拟货币",
        "加密货币",
        "比特币",
        "btc",
        "usdt",
        "泰达币",
        "区块链",
        "赌场",
        "网赌",
        "博彩",
        "开盘号码",
        "稳赚",
        "包赢",
        "返水",
        "充值送",
        "老师带单",
        "跟单",
        "杀猪盘",
        "内幕消息",
        "杠杆爆仓回本",
        "买彩票",
        "投资",
        "理财",
        "转账",
        "缅甸"
    };

    /** 字数上限：等于或超过就不判（长文本多为正常叙述） */
    public static final int MAX_TEXT_CODEPOINTS = 30;
    /** 连续数字段阈值：全工程唯一可调常量，收紧口径只动它，不动词表 */
    public static final int MIN_DIGIT_RUN = 12;

    private TransferKeywords() { }

    /** 这条接收文本是否应当「静默丢弃」（发送端照旧收到 ack，四处不留痕迹） */
    public static boolean isSilentlyFiltered(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (text.codePointCount(0, text.length()) >= MAX_TEXT_CODEPOINTS) {
            return false;
        }
        String norm = normalize(text);
        boolean wordHit = false;
        for (String w : WORDS) {
            if (norm.indexOf(w) >= 0) {
                wordHit = true;
                break;
            }
        }
        if (!wordHit) {
            return false;
        }
        return maxDigitRunAfterGlue(norm) >= MIN_DIGIT_RUN;
    }

    /** 全角数字折半角 + 拉丁小写化（只影响 BTC / USDT 两条，故只做一次） */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') {
                sb.append((char) ('0' + (c - '０')));
            } else {
                sb.append(c);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    /** 只粘「数字与数字之间」的空白（含全角空格）后，求最长连续数字段 */
    static int maxDigitRunAfterGlue(String s) {
        int best = 0;
        int run = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                run++;
                if (run > best) {
                    best = run;
                }
                continue;
            }
            if (isSpaceish(c) && run > 0 && nextIsDigit(s, i)) {
                continue;                       // 数字之间的空格：跳过，游标继续
            }
            run = 0;
        }
        return best;
    }

    static boolean isSpaceish(char c) {
        return c == 0x0020 || c == 0x0009 || c == 0x000A || c == 0x000D || c == 0x00A0
                || c == 0x1680 || c == 0x2007 || c == 0x202F || c == 0x205F
                || c == 0x3000 || c == 0xFEFF;
    }

    /** 下一个非空白字符是不是数字（往前看，连续多个空格也只粘一次） */
    static boolean nextIsDigit(String s, int from) {
        for (int i = from + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isSpaceish(c)) {
                continue;
            }
            return c >= '0' && c <= '9';
        }
        return false;
    }

    public static int wordCount() {
        return WORDS.length;
    }
}
