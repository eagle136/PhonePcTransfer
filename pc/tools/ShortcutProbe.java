import java.lang.reflect.Method;

/**
 * 探针：直接调 PcMain.ensureShortcutsFor(安装版 exe)，验一遍中文快捷方式的建/修/删英文名
 * 这条路（安装版 exe 在本机是 %LOCALAPPDATA%\PhonePcTransfer\PhonePcTransfer.exe）。
 * 用法：java -cp dist/PhonePcTransfer-PC.jar;. ShortcutProbe [exePath]
 */
public class ShortcutProbe {
    public static void main(String[] args) throws Exception {
        Class<?> c = Class.forName("com.phonepctransfer.pc.PcMain");
        Method ensure = c.getDeclaredMethod("ensureShortcutsFor", String.class);
        ensure.setAccessible(true);
        String exe = args.length > 0 ? args[0]
                : System.getenv("LOCALAPPDATA") + "\\PhonePcTransfer\\PhonePcTransfer.exe";
        System.out.println("exe = " + exe);
        ensure.invoke(null, exe);

        Method own = c.getDeclaredMethod("installedLauncherExe");
        own.setAccessible(true);
        System.out.println("installedLauncherExe(当前进程=" 
                + ProcessHandle.current().info().command().orElse("?") + ") = " + own.invoke(null));
    }
}
