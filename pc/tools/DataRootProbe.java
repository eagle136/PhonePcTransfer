package com.phonepctransfer.pc;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 数据目录自检（2026-09-21 加）—— 数据从「程序目录」搬到 `%APPDATA%\PhonePcTransfer` 之后，
 * 用这个探针核对：解析出来的根目录对不对、旧数据有没有被搬过来、每个设备组的记录还在不在。
 *
 * <p>**不启动 GUI、不抢端口、不发广播**，只读配置与历史（搬迁那一步会写入目标目录）。
 *
 * <pre>
 * javac -cp build/classes -d build/probe tools/DataRootProbe.java
 * # 干跑：临时冒充 APPDATA，验证搬迁逻辑（不碰真实用户数据）
 * APPDATA="C:/.../build/tmp/fake-appdata" java -Dfile.encoding=UTF-8 -cp "build/classes;build/probe" \
 *      com.phonepctransfer.pc.DataRootProbe
 * # 真跑：不设 APPDATA，就是真实搬迁
 * </pre>
 *
 * 只用于自检，**不进**发布 jar。
 */
public final class DataRootProbe {

    public static void main(String[] args) throws Exception {
        Path root = Store.defaultRoot();
        System.out.println("数据目录 = " + root.toAbsolutePath());
        System.out.println("  config.json 存在 = " + Files.isRegularFile(root.resolve("config.json")));
        System.out.println("  搬迁标记 = " + Files.isRegularFile(root.resolve(".migrated-into-appdata")));

        Store store = new Store(root);
        store.load();
        System.out.println("  本机标识 = " + store.deviceId() + " / " + store.deviceName());
        System.out.println("  保存位置 = " + (store.saveDir().isEmpty() ? "（默认 received/）" : store.saveDir()));
        System.out.println("  上次选中 = " + store.lastIp());
        List<Store.Group> gs = store.groups();
        System.out.println("设备组 = " + gs.size() + " 组");
        int total = 0;
        for (Store.Group g : gs) {
            int n = store.loadHistory(g.curIp, 500).size();
            total += n;
            System.out.println(String.format("  %-42s 地址 %d 个  记录 %d 条  名=%s",
                    g.key, g.ips.size(), n, g.displayName()));
        }
        System.out.println("记录合计 = " + total + " 条");
        int files = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root.resolve("history"), "*.jsonl")) {
            for (Path p : ds) {
                files++;
            }
        }
        System.out.println("历史文件 = " + files + " 份");

        // ffmpeg（随包文件）与数据目录已经解耦，这里顺手报一下它在哪
        System.out.println("内置 ffmpeg = " + FfmpegDecoder.bundledPath());
        System.exit(0);
    }
}
