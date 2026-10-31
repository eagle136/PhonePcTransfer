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
        return cleaned;
    }

    /** 目录名/相对路径段清洗（比 sanitize 更狠：/ \ 都换掉，防路径穿越） */
    static String segSanitize(String s) {
        String base = stripEmoji(s).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        base = winSafe(base);
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            base = "_" + System.currentTimeMillis();
        }
        return base;
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
     * 旧写法「exists 检查→返回路径→调用方创建」存在 TOCTOU 竞态窗口：两台设备同时传
     * 同名文件时可能拿到同一路径，两个 FileOutputStream 交错写把文件写坏。
     * 现在用 Files.createFile 的原子性：谁先创建成功谁用，失败者自动换下一个名字。
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
     * @param emptyDirs 收集「整棵子树一个文件都没有」的目录（相对路径），随 dir_meta 发给对端重建空目录
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
