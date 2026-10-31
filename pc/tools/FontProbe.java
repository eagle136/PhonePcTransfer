import com.phonepctransfer.pc.Fonts;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * 字体探针（2026-09-29 新增，离屏渲染，不起真窗，可 -Djava.awt.headless=true 跑）：
 *
 *  1. 打印全部可用字体族名（确认「宋体」/「SimSun」/「NSimSun」是否在列）
 *  2. 打印 Fonts.FAMILY 的解析结果（全项目字体族真源）
 *  3. 按 S2 新字号档（12/13/14/15/16/17/21/22/36）每档一行中英混排文本，
 *     出 4 张对比 PNG：{解析族, 雅黑} × {AA on, AA off}，肉眼比对清晰度
 *  4. 每档打印 stringWidth / getHeight / ascent / descent（与 Ui.ModernButtonUI
 *     的 12% 墨迹余量口径对照）
 *
 *  编译（工程根目录，JBR = "C:\Program Files\Android\Android Studio\jbr"）：
 *    "%JBR%\bin\javac.exe" -encoding UTF-8 -cp pc\src -d pc\tools ^
 *        pc\src\com\phonepctransfer\pc\Fonts.java pc\tools\FontProbe.java
 *  运行：
 *    "%JBR%\bin\java.exe" -Dfile.encoding=UTF-8 -Djava.awt.headless=true ^
 *        -cp pc\tools FontProbe [输出目录，默认 pc\tools]
 *
 *  判读：
 *   · 「宋体」或「SimSun」在列且 Fonts.FAMILY 命中 → R2 成立
 *   · 4 张图里 AA off 的小字号（12~16）应明显更锐利；大字号（21/22/36）
 *     若有锯齿 → 对应组件已用 Ui.AALabel 局部开 AA，属预期
 */
public final class FontProbe {

    /** S2 新字号档（100% 下 1pt 数值 = 1px） */
    private static final int[] SIZES = { 12, 13, 14, 15, 16, 17, 21, 22, 36 };

    /** 中英混排样本文本（覆盖中文/数字/大小写） */
    private static final String TEXT = "手机电脑互传 1234567890 ABCabc";

    public static void main(String[] args) throws Exception {
        String outDir = args.length > 0 ? args[0] : "pc/tools";
        new File(outDir).mkdirs();

        // ===== 1) 可用族名清单 =====
        String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        System.out.println("可用字体族总数 = " + families.length);
        System.out.println("「宋体」在列    = " + contains(families, "宋体"));
        System.out.println("「SimSun」在列  = " + contains(families, "SimSun"));
        System.out.println("「NSimSun」在列 = " + contains(families, "NSimSun"));
        System.out.println("「Microsoft YaHei UI」在列 = " + contains(families, "Microsoft YaHei UI"));

        // ===== 2) Fonts.FAMILY 解析结果 =====
        System.out.println("Fonts.FAMILY = " + Fonts.FAMILY);

        // ===== 3) 4 张对比 PNG（族 × AA）=====
        boolean hasYaHei = contains(families, "Microsoft YaHei UI");
        String[] probeFamilies = hasYaHei
                ? new String[] { Fonts.FAMILY, "Microsoft YaHei UI" }
                : new String[] { Fonts.FAMILY };
        for (String fam : probeFamilies) {
            renderSheet(fam, true, pngPath(outDir, fam, "aaon"));
            renderSheet(fam, false, pngPath(outDir, fam, "aao"));
        }

        // ===== 4) 每档度量 =====
        for (String fam : probeFamilies) {
            printMetrics(fam);
        }
        System.out.println("完成。PNG 在 " + new File(outDir).getAbsolutePath());
    }

    private static boolean contains(String[] arr, String s) {
        return Arrays.asList(arr).contains(s);
    }

    private static String pngPath(String outDir, String fam, String aaTag) {
        String safe = fam.replaceAll("[^A-Za-z0-9]", "_");
        return outDir + File.separator + "fontprobe_" + safe + "_" + aaTag + ".png";
    }

    /** 一族一 AA 模式：每档一行，输出 PNG */
    private static void renderSheet(String fam, boolean aaOn, String path) throws Exception {
        // 度量行高与总宽
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D tg = tmp.createGraphics();
        tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                aaOn ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                     : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        int[] rowH = new int[SIZES.length];
        int maxW = 0;
        for (int i = 0; i < SIZES.length; i++) {
            Font f = new Font(fam, Font.PLAIN, SIZES[i]);
            FontMetrics fm = tg.getFontMetrics(f);
            rowH[i] = fm.getAscent() + fm.getDescent() + 10;
            maxW = Math.max(maxW, fm.stringWidth(TEXT) + 90);
        }
        tg.dispose();

        int W = maxW + 40;
        int H = 0;
        for (int h : rowH) {
            H += h;
        }
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    aaOn ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                         : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            int y = 0;
            for (int i = 0; i < SIZES.length; i++) {
                Font f = new Font(fam, Font.PLAIN, SIZES[i]);
                FontMetrics fm = g.getFontMetrics(f);
                // 行首灰色档位标签（等宽小字，不参与对比）
                g.setColor(new Color(0x80, 0x80, 0x80));
                g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                String tag = SIZES[i] + "px";
                g.drawString(tag, 16, y + fm.getAscent() + 2);
                // 样本文本
                g.setColor(Color.BLACK);
                g.setFont(f);
                g.drawString(TEXT, 80, y + fm.getAscent() + 2);
                y += rowH[i];
            }
        } finally {
            g.dispose();
        }
        ImageIO.write(img, "png", new File(path));
        System.out.println("PNG: " + path);
    }

    /** 每档 stringWidth / getHeight / ascent / descent */
    private static void printMetrics(String fam) {
        BufferedImage tmp = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D tg = tmp.createGraphics();
        tg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        System.out.println("---- 度量[" + fam + "] 文本: " + TEXT);
        for (int s : SIZES) {
            Font f = new Font(fam, Font.PLAIN, s);
            FontMetrics fm = tg.getFontMetrics(f);
            System.out.printf("  %2dpx  stringWidth=%4d  height=%2d  ascent=%2d  descent=%2d%n",
                    s, fm.stringWidth(TEXT), fm.getHeight(), fm.getAscent(), fm.getDescent());
        }
        tg.dispose();
    }
}
