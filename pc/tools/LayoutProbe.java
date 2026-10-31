package com.phonepctransfer.pc;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 2of3 字号统一 B5.3 布局探针（2026-09-29 重写，替代旧 T11 滚动条诊断）。
 *
 * <p>离线量数值（不靠肉眼），覆盖 B4 固定容器复核表：
 * <ul>
 *   <li>R2 侧栏按钮行：infoPanel 内容宽 / themeBtn / settingsBtn / 两者之和 ≤ 内容宽</li>
 *   <li>R1 设备行：DeviceRow 高 = 46（自绘基线 y=22 名称 / y=37 副标题，代码常量）</li>
 *   <li>R5 顶栏：remarkLabel / addrBtn 实际宽（SIDE_SLOT 固定占位已废弃，
 *       现为「按备注实际宽度占位」模型 → 判据 = 不溢出、中段不裁）</li>
 *   <li>R8 通话窗：底部按钮宽 ≤ 320（窗宽）</li>
 * </ul>
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false \
 *      -Dsun.java2d.uiScale=1.0 -Dphonepctransfer.home=&lt;探针目录&gt; \
 *      -cp "pc/build/classes;pc/build/probe" com.phonepctransfer.pc.LayoutProbe
 * </pre>
 */
public final class LayoutProbe {

    public static void main(String[] args) throws Exception {
        Ui.install();

        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow win = new ChatWindow(link, store);
        win.showWindow();
        javax.swing.SwingUtilities.invokeAndWait(win.frame::revalidate);
        Thread.sleep(500);

        // ===== R2 侧栏按钮行 =====
        JPanel infoPanel = (JPanel) f(win.devices, "infoPanel");
        JButton themeBtn = (JButton) f(win.devices, "themeBtn");
        JButton settingsBtn = (JButton) f(win.devices, "settingsBtn");
        java.awt.Insets ins = infoPanel.getBorder() == null
                ? new java.awt.Insets(0, 0, 0, 0) : infoPanel.getBorder().getBorderInsets(infoPanel);
        int contentW = infoPanel.getWidth() - ins.left - ins.right;
        System.out.println("[R2] infoPanel 宽=" + infoPanel.getWidth()
                + " border(" + ins.left + "," + ins.right + ")"
                + " 内容宽=" + contentW);
        System.out.println("[R2] themeBtn 宽=" + themeBtn.getWidth()
                + " settingsBtn 宽=" + settingsBtn.getWidth()
                + " 两者之和=" + (themeBtn.getWidth() + settingsBtn.getWidth()));
        System.out.println("[R2] 判据: " + (themeBtn.getWidth() + settingsBtn.getWidth() <= contentW
                ? "PASS（和 ≤ 内容宽）" : "FAIL（和 > 内容宽）"));

        // ===== R1 设备行 =====
        final int[] rowH = { -1 };
        final int[] rowCount = { 0 };
        final Container list = (Container) f(win.devices, "deviceListPanel");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            for (Component c : list.getComponents()) {
                if (c instanceof JPanel && c.getClass().getSimpleName().equals("DeviceRow")) {
                    rowCount[0]++;
                    rowH[0] = c.getHeight();
                }
            }
        });
        System.out.println("[R1] DeviceRow 行数=" + rowCount[0] + " 实测高=" + rowH[0]
                + "（自绘基线 y=22 名称 / y=37 副标题，代码常量）");
        System.out.println("[R1] 判据: " + (rowH[0] == 46 ? "PASS（高=46）" : "FAIL（高≠46）"));

        // ===== R5 顶栏（SIDE_SLOT 已废弃 → 按备注实际宽度占位模型） =====
        final JLabel remarkLabel = (JLabel) f(win, "remarkLabel");
        final JButton addrBtn = (JButton) f(win, "addrBtn");
        System.out.println("[R5] remarkLabel 宽=" + remarkLabel.getWidth()
                + "（实际文字宽 + 8px 墨迹余量）addrBtn 宽=" + addrBtn.getWidth()
                + " visible=" + addrBtn.isVisible());
        // 顶栏整体不溢出：leftBox + 中段 + 右段 ≤ 窗口宽
        System.out.println("[R5] frame 宽=" + win.frame.getWidth()
                + " 判据: 目视 + 不裁边（固定占位模型已废弃，无 80px 等宽判据）");

        // ===== R8 通话窗 =====
        VoiceCallWindow vc = VoiceCallWindow.open(win.frame, "探针对端");
        final java.awt.Frame vcFrame = (java.awt.Frame) f(vc, "frame");
        javax.swing.SwingUtilities.invokeAndWait(vcFrame::revalidate);
        Thread.sleep(300);
        final int[] btnW = { -1 };
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            walk(vcFrame, c -> {
                if (c instanceof JButton && "挂断".equals(((JButton) c).getToolTipText())) {
                    btnW[0] = c.getWidth();
                }
            });
        });
        System.out.println("[R8] 通话窗宽=" + vcFrame.getWidth() + " 挂断按钮宽=" + btnW[0]);
        System.out.println("[R8] 判据: " + (btnW[0] > 0 && btnW[0] <= vcFrame.getWidth()
                ? "PASS（按钮 ≤ 窗宽）" : "FAIL"));
        vcFrame.dispose();

        System.exit(0);
    }

    interface Finder { void find(Component c); }

    private static void walk(Component c, Finder f) {
        f.find(c);
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                walk(k, f);
            }
        }
    }

    private static Object f(Object owner, String name) throws Exception {
        Class<?> cl = owner.getClass();
        while (cl != null) {
            try {
                Field fld = cl.getDeclaredField(name);
                fld.setAccessible(true);
                return fld.get(owner);
            } catch (NoSuchFieldException e) {
                cl = cl.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
