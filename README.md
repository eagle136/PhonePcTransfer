# 手机电脑互传（PhonePcTransfer）

局域网内手机 ⇄ 电脑的**文件 / 文本 / 文件夹互传**，自动识别对方客户端；同时支持**局域网视频**与**语音通话**。
手机端是 Android 应用，电脑端是常驻托盘的 Windows 程序，两端在同一个 WiFi（或手机热点）下直连，界面像聊天软件。

## 下载

在 [Releases](https://github.com/eagle136/PhonePcTransfer/releases) 下载最新版：

| 平台 | 文件 | 说明 |
|---|---|---|
| Android | `PhonePcTransfer-<版本>.apk` | Android 8.0+，直接安装 |
| Windows | `PhonePcTransfer-<版本>.msi` | 一键安装，自带运行时与视频解码器 |
| 任意桌面 | `PhonePcTransfer-PC.jar` | 免安装，需 JDK 17+：`java -jar PhonePcTransfer-PC.jar` |

> CI 构建使用独立签名证书，**首次安装请先卸载本地开发版**；之后各 Release 之间可直接覆盖升级。

## 使用

### 1. 电脑端

1. 安装 MSI，或直接运行 jar。
2. **首次使用点一次防火墙放行**（入站 TCP 28765 / UDP 28766）：
   - 安装版：安装目录的 `app\firewall-addrules.cmd`，右键「以管理员身份运行」；
   - 源码版：`pc\run_admin.bat`。
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

- **电脑和手机互相找不到**：确认在同一网段；路由器关闭 AP 隔离；手机确认已给「本地网络」权限；电脑防火墙已放行 28765/28766。
- **手机切后台/熄屏就断**：部分 ROM 会冻结后台应用。把本应用加入电池优化白名单（允许完全后台行为）。
- **电脑关了窗口还在传吗**：关窗口只是隐藏到托盘，程序仍在运行；退出要从托盘菜单退出。
- **传文件同名不覆盖**：自动加 ` (1)` ` (2)`，序号插在扩展名前。

## 从源码构建

```bash
# Android（JDK 17+，Android SDK Platform 37）
cd android && ./gradlew :app:assembleRelease
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

```bat
:: PC（Windows + Git Bash + JDK 21 + WiX 3.11）
build.bat
```

签名材料不入库：本地放在仓库根 `signing/keystore.properties` 与对应 `.jks`。
推送到 `main` 会由 GitHub Actions 自动构建三件套并发布 Release（版本号 `1.1.<构建序号>`）。

## 目录

```
android/      Android 端（Kotlin + Jetpack Compose）
pc/           PC 端（Java Swing + 系统托盘，零第三方运行时依赖）
docs/         协议规范 docs/PROTOCOL.md 与验证记录
SOURCE_MAP.md 源码地图（改代码前先在这里定位文件）
.github/      CI：推送 main 自动构建并发布 Release
```
