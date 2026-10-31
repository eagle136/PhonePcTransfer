package com.phonepctransfer.pc;

import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

/**
 * 2of3 字号统一 B5.6 —— 7 窗口溢出核对截图（2026-09-29）：
 * 主窗 / 添加设备 / 设置 / 来电 / 通话 / 视频 / 图片查看器。
 * 每窗一张 PNG（printAll 真窗渲染），供逐张核对「无文字裁边、无换行错位」。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false \
 *      -Dsun.java2d.uiScale=1.0 -Dphonepctransfer.home=&lt;探针目录&gt; \
 *      -cp "pc/build/classes;pc/build/probe" com.phonepctransfer.pc.WinShots
 * </pre>
 */
public final class WinShots {

    public static void main(String[] args) throws Exception {
        Ui.install();
        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        ChatWindow win = new ChatWindow(link, store);
        win.showWindow();
        SwingUtilities.invokeAndWait(win.frame::revalidate);
        Thread.sleep(500);

        File dir = new File("pc/build/probe/winshots");
        dir.mkdirs();

        // 1 主窗
        shot(win.frame, dir, "1_main");

        // 2 添加设备（模态：后台线程开，主线程截图，再 dispose 解除模态）
        shotModal(dir, "2_add_device", "添加设备", () -> win.devices.editPeer(null));

        // 3 设置（模态）
        shotModal(dir, "3_settings", "设置",
                () -> ChatWindow.SettingsDialog.show(win.frame, store, link));

        // 4 来电弹窗（离线构建：反射 buildIncomingPanel + DialogChrome，同 ChromeProbe 口径）
        shotIncoming(dir);

        // 5 语音通话
        VoiceCallWindow vc = VoiceCallWindow.open(win.frame, "探针对端");
        Frame vcFrame = (Frame) f(vc, "frame");
        SwingUtilities.invokeAndWait(vcFrame::revalidate);
        Thread.sleep(300);
        shot(vcFrame, dir, "5_voice_call");
        vcFrame.dispose();

        // 6 视频
        VideoWindow vw = VideoWindow.open(win.frame, "探针对端");
        SwingUtilities.invokeAndWait(vw.frame()::revalidate);
        Thread.sleep(300);
        shot(vw.frame(), dir, "6_video");
        vw.frame().dispose();

        // 7 图片查看器（异步解码 → 等窗口出现）
        ImageViewer.show(win.frame, new File("pc/tools/app_2.png"));
        Window viewer = null;
        for (int i = 0; i < 40 && viewer == null; i++) {
            Thread.sleep(250);
            for (Window w : Window.getWindows()) {
                if (w instanceof java.awt.Dialog
                        && "app_2.png".equals(((java.awt.Dialog) w).getTitle())) {
                    viewer = w;
                }
            }
        }
        if (viewer != null) {
            SwingUtilities.invokeAndWait(viewer::revalidate);
            Thread.sleep(300);
            // 全屏查看器 = 整屏，截图太大没意义 → 只截右上 900x220（关闭按钮区）
            shotRegion(viewer, dir, "7_image_viewer",
                    viewer.getWidth() - 900, 0, 900, 220);
            viewer.dispose();
        } else {
            System.out.println("7_image_viewer: 窗口未出现");
        }

        System.out.println("WINSHOTS DONE -> " + dir.getAbsolutePath());
        System.exit(0);
    }

    /** 模态对话框：后台线程开（模态事件循环占住后台线程），主线程截图后 dispose 解除 */
    private static void shotModal(File dir, String name, String title, Runnable opener)
            throws Exception {
        Thread t = new Thread(opener, "modal-opener-" + name);
        t.setDaemon(true);
        t.start();
        Window dlg = null;
        for (int i = 0; i < 40 && dlg == null; i++) {
            Thread.sleep(250);
            for (Window w : Window.getWindows()) {
                if (w instanceof java.awt.Dialog
                        && title.equals(((java.awt.Dialog) w).getTitle())) {
                    dlg = w;
                }
            }
        }
        if (dlg == null) {
            System.out.println(name + ": 窗口未出现");
            return;
        }
        SwingUtilities.invokeAndWait(dlg::revalidate);
        Thread.sleep(300);
        shot(dlg, dir, name);
        final Window d = dlg;
        SwingUtilities.invokeAndWait(d::dispose);
        t.join(2000);
    }

    /** 来电弹窗离线构建（showIncomingDialog 私有且依赖通话状态机 → 反射 buildIncomingPanel） */
    private static void shotIncoming(File dir) throws Exception {
        JDialog incoming = new JDialog((Frame) null, "来电", true);
        WindowChrome.install(incoming, "来电", () -> { });
        Method bip = ChatWindow.class.getDeclaredMethod("buildIncomingPanel",
                String.class, String.class, boolean.class, Runnable.class, Runnable.class);
        bip.setAccessible(true);
        Runnable noop = () -> { };
        JComponent panel = (JComponent) bip.invoke(null, new Object[] {
                "探针对端", "192.168.1.99", false, noop, noop });
        incoming.add(panel, BorderLayout.CENTER);
        incoming.setSize(380, 280 + WindowChrome.DIALOG_TITLE_H);
        SwingUtilities.invokeAndWait(incoming::revalidate);
        Thread.sleep(200);
        shot(incoming, dir, "4_incoming");
        incoming.dispose();
    }

    private static void shot(Window w, File dir, String name) throws Exception {
        shotRegion(w, dir, name, 0, 0, w.getWidth(), w.getHeight());
    }

    private static void shotRegion(Window w, File dir, String name,
                                   int x, int y, int rw, int rh) throws Exception {
        BufferedImage img = new BufferedImage(
                Math.max(1, Math.min(rw, w.getWidth() - x)),
                Math.max(1, Math.min(rh, w.getHeight() - y)),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.translate(-x, -y);
        w.printAll(g);
        g.dispose();
        File out = new File(dir, name + ".png");
        ImageIO.write(img, "png", out);
        System.out.println("shot " + name + " " + img.getWidth() + "x" + img.getHeight());
    }

    private static Object f(Object owner, String name) throws Exception {
        Class<?> cl = owner.getClass();
        while (cl != null) {
            try {
                java.lang.reflect.Field fld = cl.getDeclaredField(name);
                fld.setAccessible(true);
                return fld.get(owner);
            } catch (NoSuchFieldException e) {
                cl = cl.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
