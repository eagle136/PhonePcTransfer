# 手机电脑互传（PhonePcTransfer）

局域网内手机 ⇄ 电脑的**文件 / 文本 / 文件夹互传**，自动识别对方客户端；同时支持**局域网视频**与**语音通话**。
手机端是 Android 应用，电脑端是常驻托盘的 Windows 程序，两端在同一个 WiFi（或手机热点）下直连，界面像聊天软件。

## 下载

在 [Releases](https://github.com/eagle136/PhonePcTransfer/releases) 下载最新版：


## 使用

### 1. 电脑端

1. 安装 MSI，或直接运行 jar。
2. **首次使用点一次防火墙放行**（入站 TCP 28765 / UDP 28766）：
3. 启动后常驻系统托盘，关闭窗口只是隐藏到托盘。

注意：**主程序不要用管理员身份运行**，否则拖拽文件会被 Windows 拦掉。

接收的文件默认保存在 `%APPDATA%\PhonePcTransfer\received`，可在设置里改。

### 2. 手机端

1. 安装 APK 并打开。
2. 按提示授权：通知、文件访问；**Android 16 及以上还需允许「本地网络」权限**。
3. 同一 WiFi 下会自动发现电脑，点设备即可连接；找不到就点「＋」手动填电脑上显示的 IP。

### 3. 互传

| 要做什么 | 操作 |
|---|---|
| 发文本 | 输入框输入后发送 |
| 同步剪贴板 | 默认开启：手机复制 → 电脑；电脑复制 → 手机 |
| 发文件 / 文件夹（电脑） | 直接把文件或文件夹拖进窗口，确认后发送 |
| 发文件 / 文件夹（手机） | 点「＋」选文件 / 文件夹；或在其它应用里「分享」到本应用 |
| 视频通话 / 语音通话 | 连上设备后点「＋」菜单里的对应项 |

文件落点：手机在「下载/手机电脑互传/」，电脑在 `%APPDATA%\PhonePcTransfer\received`。

## 常见问题

- **电脑和手机互相找不到**：确认在同一网段；没有使用VPN拦截通信；路由器关闭 AP 隔离；手机确认已给「本地网络」权限；电脑防火墙已放行 28765/28766。
- **手机切后台/熄屏就断**：部分 ROM 会冻结后台应用。把本应用加入电池优化白名单（允许完全后台行为）。

## 从源码构建

版本号只在根目录 `var` 文件里维护，三个打包脚本都会自动按它打包：

```ini
VERSION=1.2.13
```

```bat
:: PC 可运行 jar（JDK 17+）
py build_jar.py

:: PC MSI 安装包（Windows + Git Bash + JDK 21 + WiX 3.11，普通用户窗口运行）
py build_msi.py

:: Android APK（JDK 17+，Android SDK Platform 37）
py build_apk.py
py build_apk.py no-r8     :: 不打 R8 的真机回归包
```

产物统一在 `out\`：`手机电脑互传-<版本号>.apk`、`手机电脑互传-<版本号>.msi`、`PhonePcTransfer-PC.jar`（同一份 jar 也会写到 `pc\dist\`，供 `pc\run.bat` 直接启动）。

`build_apk.py` 的 APK 打包约定（2026-11-25 起）：

- **强制离线**：Gradle 恒定带 `--offline`，只用本机已解压的发行版（`%USERPROFILE%\.gradle\wrapper\dists`）；
  找不到就直接报错，不再回退 `gradlew.bat` 联网下载。首次请先正常联网构建一次，把依赖灌进缓存。
- **temp 中转**：APK 先落到项目根 `temp\`，再从 `temp\` 移动到 `out\`。
- **回收站收尾**：`temp\` 整个移入回收站（成功/失败都执行），比逐文件删除快得多，误删也能还原。

## 目录

```
android/      Android 端（Kotlin + Jetpack Compose）
pc/           PC 端（Java Swing + 系统托盘，零第三方运行时依赖）
tools/        IP 库生成等辅助工具、MSI 打包内核（make-msi.sh）
.github/      CI：推送 v* tag 自动构建两端并发布 Release
```
