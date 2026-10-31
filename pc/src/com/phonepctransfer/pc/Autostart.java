package com.phonepctransfer.pc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 开机自启（Windows）：往当前用户的 {@code HKCU\...\CurrentVersion\Run} 写/删一个值，
 * 值里带的命令行**含 {@code --hide}** —— 开机拉起时直接进托盘，不弹聊天窗口。
 *
 * 为什么走注册表而不是「启动」文件夹放快捷方式：Run 键是任务管理器「启动应用」
 * 列的主数据源，用户能在系统界面里一眼看到并管理；且写 HKCU 不需要管理员权限
 * （本程序刻意不提权运行，见 run.bat 顶部关于 UIPI 拖放的说明）。
 *
 * ⚠ 所有方法都会起一个 powershell 进程（约 0.2~0.5s），**不要在 EDT 上调用**。
 */
final class Autostart {

    /** Run 键里的值名；任务管理器显示的名字取自 exe 的文件描述，不受这里影响 */
    private static final String VALUE_NAME = "PhonePcTransfer";
    private static final String RUN_KEY =
            "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private Autostart() { }

    /** 只有 Windows 有「注册表 Run 键」这套自启机制 */
    static boolean supported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("windows");
    }

    /**
     * 开机要执行的命令行（已经带好引号与 {@code --hide}）。
     *
     * @return 安装版 = 启动器 exe；源码/jar 版 = javaw + jar 或 class 目录；取不到返回 null
     */
    static String launchCommand() {
        String exe = PcMain.installedLauncherExe();
        if (exe != null) {
            return "\"" + exe + "\" --hide";
        }
        Path code;
        try {
            code = Paths.get(PcMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            Log.w("自启：定位不到自身代码路径: " + e);
            return null;
        }
        String javaBin = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe").toString();
        String head = "\"" + javaBin + "\" -Dfile.encoding=UTF-8 ";
        if (Files.isDirectory(code)) {
            return head + "-cp \"" + code + "\" " + PcMain.class.getName() + " --hide";
        }
        return head + "-jar \"" + code + "\" --hide";
    }

    /** 已注册的命令行；null = 未开启自启 */
    static String registeredCommand() {
        if (!supported()) {
            return null;
        }
        String ps = PROLOG + """
                $p=Get-ItemProperty -Path '__KEY__' -Name '__NAME__'
                if ($null -ne $p -and $null -ne $p.__NAME__) { Write-Output ('VAL ' + $p.__NAME__) }
                else { Write-Output 'NONE' }
                """
                .replace("__KEY__", RUN_KEY).replace("__NAME__", VALUE_NAME);
        try {
            for (String line : PcMain.runPowerShell(ps).split("\n")) {
                String s = line.trim();
                if (s.startsWith("VAL ")) {
                    return s.substring(4).trim();
                }
                if (s.equals("NONE")) {
                    return null;
                }
            }
        } catch (Throwable t) {
            Log.w("读取自启注册状态失败: " + t);
        }
        return null;
    }

    /**
     * 注册自启：写入 Run 值，并读回确认（写成功 ≠ 生效，精简系统/组策略可能拦住 HKCU 写入）。
     *
     * @return 读回确认后的实际状态
     */
    static boolean enable() {
        String cmd = launchCommand();
        if (cmd == null) {
            return false;
        }
        String ps = PROLOG + """
                New-ItemProperty -Path '__KEY__' -Name '__NAME__' -PropertyType String -Value '__CMD__' -Force | Out-Null
                $p=Get-ItemProperty -Path '__KEY__' -Name '__NAME__'
                if ($null -ne $p -and $null -ne $p.__NAME__) { Write-Output 'OK' } else { Write-Output 'FAIL' }
                """
                .replace("__KEY__", RUN_KEY)
                .replace("__NAME__", VALUE_NAME)
                .replace("__CMD__", cmd.replace("'", "''"));
        try {
            String out = PcMain.runPowerShell(ps);
            if (!out.contains("OK")) {
                Log.w("自启注册未生效，PowerShell 输出: " + out.trim());
                return false;
            }
            Log.i("开机自启已注册: " + cmd);
            return true;
        } catch (Throwable t) {
            Log.w("注册开机自启失败: " + t);
            return false;
        }
    }

    /** 取消自启：删除 Run 值（值不存在也算成功）。 */
    static boolean disable() {
        String ps = PROLOG + """
                Remove-ItemProperty -Path '__KEY__' -Name '__NAME__' -ErrorAction SilentlyContinue
                $p=Get-ItemProperty -Path '__KEY__' -Name '__NAME__'
                if ($null -ne $p -and $null -ne $p.__NAME__) { Write-Output 'STILL' } else { Write-Output 'OK' }
                """
                .replace("__KEY__", RUN_KEY).replace("__NAME__", VALUE_NAME);
        try {
            String out = PcMain.runPowerShell(ps);
            if (!out.contains("OK")) {
                Log.w("取消开机自启未生效，PowerShell 输出: " + out.trim());
                return false;
            }
            Log.i("开机自启已取消");
            return true;
        } catch (Throwable t) {
            Log.w("取消开机自启失败: " + t);
            return false;
        }
    }

    /**
     * PowerShell 公共头：静默错误 + UTF-8 输出（-EncodedCommand 里中文/中文路径都要靠它）。
     * 各脚本里用 {@code '__X__'} 占位，替换前必须对值做 {@code ''} 转义。
     */
    private static final String PROLOG = """
            $ErrorActionPreference='SilentlyContinue'
            $ProgressPreference='SilentlyContinue'
            [Console]::OutputEncoding=[Text.Encoding]::UTF8
            """;
}
