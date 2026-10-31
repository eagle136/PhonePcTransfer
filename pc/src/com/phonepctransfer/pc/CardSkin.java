package com.phonepctransfer.pc;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 透明 PNG 圆角卡片贴图工具（2026-09-28 新增）——「界面内圆角卡片」的**唯一**绘制入口。
 *
 * <p>为什么要有这个类（承接 Ui.java 里 2026-09-27/09-28 两次实测的历史）：
 * <ul>
 *   <li><b>问题一：矢量描边在分数 DPI 下四边粗细不一。</b>边框原先用 Java2D 现画
 *       （1px {@code drawRoundRect} 描边 → 后改「外框填 border 色 + 内框填 fill 色」的填充环）。
 *       在 Windows 125% 缩放下（旧钉定值；2026-09-29 起钉 100%、坐标全整数，但「跟随系统缩放」
 *       模式下分数坐标仍会出现）组件坐标系落到分数设备像素，四条边命中的亚像素偏移各不相同，
 *       于是同一条边一会儿几乎隐形、一会儿发虚变粗（用户实测「设置」按钮上边框消失、右边框缺失）。</li>
 *   <li><b>问题二：文字被自己所在组件的 bounds 裁掉。</b>JLabel/JButton 首选宽 = FontMetrics
 *       .stringWidth（零容差），而抗锯齿渲染的实际墨迹比度量宽 5~13%（见
 *       {@code ChatDevices.INK_SAFETY = 1.12} 同源口径），首末字形会被组件自身边界削边。</li>
 * </ul>
 *
 * <p>本类的做法：卡片边界**不再每次重绘现算矢量**，而是<b>预先栅格化成一张真正的透明 PNG 图</b>
 * （Java2D 画 → {@code ImageIO} 编码 PNG 字节 → 再解码回 {@code Image}），抗锯齿结果固化在像素里；
 * 绘制时按 <b>9 宫格</b>拉伸铺满目标矩形（四角 1:1 锐利、四边单向拉伸、中心双向拉伸）。
 * 这样任意 DPI 下四边粗细一致，且卡片总是先画在组件背景层，文字/图标随后覆盖其上
 * ⇒ 文本永不被卡片边界裁掉、也不会被卡片盖住。
 *
 * <p><b>线程约定：</b>{@link #paint} 只允许在 EDT / Swing 绘制线程调用（贴图缓存的同步只保证
 * 不损坏 {@code HashMap}，并不保证与组件树并发访问安全）。贴图缓存本身是线程安全的。
 *
 * <p>零外部依赖：只用 JDK 自带 AWT / ImageIO。
 */
final class CardSkin {

    private CardSkin() { }

    /** 默认环宽（逻辑像素）——2026-09-29 起 100% 钉定下取整 2.0（旧 1.8 在整数像素下仍发虚） */
    private static final double DEFAULT_RING_PX = 2.0;

    /** 贴图缓存：key = arc|fillArgb|borderArgb|ringPx。贴图数量天然有限（几种圆角 × 几种配色），无需淘汰 */
    private static final Map<String, Image> CACHE = new HashMap<String, Image>();

    /**
     * 画圆角卡片：fill=底色（null=只画空心环，芯透明）；border=边框色（null=无边框）；
     * ringPx=边框粗细（逻辑像素）；arc=圆角半径。9 宫格拉伸铺满 (0,0,w,h)。
     *
     * <p>调用顺序铁律：只在组件**背景层**（paintComponent / 按钮 UI 的 paint 开头）调用，
     * 且必须在画文字之前 —— 卡片在下、文字在上，文字正常显示。
     * <b>绝不要</b>在 {@code paintBorder} 里用 fill != null 调用（见
     * {@link Ui.RoundedFieldBorder} 注释里的铁律）。
     */
    static void paint(Graphics2D g2, int w, int h, int arc, Color fill, Color border, double ringPx) {
        if (g2 == null || w <= 0 || h <= 0) {
            return; // 未布局 / 被移出屏幕的尺寸：什么都不画，绝不算负宽高
        }
        int c = arc + 1;                        // 角块边长（= 贴图里圆角区域占的像素数）
        if (w < 2 * c || h < 2 * c) {
            // 退化保护：按钮/输入框极小（角块都放不下）时，9 宫格会出现零宽甚至负宽的中间切片。
            // 这里退回一次性矢量绘制：宁可短一帧的边框精度，也不能抛异常或画出负宽图形。
            paintDegenerate(g2, w, h, arc, fill, border, ringPx);
            return;
        }
        Image img = cardPng(arc, fill, border, ringPx);

        // 9 宫格：源切片 [0,c) / [c,c+2) / [c+2,S)，目标 [0,c) / [c,w-c) / [w-c,w)（y 同理）。
        // 贴图尺寸 S = 2*(arc+1) + 2 = 2c + 2 ⇒ S - c = c + 2，中间源切片恰好 2px。
        // 这 2px 位于圆角之外的直线段上，是纯色（fill 或 border），拉伸不会产生接缝。
        int[] sx = { 0, c, c + 2 };
        int[] sw = { c, 2, c };
        int[] dx = { 0, c, w - c };
        int[] dw = { c, w - 2 * c, c };
        int[] sy = { 0, c, c + 2 };
        int[] sh = { c, 2, c };
        int[] dy = { 0, c, h - c };
        int[] dh = { c, h - 2 * c, c };

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                g2.drawImage(img,
                        dx[col], dy[row], dx[col] + dw[col], dy[row] + dh[row],
                        sx[col], sy[row], sx[col] + sw[col], sy[row] + sh[row],
                        null);
            }
        }
    }

    /** 同 {@link #paint(Graphics2D, int, int, int, Color, Color, double)}，环宽取默认 2.0px */
    static void paint(Graphics2D g2, int w, int h, int arc, Color fill, Color border) {
        paint(g2, w, h, arc, fill, border, DEFAULT_RING_PX);
    }

    // ===== 贴图生成与缓存 =====

    /**
     * 取（必要时生成）「透明 PNG 圆角卡片」贴图。
     *
     * <p>生成步骤照需求照做，顺序不能变：
     * <ol>
     *   <li>{@code BufferedImage(TYPE_INT_ARGB)}，尺寸 {@code S = 2*(arc+1) + 2}，角块 {@code c = arc+1}；</li>
     *   <li>Java2D 开抗锯齿：外圆角矩形填 border（若有），内圆角矩形（inset = ringPx）填 fill；
     *       {@code fill == null} 时用 {@link AlphaComposite#Clear} 把内圆角矩形<b>挖成透明</b>，
     *       得到**空心环**（芯完全透明，才不会盖住底下/上面的文字）；</li>
     *   <li>{@code ImageIO.write(img, "png", baos)} 编码成 PNG 字节 → {@code ImageIO.read} 读回 Image。
     *       <b>这一步是需求要求（要的是真正的透明 PNG 贴图），不是多余动作</b>：它把抗锯齿墨迹、
     *       圆角色与环宽全部固化成一份可复用的像素资源，而不是每次重绘重跑一遍矢量栅格化。</li>
     * </ol>
     * 失败（IOException，例如编码器不可用）时降级返回原 {@code BufferedImage} 并写日志，
     * 保证界面永远画得出来（贴图退化、观感不变）。
     */
    private static Image cardPng(int arc, Color fill, Color border, double ringPx) {
        String key = key(arc, fill, border, ringPx);
        synchronized (CACHE) {
            Image cached = CACHE.get(key);
            if (cached != null) {
                return cached;
            }
            Image img = buildPng(arc, fill, border, ringPx);
            CACHE.put(key, img);
            return img;
        }
    }

    /** 真正生成 PNG 贴图（只在缓存未命中时调用；同步由 {@link #cardPng} 把持） */
    private static Image buildPng(int arc, Color fill, Color border, double ringPx) {
        int c = arc + 1;                    // 圆角区域边长
        int s = 2 * c + 2;                  // 贴图边长：两侧各留 c，中间 2px 均匀色供拉伸

        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                    RenderingHints.VALUE_STROKE_PURE);
            // 几何取 +0.5 半像素对齐：边正好落在整像素格上并有 1px 抗锯齿过渡，
            // 半透明边像素在 9 宫格拉伸时是均匀的，不会出现一边实一边虚。
            double half = 0.5;
            if (border != null) {
                g.setColor(border);
                g.fill(new RoundRectangle2D.Double(half, half, s - 1.0, s - 1.0, arc, arc));
            }
            // 内缩量：只有「有边框」时才内缩出环宽；**无边框（border==null）必须铺满整张贴图**，
            // 否则实心卡片四周会留出一条约 ringPx 宽的透明缝（气泡/主按钮会在父底色上现出一圈细边，
            // 视觉上卡片比组件小 —— 2026-09-28 由 T4 实测发现）。
            double ri = (border == null) ? 0.0 : Math.max(0.0, ringPx);
            double innerArc = Math.max(0.0, arc - ri);  // 内圆角与外圆角同心 ⇒ 四边环宽恒等
            RoundRectangle2D inner = new RoundRectangle2D.Double(
                    half + ri, half + ri, s - 1.0 - 2 * ri, s - 1.0 - 2 * ri, innerArc, innerArc);
            if (fill != null) {
                g.setColor(fill);
                g.fill(inner);
            } else {
                // 空心环：把内圆角矩形挖成全透明（芯里连半透明像素都不留），
                // 这样它叠在文字上方时也挡不住字 —— 输入框边框专用（paintBorder 里画）。
                g.setComposite(AlphaComposite.Clear);
                g.fill(inner);
            }
        } finally {
            g.dispose();
        }

        // 真·PNG 往返：编码 → 解码。需求明确要「透明 PNG 卡片贴图」，而不是直接把矢量画到屏幕上。
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", baos);
            Image decoded = ImageIO.read(new ByteArrayInputStream(baos.toByteArray()));
            if (decoded != null) {
                return decoded;
            }
            Log.i("CardSkin: PNG decode returned null, fallback to raw BufferedImage, arc=" + arc);
        } catch (IOException e) {
            // 降级：直接用内存图，界面观感不变（只是少了 PNG 往返这一步）
            Log.i("CardSkin: PNG round-trip failed, fallback to raw BufferedImage: " + e);
        }
        return img;
    }

    /** 缓存 key：arc + 两色 ARGB + 环宽（环宽按 0.1px 量化，避免 double 字符串抖动导致缓存失效） */
    private static String key(int arc, Color fill, Color border, double ringPx) {
        return arc + "|" + (fill == null ? "null" : Integer.toString(fill.getRGB(), 16))
                + "|" + (border == null ? "null" : Integer.toString(border.getRGB(), 16))
                + "|" + Math.round(ringPx * 10) / 10.0;
    }

    // ===== 退化路径 =====

    /**
     * 9 宫格放不下时的矢量退化绘制（组件极小 / 尚未布局）。
     * 语义与贴图一致：fill = null → 只描边不填芯；border = null → 不描边。
     */
    private static void paintDegenerate(Graphics2D g2, int w, int h, int arc,
                                        Color fill, Color border, double ringPx) {
        int a = Math.max(0, Math.min(arc, Math.min(w, h))); // 圆角半径不能超过矩形本身
        Object oldAA = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (fill != null) {
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, w, h, a, a);
        }
        if (border != null) {
            g2.setColor(border);
            java.awt.BasicStroke oldStroke = null;
            java.awt.Stroke st = g2.getStroke();
            if (st instanceof java.awt.BasicStroke) {
                oldStroke = (java.awt.BasicStroke) st;
            }
            float bw = (float) Math.max(1.0, ringPx);
            g2.setStroke(new java.awt.BasicStroke(bw)); // 1px 描边会落一半在组件外，这里统一取 ≥1px
            int off = (int) Math.round(bw / 2.0);
            g2.drawRoundRect(off, off, Math.max(0, w - 1 - off * 2), Math.max(0, h - 1 - off * 2), a, a);
            g2.setStroke(oldStroke != null ? oldStroke : st);
        }
        if (oldAA != null) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
    }
}
