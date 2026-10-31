package com.phonepctransfer.pc;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * UI 字体族解析（2026-09-29 新增）—— 全项目字体族的**唯一真源**。
 *
 * <p>策略（用户定调）：优先宋体（笔画细、小字号有内嵌点阵位图，100% 缩放下最锐利）；
 * 系统没有宋体（非中文 Windows / 精简系统）→ 回退系统默认 {@link Font#DIALOG}。
 *
 * <p>判定铁律：必须用 {@link GraphicsEnvironment#getAvailableFontFamilyNames()}
 * 做**精确** contains 判存在 —— 字体缺失时 {@code new Font("宋体",...)} 会静默回退
 * Dialog、不报错，只靠 new Font 会误判「宋体存在」。族名不做 startsWith 模糊匹配。
 *
 * <p>族名双写：中文 Windows 的族名是「宋体」，英文环境是「SimSun」；两个都列上，
 * 按本机实际返回的清单命中。
 *
 * <p><b>⚠ 字重铁律（2026-09-30）：本字体族一律 {@link Font#PLAIN}，禁止 {@code Font.BOLD}。</b>
 * 宋体只有一个字面（无粗体），请求 BOLD 时拿到的是**合成假粗**：实测同一段文字 BOLD 与 PLAIN
 * 的步进宽度完全相同（90px），墨迹却多 25%（369 → 464 像素）——也就是把每条笔画原地加宽、
 * 相邻笔画并成墨块，15px 下看着就是「像粗体但形状不正常」。需要层级感请用<b>颜色</b>
 *（{@code TEXT_MAIN} vs {@code TEXT_SUB}、{@code ACCENT}）或<b>底色</b>（{@code ROW_CURRENT}），
 * 别靠字重。拉丁字体（{@code Font.MONOSPACED} 计时器）有真粗体字面，不受此限。
 *
 * <p><b>⚠ 字号铁律：不低于 {@code Ui.FS_MIN}=13px，且字号只走档位常量。</b>
 * 别在 HTML 标签的内联 CSS 里写 {@code font-size:10px/11px}：一是绕过档位体系（中文小字
 * 可读性差），二是内联 {@code color:#666} 这类写死的颜色不跟主题（深色下灰字贴黑底看不见）。
 *
 * <p><b>⚠ 自绘文字注意：</b>{@code KEY_ANTIALIASING=ON}（画圆角/形状用的）会**顺带作用到文字**
 *（实测：同一行宋体 15px，不设形状 AA 时灰阶像素 0 个，设了之后 545 个）——Java2D 在没有显式
 * {@code KEY_TEXT_ANTIALIASING} 时拿 {@code KEY_ANTIALIASING} 兜底。所以凡是「拷一份 Graphics
 * 开 AA 画形状、又在这份 Graphics 上画字/交给子组件画字」的地方，必须同时显式设
 * {@code KEY_TEXT_ANTIALIASING}（本项目要的是 OFF，锐利点阵），否则整窗重绘与局部重绘的
 * 文字会一个糊一个清晰（见 {@code WindowChrome} 的两处 {@code paintChildren}）。
 *
 * <p>初始化时机：{@link #FAMILY} 是静态字段，类首次加载时访问
 * {@code GraphicsEnvironment}（触发 AWT 初始化）。全项目最早触点 =
 * {@code Ui.install()}（PcMain.main 里，在 sun.java2d.uiScale / 文本 AA 属性设置**之后**），
 * 顺序安全；tools 探针（FontProbe）可直接引用本类。
 *
 * <p>零外部依赖：只用 JDK 自带 AWT。
 */
public final class Fonts {

    /** 已解析的 UI 字体族名（进程内只解析一次） */
    public static final String FAMILY = resolve();

    private static String resolve() {
        String[] prefer = { "宋体", "SimSun", "NSimSun" };
        Set<String> avail = new HashSet<String>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                        .getAvailableFontFamilyNames()));
        for (String f : prefer) {
            if (avail.contains(f)) {
                return f;
            }
        }
        return Font.DIALOG; // 没有宋体 → 系统默认（逻辑字体族，永远存在）
    }

    private Fonts() { }
}
