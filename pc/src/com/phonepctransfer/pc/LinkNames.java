package com.phonepctransfer.pc;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.regex.Pattern;

// ==================================================================
// 文件名/路径净化与唯一命名工具（v2.7 拆分自 Link.java，纯静态零耦合）。
// ==================================================================

final class LinkNames {

    private LinkNames() {
    }

    // ==================================================================
    // 发送端名校验（与 Android 端 util/NameRules.kt 同一口径，双端同步改）：
    //   ① UTF-8 字节数 ≤ MAX_NAME_BYTES（含扩展名）→ 提示「文件名过长」并禁止发送；
    //   ② 含可能影响 Linux/Windows 的字符/保留名/结尾点空格 → 提示后取消本次传输任务。
    // 校验失败 = 整次发送请求取消；接收端仍用 sanitize 兜底 + clampName 截断。
    // ==================================================================

    /**
     * 文件名上限（UTF-8 字节数，含扩展名）。
     * <p>取 200：仍低于 ext4/NTFS 单段 255 字节的硬上限，又足够容纳「项目名+日期+后缀」这类
     * 真实长名（原 48 字节把大量正常文件名挡在发送前）。
     */
    static final int MAX_NAME_BYTES = 200;

    private static final char[] ILLEGAL_CHARS = {'\\', '/', ':', '*', '?', '"', '<', '>', '|'};

    static int utf8Len(String s) {
        try {
            return s.getBytes("UTF-8").length;
        } catch (java.io.UnsupportedEncodingException e) {
            return s.length();
        }
    }

