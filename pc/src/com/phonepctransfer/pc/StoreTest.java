package com.phonepctransfer.pc;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 保存目录功能自测（无界面）：
 *   默认值 / 自定义 / 持久化 / 非法目录回落 / 同名不覆盖
 *
 * 运行：
 *   javac -encoding UTF-8 --release 17 -d build/classes src/com/phonepctransfer/pc/*.java
 *   java -cp build/classes com.phonepctransfer.pc.StoreTest
 */
public class StoreTest {

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("ppstore");
        Path custom = Files.createDirectories(tmp.resolve("自定义输出"));
        Path root = Files.createDirectories(tmp.resolve("pcroot"));

        Store s = new Store(root);
        s.load();
        System.out.println("1. 默认目录      = " + s.receivedDirText());
        System.out.println("   saveDir 原值  = '" + s.saveDir() + "'");

        s.setSaveDir(custom.toString());
        System.out.println("2. 改成自定义后  = " + s.receivedDirText());

        Store s2 = new Store(root);
        s2.load();
        System.out.println("3. 重载后 saveDir= '" + s2.saveDir() + "'");
        System.out.println("   重载后生效目录= " + s2.receivedDirText());

        s2.setSaveDir("Z:\\nonexistent_pp_dir\\x");
        System.out.println("4. 非法目录后    = " + s2.receivedDirText());

        s2.setSaveDir("");
        System.out.println("5. 恢复默认后    = " + s2.receivedDirText());

        // createUniqueFile 是原子创建：返回的文件已存在，不再需要 createNewFile
        // ⚠️ 方法在 v2.7 已从 Link 搬到 LinkNames（同包直调即可，别再用反射点名 Link）
        Path recv = Files.createDirectories(tmp.resolve("recv"));
        File f1 = LinkNames.createUniqueFile(recv.toFile(), "a.txt");
        File f2 = LinkNames.createUniqueFile(recv.toFile(), "a.txt");
        File f3 = LinkNames.createUniqueFile(recv.toFile(), "a.txt");
        File f4 = LinkNames.createUniqueFile(recv.toFile(), "无扩展名");
        File f5 = LinkNames.createUniqueFile(recv.toFile(), ".gitignore");
        File f6 = LinkNames.createUniqueFile(recv.toFile(), "备份.tar.gz");
        File f7 = LinkNames.createUniqueFile(recv.toFile(), "备份.tar.gz");
        File d1 = LinkNames.createUniqueDir(recv.toFile(), "文件夹");
        File d2 = LinkNames.createUniqueDir(recv.toFile(), "文件夹");
        System.out.println("6. 同名第1/2/3次 = " + f1.getName() + " / " + f2.getName() + " / " + f3.getName());
        System.out.println("   无扩展名      = " + f4.getName());
        System.out.println("   点开头        = " + f5.getName());
        System.out.println("   多段扩展名    = " + f6.getName() + " / " + f7.getName());
        System.out.println("   同名目录      = " + d1.getName() + " / " + d2.getName());

        // 铁律：序号必须插在**扩展名前**（`a.txt.1` / `a.txt (1)` 这种系统按扩展名认类型时认不出，
        //       装不上 APK、打不开图片）—— 与安卓端 FileIO.uniqueFileName 同口径
        int fail = 0;
        fail += expect(f1.getName(), "a.txt");
        fail += expect(f2.getName(), "a (1).txt");
        fail += expect(f3.getName(), "a (2).txt");
        fail += expect(f4.getName(), "无扩展名");
        fail += expect(f5.getName(), ".gitignore");
        fail += expect(f6.getName(), "备份.tar.gz");
        fail += expect(f7.getName(), "备份.tar (1).gz");
        fail += expect(d1.getName(), "文件夹");
        fail += expect(d2.getName(), "文件夹 (1)");
        System.out.println(fail == 0 ? "NAME PROBE PASS" : "NAME PROBE FAIL x" + fail);

        System.out.println("7. config.json = ");
        for (String ln : Files.readAllLines(root.resolve("config.json"))) {
            System.out.println("   " + ln);
        }

        deleteTree(tmp);
        System.out.println("（临时目录已清理）");
    }

    private static int expect(String actual, String want) {
        boolean ok = want.equals(actual);
        System.out.println((ok ? "   ok   " : "   FAIL ") + want + "  ←  " + actual);
        return ok ? 0 : 1;
    }

    private static void deleteTree(Path p) {
        try (java.util.stream.Stream<Path> st = Files.walk(p)) {
            st.sorted(java.util.Comparator.reverseOrder()).forEach(x -> {
                try {
                    Files.deleteIfExists(x);
                } catch (Exception ignore) {
                }
            });
        } catch (Exception ignore) {
        }
    }
}
