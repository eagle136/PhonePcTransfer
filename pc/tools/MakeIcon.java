import com.phonepctransfer.pc.AppIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 生成 Windows 图标文件（jpackage --icon 用）。
 *
 * <p>2026-09-15：图形**不再手绘**，改为调用 {@link AppIcon#render(int)} —— 与安卓端 launcher
 * 图标（{@code drawable/ic_launcher_foreground.xml} + {@code @color/ic_launcher_background}）
 * 同源。本类只负责把位图封装成 ICO（jpackage 只认经典 BMP 帧，不认 PNG 帧）。
 *
 * <p>编译（必须先编过 src，AppIcon.class 在 ../build/classes）：
 * <pre>  javac -encoding UTF-8 -cp ../build/classes -d . MakeIcon.java</pre>
 * <p>运行（Windows 分号分隔 classpath）：
 * <pre>  java -cp "../build/classes;." MakeIcon app.ico</pre>
 * <p>可选第二个参数 = 预览图输出（拼图 PNG，仅供开发期肉眼核对）：
 * <pre>  java -cp "../build/classes;." MakeIcon app.ico preview.png</pre>
 */
public class MakeIcon {

    /** ICO 里要装的尺寸（Windows 会按显示场景挑：16 任务栏/32 桌面/256 大图标视图） */
    private static final int[] SIZES = {256, 128, 64, 48, 32, 16};

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("用法: MakeIcon <out.ico> [preview.png]");
            System.exit(2);
        }
        byte[][] frames = new byte[SIZES.length][];
        for (int i = 0; i < SIZES.length; i++) {
            frames[i] = dibFrame(AppIcon.render(SIZES[i]));
        }

        ByteBuffer out = ByteBuffer.allocate(6 + 16 * SIZES.length + total(frames))
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) 0);            // reserved
        out.putShort((short) 1);            // type = icon
        out.putShort((short) SIZES.length);
        int offset = 6 + 16 * SIZES.length;
        for (int i = 0; i < SIZES.length; i++) {
            int s = SIZES[i];
            out.put((byte) (s >= 256 ? 0 : s));  // width（0 表示 256）
            out.put((byte) (s >= 256 ? 0 : s));  // height
            out.put((byte) 0);                   // palette
            out.put((byte) 0);                   // reserved
            out.putShort((short) 1);             // planes
            out.putShort((short) 32);            // bpp
            out.putInt(frames[i].length);
            out.putInt(offset);
            offset += frames[i].length;
        }
        for (byte[] f : frames) out.put(f);

        File target = new File(args[0]);
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream o = new FileOutputStream(target)) {
            o.write(out.array());
        }
        System.out.println("ICO_OK " + target + " 尺寸=" + SIZES.length
                + " 字节=" + target.length());

        if (args.length > 1) {
            BufferedImage sheet = AppIcon.contactSheet(160, 128, 64, 48, 32, 16);
            ImageIO.write(sheet, "png", new File(args[1]));
            System.out.println("PREVIEW_OK " + args[1]);
        }
    }

    static int total(byte[][] f) {
        int n = 0;
        for (byte[] b : f) n += b.length;
        return n;
    }

    /** 一帧 = BITMAPINFOHEADER(高加倍) + BGRA 像素(自底向上) + 全 0 AND 掩码 */
    static byte[] dibFrame(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int xorSize = w * h * 4;
        int maskRow = ((w + 31) / 32) * 4;
        int maskSize = maskRow * h;
        ByteBuffer b = ByteBuffer.allocate(40 + xorSize + maskSize)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40);           // biSize
        b.putInt(w);
        b.putInt(h * 2);        // XOR + AND 高度加倍
        b.putShort((short) 1);
        b.putShort((short) 32);
        b.putInt(0);            // BI_RGB
        b.putInt(xorSize + maskSize);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        b.putInt(0);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);
                b.put((byte) (argb & 0xFF));         // B
                b.put((byte) ((argb >> 8) & 0xFF));  // G
                b.put((byte) ((argb >> 16) & 0xFF)); // R
                b.put((byte) ((argb >> 24) & 0xFF)); // A
            }
        }
        for (int i = 0; i < maskSize; i++) b.put((byte) 0);
        return b.array();
    }
}
