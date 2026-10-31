package com.phonepctransfer.pc;

import java.awt.Color;
import java.lang.reflect.Method;

/**
 * 主题切换槽位映射离线断言（2026-09-29，1of3 A9.1）。
 *
 * <p>模拟浅→深与深→浅两个方向：oldVals = snapshot() → assignPalette(x) → newVals = snapshot()，
 * 对 23 个槽位逐一断言 map.get(oldVals[i]) == newVals[i]（**实例相等**，不是值相等）。
 * 每个方向打印 23 行「槽位名 旧值→新值 ✓/✗」+ 一行小结论，末行「结论: 46/46 ✓」。
 *
 * <p>Ui.snapshot / buildThemeMap / assignPalette 都是 private（唯一真源，不对外扩权），
 * 同包也破不了 private，故用 getDeclaredMethod + setAccessible 反射调用。
 *
 * <pre>
 * javac -encoding UTF-8 -cp pc/build/classes -d pc/build/probe pc/tools/PaletteMapCheck.java
 * java  -Dfile.encoding=UTF-8 -cp "pc/build/classes;pc/build/probe" com.phonepctransfer.pc.PaletteMapCheck
 * </pre>
 */
public final class PaletteMapCheck {

    private PaletteMapCheck() { }

    /** 必须与 Ui.snapshot() 的槽位顺序逐一对应（23 槽，顺序即语义） */
    private static final String[] NAMES = {
            "SURFACE", "PAGE_BG", "TEXT_MAIN", "TEXT_SUB", "TEXT_TERTIARY", "BORDER",
            "DIVIDER", "BUBBLE_SELF", "BUBBLE_PEER", "BUBBLE_META", "BUBBLE_TEXT",
            "ROW_CURRENT", "ROW_CONNECTED", "IP_TEXT", "IP_CAPTION",
            "CHROME_BG", "CHROME_FG", "CHROME_LINE", "WIN_BORDER",
            "SCROLL_TRACK", "SCROLL_THUMB", "SCROLL_THUMB_HOVER", "SCROLL_ARROW" };

    public static void main(String[] args) throws Exception {
        Method snapshot = Ui.class.getDeclaredMethod("snapshot");
        snapshot.setAccessible(true);
        Method assign = Ui.class.getDeclaredMethod("assignPalette", boolean.class);
        assign.setAccessible(true);
        Method build = Ui.class.getDeclaredMethod("buildThemeMap", Color[].class, Color[].class);
        build.setAccessible(true);
        Class<?> mapCls = build.getReturnType();
        Method get = mapCls.getDeclaredMethod("get", Color.class);
        get.setAccessible(true);

        int ok = 0;
        // 方向一：浅 → 深（先把调色板钉到浅色，保证起点确定）
        assign.invoke(null, Boolean.FALSE);
        ok += runDir(snapshot, assign, build, get, "浅→深", true);
        // 方向二：深 → 浅（此时调色板已是深色）
        ok += runDir(snapshot, assign, build, get, "深→浅", false);

        int total = 2 * NAMES.length;
        System.out.println("结论: " + ok + "/" + total + (ok == total ? " ✓" : " ✗"));
        System.exit(ok == total ? 0 : 1);
    }

    /** 跑一个方向：打印 23 行「槽位名 旧值→新值 ✓/✗」+ 一行小结论，返回通过数 */
    private static int runDir(Method snapshot, Method assign, Method build, Method get,
                              String label, boolean toDark) throws Exception {
        Color[] oldVals = (Color[]) snapshot.invoke(null);
        assign.invoke(null, toDark);
        Color[] newVals = (Color[]) snapshot.invoke(null);
        Object map = build.invoke(null, (Object) oldVals, (Object) newVals);
        int ok = 0;
        for (int i = 0; i < NAMES.length; i++) {
            Color mapped = (Color) get.invoke(map, oldVals[i]);
            boolean pass = mapped == newVals[i];   // 实例相等：必须是该槽位的新值实例本身
            if (pass) {
                ok++;
            }
            System.out.printf("  %-18s %s -> %s  %s%n", NAMES[i], hex(oldVals[i]),
                    hex(newVals[i]), pass ? "✓" : "✗");
        }
        System.out.println(label + " 小结论: " + ok + "/" + NAMES.length
                + (ok == NAMES.length ? " ✓" : " ✗"));
        return ok;
    }

    private static String hex(Color c) {
        return String.format("#%06X", c.getRGB() & 0xFFFFFF);
    }
}
