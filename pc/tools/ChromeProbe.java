package com.phonepctransfer.pc;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPanel;

/**
 * 离屏 chrome 探针（2026-09-28 加，T11）：把 4 个改自绘外壳的窗口
 *（设置 / 来电 / 语音 / 视频）渲染成 PNG，供像素判据验收。
 *
 * 与 {@code UiWinProbe} 的区别：
 *  - **不 show() 任何窗口**（设置 / 来电弹窗是模态，一 show 就阻塞探针）；
 *    直接 paint 装好 chrome 后的内容面板到 ARGB 画布 —— 四角圆角处会留下
 *    alpha==0 的透明像素，Pillow 可量化判据；
 *  - 语音 / 视频窗走**反射构造**（私有构造器），只取 frame，绝不调 open()
 *    （不抢端口、不绑链路）；
 *  - VideoWindow 构造里起了 5s 隐藏控件的 javax.swing.Timer（非守护线程）
 *    → 探针末尾必须 System.exit(0) 才能退出。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "build/classes;build/probe" \
 *      com.phonepctransfer.pc.ChromeProbe --dark --out ../shots/chrome_dark
 * </pre>
 *
 * 只用于自检：编译进 build/probe，**不进** dist 里的发布 jar（打包清单只收 src/**）。
 */
public final class ChromeProbe {

    public static void main(String[] args) throws Exception {
        List<String> a = Arrays.asList(args);
        boolean dark = a.contains("--dark");
        String out = "chrome";
        int i = a.indexOf("--out");
        if (i >= 0 && i + 1 < args.length) {
            out = args[i + 1];
        }

        if (dark) {
            Ui.applyDark(true);
        }

        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);

        // ① 设置窗：与 SettingsDialog.show 同一条路（install → 内容 CENTER → 关闭按钮 SOUTH），
        //    但不 setVisible
        JDialog settings = new JDialog((Frame) null, "设置", true);
        WindowChrome.install(settings, "设置", settings::dispose);
        settings.add(ChatWindow.SettingsDialog.buildContent(settings, store, link),
                BorderLayout.CENTER);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        JButton ok = Ui.primary(new JButton("关闭"));
        ok.addActionListener(e -> settings.dispose());
        south.add(ok);
        settings.add(south, BorderLayout.SOUTH);
        settings.setSize(470, 420 + WindowChrome.DIALOG_TITLE_H);
        paint(settings, out + "_settings.png");

        // ② 来电弹窗：buildIncomingPanel 是 private static → 反射调用
        JDialog incoming = new JDialog((Frame) null, "来电", true);
        WindowChrome.install(incoming, "来电", () -> { });
        Method bip = ChatWindow.class.getDeclaredMethod("buildIncomingPanel",
                String.class, String.class, boolean.class, Runnable.class, Runnable.class);
        bip.setAccessible(true);
        Runnable noop = () -> { };
        JComponent panel = (JComponent) bip.invoke(null, new Object[] {
                "探针手机", "192.168.1.99", false, noop, noop });
        incoming.add(panel, BorderLayout.CENTER);
        incoming.setSize(380, 280 + WindowChrome.DIALOG_TITLE_H);
        paint(incoming, out + "_incoming.png");

        // ③ 语音窗：私有构造器 → 反射；不调 open()（不 show、不抢焦点）
        Constructor<VoiceCallWindow> vc = VoiceCallWindow.class
                .getDeclaredConstructor(Frame.class, String.class);
        vc.setAccessible(true);
        VoiceCallWindow voice = vc.newInstance((Frame) null, "探针手机");
        Field vf = VoiceCallWindow.class.getDeclaredField("frame");
        vf.setAccessible(true);
        paint((Window) vf.get(voice), out + "_voice.png");

        // ④ 视频窗：同 ③（frame 有 public frame() 访问器）
        Constructor<VideoWindow> xc = VideoWindow.class
                .getDeclaredConstructor(Window.class, String.class);
        xc.setAccessible(true);
        VideoWindow video = xc.newInstance((Window) null, "探针手机");
        paint(video.frame(), out + "_video.png");

        System.out.println("CHROME PROBE DONE (" + (dark ? "dark" : "light") + ")");
        System.exit(0); // VideoWindow 的 hideTimer 非守护 → 必须显式退出
    }

    /**
     * 把窗口的内容面板（装 chrome 后 = DialogChrome）离屏 paint 到 ARGB 画布。
     * 全程不 show() 窗口：无模态阻塞、无网络、不占端口。
     */
    private static void paint(Window w, String path) throws Exception {
        java.awt.Container pane;
        if (w instanceof JFrame) {
            pane = ((JFrame) w).getContentPane();
        } else if (w instanceof JDialog) {
            pane = ((JDialog) w).getContentPane();
        } else {
            throw new IllegalArgumentException("unsupported window: " + w);
        }
        JComponent c = (JComponent) pane;
        int wd = Math.max(1, w.getWidth());
        int ht = Math.max(1, w.getHeight());
        c.setSize(wd, ht);
        layoutRec(c); // 离屏组件树 setSize 不触发布局（LayoutProbe 实测）→ 递归 doLayout
        BufferedImage img = new BufferedImage(wd, ht, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        c.paint(g);
        g.dispose();
        ImageIO.write(img, "png", new File(path));
        System.out.println("saved " + path + " " + wd + "x" + ht);
    }

    /** 递归 doLayout：离屏组件树必须手动布局，validate()/setSize 不生效（LayoutProbe 实测） */
    private static void layoutRec(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component ch : c.getComponents()) {
            if (ch instanceof java.awt.Container) {
                layoutRec((java.awt.Container) ch);
            }
        }
    }
}