import javax.imageio.ImageIO;
import java.io.File;

public class MakePng {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("MakeIcon");
        java.lang.reflect.Method m = c.getDeclaredMethod("render", int.class);
        m.setAccessible(true);
        ImageIO.write((java.awt.image.BufferedImage) m.invoke(null, 256), "png", new File(args[0]));
        System.out.println("PNG_OK " + args[0]);
    }
}
