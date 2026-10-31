package com.phonepctransfer.pc;

import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * 开发期界面自检 —— **不启动整套 App** 的离屏渲染（2026-09-21 加）。
 *
 * 与 {@code tools/UiProbe.java}（跑 {@code PcMain.main}）的区别：
 *  - 不抢单实例守卫口 **8767**、不绑 TCP **28765** / UDP **28766**
 *    → **正式实例还开着的时候也能跑**（PcMain 那条路会被单实例闸门直接劝退）；
 *  - 不发 UDP 广播 → 不会在手机上凭空多出一台设备；
 *  - store 指向真实的 `pc/` 目录（设备列表 / 接收目录都是真的），但**只读**：不写配置到磁盘。
 *
 * 代价：链路上什么都没起，所以设备行全是灰点、顶栏第二行显示「○ 未连接」——
 * 这一版只看**布局**（谁在哪个角、有没有多余文字、宽度对不对），不看连接状态。
 *
 * 只用于自检：编译进 build/probe，**不进** dist 里的发布 jar（打包清单只收 src/**）。
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp "build/classes;build/probe" \
 *      com.phonepctransfer.pc.UiWinProbe ../shots/pc_win.png
 * </pre>
 */
public final class UiWinProbe {

    public static void main(String[] args) throws Exception {
        final java.util.List<String> a = java.util.Arrays.asList(args);
        String out = "ui_win_probe.png";
        for (String s : args) {
            if (!s.startsWith("--")) {
                out = s;
                break;
            }
        }
        boolean dark = a.contains("--dark");
        boolean toggle = a.contains("--toggle");
        boolean longMode = a.contains("--long");

        // 主题探针（2026-09-27）：--dark = 建窗口前切到深色调色板（与 PcMain 启动期同一路径）；
        // --toggle = 先按浅色建窗，再走「运行时切换」路径（applyDark(d, root) 组件树重着色），
        // 直接覆盖用户点左下角按钮那条代码路，验证已建组件的旧色能不能全换掉
        if (dark) {
            Ui.applyDark(true);
        }

        Store store = new Store(Store.defaultRoot());
        store.load();
        Link link = new Link();
        link.setStore(store);
        // 默认窗口尺寸 920×660（ChatWindow 构造里 setSize），构完就是最终布局
        final ChatWindow win = new ChatWindow(link, store);
        // --long（2026-09-28 T11）：往 msgPanel 塞 40 条伪消息，强制聊天区出竖直滚动条
        //（AS_NEEDED 要内容超出视口才出条）→ 供滚动条像素判据
        if (longMode) {
            for (int i = 0; i < 40; i++) {
                Msg m = new Msg();
                m.mid = "probe-" + i;
                m.dir = (i % 2 == 0) ? Msg.DIR_SELF : Msg.DIR_PEER;
                m.kind = Msg.KIND_TEXT;
                m.peerIp = "192.168.1.99";
                m.text = "probe 伪消息 " + (i + 1);
                win.bubbles.addBubble(m);
            }
            // 设备列表同样塞填充行（设备太少时 AS_NEEDED 不出条，量不到 10px）
            win.devices.probePadDeviceList(40);
        }
        if (toggle) {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                Ui.applyDark(true, win.frame.getContentPane());
                // 与 ChatDevices.toggleTheme 同口径：输入框配色是 Ui.field 烤进去的，要重套
                Ui.field(win.input);
            });
        }
        win.showWindow();
        Thread.sleep(900);

        Frame target = null;
        for (Window w : Window.getWindows()) {
            if (w instanceof Frame && "手机电脑互传 — PC 端".equals(((Frame) w).getTitle())) {
                target = (Frame) w;
            }
        }
        if (target == null) {
            System.out.println("NO_FRAME");
            System.exit(2);
        }
        System.out.println("frame = " + target.getWidth() + "x" + target.getHeight());
        // 滚动条宽度真值（T11 判据：聊天区 12 / 设备列表 10，左比右窄）
        System.out.println("chat scrollbar width = " + win.scroll.getVerticalScrollBar().getWidth());
        System.out.println("device scrollbar width = " + win.devices.deviceScroll.getVerticalScrollBar().getWidth());

        BufferedImage img = new BufferedImage(
                Math.max(1, target.getWidth()), Math.max(1, target.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        target.printAll(g);
        g.dispose();
        ImageIO.write(img, "png", new File(out));
        System.out.println("saved " + out + " " + img.getWidth() + "x" + img.getHeight());
        System.exit(0);
    }
}