    /** @return null = 通过；否则为不含主语的原因（调用方拼「文件名 / 文件夹名 / 成员」前缀） */
    static String checkName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            return "为空";
        }
        if (!name.equals(raw)) {
            return "不能以空格开头或结尾";
        }
        int bytes = utf8Len(name);
        if (bytes > MAX_NAME_BYTES) {
            return "过长（" + bytes + " 字节，上限 " + MAX_NAME_BYTES + " 字节）";
        }
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            boolean bad = ch < 0x20 || ch == 0x7F;
            for (char c : ILLEGAL_CHARS) {
                if (ch == c) {
                    bad = true;
                    break;
                }
            }
            if (bad) {
                String show = ch < 0x20 || ch == 0x7F
                        ? String.format("\\x%02X", Integer.valueOf(ch)) : String.valueOf(ch);
                return "含特殊字符「" + show + "」，可能影响 Linux/Windows 文件系统";
            }
        }
        if (".".equals(name) || "..".equals(name)) {
            return "不能是「" + name + "」";
        }
        if (name.endsWith(".")) {
            return "不能以点结尾";
        }
        if (RESERVED_NAME.matcher(name).matches()) {
            return "「" + name + "」是系统保留名";
        }
        return null;
    }

    static String fileNameError(String name) {
        String r = checkName(name);
        return r == null ? null : "文件名" + r;
    }

    static String folderNameError(String name) {
        String r = checkName(name);
        return r == null ? null : "文件夹名" + r;
    }

    /** 逐段校验成员相对路径（只有 '/' 才是分隔符：段内出现 '\' 本身就是非法字符，必须被查出来） */
    static String relPathError(String relPath) {
        String[] segs = relPath.split("/");
        java.util.List<String> kept = new ArrayList<String>();
        for (String seg : segs) {
            if (!seg.isEmpty()) {
                kept.add(seg);
            }
        }
        if (kept.isEmpty()) {
            return "成员「" + relPath + "」文件名为空";
        }
        String full = String.join("/", kept);
        for (int i = 0; i < kept.size(); i++) {
            String reason = checkName(kept.get(i));
            if (reason == null) {
                continue;
            }
            return i == kept.size() - 1
                    ? "成员「" + full + "」的文件名" + reason
                    : "成员「" + full + "」的目录段「" + kept.get(i) + "」" + reason;
        }
        return null;
    }

    /** 文件夹整体校验：目录名 + 全部成员相对路径 */
    static String folderError(String folderName, java.util.List<String> relPaths) {
        String e = folderNameError(folderName);
        if (e != null) {
            return e;
        }
        for (String rel : relPaths) {
            String r = relPathError(rel);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** 按码点截断到 maxBytes（UTF-8），不劈开代理对 */
    static String trimToBytes(String s, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        if (utf8Len(s) <= maxBytes) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int n = cp < 0x80 ? 1 : (cp < 0x800 ? 2 : (cp < 0x10000 ? 3 : 4));
            if (used + n > maxBytes) {
                break;
            }
            sb.appendCodePoint(cp);
            used += n;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /**
     * 接收端 [MAX_NAME_BYTES] 字节兜底（超长保留扩展名 + 预留 8 字节给 " (n)" 序号）。
     */
    static String clampName(String name) {
        if (utf8Len(name) <= MAX_NAME_BYTES) {
            return name;
        }
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot - 1 >= 1 && name.length() - dot - 1 <= 12) {
            ext = name.substring(dot);
        }
        String stem = ext.isEmpty() ? name : name.substring(0, dot);
        int budget = MAX_NAME_BYTES - utf8Len(ext) - 8;
        if (budget < 4) {
            budget = 4;
        }
        return trimToBytes(stem, budget) + ext;
    }

    static String sanitize(String name) {
        String base = name;
        int i = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (i >= 0) {
            base = base.substring(i + 1);
        }
        String cleaned = stripEmoji(base).replaceAll("[\\\\:*?\"<>|]", "_").trim();
        cleaned = winSafe(cleaned);
        if (cleaned.isEmpty()) {
            return "file_" + randomTag();
        }
        // 原名几乎全是 emoji（如「😀.png」只剩扩展名）→ 随机前缀补主名
        if (cleaned.startsWith(".")) {
            int dot = cleaned.lastIndexOf('.');
            if (dot == 0 || cleaned.substring(0, dot).trim().isEmpty()) {
                return "file_" + randomTag() + cleaned.substring(dot);
            }
        }
        return clampName(cleaned);
    }

    /** 目录名/相对路径段清洗（比 sanitize 更狠：/ \ 都换掉，防路径穿越；并受 MAX_NAME_BYTES 上限约束） */
    static String segSanitize(String s) {
        String base = stripEmoji(s).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        base = winSafe(base);
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            base = "_" + System.currentTimeMillis();
        }
        return clampName(base);
    }

    /** Windows 保留设备名与结尾的点/空格（对端可能是 PC，这些名字在 NTFS 上创建不了） */
    static final java.util.regex.Pattern RESERVED_NAME =
            java.util.regex.Pattern.compile("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?$");

    static String winSafe(String n) {
        String s = n;
        String tail = "";
        while (!s.isEmpty()
                && (s.charAt(s.length() - 1) == '.' || s.charAt(s.length() - 1) == ' ')) {
            tail = tail + "_";
            s = s.substring(0, s.length() - 1);
        }
        if (RESERVED_NAME.matcher(s).matches()) {
            s = s + "_";
        }
        return s + tail;
    }

    /** emoji 直接删除（Windows 资源管理器/部分字体显示不了；中日韩等正常文字不受影响） */
    static String stripEmoji(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (!isEmoji(cp)) {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    static boolean isEmoji(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2B00 && cp <= 0x2BFF)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || cp == 0x200D || cp == 0x20E3;
    }

    static String randomTag() {
        return String.format("%06x",
                Integer.valueOf(new java.security.SecureRandom().nextInt(0x1000000)));
    }

    static String sanitizeDir(String name) {
        return segSanitize(name);
    }

    /** 相对路径清洗：逐段清洗后再拼回，拒绝路径穿越 */
    static String sanitizeRelPath(String relPath) {
        String[] segs = relPath.split("[/\\\\]");
        StringBuilder sb = new StringBuilder();
        for (String seg : segs) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(File.separator);
            }
            sb.append(segSanitize(seg));
        }
        return sb.toString();
    }

    /**
     * **原子**创建不重名文件（返回已创建好的空文件），自动追加 " (1)"、" (2)" 序号。
     * 「exists 检查→返回路径→调用方创建」存在 TOCTOU 竞态窗口:两台设备同时传
     * 同名文件时可能拿到同一路径,两个 FileOutputStream 交错写把文件写坏;
     * 用 Files.createFile 的原子性:谁先创建成功谁用,失败者自动换下一个名字。
     * 保存目录可以由用户改成「下载」这类已有大量文件的目录，直接覆盖会毁掉旧文件。
     */
    static File createUniqueFile(File dir, String name) throws java.io.IOException {
        File f = new File(dir, name);
        if (tryCreateNew(f)) {
            return f;
        }
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        for (int i = 1; i < 100000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (tryCreateNew(c)) {
                return c;
            }
        }
        File last = new File(dir, System.currentTimeMillis() + "_" + name);
        tryCreateNew(last);
        return last;
    }

    /** 同名目录不覆盖，自动追加 " (1)" 序号；原子创建（消除与 createUniqueFile 同源的竞态） */
    static File createUniqueDir(File parent, String name) throws java.io.IOException {
        File f = new File(parent, name);
        if (tryCreateDir(f)) {
            return f;
        }
        for (int i = 1; i < 100000; i++) {
            File c = new File(parent, name + " (" + i + ")");
            if (tryCreateDir(c)) {
                return c;
            }
        }
        File last = new File(parent, name + "_" + System.currentTimeMillis());
        tryCreateDir(last);
        return last;
    }

    static boolean tryCreateNew(File f) throws java.io.IOException {
        try {
            Files.createFile(f.toPath());
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    static boolean tryCreateDir(File d) throws java.io.IOException {
        try {
            Files.createDirectory(d.toPath());
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        }
    }

    static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteRecursively(k);
            }
        }
        try {
            f.delete();
        } catch (Throwable ignore) {
        }
    }

    static final int MAX_TREE_DEPTH = 64;
    static final int MAX_TREE_FILES = 20000;

    /**
     * 递归枚举目录内全部文件（相对路径），跳过不可读项。
     *
     * @param emptyDirs 收集「整棵子树一个文件都没有」的目录（相对路径）
     *                  —— ⚠ 本轮起**不再随 dir_meta 下发**（空文件夹一律忽略、不记清单），
     *                  参数保留只为既有调用点签名不变；发送侧请在组帧时传空列表。
     * @param skipped   [0] 累计因深度/数量上限被跳过的分支数——绝不让内容无声消失
     * @return 子树内文件数
     */
    static int listFilesRecursive(File dir, String prefix, List<File> out,
                                          List<String> emptyDirs, int[] skipped, int depth) {
        if (depth > MAX_TREE_DEPTH) {
            skipped[0]++;
            return 0;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return 0;
        }
        Arrays.sort(kids, Comparator.comparing(File::getName));
        int count = 0;
        for (File k : kids) {
            if (Files.isSymbolicLink(k.toPath())) {
                // 目录符号链接会被 File.isDirectory() 跟随，一路递归出去能把整盘扫进来
                // （甚至自引用成环）→ 一律跳过并计数，绝不跟随
                skipped[0]++;
                continue;
            }
            if (k.isDirectory()) {
                count += listFilesRecursive(k,
                        prefix.isEmpty() ? k.getName() : prefix + "/" + k.getName(),
                        out, emptyDirs, skipped, depth + 1);
            } else if (k.isFile()) {
                if (out.size() < MAX_TREE_FILES) {
                    out.add(k);
                    count++;
                } else {
                    skipped[0]++;
                }
            } else {
                // 既不是目录也不是文件（损坏的符号链接 / 已被删除 / 特殊文件）：
                // 明说跳过，不再无声消失
                skipped[0]++;
            }
        }
        // 走到这里说明 kids != null（确实读到了目录内容），才能判「这棵子树真的是空的」
        if (count == 0 && !prefix.isEmpty()) {
            emptyDirs.add(prefix);
        }
        return count;
    }

    static String relPathOf(File root, File f) {
        String rp = root.toPath().relativize(f.toPath()).toString();
        return rp.replace('\\', '/');
    }
}
