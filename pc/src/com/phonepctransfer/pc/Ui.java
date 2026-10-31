package com.phonepctransfer.pc;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * 统一的现代扁平视觉风格（纯 Swing，零外部依赖）。
 *
 * <p>改造前所有按钮都是 Windows 原生立体灰按钮、输入框是凹槽灰框 —— 这是"老土"感的主要来源。
 * 这里集中提供：
 * <ul>
 *   <li>{@link #install()}：在创建任何 UI 之前调用一次，调全局默认字体 / 滚动条 / 弹出菜单配色；</li>
 *   <li>{@link #primary}/{@link #secondary}/{@link #subtle}：给已有 JButton 套上圆角现代外观；</li>
 *   <li>{@link #field(JTextField)}：给输入框套上白底圆角边框 + 聚焦高亮。</li>
 * </ul>
 *
 * 配色与现有主色（顶栏地址蓝 #1565C0）对齐，保持产品一致。
 */
final class Ui {

    private Ui() { }

    // ===== 调色板 =====
    //
    // 主题切换（2026-09-27）：下面这组「会跟主题走」的颜色**不能再是 final** ——
    // 它们在运行时被 {@link #applyDark(boolean, java.awt.Container)} 整体替换，
    // 所有引用点（含自定义 paint 里的直接读字段）下一次重绘自动拿到新值。
    // 2026-09-28 用户改调：按钮 / 弹菜单**也要跟主题**（深色=黑底白字），
    // 见下方 BTN_* 与 MENU_* 两组；两组都随 assignPalette 一起被替换。
    // ⚠ 槽位顺序即语义：snapshot() / assignPalette 深浅两分支必须同步维护（三个位置齐动）。
    // ⚠ 同值槽位靠实例身份映射（见 ThemeMap）；新增槽位前先读 checkPaletteDuplicates 的日志。
    static volatile Color PRIMARY      = new Color(0x15, 0x65, 0xC0); // 主色（与地址链接蓝一致）
    static volatile Color PRIMARY_HOVER= new Color(0x1E, 0x76, 0xD2);
    static volatile Color PRIMARY_DOWN = new Color(0x0D, 0x4C, 0x9B);
    static volatile Color TEXT_MAIN    = new Color(0x1F, 0x23, 0x28);
    static volatile Color TEXT_SUB     = new Color(0x6B, 0x72, 0x7B);
    static volatile Color TEXT_TERTIARY= new Color(0x9A, 0xA0, 0xA8);
    static volatile Color BORDER       = new Color(0xE2, 0xE5, 0xEA);
    static volatile Color DIVIDER      = new Color(0xEA, 0xEC, 0xF0); // 更淡的分割线
    static final Color FOCUS_BORDER = new Color(0x15, 0x65, 0xC0);
    static volatile Color SURFACE      = Color.WHITE;                 // 顶栏 / 侧栏 / 面板底
    static volatile Color PAGE_BG      = new Color(0xF4, 0xF5, 0xF7); // 聊天区底色

    // ===== 弹菜单专属配色（2026-09-28 新增，跟主题走，不复用 DIVIDER）=====
    // ⚠ MENU_BORDER **不是** DIVIDER：DIVIDER 是顶/底栏那种可有可无的极淡分割线
    //   （浅色 #EAECF0 与白底亮度差仅 19、深色 #2A2C30 与黑底亮度差 44，弹菜单用它当边框
    //   就是「太浅 / 看不见」的 R1/R2 根因①）。MENU_BORDER 保证与相邻底亮度差 ≥45。
    //   以后做「统一色板」重构时**别把这两个合并**。
    static volatile Color MENU_BG      = new Color(0xFF, 0xFF, 0xFF); // 弹菜单底 + 菜单项底
    static volatile Color MENU_BORDER  = new Color(0xC8, 0xCD, 0xD4); // 弹菜单外框 1px
    static volatile Color MENU_FG      = new Color(0x1F, 0x23, 0x28); // 菜单项文字
    static volatile Color MENU_SEP     = new Color(0xE4, 0xE7, 0xEB); // 菜单内分隔线
    static volatile Color MENU_SEL_BG  = new Color(0xE8, 0xF0, 0xFE); // 悬停行底
    static volatile Color MENU_SEL_FG  = new Color(0x15, 0x65, 0xC0); // 悬停行文字
    static volatile Color ACCENT       = new Color(0x15, 0x65, 0xC0); // 菜单里「当前项」强调字

    // 气泡配色（更柔和现代）
    static volatile Color BUBBLE_SELF  = new Color(0xD6, 0xE8, 0xFF); // 自己的气泡：清新浅蓝
    static volatile Color BUBBLE_PEER   = new Color(0xF0, 0xF2, 0xF5); // 对方的气泡：中性浅灰
    static volatile Color BUBBLE_META  = new Color(0x6B, 0x72, 0x7B); // 气泡内次要文字
    /** 气泡正文文字色（浅色=近黑；深色主题下气泡变灰、正文转白，2026-09-27 新增） */
    static volatile Color BUBBLE_TEXT  = new Color(0x1F, 0x23, 0x28);
    /** 深色模式下输入框底色（浅色恒白） */
    static final Color FIELD_DARK = new Color(0x1B, 0x1C, 0x1F);

    // 设备行
    static volatile Color ROW_CURRENT  = new Color(0xE8, 0xF0, 0xFE); // 当前选中设备：柔和蓝底
    static volatile Color ROW_CONNECTED= new Color(0xF4, 0xF7, 0xFD); // 已连接（非当前）：极淡蓝
    static final Color DOT_ONLINE   = new Color(0x2E, 0x7D, 0x32);
    static final Color DOT_OFFLINE  = new Color(0xB0, 0xB5, 0xBC);
    /**
     * 气泡里「已保存」状态字的绿色（与 Android 端 Bubble.kt 的 0xFF2E7D32 同色）。
     * 与 DOT_ONLINE 是同一个绿，但语义不同（在线圆点 vs 落盘完成标记），故不共用常量名。
     * 不参与深浅主题重着色：成功语义在两种主题下都应为绿色。
     */
    static final Color SAVED_GREEN  = new Color(0x2E, 0x7D, 0x32);

    // IP 地址（本机地址块）
    static volatile Color IP_TEXT      = new Color(0x4A, 0x6B, 0x8A); // 灰蓝，替代原突兀深绿
    static volatile Color IP_CAPTION   = new Color(0x9A, 0xA0, 0xA8);

    // ===== 按钮专属配色（2026-09-28 起跟主题）=====
    // 2026-09-28 起按钮跟主题（深色=黑底 #000000 + 白字 #E8EAED + 边框 #4A4E54）；
    // 数值在 assignPalette 里成对维护，改浅色必须同步深浅两分支。
    //（旧 2026-09-27 定调「按钮之类的颜色不用改」**已作废**，见下方调色板注释与 README。）
    private static volatile Color BTN_FILL      = Color.WHITE;                 // = 浅色 SURFACE
    private static volatile Color BTN_HOVER     = new Color(0xF0, 0xF2, 0xF6); // SECONDARY hover
    private static volatile Color BTN_SUB_HOVER = new Color(0xEC, 0xEE, 0xF2); // SUBTLE hover
    private static volatile Color BTN_BORDER    = new Color(0xE2, 0xE5, 0xEA); // SECONDARY 边框
    private static volatile Color BTN_BORDER_OFF= new Color(0xE6, 0xE8, 0xEC); // SECONDARY 边框（禁用）
    private static volatile Color BTN_FG        = new Color(0x1F, 0x23, 0x28); // = 浅色 TEXT_MAIN
    private static volatile Color BTN_FG_SUB    = new Color(0x6B, 0x72, 0x7B); // = 浅色 TEXT_SUB

    // ===== 标题栏（自绘窗口外壳）专属配色（2026-09-28 新增，跟主题）=====
    // 深色 = 深灰顶栏 #313338（用户 Q1 定稿）；浅色 = 浅灰白 #F0F1F4（Q2 = 一起改，同一套自绘路径）。
    // 数值在 assignPalette 里成对维护，改浅色必须同步深浅两分支。
    static volatile Color CHROME_BG   = new Color(0xF0, 0xF1, 0xF4); // 标题栏底（浅色默认值）
    static volatile Color CHROME_FG   = new Color(0x6B, 0x72, 0x7B); // 标题栏文字
    static volatile Color CHROME_LINE = new Color(0xE4, 0xE7, 0xEB); // 标题栏下 1px 分割线

    // ===== 窗口外框 1px 细线（2026-09-29 新增，跟主题）=====
    // 目的：深色 SURFACE 纯黑 / 浅色 SURFACE 纯白，窗口与桌面之间没有可辨边界
    //   → 沿圆角内容区画一圈 1px 描边（深色=浅灰、浅色=深灰），边界一眼可见。
    // ⚠ 与 DIVIDER / CHROME_LINE 都不是一个东西：那两个是"内部"分割线，亮度差可以很小；
    //   本常量是**窗口最外圈**，必须与相邻底色有明显亮度差。别合并。
    static volatile Color WIN_BORDER = new Color(0x86, 0x8C, 0x94); // 浅色：深灰细线（默认值）

    // ===== 滚动条（FlatScrollBarUI 专用）配色（2026-09-28 新增，跟主题）=====
    // 深色 = 黑底 + 白箭头 + 绿滑块（用户 R4 定稿）；滑块绿 #43A047（Q3）、hover #4CAF50。
    static volatile Color SCROLL_TRACK       = new Color(0xF2, 0xF3, 0xF5); // 轨道底（浅色）
    static volatile Color SCROLL_THUMB       = new Color(0xB4, 0xBA, 0xC2); // 滑块（浅色）
    static volatile Color SCROLL_THUMB_HOVER = new Color(0x9A, 0xA0, 0xA8); // 滑块 hover（浅色）
    static volatile Color SCROLL_ARROW       = new Color(0x6B, 0x72, 0x7B); // 箭头三角（浅色）

    // ===== 主题状态与切换 =====

    /** 当前是否深色主题（volatile：EDT 与后台线程都可能读） */
    private static volatile boolean dark = false;

    static boolean isDark() {
        return dark;
    }

    /** 深色主题调色板（只被 {@link #applyDark} 用来整体赋值） */
    private static void assignPalette(boolean d) {
        dark = d;
        if (d) {
            // 深色：背景纯黑、气泡灰、字体白（2026-09-27 用户定调）；按钮/弹菜单也跟（2026-09-28）
            SURFACE       = new Color(0x00, 0x00, 0x00);       // 顶栏/侧栏/面板底 = 纯黑（2026-09-29：独立实例，不共享 Color.BLACK，槽位身份映射才精确）
            PAGE_BG       = new Color(0x00, 0x00, 0x00);       // 聊天区底 = 纯黑（同上）
            TEXT_MAIN     = new Color(0xE8, 0xEA, 0xED);       // 正文白
            TEXT_SUB      = new Color(0x9A, 0xA0, 0xA6);       // 次要文字亮灰
            TEXT_TERTIARY = new Color(0x80, 0x86, 0x8F);
            BORDER        = new Color(0x3A, 0x3D, 0x42);       // 输入框边框
            DIVIDER       = new Color(0x2A, 0x2C, 0x30);       // 分割线（暗灰）
            BUBBLE_SELF   = new Color(0x3C, 0x40, 0x43);       // 自己的气泡：亮一点的灰
            BUBBLE_PEER   = new Color(0x2A, 0x2C, 0x2E);       // 对方的气泡：深一点的灰
            BUBBLE_META   = new Color(0x9A, 0xA0, 0xA6);
            BUBBLE_TEXT   = new Color(0xE8, 0xEA, 0xED);       // 气泡正文白
            ROW_CURRENT   = new Color(0x2C, 0x3A, 0x52);       // 选中设备：暗蓝灰
            ROW_CONNECTED = new Color(0x1C, 0x24, 0x30);       // 已连接：极暗蓝
            IP_TEXT       = new Color(0x8F, 0xB8, 0xE8);       // IP：亮蓝灰
            IP_CAPTION    = new Color(0x80, 0x86, 0x8F);
            // 弹菜单（深色）：底色 #1B1C1F 与输入框 FIELD_DARK 同色，能托住 1px 边框
            MENU_BG      = new Color(0x1B, 0x1C, 0x1F);
            MENU_BORDER  = new Color(0x4A, 0x4E, 0x54);
            MENU_FG      = new Color(0xE8, 0xEA, 0xED);
            MENU_SEP     = new Color(0x3A, 0x3D, 0x42);
            MENU_SEL_BG  = new Color(0x2C, 0x3A, 0x52);
            MENU_SEL_FG  = Color.WHITE;
            ACCENT       = new Color(0x8F, 0xB8, 0xE8);
            // 按钮（深色）：黑底 + 白字 + 可见边框（R3）
            BTN_FILL     = Color.BLACK;
            BTN_HOVER    = new Color(0x1B, 0x1C, 0x1F);
            BTN_SUB_HOVER= new Color(0x14, 0x15, 0x17);
            BTN_BORDER   = new Color(0x4A, 0x4E, 0x54);
            BTN_BORDER_OFF = new Color(0x2A, 0x2C, 0x30);
            BTN_FG       = new Color(0xE8, 0xEA, 0xED);
            BTN_FG_SUB   = new Color(0x8A, 0x90, 0x99);
            // 标题栏（深色）：深灰 #313338 顶栏（R1/R2/R3 的目标色）
            CHROME_BG      = new Color(0x31, 0x33, 0x38);
            CHROME_FG      = new Color(0xE8, 0xEA, 0xED);
            CHROME_LINE    = new Color(0x3A, 0x3D, 0x42);
            // 窗口外框（深色）：浅灰细线，压在纯黑 SURFACE 上（对比度 ≈ 4.6:1，边界清晰且不刺眼）
            WIN_BORDER     = new Color(0x6E, 0x74, 0x7C);
            // 滚动条（深色）：黑底 + 白箭头 + 绿滑块（R4）
            SCROLL_TRACK       = new Color(0x00, 0x00, 0x00);  // 同上：独立实例（paint 期直读字段，不走映射，保持口径一致）
            SCROLL_THUMB       = new Color(0x43, 0xA0, 0x47);
            SCROLL_THUMB_HOVER = new Color(0x4C, 0xAF, 0x50);
            SCROLL_ARROW       = Color.WHITE;
        } else {
            // 浅色 = 历史观感，逐字段还原（与上面初始值一一对应，改色前先同步这里）
            SURFACE       = Color.WHITE;
            PAGE_BG       = new Color(0xF4, 0xF5, 0xF7);
            TEXT_MAIN     = new Color(0x1F, 0x23, 0x28);
            TEXT_SUB      = new Color(0x6B, 0x72, 0x7B);
            TEXT_TERTIARY = new Color(0x9A, 0xA0, 0xA8);
            BORDER        = new Color(0xE2, 0xE5, 0xEA);
            DIVIDER       = new Color(0xEA, 0xEC, 0xF0);
            BUBBLE_SELF   = new Color(0xD6, 0xE8, 0xFF);
            BUBBLE_PEER   = new Color(0xF0, 0xF2, 0xF5);
            BUBBLE_META   = new Color(0x6B, 0x72, 0x7B);
            BUBBLE_TEXT   = new Color(0x1F, 0x23, 0x28);
            ROW_CURRENT   = new Color(0xE8, 0xF0, 0xFE);
            ROW_CONNECTED = new Color(0xF4, 0xF7, 0xFD);
            IP_TEXT       = new Color(0x4A, 0x6B, 0x8A);
            IP_CAPTION    = new Color(0x9A, 0xA0, 0xA8);
            // 弹菜单 / 按钮（浅色 = 历史观感，逐字段还原；除菜单项行底由 LAF 亮灰变纯白，见 installLafColors 注释）
            MENU_BG      = new Color(0xFF, 0xFF, 0xFF);
            MENU_BORDER  = new Color(0xC8, 0xCD, 0xD4);
            MENU_FG      = new Color(0x1F, 0x23, 0x28);
            MENU_SEP     = new Color(0xE4, 0xE7, 0xEB);
            MENU_SEL_BG  = new Color(0xE8, 0xF0, 0xFE);
            MENU_SEL_FG  = new Color(0x15, 0x65, 0xC0);
            ACCENT       = new Color(0x15, 0x65, 0xC0);
            BTN_FILL     = Color.WHITE;
            BTN_HOVER    = new Color(0xF0, 0xF2, 0xF6);
            BTN_SUB_HOVER= new Color(0xEC, 0xEE, 0xF2);
            BTN_BORDER   = new Color(0xE2, 0xE5, 0xEA);
            BTN_BORDER_OFF = new Color(0xE6, 0xE8, 0xEC);
            BTN_FG       = new Color(0x1F, 0x23, 0x28);
            BTN_FG_SUB   = new Color(0x6B, 0x72, 0x7B);
            // 标题栏（浅色）：浅灰白顶栏（Q2 = 一起改，与深色同一套自绘路径）
            CHROME_BG      = new Color(0xF0, 0xF1, 0xF4);
            CHROME_FG      = new Color(0x6B, 0x72, 0x7B);
            CHROME_LINE    = new Color(0xE4, 0xE7, 0xEB);
            // 窗口外框（浅色）：深灰细线，压在纯白 SURFACE 上（不能跟 CHROME_LINE 一样淡，
            // 否则等于没画；亮度差按 ≥100 定）
            WIN_BORDER     = new Color(0x86, 0x8C, 0x94);
            // 滚动条（浅色）：浅灰轨道 + 灰滑块（深色才是黑底 + 绿滑块）
            SCROLL_TRACK       = new Color(0xF2, 0xF3, 0xF5);
            SCROLL_THUMB       = new Color(0xB4, 0xBA, 0xC2);
            SCROLL_THUMB_HOVER = new Color(0x9A, 0xA0, 0xA8);
            SCROLL_ARROW       = new Color(0x6B, 0x72, 0x7B);
        }
    }

    /**
     * 启动期应用主题（窗口还没建，只需换调色板 + UIManager，不做组件树重着色）。
     * 必须在创建任何窗口**之前**调用（PcMain.main 里 store.load() 之后、new ChatWindow 之前）。
     */
    static void applyDark(boolean d) {
        assignPalette(d);
        checkPaletteDuplicates();
        installLafColors();
        // 启动期也要触发：监听注册顺序不保证（PcMain 里 Tray.init 在 applyDark 之后），宁可空跑一次
        fireThemeChanged();
    }

    /**
     * 运行期切换主题：换调色板 → 刷 UIManager → 对已建组件树做一次「旧色 → 新色」重着色。
     *
     * <p>Swing 的组件颜色是**建的时候拷走**的（setBackground/setForeground 存的是 Color 引用），
     * 只换调色板的话已建组件不会变 —— 所以按**槽位**配对映射（旧板快照 → 新板快照，
     * 下标一一对应）整棵树替换一遍；颜色值相同的槽位靠实例身份区分，不用颜色值当键 ——
     * 后者会串味（2026-09-29 修）。自定义 paint 里直接读 {@code Ui.XXX} 字段的（设备行/圆角气泡/窗口底），repaint 自动生效。
     *
     * @param d    true=深色 false=浅色
     * @param root 已建窗口的根容器（frame.getContentPane()）；null = 只换调色板不做重着色
     */
    /**
     * 当前调色板的槽位快照（23 槽）。
     *
     * <p>⚠ 顺序即语义：下标 i 的槽位在「旧板/新板」两次快照里必须指同一个语义槽，
     * 这是主题重着色映射的**唯一真源**。禁止重排；新增/删除槽位必须三处齐动：
     * 本方法、{@link #assignPalette(boolean)} 的深色分支、浅色分支。
     */
    private static Color[] snapshot() {
        return new Color[] { SURFACE, PAGE_BG, TEXT_MAIN, TEXT_SUB, TEXT_TERTIARY, BORDER,
                DIVIDER, BUBBLE_SELF, BUBBLE_PEER, BUBBLE_META, BUBBLE_TEXT,
                ROW_CURRENT, ROW_CONNECTED, IP_TEXT, IP_CAPTION,
                CHROME_BG, CHROME_FG, CHROME_LINE, WIN_BORDER,
                SCROLL_TRACK, SCROLL_THUMB, SCROLL_THUMB_HOVER, SCROLL_ARROW };
    }

    /**
     * 主题重着色用的颜色映射：**实例身份优先，值回退次之**。
     *
     * <p>为什么不能再用 {@code HashMap<Color,Color>}：{@code Color.equals} 比的是 RGB 值，
     * 而同一个值可能同时属于多个槽位 —— 浅色板 {@code #6B727B} =
     * TEXT_SUB/BUBBLE_META/CHROME_FG/SCROLL_ARROW，深色板 {@code #E8EAED} =
     * TEXT_MAIN/BUBBLE_TEXT/CHROME_FG。逐个 put 会「后写覆盖先写」，把其中某个槽位的
     * 目标色套到**所有**同值槽位上（2026-09-29 实测：切回浅色后气泡正文/正文被映射成
     * CHROME_FG 的 #6B727B，整片发灰、最深像素 34→112，看着就是「糊」）。
     *
     * <p>组件颜色都是直接持有 {@code Ui.XXX} 字段实例（全项目已核对：无
     * {@code new Color(x.getRGB())} 这类等价色），所以用 {@code ==} 命中即精确；
     * 值回退只为兜底代码里手写的同值色，并按**声明序首个槽位**取值，保证确定性。
     */
    private static final class ThemeMap {
        private final java.util.IdentityHashMap<Color, Color> byIdentity;
        private final java.util.LinkedHashMap<Color, Color> byValue;

        ThemeMap(java.util.IdentityHashMap<Color, Color> byIdentity,
                 java.util.LinkedHashMap<Color, Color> byValue) {
            this.byIdentity = byIdentity;
            this.byValue = byValue;
        }

        /** @return 映射后的新色；未命中返回 null（调用方保持原色，不做任何替换） */
        Color get(Color c) {
            if (c == null) {
                return null;
            }
            Color byId = byIdentity.get(c);
            return byId != null ? byId : byValue.get(c);
        }
    }

    /** 由「旧板快照 + 新板快照」按下标一一配对建映射；两数组长度必须相等 */
    private static ThemeMap buildThemeMap(Color[] oldVals, Color[] newVals) {
        java.util.IdentityHashMap<Color, Color> byIdentity =
                new java.util.IdentityHashMap<Color, Color>();
        java.util.LinkedHashMap<Color, Color> byValue =
                new java.util.LinkedHashMap<Color, Color>();
        for (int i = 0; i < oldVals.length; i++) {
            byIdentity.put(oldVals[i], newVals[i]);
            if (!byValue.containsKey(oldVals[i])) {
                byValue.put(oldVals[i], newVals[i]);   // 同值只取声明序**首个**槽位
            }
        }
        return new ThemeMap(byIdentity, byValue);
    }

    static void applyDark(boolean d, java.awt.Container root) {
        // ① 先记下旧值（按引用比对用）
        Color[] oldVals = snapshot();              // ① 先记下旧值（槽位下标 = 语义）
        assignPalette(d);
        Color[] newVals = snapshot();              // ② 再取新值（与旧值下标一一对应）
        ThemeMap map = buildThemeMap(oldVals, newVals);
        // ② UIManager 默认值跟上（此后新建的对话框/弹出菜单直接用新配色）
        installLafColors();
        if (root == null) {
            return; // 早退路径在 fireThemeChanged() 之前：只换调色板时不刷弹菜单（无害但没必要）
        }
        // ③ 已建组件树：逐组件把「旧主题色」换「新主题色」
        retheme(root, map);
        root.revalidate();
        root.repaint();
        // ③.5 2026-09-29：再排一次「整窗重绘」（invokeLater = 排在当前队列所有任务之后，
        // 含调用方随后排入的 refreshDevices 局部重绘任务；脏区累积到下一次绘制脉冲才真正画）。
        // 原因：调用方（toggleTheme）随后做的是局部重绘（deviceListPanel / msgPanel）——
        // 局部重绘只重画子组件，会把父级 paintChildren 里最后画的「窗口描边线」
        // （WindowChrome.RoundedContent）盖掉且不重画；实测浅色→深色后设备列表区域
        // 左描边线消失（与启动即深色差 1956px）。整窗标脏让局部重绘并入一次全量重绘，
        // 描边线在所有子组件之上重画，两种深色路径像素一致。
        final java.awt.Container root0 = root;
        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                Component w = javax.swing.SwingUtilities.getWindowAncestor(root0);
                if (w != null) {
                    w.repaint();
                }
            }
        });
        // ④ 长期存活的弹菜单（托盘等）不在组件树里，靠监听刷新（统一放最后一行）
        fireThemeChanged();
    }

    /** 单个组件重着色：背景/前景命中旧色映射就换；边框里嵌的分割线颜色一并重建。 */
    private static void rethemeComp(Component comp, ThemeMap map) {
        Color nbg = map.get(comp.getBackground());
        if (nbg != null) {
            comp.setBackground(nbg);
        }
        Color nfg = map.get(comp.getForeground());
        if (nfg != null) {
            comp.setForeground(nfg);
        }
        if (comp instanceof JComponent) {
            JComponent jc = (JComponent) comp;
            javax.swing.border.Border b = jc.getBorder();
            javax.swing.border.Border nb = rematte(b, map);
            if (nb != b) {
                jc.setBorder(nb);
            }
        }
    }

    /** 递归重着色：背景/前景命中旧色映射就换；边框里嵌的分割线颜色一并重建。
     * 映射按槽位身份（实例）优先，不再用颜色值当键（2026-09-29）。
     * 2026-09-29：根容器**自身**也重着色——contentPane 的 bg 是建窗时从
     * "Panel.background" 拷走的槽位色，早先只换它的子组件、根自己留在旧色。
     * 不透明窗（逐像素透明不支持的回退路径 / 全屏）下根 bg 会整窗填充，
     * 浅→深切完整个窗框留一圈白（离屏截图实测 margin 全白）。 */
    private static void retheme(java.awt.Container c, ThemeMap map) {
        rethemeComp(c, map);
        for (Component comp : c.getComponents()) {
            if (comp instanceof java.awt.Container) {
                // 递归调用会对 comp 自己再调一次 rethemeComp，父循环里不能再调，
                // 否则每个容器被重着色**两次**：第二遍把「刚换上的新色」再当旧色查映射。
                // 2026-09-29 实证（BgChainProbe）：深色→浅色时新色白命中值回退
                // （旧板 SCROLL_ARROW = Color.WHITE → 新板 #6B727B），左栏整片变灰。
                retheme((java.awt.Container) comp, map);
            } else {
                rethemeComp(comp, map);
            }
        }
    }

    /**
     * 边框里的主题分割线换色：裸 MatteBorder 直接换；CompoundBorder（顶栏/底栏都是
     * 「分割线 + 留白」的复合边框，2026-09-27 实测漏换）逐层递归；其它边框原样返回。
     *
     * @return 新边框；没有需要换的颜色时返回**原引用**（调用方按引用比对决定要不要 setBorder）
     */
    private static javax.swing.border.Border rematte(javax.swing.border.Border b,
                                                     ThemeMap map) {
        if (b instanceof javax.swing.border.MatteBorder) {
            javax.swing.border.MatteBorder mb = (javax.swing.border.MatteBorder) b;
            Color nc = map.get(mb.getMatteColor());
            if (nc != null) {
                Insets in = mb.getBorderInsets(null);
                return BorderFactory.createMatteBorder(
                        in.top, in.left, in.bottom, in.right, nc);
            }
            return b;
        }
        if (b instanceof javax.swing.border.CompoundBorder) {
            javax.swing.border.CompoundBorder cb = (javax.swing.border.CompoundBorder) b;
            javax.swing.border.Border outer = rematte(cb.getOutsideBorder(), map);
            javax.swing.border.Border inner = rematte(cb.getInsideBorder(), map);
            if (outer != cb.getOutsideBorder() || inner != cb.getInsideBorder()) {
                return BorderFactory.createCompoundBorder(outer, inner);
            }
            return b;
        }
        return b;
    }

    /** UI 字体族：统一走 {@link Fonts#FAMILY}（优先宋体，缺失回退系统默认） */
    static final String FONT = Fonts.FAMILY;

    /** 最小档字号（px）——唯一基准：本文件 ToolTip.font = new Font(FONT, PLAIN, 13)（IP 悬停提示） */
    static final int FS_MIN = 13;
    /** 常规档字号（px）——唯一基准：底栏「发送文件」按钮（ChatWindow）/ 气泡正文（ChatBubbles） */
    static final int FS_STD = 15;

    /** 在 PcMain.main 最前面、创建任何窗口之前调用一次 */
    static void install() {
        // 全局默认字体：优先宋体（100% 缩放下小字号点阵最锐利），缺失时回退系统默认
        // 档位：常规 15（=发送文件按钮同档）——未显式设字体的组件都吃它（风险 R3）
        Font base = new Font(FONT, Font.PLAIN, FS_STD);
        UIManager.put("defaultFont", base);
        UIManager.put("Button.font", base);
        UIManager.put("Label.font", base);
        UIManager.put("PopupMenu.font", base);
        UIManager.put("Menu.font", base);
        UIManager.put("MenuItem.font", base);
        UIManager.put("CheckBox.font", base);
        UIManager.put("TextField.font", base);
        UIManager.put("TextArea.font", base);

        // 2026-09-29（用户「全部统一这样显示」）：所有 JButton 默认走 ModernButtonUI——
        // 文件选择器 / 确认框 / 弹窗里的按钮与主窗按钮同一观感；未显式设样式的按钮
        // 落 SECONDARY（可见灰卡 + 深字）。自绘 / 扁平按钮（滚动条箭头 / 顶栏地址 /
        // 圆形图标 / 图片查看器 × / 气泡内链接式文字）显式用 FLAT（不画卡片、不覆盖前景）。
        // 注意（JDK 21 UIDefaults.getUIClass 源码实证）：值必须是**字符串类名**——
        // (String) get(key) 取类名 → loadClass(className) → 反射调 public static
        // createUI(JComponent) 工厂（见 ModernButtonUI.createUI）。放实例或 Class 对象都会失败。
        UIManager.put("ButtonUI", "com.phonepctransfer.pc.Ui$ModernButtonUI");

        // 细一点的滚动条
        // 注（2026-09-28）：以下 3 个 ScrollBar.* 键对自定义滚动条已**失效**——
        // 聊天区/设备列表的滚动条改用 FlatScrollBarUI（见本文件底部），颜色在 paint 期
        // 现取 Ui.SCROLL_* 字段，不走 UIManager 值。保留这几行只为不影响其它仍用默认
        // 滚动条的组件（如有），删除反而可能改变它们的观感。
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumb", new Color(0xCC, 0xD1, 0xD8));
        UIManager.put("ScrollBar.track", new Color(0xF2, 0xF3, 0xF5));

        // 输入框 / 对话框默认白底
        UIManager.put("Panel.background", SURFACE);
        UIManager.put("OptionPane.background", SURFACE);

        // 弹出菜单 / 对话框的文字配色跟主题走（深色下面板是黑的，默认黑字会看不见）
        installLafColors();
        checkPaletteDuplicates();
    }

    /**
     * UIManager 层的配色（弹出菜单 / 对话框 / 滚动条），跟当前调色板走。
     * install() 与 applyDark() 都会调：新建的组件从此取到当前主题的默认值。
     */
    private static void installLafColors() {
        // ===== 弹菜单（R1/R2 修复）：用 MENU_* 专属色板，**不复用 DIVIDER** =====
        // 根因②：以前只设了 PopupMenu.background / MenuItem.foreground，菜单项底色
        // 一直是 LAF 默认亮灰 #EEEEEE —— 深色下与近白文字撞成一团（R2 的真身）。
        // 这里把 MenuItem.background / Menu.background / Separator.* 一并纳入主题。
        // 已知且接受的浅色视觉变化：菜单项行底由 LAF 亮灰 #EEEEEE 变纯白 #FFFFFF，
        // 弹菜单由「白框 + 亮灰行」变「纯白底 + 1px #C8CDD4 边框」—— 这正是现代扁平该有的样子。
        UIManager.put("PopupMenu.background", MENU_BG);
        UIManager.put("PopupMenu.foreground", MENU_FG);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(MENU_BORDER, 1));
        UIManager.put("MenuItem.background", MENU_BG);      // ← 根因②：本次新增
        UIManager.put("MenuItem.foreground", MENU_FG);
        UIManager.put("Menu.background", MENU_BG);          // ← JMenu 行底，本次新增
        UIManager.put("Menu.foreground", MENU_FG);
        UIManager.put("MenuItem.selectionBackground", MENU_SEL_BG);
        UIManager.put("MenuItem.selectionForeground", MENU_SEL_FG);
        UIManager.put("MenuItem.borderPainted", Boolean.FALSE);   // 实测默认 true，避免多一圈装饰边
        UIManager.put("Separator.foreground", MENU_SEP);    // ← Metal 默认 #6382BF 蓝，本次新增
        UIManager.put("Separator.background", MENU_SEP);
        UIManager.put("ToolTip.background", new Color(0x3A, 0x3F, 0x46));
        UIManager.put("ToolTip.foreground", Color.WHITE);
        // 档位基准：最小 13（= IP 悬停提示，= FS_MIN，基准不可改）
        UIManager.put("ToolTip.font", new Font(FONT, Font.PLAIN, 13));

        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.thumb", dark ? new Color(0x4A, 0x4E, 0x54)
                : new Color(0xCC, 0xD1, 0xD8));
        UIManager.put("ScrollBar.track", dark ? new Color(0x1A, 0x1B, 0x1D)
                : new Color(0xF2, 0xF3, 0xF5));

        UIManager.put("Panel.background", SURFACE);
        UIManager.put("OptionPane.background", SURFACE);
        // 对话框（设置/确认框等）在深色下的默认文字：面板黑底，Label/TextField 默认黑字会隐形
        UIManager.put("OptionPane.messageForeground", TEXT_MAIN);
        UIManager.put("Label.foreground", TEXT_MAIN);
        UIManager.put("TextField.foreground", TEXT_MAIN);
        // 输入框底色一并跟主题：以前只换前景，漏套 field() 的裸 JTextField 就是
        // 「白底 + 深色下的近白字」= 字看不见（2026-10-04「添加设备」实测 1.21:1）。
        // field() 自己还会再设一遍同值，两者一致，这里只是兜底。
        UIManager.put("TextField.background", dark ? FIELD_DARK : Color.WHITE);
    }

    /**
     * 启动期自检：把「同一板内多槽位同值」的组打出来。
     *
     * <p>这些槽位靠**实例身份**精确映射；若将来有人在同值组里新增槽位、却期望与
     * 声明序首个槽位不同的目标色，值回退就会串味。本自检把这件事显式暴露在日志里，
     * 而不是让它静默出错。正常浅色板应打 2 组、深色板应打 3 组。
     */
    private static void checkPaletteDuplicates() {
        final String[] names = { "SURFACE", "PAGE_BG", "TEXT_MAIN", "TEXT_SUB", "TEXT_TERTIARY",
                "BORDER", "DIVIDER", "BUBBLE_SELF", "BUBBLE_PEER", "BUBBLE_META", "BUBBLE_TEXT",
                "ROW_CURRENT", "ROW_CONNECTED", "IP_TEXT", "IP_CAPTION", "CHROME_BG", "CHROME_FG",
                "CHROME_LINE", "WIN_BORDER", "SCROLL_TRACK", "SCROLL_THUMB",
                "SCROLL_THUMB_HOVER", "SCROLL_ARROW" };
        Color[] v = snapshot();
        java.util.LinkedHashMap<Color, StringBuilder> g =
                new java.util.LinkedHashMap<Color, StringBuilder>();
        for (int i = 0; i < v.length; i++) {
            StringBuilder b = g.get(v[i]);
            if (b == null) {
                g.put(v[i], new StringBuilder(names[i]));
            } else {
                b.append(", ").append(names[i]);
            }
        }
        for (java.util.Map.Entry<Color, StringBuilder> e : g.entrySet()) {
            if (e.getValue().toString().indexOf(',') >= 0) {
                Log.w("调色板同值槽位 #" + String.format("%06X", e.getKey().getRGB() & 0xFFFFFF)
                        + " = [" + e.getValue() + "]（按实例身份映射，值回退取首个槽位）");
            }
        }
    }

    // ===== 弹菜单运行期主题化（2026-09-28 新增，根因③）=====

    /**
     * 把当前调色板的 MENU_* 落到一个**已建好**的 JPopupMenu 上（含递归子菜单）。
     *
     * <p>Swing 的 JMenuItem 颜色是 UI delegate 在**构造时**烤进去的；JPopupMenu 是独立的重型
     * 弹出窗口、不在组件树里，applyDark 的整树重着色够不着它 —— 早先建好的菜单必须靠这里刷新。
     */
    static javax.swing.JPopupMenu theme(javax.swing.JPopupMenu pm) {
        if (pm == null) {
            return pm;
        }
        pm.setBackground(MENU_BG);
        pm.setForeground(MENU_FG);
        pm.setBorder(BorderFactory.createLineBorder(MENU_BORDER, 1));
        for (java.awt.Component c : pm.getComponents()) {
            themeMenuComponent(c);
        }
        pm.revalidate();
        pm.repaint();
        return pm;
    }

    /**
     * 对菜单里单个组件套 MENU_*；JMenu 的子菜单是**独立 JPopupMenu**，
     * 必须递归到 getPopupMenu()，否则「设备行→切换地址」子菜单仍是旧主题色。
     */
    private static void themeMenuComponent(java.awt.Component c) {
        c.setBackground(MENU_BG);
        c.setForeground(MENU_FG);
        if (c instanceof javax.swing.JMenu) {
            theme(((javax.swing.JMenu) c).getPopupMenu());
        }
        if (c instanceof javax.swing.JSeparator) {
            c.setBackground(MENU_BG);
            c.setForeground(MENU_SEP);
        }
    }

    // ===== 主题切换监听（2026-09-28 新增）=====
    // 长期存活的弹菜单（托盘菜单）不在组件树里，只能靠监听刷新；
    // 漏注册一个监听 = 那个菜单永远停在创建时那套色（根因③）。
    private static final java.util.List<Runnable> THEME_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<Runnable>();

    static void addThemeListener(Runnable r) {
        if (r != null) {
            THEME_LISTENERS.add(r);
        }
    }

    /**
     * 摘除主题监听。WindowChrome 的自绘外壳在窗口 dispose（windowClosed）后自动调用：
     * 来电弹窗 / 通话窗 / 设置窗每次打开都会新建外壳并注册监听，不摘的话
     * {@code THEME_LISTENERS} 会永久持有整棵 chrome 树 → 长期运行累积泄漏。
     */
    static void removeThemeListener(Runnable r) {
        if (r != null) {
            THEME_LISTENERS.remove(r);
        }
    }

    private static void fireThemeChanged() {
        for (Runnable r : THEME_LISTENERS) {
            try {
                r.run();
            } catch (Throwable t) {
                Log.w("主题监听异常: " + t);
            }
        }
    }

    // ===== 按钮 =====

    /** 主按钮：实心蓝底白字（发送 / 保存） */
    static JButton primary(JButton b) {
        return style(b, Style.PRIMARY);
    }

    /** 次要按钮：白底灰边深字（通话 / 发送文件 / 设置 / +设备 / 取消） */
    static JButton secondary(JButton b) {
        return style(b, Style.SECONDARY);
    }

    /** 弱按钮：完全扁平无边框，hover 浅灰底（顶栏「清空」这类） */
    static JButton subtle(JButton b) {
        return style(b, Style.SUBTLE);
    }

    /** 扁平按钮：不画卡片、不覆盖前景色（自绘圆底图标 / 顶栏地址 / 链接式蓝红文字 / 「×」这类） */
    static JButton flat(JButton b) {
        return style(b, Style.FLAT);
    }

    private static JButton style(JButton b, Style s) {
        b.putClientProperty(ModernButtonUI.STYLE_KEY, s);
        b.setUI(new ModernButtonUI());
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setRolloverEnabled(true);
        return b;
    }

    private enum Style { PRIMARY, SECONDARY, SUBTLE, FLAT }

    /** 圆角现代按钮 UI：自己画背景，文字/图标仍交给 BasicButtonUI 画。
     *  ⚠ 一律读 BTN_* 字段（2026-09-28 起跟主题走，见「按钮专属配色」注释）。
     *  2026-09-29 起也作为全局 ButtonUI（见 install）：public + createUI 工厂是 JDK
     *  UIDefaults.getUI(JComponent) 对 Class 值的硬性要求。 */
    public static final class ModernButtonUI extends BasicButtonUI {

        /** LAF 式工厂：UIManager.put("ButtonUI", ModernButtonUI.class) 按此创建实例 */
        public static ComponentUI createUI(JComponent c) {
            return new ModernButtonUI();
        }

        static final String STYLE_KEY = "ui.modernStyle";

        @Override
        protected void installDefaults(AbstractButton b) {
            super.installDefaults(b);
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setOpaque(false);
            b.setBorder(BorderFactory.createEmptyBorder(7, 18, 7, 18));
            b.setForeground(BTN_FG);
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            AbstractButton b = (AbstractButton) c;
            Style s = (Style) b.getClientProperty(STYLE_KEY);
            if (s == null) s = Style.SECONDARY;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int w = c.getWidth();
            int h = c.getHeight();
            int arc = 9;

            boolean armed = b.getModel().isArmed();
            boolean over = b.getModel().isRollover();
            boolean enabled = b.isEnabled();

            Color fill = null;
            Color fg;
            // 2026-09-29（用户反馈「默认发糊、移上去才清晰」）：SECONDARY/SUBTLE 的**默认态**
            // 直接用原 hover 底色 BTN_HOVER——旧默认 BTN_FILL 与 SURFACE 同色（浅色白卡白底 /
            // 深色黑卡黑底），卡片几乎隐形、整颗按钮看着发淡发糊；BTN_HOVER 与底有亮度差，
            // 卡片可见即「清晰」。hover/按下再深一档 BTN_SUB_HOVER 保留反馈（复用既有色，无新色）。
            // PRIMARY（蓝底白字）两态本来就高对比，不动。
            // FLAT（2026-09-29「全部统一」配套）：自绘 / 链接式按钮不画卡片、保留自身前景色。
            switch (s) {
                case FLAT:
                    fg = null;
                    break;
                case PRIMARY:
                    fill = !enabled ? new Color(0xB9, 0xC6, 0xD8)
                            : armed ? PRIMARY_DOWN : over ? PRIMARY_HOVER : PRIMARY;
                    fg = Color.WHITE;
                    break;
                case SUBTLE:
                    fill = !enabled ? BTN_FILL : (over || armed) ? BTN_SUB_HOVER : BTN_HOVER;
                    fg = !enabled ? BTN_FG_SUB : BTN_FG;
                    break;
                case SECONDARY:
                default:
                    fill = !enabled ? BTN_FILL : (over || armed) ? BTN_SUB_HOVER : BTN_HOVER;
                    fg = !enabled ? BTN_FG_SUB : BTN_FG;
                    break;
            }

            // ===== 卡片绘制的历史沿革（保留，避免以后再踩同一个坑）=====
            // ① 最早：1px drawRoundRect 矢量描边。细描边在分数设备坐标（如 Windows 125% 缩放——
            //    旧钉定值，2026-09-29 起钉 100%；「跟随系统缩放」模式下仍会出现）下，
            //    四条边落到的亚像素偏移各不相同，同一笔宽在不同边上视觉粗细不同
            //    （2026-09-27 用户实测「设置」按钮上边框消失；同日还反馈按钮右边框看起来缺失）。
            // ② 2026-09-27/09-28：改成「填充环」——外圆角矩形填 border 色 + 内圆角矩形填 fill 色。
            //    填充环在任何 DPI 下四条边的覆盖结构相同（整行 + 整行 + 两端部分行），比描边稳，
            //    但仍然是**每次重绘现算矢量**，抗锯齿边缘依旧随分数坐标抖动，且卡片底与文字同处
            //    一个按钮矩形、首选宽零容差 ⇒ 文字仍可能被自身 bounds 削边。
            // ③ 2026-09-28（本次）：改为 **透明 PNG 圆角卡片贴图 + 9 宫格拉伸**（见 CardSkin）。
            //    栅格化只在首次发生一次，抗锯齿结果固化在 PNG 像素里，之后按 9 宫格铺满任意尺寸；
            //    四边粗细与 DPI 无关，恒定一致。
            // ==========================================================
            //
            // L1 卡片层：先在组件背景上垫卡片（按钮不透明=false，四角透明的部分透出父容器底色）。
            //   · SECONDARY：白底 + 浅灰环（禁用时用更淡的 BTN_BORDER_OFF）；
            //   · PRIMARY / SUBTLE：只要实心底，不要边（border=null）。
            if (s == Style.FLAT) {
                // 无卡片层：背景由组件自身（自绘 / 自设 background）或父容器负责
            } else if (s == Style.SECONDARY) {
                CardSkin.paint(g2, w, h, arc, fill, enabled ? BTN_BORDER : BTN_BORDER_OFF);
            } else {
                CardSkin.paint(g2, w, h, arc, fill, null);
            }

            g2.dispose();

            // L2 文字 / 图标层：必须在卡片之后画（BasicButtonUI 负责文字与图标）。
            // 这是需求核心：卡片在下、文字在上 ⇒ 文字正常显示，永不被卡片边界削掉或盖住。
            // FLAT 不覆盖前景：按钮自身 setForeground 的颜色（蓝字 / 红字 / 白字）保持。
            if (fg != null) {
                b.setForeground(fg);
            }
            super.paint(g, c);
        }

        /**
         * 首选尺寸 + 墨迹安全余量（2026-09-28）。
         *
         * <p>JButton 的首选宽 = 文字 stringWidth + 边框 insets，是**零容差**的；而抗锯齿渲染的
         * 实际墨迹比度量宽 5~13%（与 {@code ChatDevices.INK_SAFETY = 1.12} 同源口径，2026-09-27
         * 实测 Consolas 11px：度量 140px、渲染 147px@100%、157px@125%）。按钮的绘制被裁剪到自身
         * bounds，于是首/末字形被削掉半边（用户附图里的「文字被裁」）。
         *
         * <p>这里按同一口径补 12% + 2px 冗余（向上取整），让墨迹永远落在按钮内部。
         */
        @Override
        public java.awt.Dimension getPreferredSize(JComponent c) {
            java.awt.Dimension d = super.getPreferredSize(c);
            if (!(c instanceof AbstractButton)) {
                return d;
            }
            String text = ((AbstractButton) c).getText();
            if (text == null || text.isEmpty()) {
                return d; // 纯图标按钮：不额外加余量，保持原居中观感
            }
            java.awt.FontMetrics fm = c.getFontMetrics(c.getFont());
            int ink = (int) Math.ceil(fm.stringWidth(text) * 0.12) + 2; // 12% 余量 + 2px 冗余
            return new java.awt.Dimension(d.width + ink, d.height);
        }
    }

    // ===== 大字号文本 AA（2026-09-29）=====

    /**
     * 显式开文本 AA 的 JLabel：全局文本 AA 已关（小字号走宋体内嵌点阵最锐利），
     * 但 >18px 的大字号走 outline 渲染、关 AA 会明显锯齿 —— 全项目仅 3 处大字号标签
     * （来电标题 22 / 通话设备名 21 / 通话计时 36）用本类单独显式开 AA。
     * 实现：paint 时拷一份 Graphics2D 设 KEY_TEXT_ANTIALIASING=ON，只影响本组件绘制。
     */
    static final class AALabel extends JLabel {
        AALabel(String text, int halign) {
            super(text, halign);
        }
        @Override
        public void paint(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            super.paint(g2);
            g2.dispose();
        }
    }

    // ===== 输入框 =====

    /** 输入框焦点监听已挂过的标记（client property）：field() 可重复调用（主题切换时重套配色），监听不叠加 */
    private static final String FIELD_LISTENER_KEY = "ui.fieldFocusListener";

    /** 圆角输入框，聚焦时蓝边。可重复调用：主题切换后重套一遍底色/文字色/边框即可跟随新主题 */
    static JTextField field(JTextField tf) {
        tf.setOpaque(true);
        tf.setBackground(dark ? FIELD_DARK : Color.WHITE);
        tf.setForeground(TEXT_MAIN);
        tf.setCaretColor(PRIMARY);
        tf.setBorder(new RoundedFieldBorder(BORDER, false));
        if (tf.getClientProperty(FIELD_LISTENER_KEY) == null) {
            tf.putClientProperty(FIELD_LISTENER_KEY, Boolean.TRUE);
            tf.addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e) {
                    // 边框色在事件发生时现取：主题切换后聚焦/失焦自动用新色
                    Object src = e.getSource();
                    if (src instanceof JTextField) {
                        ((JTextField) src).setBorder(new RoundedFieldBorder(FOCUS_BORDER, true));
                    }
                }
                @Override public void focusLost(java.awt.event.FocusEvent e) {
                    Object src = e.getSource();
                    if (src instanceof JTextField) {
                        ((JTextField) src).setBorder(new RoundedFieldBorder(BORDER, false));
                    }
                }
            });
        }
        return tf;
    }

    /**
     * 圆角输入框边框：聚焦时描边略粗一点表示状态。
     *
     * <p><b>铁律（2026-09-28）：{@code paintBorder} 里只准画「空心环」，绝不画实心卡片。</b>
     * 因为 {@code JComponent.paint} 的顺序是 paintComponent → paintChildren → <b>最后</b>
     * paintBorder，边框是画在文字**之上**的；一旦这里画实心卡片，就会把输入框里的文字整个盖住。
     * 所以本类一律以 {@code fill = null} 调 {@link CardSkin#paint}（内芯用 AlphaComposite.Clear
     * 挖成完全透明），只留一圈环。当前文字不贴边是靠 {@link #getBorderInsets} 的 12px 余量，
     * 那是「视觉余量」；这里挖空芯才是「层次保证」。
     *
     * <p>改用透明 PNG 贴图（空心环）替代原先的 1px {@code drawRoundRect} 矢量描边，理由同
     * {@link ModernButtonUI}：矢量描边粗细随分数 DPI 抖动，贴图四边恒定。
     */
    private static final class RoundedFieldBorder extends AbstractBorder {
        private final Color color;
        private final boolean focused;

        RoundedFieldBorder(Color color, boolean focused) {
            this.color = color;
            this.focused = focused;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            if (w <= 0 || h <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            // 空心环：芯透明（fill=null），聚焦时环宽 3.0px、失焦 2.0px（2026-09-29 起 100% 下取整，
            // 保留聚焦层次）—— 层次上永远挡不住文字
            CardSkin.paint(g2, w, h, 9, null, color, focused ? 3.0 : 2.0);
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(7, 12, 7, 12);
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.left = 12; insets.right = 12; insets.top = 7; insets.bottom = 7;
            return insets;
        }
    }

    // ==================== 自定义滚动条（R4：黑轨道 / 白箭头 / 绿滑块） ====================

    /**
     * 扁平滚动条 UI：圆角黑轨道 + 自绘白箭头按钮 + 绿色胶囊滑块。
     *
     * <p>颜色全部在 paint 期现取 {@code Ui.SCROLL_*} 字段（切主题 repaint 即换色）；
     * 刻意**不**走 UIManager 值（install() 里的旧 ScrollBar.* 键已失效，见那里注释）。
     */
    private static final class FlatScrollBarUI extends BasicScrollBarUI {
        /** 箭头按钮短边尺寸（px） */
        private static final int ARROW_H = 14;
        /** 条宽（px）——默认 12，调用方可经 installScrollBar 覆盖 */
        private static final int WIDTH = 12;
        /**
         * 滑块圆角半径（px）——2026-09-29 新增。
         * 原来 fillRoundRect 传的是 (w, h)：arcH 被绑到滑块**长度**上，两端变成细长半椭圆
         * （"拉尖"）。现在固定半径，arcW = arcH = 2 × 本值，再按短边收敛。
         * 3 = 与 ArrowButton 悬停圆角(6,6)同系；要更方正改 2，要更圆改 4。
         */
        private static final int THUMB_R = 3;

        /** 轨道：SCROLL_TRACK 填充，两端圆角（竖条上下圆 / 横条左右圆） */
        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(SCROLL_TRACK);
            JScrollBar sb = (JScrollBar) c;
            boolean horiz = sb.getOrientation() == JScrollBar.HORIZONTAL;
            float arc = horiz ? bounds.height : bounds.width;
            // 铺满整条（含箭头按钮区）：箭头是透明底，要透出轨道。
            // 箭头按钮修成 14px 高后（2026-09-28 T11），BasicScrollBarUI 算出的
            // trackBounds 会内缩掉两端 → 这里还原成整条，否则箭头区露出滚动面板底色
            Rectangle full = horiz
                    ? new Rectangle(0, bounds.y, c.getWidth(), bounds.height)
                    : new Rectangle(bounds.x, 0, bounds.width, c.getHeight());
            g2.fill(new RoundRectangle2D.Float(full.x, full.y, full.width, full.height, arc, arc));
            g2.dispose();
        }

        /** 滑块：SCROLL_THUMB（指针悬停其上时 SCROLL_THUMB_HOVER），四周 2px 内缩，全圆角胶囊 */
        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
            // 悬停检测：指针位置与滑块屏幕矩形比对（无状态，不额外挂监听器）
            boolean hover = false;
            try {
                Point mouse = java.awt.MouseInfo.getPointerInfo().getLocation();
                Point loc = c.getLocationOnScreen();
                hover = new Rectangle(bounds.x + loc.x, bounds.y + loc.y,
                        bounds.width, bounds.height).contains(mouse);
            } catch (Exception ignore) {
                // 尚未上屏 / 离屏：按非悬停处理
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hover ? SCROLL_THUMB_HOVER : SCROLL_THUMB);
            int w = Math.max(4, bounds.width - 4);
            int h = Math.max(4, bounds.height - 4);
            // 小圆角长条（2026-09-29）：arcW = arcH = 2×THUMB_R（半径），与滑块长度解耦；
            // 滑块极短时 (w,h) 会小于 arc，用 min 收敛，避免圆角大于短边画出畸形
            int arc = Math.min(THUMB_R * 2, Math.min(w, h));
            g2.fillRoundRect(bounds.x + 2, bounds.y + 2, w, h, arc, arc);
            g2.dispose();
        }

        /** 箭头按钮：方向 0=上 1=下 2=左 3=右（参数 = 滚动条方向常量） */
        @Override
        protected JButton createDecreaseButton(int orientation) {
            return new ArrowButton(orientation == JScrollBar.HORIZONTAL ? 2 : 0);
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return new ArrowButton(orientation == JScrollBar.HORIZONTAL ? 3 : 1);
        }

        /** 条宽 12（原 10；圆角 + 可点性更好） */
        @Override
        public Dimension getPreferredSize(JComponent c) {
            JScrollBar sb = (JScrollBar) c;
            return sb.getOrientation() == JScrollBar.HORIZONTAL
                    ? new Dimension(ARROW_H, WIDTH)
                    : new Dimension(WIDTH, ARROW_H);
        }

        /** 箭头按钮：透明底（透出轨道）+ 自绘实心三角（禁用字符/emoji，符合「按钮字形自绘」约定） */
        private static final class ArrowButton extends JButton {
            private final int dir;
            private boolean hover;

            ArrowButton(int dir) {
                this.dir = dir;
                // 全局 ButtonUI = ModernButtonUI 后，箭头按钮必须 FLAT：透明底透出轨道，
                // 不能被 SECONDARY 灰卡垫底（2026-09-29「全部统一」配套）
                putClientProperty(ModernButtonUI.STYLE_KEY, Style.FLAT);
                setOpaque(false);
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setBorder(BorderFactory.createEmptyBorder());
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                // BasicScrollBarUI.layoutVScrollbar/HScrollbar 按按钮 getPreferredSize() 定按钮尺寸：
                // 无文本/无边框/无图标的 JButton 首选尺寸是 0×0 → 按钮被布局成 12×0，
                // 三角被零高裁掉不显示（2026-09-28 T11 离屏实测）→ 显式钉死 14×14
                setPreferredSize(new Dimension(ARROW_H, ARROW_H));
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }
                    @Override public void mouseExited(MouseEvent e)  { hover = false; repaint(); }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                // 悬停先铺圆角底（不悬停时透明，透出轨道黑）
                if (hover) {
                    g2.setColor(SCROLL_THUMB_HOVER);
                    g2.fillRoundRect(1, 1, w - 2, h - 2, 6, 6);
                }
                // 实心三角
                g2.setColor(SCROLL_ARROW);
                int cx = w / 2, cy = h / 2;
                int s = Math.min(w, h) / 4 + 1;
                Path2D p = new Path2D.Float();
                switch (dir) {
                    case 0:  // 上
                        p.moveTo(cx - s, cy + s / 2); p.lineTo(cx + s, cy + s / 2); p.lineTo(cx, cy - s);
                        break;
                    case 1:  // 下
                        p.moveTo(cx - s, cy - s / 2); p.lineTo(cx + s, cy - s / 2); p.lineTo(cx, cy + s);
                        break;
                    case 2:  // 左
                        p.moveTo(cx + s / 2, cy - s); p.lineTo(cx + s / 2, cy + s); p.lineTo(cx - s, cy);
                        break;
                    default: // 右
                        p.moveTo(cx - s / 2, cy - s); p.lineTo(cx - s / 2, cy + s); p.lineTo(cx + s, cy);
                        break;
                }
                p.closePath();
                g2.fill(p);
                g2.dispose();
            }
        }
    }

    /**
     * 给滚动条装自定义 UI（只作用于垂直条，一行接入）。
     *
     * @param sp    目标滚动面板
     * @param width 条宽：聊天区 = 12，左侧设备列表 = 10（Q4/Q6 定稿）
     */
    static void installScrollBar(JScrollPane sp, int width) {
        JScrollBar bar = sp.getVerticalScrollBar();
        bar.setUI(new FlatScrollBarUI());
        bar.setPreferredSize(new Dimension(width, 0));
        bar.setUnitIncrement(16);
        bar.setOpaque(false);
    }
}
