# 手机电脑互传（PhonePcTransfer）

局域网内手机 ⇄ 电脑的**文件 / 文本 / 文件夹互传**，自动识别对方客户端；同时支持**局域网视频**与**语音通话**。
手机端是 Android 应用，电脑端是常驻托盘的后台程序，两端跑在同一个 WiFi（或手机热点）下，
走裸 TCP 长连接，像聊天软件一样左右分栏显示。

## 下载

每次推送到 `main` 都会自动构建并发布三件套，见 [Releases](https://github.com/eagle136/PhonePcTransfer/releases)：

| 平台 | 文件 | 说明 |
|---|---|---|
| Android | `PhonePcTransfer-<版本>.apk` | Android 8.0+，直接安装 |
| Windows | `PhonePcTransfer-<版本>.msi` | 一键安装，自带运行时与 ffmpeg |
| 任意桌面 | `PhonePcTransfer-PC.jar` | 免安装，需 JDK 17+ |

> CI 构建使用独立签名证书，与本地开发包签名不同：首次安装请先卸载旧版；
> 之后各 Release 之间可以直接覆盖升级。

> 目录名用纯 ASCII `PhonePcTransfer`（中文路径会让 Gradle/NDK/adb 出现各种不可预料的问题）；
> 「手机电脑互传」只作为 App 名称与界面标题。

```
PhonePcTransfer/
├── README.md              功能、使用、协议行为与实测记录
├── SOURCE_MAP.md          源码地图（改代码前先在这里定位文件）
├── .github/workflows/     CI：推送 main 自动构建 APK / MSI / jar 并发布 Release
├── docs/
│   ├── PROTOCOL.md        传输协议完整规范（帧格式 / 消息类型 / 去重收敛 / 剪贴板语义）
│   └── FOLDER-TRANSFER-VERIFY-20260917.md
├── android/               Android 端（Kotlin + Compose，AGP 9.3 / Kotlin 2.2 / Gradle 9.5）
└── pc/                    PC 端（Java Swing + 系统托盘，零外部依赖，JDK 17 目标）
```

## 从源码构建

```bash
# Android（JDK 17+，Android SDK Platform 37）
cd android && ./gradlew :app:assembleRelease
# 产物：android/app/build/outputs/apk/release/app-release.apk
```

```bat
:: PC（Windows + Git Bash + JDK 21 + WiX 3.11）
build.bat          :: 编译 jar，并调用 make-msi.sh 打出 MSI
```

签名材料不入库：本地放在仓库根 `signing/keystore.properties` 与对应的 `.jks`；
CI 版本号由工作流注入（`-PppVersionName` / `-PppVersionCode`，MSI 用 `APP_VER`）。

## 功能

| 能力 | 说明 |
|---|---|
| 双向聊天式界面 | 对方消息在左、自己的在右；文本 / 剪贴板 / 文件三类气泡 |
| 文本互传 | 输入框 + 发送；发送后台自动同步一次 |
| 文件互传 | 256KB 分片 + sha256 校验；手机收到的文件落在「下载/手机电脑互传/」，PC 收到的默认落在 `%APPDATA%\PhonePcTransfer\received\`（保存位置可改，见下） |
| **拖拽发送（PC）** | 文件 / 文件夹**直接拖进聊天窗口** → 弹确认「是否发送到 &lt;当前对话的设备&gt;」→ 确认即传；**设备不在线也照收**：内容先进待发队列，该设备连上后自动补发 |
| **系统分享接收（手机）** | 注册了全部格式（`*/*`）的 `ACTION_SEND` / `ACTION_SEND_MULTIPLE`：相册/文件管理器/任意应用点「分享」都能直接选到本应用，选定后弹「发送到哪台设备」，选完立刻在传 |
| **点通知回前台（手机）** | 常驻通知点一下即把应用切到前台（等价于点桌面图标，已有界面状态不丢） |
| 接收目录可调（PC） | 设置里可挑选接收文件的保存位置（更改 / 恢复默认 / 打开目录），写入 `config.json` 的 `saveDir`；目录不可写自动回落，同名文件自动加 ` (1)` ` (2)`（**插在扩展名前**）不覆盖 |
| 剪贴板同步模式 | 手机端一个总开关（**默认开**）：手机复制 → 电脑；电脑复制 → 手机**不分前后台**都自动写入手机剪贴板。关闭后改为手动 |
| 剪贴板联动 | 电脑复制 → 存缓冲（只留最新一条）并通知手机；手机前台时变化**即时**推送，失焦/切后台再补一次 |
| 长内容头尾截断 | 超过 300+150 字只显示头尾，可点「展开全文」 |
| 多候选 IP | 内置可保存的候选 IP 列表；UDP 广播发现到的设备会自动加入列表 |
| WiFi/热点门禁 | **已移除**（2026-09-16）：有没有 WiFi 都常驻运行（走数据网时发起前会先弹流量提醒）；**只有局域网扫描**要求本机有 WiFi/热点/以太网，见「局域网发现与扫描」 |
| 前台服务 | WiFi/热点可用时以 `dataSync` 类型前台服务保活链路 |
| 后台常驻 | PC 端为托盘常驻程序（关窗口只隐藏到托盘）；**不要以管理员身份运行**，否则拖放会被 UIPI 拦掉 |

## 使用

### 1. PC 端

```bat
build.bat            :: 编译（在工程根目录跑，用 Android Studio 自带 JBR，输出 pc\dist\PhonePcTransfer-PC.jar）
cd pc
run_admin.bat        :: **只做防火墙放行**（TCP 28765 / UDP 28766），随后以**非管理员**身份启动 run.bat
run.bat              :: 直接启动
```

> ⚠️ **不要以管理员身份运行主程序**：Windows UIPI 会静默拦掉资源管理器拖进管理员进程的拖放，
> 表现是「拖拽发送完全没反应」。`run_admin.bat` 只借管理员权限加防火墙规则，然后用 `explorer.exe`
> 转手启动（自动降回普通权限）。

**图标（PC/安卓同源，2026-09-15）**：`src/com/phonepctransfer/pc/AppIcon.java` 是**唯一来源** ——
几何 1:1 复刻安卓的 `drawable/ic_launcher_foreground.xml`（108×108 矢量）+ `@color/ic_launcher_background`
(#0E4C8A)。exe/开始菜单快捷方式（`tools/app.ico`，由 `tools/MakeIcon.java` 调 AppIcon 生成）、
托盘图标、窗口标题栏图标 四处全部取它。改图标只改 AppIcon（并同步安卓矢量图），**别再各画一套**。

```bash
# 重新生成 app.ico（先编过 src，AppIcon.class 在 build/classes；图标含 16/32/48/64/128/256 六档）
JBR="C:/Program Files/Android/Android Studio/jbr"
"$JBR/bin/javac.exe" -encoding UTF-8 -cp build/classes -d tools tools/MakeIcon.java
"$JBR/bin/java.exe" -cp "build/classes;tools" MakeIcon tools/app.ico tools/preview.png   # 第二个参数=预览拼图
```

**打安装包（MSI）** —— `make-msi.sh`（工程根目录；⚠️ **必须用 Git Bash 显式路径**执行，裸 `bash` 会解析到 WSL）：

```bash
"/c/Program Files/Git/usr/bin/bash" make-msi.sh
# 产物 pc/installer/output/手机电脑互传-<内部版本>.msi，并自动拷进 out/手机电脑互传-<对外版本>.msi
```

**版本号是两套（2026-09-21 起）**：

| 口径 | 值 | 用在哪 |
|---|---|---|
| 对外版本 `APP_VER` | `1.0.1`（默认，可 `APP_VER=1.0.2 …` 覆盖） | `out/` 里的文件名、交付给用户的说法、安卓 `versionName` |
| 内部 `ProductVersion` | 构建时刻 `YYMMDDHHMM` 编码成三段：`2609211548` → `26.9.31188`（`YY . MM . DD×1440+HH×60+MM`） | MSI 数据库内部，Windows「应用」列表里显示的就是它 |

- 内部版本必须**严格单调递增**，否则 Windows Installer 认为「同版本」不做覆盖安装、更低版本**直接拒装**。
  三段各有上限（major/minor ≤ 255、build ≤ 65535），10 位时刻戳塞不进去，所以按上面编码 ——
  月变则 minor 变大、同月内 build 严格单调，天然递增；且 `26.x.x` 远大于历史 `1.0.6281`，覆盖升级照常。
- ⚠️ 同一分钟内重复构建会算出同一个号 → 脚本再兜一道「取 `installer/output/` 里已有最大号 +1」。
- 内含 `dist/PhonePcTransfer-PC.jar` + `bin/ffmpeg.exe`（`--input` 会摊平目录，正好是 `<app>/bin/ffmpeg.exe`）。
- ⚠️ 出包前**先退出 PC 端旧实例**（运行中的进程会锁住 jar，覆盖写会失败 → 打到旧 jar）。
  打完用 md5 复核 `dist/PhonePcTransfer-PC.jar` 与 `installer/input/` 里那份一致。

### 2.1 交付包归置：`out/`

两端发布产物统一拷一份到仓库根的 `out/`，方便直接拿走：

```
out/
├── app-release.apk          # 安卓 R8 发布包（≈2.8 MB），build-release.bat 自动拷入，直接覆盖安装
└── 手机电脑互传-1.0.1.msi   # Windows 安装包（≈37 MB），make-msi.sh 自动拷入，双击装
```

- MSI 由 `make-msi.sh` **自动**拷过去（不用手抄路径）；APK 由 `build-release.bat`（工程根目录）**自动**拷过去
  （不跑脚本时手工一条）：
  ```bash
  cp android/app/build/outputs/apk/release/app-release.apk out/app-release.apk
  ```
- **取内部 ProductVersion**（不看文件名也能确认装的是不是这一版）：
  ```bash
  # 面板显示值 = 解出来的 ProductVersion；管理安装可把内嵌文件摊出来核对 jar
  msiexec /a "out/手机电脑互传-1.0.1.msi" TARGETDIR="<临时空目录>" /qn
  # 然后 md5sum <临时目录>/手机电脑互传/app/PhonePcTransfer-PC.jar —— 应与 pc/dist/ 那份一致
  ```
  （`reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Uninstall" /s /f 手机电脑互传` 看
  `DisplayVersion` = 当前已装版本。）
- 拷完**两边 md5 都要核**：APK 与 `app-release.apk` 一致、MSI 里的 jar 与 `dist/PhonePcTransfer-PC.jar` 一致。

- **不想退旧实例也能出包**（2026-09-16 实战）：`make-msi.sh` 只**读** `dist/*.jar`（拷贝进
  `installer/input/`），并不改写它，所以运行中的实例不挡 MSI 打包；只有**重新构建 jar**
  （`jar --create --file dist/...`）才会撞上文件锁（报 `另一个程序正在使用此文件`）。
  此时**不必**停掉用户正在用的 App —— 先确认 `dist` jar 的 mtime 晚于所有 `src/**/*.java`，
  再 `jar --list` 抽查最新改动的新增类在不在（如 2026-09-16 的 `ChatWindow$DownTriangle.class`），
  两样都过就说明 jar 已是最新，直接用即可。

托盘图标右键可打开聊天窗口 / 打开接收目录 / 发文件 / 手动同步。关窗口不退出，只是隐藏。

**拖拽发送**：在资源管理器里选中若干文件/文件夹，直接拖进聊天窗口任意位置（聊天区、设备列表都行），
会先弹一个确认框——

```
是否将这些内容发送到 手机（192.168.1.14）？

· 📄 报告.pdf    2.3 MB
· 📁 照片（文件夹）

共 2 项：1 个文件 + 1 个文件夹，文件合计 2.3 MB
```

点「发送」才开始传；也可以在资源管理器里复制文件后到窗口里 `Ctrl+V`。
**目标永远是当前活动会话**（左侧列表点了哪台就是哪台）。**设备不在线也能拖**（2026-10-30）：
确认框会注明「当前离线」，内容进待发队列并顺手发起一次连接；等该设备连上后自动补发。
一台设备都没选时只弹提示、不发送（没有目标地址，待发记录无处归属）。
（顺带：拖拽不会影响输入框自己的复制粘贴 —— 那里做了转发而不是覆盖。）

**接收文件的保存位置**：聊天窗口右上角「设置」→「收到文件的保存位置」→「更改…」挑一个目录即可
（默认 `%APPDATA%\PhonePcTransfer\received\`；「打开目录」直接跳过去）。路径存在 `config.json` 的 `saveDir` 字段，
留空表示用默认目录。目录不可写（选了只读盘、U 盘拔了）会自动回落到默认目录，
同名文件自动加 ` (1)`、` (2)` 序号**且序号插在扩展名前**（`报告.txt` → `报告 (1).txt`），不会覆盖已有文件。

#### 1.1 PC 端数据目录：`%APPDATA%\PhonePcTransfer`（2026-09-21 起）

**改动原因**：jpackage 出的 MSI 带 `MajorUpgrade`，覆盖安装 = **先卸旧、再装新**。
旧版把 `config.json` / `history/` / `pending.jsonl` 放在**程序目录**（`<install>\app\`），
于是每次覆盖升级都被卸载流程连数据一起清掉 —— 表现就是「更新完设备列表、聊天记录、保存位置全没了」。

**现在的落点**：

| 内容 | 路径 |
|---|---|
| 数据根目录 | `%APPDATA%\PhonePcTransfer`（`C:\Users\<你>\AppData\Roaming\PhonePcTransfer`） |
| 配置 | `<数据根>\config.json`（设备/分组/保存位置/旋转角/备注…） |
| 各设备聊天历史 | `<数据根>\history\<组键>.jsonl` |
| 待发队列 | `<数据根>\pending.jsonl` |
| 默认接收目录 | `<数据根>\received\` |
| 内置视频解码器 | `<安装目录>\app\bin\ffmpeg.exe`（**不在**数据目录里，见下） |

- 非 Windows / 取不到 `APPDATA` 时回落到 `~/.phonepctransfer`。
- **一次性搬迁**（首次用新版本启动时自动做一次，只拷不删）：从这些老位置找数据，挑 `peers[].lastSeenAt`
  最大的那份配置当底，其余按「只补空缺」合并；历史按 `mid` 逐条并进对应设备文件。
  完成后在数据根写 `.migrated-into-appdata` 标记，以后不再尝试。
  老位置**原样保留**，核对无误后可手动删：
  `cwd` → `cwd/pc` → `%LOCALAPPDATA%\手机电脑互传` → `~/手机电脑互传`（后三个是本项目历史上用过的）。
- 想强制指定数据目录（调试/便携模式）：`-Dphonepctransfer.home=<路径>` 或环境变量 `PHONEPCTRANSFER_HOME=<路径>`，
  设了就完全跳过搬迁逻辑。
- ⚠️ **ffmpeg 不再跟数据目录绑定**：`FfmpegDecoder.bundledPath()` 从**运行时代码所在位置**往上找 `bin/ffmpeg.exe`
  （安装版 = `<install>\app\bin\`，源码版 = `pc\bin\`），另带 `cwd\bin`、`cwd\pc\bin` 兜底。
  数据目录搬到 AppData 后，ffmpeg 仍留在安装目录里 —— 用户自己配的解码器路径（`config.json` 的 `ffmpegPath`）优先。
- 回归探针：`tools/DataRootProbe.java`（不打界面、不占端口），打印解析出的数据目录、迁移标记、
  设备组数/记录数/历史文件数，以及实际用到的 ffmpeg 路径。

> ⚠️ **升级顺序有坑（在老数据还没搬过的机器上）**：搬迁是**运行时**做的，而 MSI 的 `MajorUpgrade`
> 是「**先卸旧、再装新**」—— 卸载会把老程序目录（`%LOCALAPPDATA%\手机电脑互传\`）连同里面的
> `config.json` / `history\` / `received\` 一起清掉，**新版还没启动，数据就没了**。
>
> 正确顺序（数据还在老程序目录时）：
> 1. **先**拿新 jar 直接启动一次（自包含，不用装）：
>    `"<JRE>\bin\java.exe" -jar PhonePcTransfer-PC.jar` → 看到日志「数据目录迁移完成」后退出；
> 2. **再**双击装新 MSI（此时老目录被清也无所谓，数据已在 `%APPDATA%\PhonePcTransfer`）。
>
> 或者干脆手动备份：装前把 `%LOCALAPPDATA%\手机电脑互传\` 里的 `config.json` `history\` `received\`
> 拷出来，装完丢进 `%APPDATA%\PhonePcTransfer\`。
> **本机已完成搬迁**（2026-09-21 实测数据根 `%APPDATA%\PhonePcTransfer`，5 组设备 / 63 条记录 / 5 份历史），
> 直接装新 MSI 即可。

#### 1.2 PC 界面缩放钉定：`uiScale`（2026-09-29：100% 整数像素渲染 + 宋体）

**需求**：按 100% 渲染 —— 1 逻辑 px = 1 物理 px，组件坐标/边框/字体底边全部落整数像素网格，
消除分数 DPI 的抗锯齿抖动（历史「按钮边框消失/文字被裁」的同一根源）；宋体小字号（12~16px）
内嵌点阵位图 1:1 命中，笔画最锐利。

**机制**：JBR 的 Swing 是 DPI-aware —— 字体与像素都是逻辑单位、整体被渲染缩放乘一遍，
所以界面**比例**在各档缩放下本来就不会破；变的是「物理观感」。因此把渲染缩放**钉在 1.0**：

- `PcMain.main` 最先读 `config.json` 的 `uiScale`（缺省 **1.0**；旧值 1.25 在 load 时自动迁移为 1.0，
  下次 save() 写回），在任何 AWT/Swing 类初始化之前设 `sun.java2d.uiScale`（⚠ setProperty 必须
  先于 AWT 初始化才生效；JBR 25 上 `-D` 与程序内 setProperty 两条路都已实测验证，探针
  `pc/tools/DpiProbe.java`）；
- 同日配套改造：UI 字体族改**宋体优先**（缺失回退系统默认，单一真源 `Fonts.FAMILY`）；字号改
  S2 整数刻度（10→12 / 11→13 / 12→14 / 13→15 / 14→16 / 15→17 / 19→21 / 20→22 / 34→36）；
  文本抗锯齿**关**（`-Dawt.useSystemAAFontSettings=off -Dswing.aatext=false`，小字号走宋体点阵；
  >18px 大字号走 outline 会锯齿，那 3 处标签用 `Ui.AALabel` 局部显式开 AA）；卡片环宽取整 2.0px；
- 托盘菜单坐标换算（`PcMain.toLogicalScreen`）以**程序钉定值**为换算因子（不是系统 transform），
  「系统缩放 = 钉定值」与「系统缩放 ≠ 钉定值」两种场景都正确；
- 想改回「跟随系统缩放」：`config.json` 里写 `"uiScale": 0` 或 `"system"`（下次启动生效，
  save() 会原样保留）；数值可填 0.5~5，越界按非法回落 1.0。

#### 1.3 MSI 卸载：问「是否删用户数据」+ 完成页「立即运行」（2026-10-05）

**两条口径**（卸载机制设计记录，未入库）：卸载时让用户自己选要不要清 `%APPDATA%\PhonePcTransfer`；
**已经传输保存的文件永远不在删除范围内**（`<数据根>\received\` 硬跳过，`saveDir` 指向数据根外的更不碰）。

| 场景 | 行为 |
|---|---|
| 开始菜单/「应用」里卸载、`msiexec /x`、`/qb` | **弹一次**「是否删除用户数据」（是/否，默认落在「否」），选否只清快捷方式 |
| 静默卸载 `msiexec /qn`（脚本、SCCM、无人值守） | 不弹，直接**保留**数据 |
| 覆盖升级（装新版本，`MajorUpgrade` 先卸旧） | 两条动作都被 `NOT UPGRADINGPRODUCTCODE` 挡掉 —— 不弹、不动数据 |
| 安装完成页 | 多一个复选项 **「Launch PhonePcTransfer」，默认已勾选**，点「完成」直接拉起 `<INSTALLDIR>PhonePcTransfer.exe` |
| 卸载收尾（两种模式都做） | 清掉本程序创建的 `手机电脑互传.lnk` / `PhonePcTransfer.lnk`（桌面 + 开始菜单 `PhonePcTransfer` 分组），分组目录空了才删；顺手结束还在跑的 `PhonePcTransfer` 进程 |

**实现**：Java 业务代码零改动，全部落在 MSI 的 WiX 层。

- `make-msi.sh` 的 `BEGIN wxi-inject` 段在构建时生成两份覆盖文件，靠 `jpackage --resource-dir` 生效：
  `installer/resource/main.wxs`（从**本次真正使用的那个 JDK** 的 `jdk.jpackage.jmod` 现取现改，
  只把 `<?include overrides.wxi ?>` 从 `<Product>` **之前**挪到 `<Product>` **结尾内**）
  + `installer/resource/overrides.wxi`（`<Include>` 根，里面直接写 Property / CustomAction / UI / InstallExecuteSequence）。
- 卸载询问的正文是 `pc/tools/uninstall-ask.ps1`，构建时整份文件 → 去 BOM → UTF-16LE → base64
  内联进 MSI 的 `CustomAction.Target`，两个分支各拼一个前缀 `$env:PHONEPCTRANSFER_UNINSTALL_ASK="1"` / `"0"`。
  为什么不把脚本直接写进 WiX 表：脚本正文里有方括号（`[Text.Encoding]`，MSI 会当属性引用吃掉），
  且安装器一旦回退到 en-US 语言包（1252）就 `light` 报 **LGHT0311**；走 base64 的话表里只有 ASCII，
  中文注释和弹窗文案都在载荷里。
- 执行序列（实测查表，`PhonePcTransfer-26.10.8065.msi`）：

  ```
  JpAskData       type=98  immediate ExeCommand  REMOVE="ALL" AND UILevel > 2  AND NOT UPGRADINGPRODUCTCODE  seq=3498
  JpSilentResidue type=98  immediate ExeCommand  REMOVE="ALL" AND UILevel <= 2 AND NOT UPGRADINGPRODUCTCODE  seq=3499
  两条都在 RemoveFiles 之前；Return=ignore（询问脚本挂了也不许把卸载流程回滚）
  ExitDialog/Finish --DoAction--> JpSetLaunchTarget(type 51, 写 WixShellExecTarget) --> JpLaunchApp(WixCA/WixShellExec)
  ```

**踩过的坑（别再试第二遍）**：

- ⚠ **deferred 自定义动作弹不出窗**：deferred `WixQuietExec` 跑在 session 0 的 custom action server 里，
  `UserInteractive=False`，MessageBox 直接吞掉、还把序列卡住 ⇒ 必须用 **immediate** 的 type 98（ExeCommand +
  `Directory="TARGETDIR"`，不给就 CNDL0037；immediate 上写 `Impersonate` candle 直接报错）。immediate 跑在
  交互用户会话里（实测 `SessionId=1`、真桌面、64 位），弹窗能**同步阻塞**卸载序列。
- ⚠ 区分「有没有人看着」靠 **`UILevel`**，不是运行时探测：`/qn` → 2，`/x` 与 `/qb` → 3。
  所以 **`/qb` 会被当成有人值守、照样弹**，无人值守一定要 `/qn`。
- ⚠ `InstallUISequence` 里的动作对 ARP 式 `/x` 卸载**根本不跑**（日志：Client-side and UI is none or basic），
  `/x` 只有 MSI 自带那条确认框，WiX 自己画的对话框插不进这条路径。
- ⚠ include 必须进 `<Product>`：先 Fragment 后 Product 报 CNDL0107，include 放 `</Product>` 之后编译能过但
  `light` 不会把 Fragment 里的 CustomAction / InstallExecuteSequence 落表（动作压根没被调度）。
  `.wxi` 根元素必须是 `<Include>`，写成 `<Wix>` 报 CNDL0048。
- ⚠ 启动目标要**运行时格式化**：Property 表里写 `[INSTALLDIR]...` 是字面量（candle 还报 CNDL1077），
  所以先用 type 51 动作把展开后的路径写进 `WixShellExecTarget`，再跑 `WixShellExec`；
  启动动作 `Return="ignore"`（`asyncNoWait` 只能配 ExeCommand，BinaryKey 形式写它报 CNDL0038）。
- ⚠ MSI 命令行里 `[...]` 会被当属性引用吃掉（`[Text.Encoding]` 直接变空、`[[...]]` 也不是转义），
  所以 PowerShell 侧不许出现方括号占位，答案**编进两份载荷**而不是运行时传参；
  `-EncodedCommand` 后面也不能再跟位置参数（PowerShell 用法错，rc=1）。
- 安装器界面文案是中文：`jpackage -J-Duser.language=zh -J-Duser.country=CN` 让 light 走 `zh_CN.wxl`
  （自带 `Codepage="936"`）和 WixUI 的 zh-CN 语言包。**不要改回 `en-US`**：`MsiInstallerStrings_en.wxl`
  里是 `Codepage="1252"`，它会覆盖 `Product/@Codepage`，数据库回到 1252 → 任何中文都让 light 报
  LGHT0311（2026-10-30 实测，light 退出码 311）。产品名/菜单组仍是 ASCII 的 `PhonePcTransfer`
  （exe、安装目录、脚本引用不动），中文桌面图标名由 `PcMain.ensureDesktopShortcut()` 在**首次启动**
  时改成「手机电脑互传.lnk」—— `.lnk` 是文件系统对象，与 MSI codepage 无关。

### 2. Android 端

```bash
cd android
./gradlew :app:assembleDebug      # 开发用（不裁剪，39 MB，装到真机做回归）
./gradlew :app:assembleRelease    # 发布用（R8 混淆+压缩+资源收缩，2.7 MB）
# 一键脚本（双击即可，在工程根目录跑）：build-release.bat
#   → R8 release，APK 自动拷到根目录 out/app-release.apk（加参数 no-r8 出不混淆回归包）
# 产物 app/build/outputs/apk/debug/app-debug.apk
#      app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

> **版本号（2026-09-21 起）**：`versionName = "1.0.1"`（与 PC 分发包对外版本号同口径）、
> `versionCode = major*10000 + minor*100 + patch = 10001`。同签名覆盖安装认 **versionCode**
> （必须 ≥ 已装版本，与 versionName 无关）→ 改版本号时两个字段一起动，`versionCode` 只能涨不能跌。
> 核对：`cat app/build/outputs/apk/release/output-metadata.json | grep -E "versionCode|versionName"`。

> **R8 发布包（2026-09-15 起）**：`buildTypes.release` 走 AGP 9 的 `optimization { enable = true }`
> → `minifyReleaseWithR8` + `convertShrunkResourcesToBinaryRelease`（资源收缩）+ `optimizeReleaseResources`。
> 自定义规则在 `app/keepRules/rules.keep`，**必须**由 `optimization { keepRules { files.add(...) } }` 挂载 ——
> 只写 `enable = true` 的话规则文件**不会生效**（AGP 9 的坑，之前这个文件一直是死的）。
> 复核是否存在 `outputs/mapping/release/configuration.txt` 里能搜到自己的规则、`mapping.txt` 里类名没被改名。
> 规则内容只做 `-keepnames`（保名不保实现）+ 落盘枚举 + `SourceFile,LineNumberTable`，裁剪与优化照常。
> 签名沿用 `signing/` 那把 projectKey（与 debug 同签名，可直接覆盖安装）。
>
> **出包复核清单（2026-09-16 实测口径，别省）**：
> 1. **规则进没进 R8** —— `grep -c "keepnames class com.phonepctransfer" app/build/outputs/mapping/release/configuration.txt` 应为 1；
> 2. **类名没被改名** —— `grep "^com.phonepctransfer.AppCore ->" mapping.txt` 应输出 `… -> com.phonepctransfer.AppCore`（两边同名）；
> 3. **dex 里组件与枚举还在** —— 解 `classes.dex` 按 **JVM 类型描述符**（斜杠形式）搜，
>    不是点号：`Lcom/phonepctransfer/util/ClipCaptureService;`（无障碍服务，系统按字符串存名字）、
>    `Lcom/phonepctransfer/MainActivity;`、`Lcom/phonepctransfer/TransferService;` 必须都在；
>    落盘枚举常量名 `SELF/PEER/TEXT/CLIP/FILE/OK/PENDING/SENDING/FAILED` 一个不能少。
>    > 坑：用**点号全限定名**搜 dex 会全部「查无此项」（dex 里是 `Lcom/x/Y;`），别据此判定被混淆掉了；
>    > 同理，搜枚举要照 `MsgItem.kt` 里**真实**的常量名（`Kind` 只有 `TEXT/CLIP/FILE`，
>    > 没有 `FOLDER`；`Status` 只有 `OK/PENDING/SENDING/FAILED`，没有 `SENT/RECV`）。
> 4. 顺带确认装的不是 debug 包：`dumpsys package com.phonepctransfer | grep pkgFlags` 里**没有** `DEBUGGABLE`。

> MIUI 上 `adb install` 会被 `INSTALL_FAILED_USER_RESTRICTED` 拦截，有 root 时走：
> `adb push <apk> /data/local/tmp/pp.apk` → `adb shell "su -c 'pm install -r /data/local/tmp/pp.apk'"`。

首次启动需要在弹窗里授予通知/媒体读取权限；然后在「设置」里确认候选 IP（含 PC 的局域网地址）与端口 8765。

**从别的应用分享过来**（相册/文件管理器/微信/浏览器下载…）：

1. 在任意应用里选中文件 → 分享 → 选「手机电脑互传」（应用注册了全部格式，图片/视频/音频/压缩包/APK 都能选到）；
2. 应用被拉到前台并弹出「发送到哪台设备？」（已连接的那台默认选中）；
3. 选好点「发送」→ 立刻开始传，进度就在聊天区看。

细节：**多选文件分享**（`ACTION_SEND_MULTIPLE`）会一次发多个；只有一段纯文本的分享（没有文件）按文本消息发。
如果选定设备时链路还没连上，应用会先把内容转存到自己的私有目录再排队发送 —— 分享给的读权限是临时的，
不这么做的话排队那条在应用重启后就打不开了。转存目录 3 天自动清理。

**点通知回前台**：常驻通知（标题就是应用名）点一下 = 回到前台，等价于点桌面图标。

## 同步语义（核心行为契约）

### 剪贴板同步模式（手机端「设置」里的总开关，默认开启）

| 开关状态 | 手机 → 电脑 | 电脑 → 手机 |
|---|---|---|
| **开**（默认） | 复制瞬间由变化回调即时推送；离开应用前补推一次；回到前台再补推 | **不分前后台**都自动拉取并写入手机剪贴板（写剪贴板不受前台限制），链路一连上还会补拉一次 |
| **关** | 只有「· 前台时自动推送本机剪贴板」开着才自动推，否则要点「立即推送剪贴板」 | 只有「· 自动接收电脑剪贴板（仅前台）」开着且手机在前台才自动拉，否则要点「同步」 |

| 手机状态 | 行为 |
|---|---|
| 前台且持有焦点 | 剪贴板变化监听 + 1s 轮询，本机剪贴板一变就推给 PC；收到 PC 的 `clip_notify` 自动拉取 |
| 失焦 / 挂小窗 / 切后台 | 同步模式开：保留变化监听（挂小窗/分屏偶尔仍能捕获）+ 电脑侧内容照旧自动写入；关：转为纯被动 |
| 重新回到前台 | 立即补推一次本机剪贴板 + 冲刷待发队列 + 拉取 PC 剪贴板 |
| 无 WiFi/热点 | 整条链路停止，界面顶部出现警告条 |

PC 端任何时刻都在被动接收；只有本机剪贴板变化时才主动发一条不含正文的通知。

## 文件夹传输可靠性（2026-09-16 修复「传文件夹漏掉部分文件」）

老版本有 4 条会让成员**静默消失**的路径，现已全部堵住（协议层见 `docs/PROTOCOL.md` §4.8）：

| 老行为 | 为什么会漏文件 | 现在的做法 |
|---|---|---|
| 发送端把 `file_done` 写进 socket 就算成功，**不等对端确认** | 对端建文件/落盘/校验失败只在它本地记日志，发送端照样报「文件夹发送完成」 | 新增 `dir_end` / `dir_missing`：收尾核对成员清单，缺失项**自动补发**（最多 2 轮），最终仍缺则**两端气泡都标 FAILED 并把缺失文件名写进日志** |
| 任一成员失败 ⇒ **整目录中止** | 一个读不了的文件就能让后面所有成员一个都发不出去（用户看到的正是「后半截全没了」） | 本地源不可读（被删/无权限/SAF 失效）**只跳过该成员继续发**，最后统一汇报；只有网络异常才中止整目录 |
| 本地读源失败也执行 `s.close()` | 本地问题误杀**健康连接**，把后续所有传输一起带走 | 本地/网络异常严格区分：本地错误不关连接、不进待发队列，并给对端发 `file_cancel` 清掉半成品 |
| 目录枚举失败 ⇒ 当成「空目录」 | 对端照重建出一个空文件夹，「文件夹在、里面的文件不见了」；一个文件都没扫到时还会发出一个「成功」的空文件夹 | 读不到 ≠ 空的：枚举失败计入 `skipped` 并随 `dir_meta` 告知对端；扫不到任何文件但有读失败时**直接中止并提示** |

另外两处配套修复：**接收端收尾后不再删除目录记录**（同一 mid 重发时就地复用原目录，不再另建「文件夹 (1)」——那正是「原文件夹里少了文件」的观感来源）；**PC 接收端对成员相对路径逐段清洗**（对端带 `:` `*` `?` 等 NTFS 非法字符时不再直接抛异常吞掉该成员）。

排障口径：`adb logcat | grep -E "文件夹(发送|接收)不完整"`（Android）/ PC 日志搜 `文件夹发送不完整`、`文件夹接收不完整`，日志会直接列出缺失的文件相对路径。

### 第 5 条：落盘竞态导致「成员齐全但内容被写空/截断」（2026-09-17 修复）

v2.6 只保证「成员都在」，**不保证内容完整** —— 实测 75 个文件夹里仍有 3 个被写坏（`avresample-lav-4.dll`、`m2p.ico` = 0 字节，`jianpianer.dll` 被截断在 4 MiB），而对端照样报「75/75 全部校验通过」。

| 环节 | 问题 |
|---|---|
| 根因 | 接收端是**双缓冲 + 独立落盘线程**：写盘线程取走缓冲时把 `pending` 置空，真正的 `sink.write()` 在锁外执行；收尾 `finishRx` 却以「`pending` 为空」为写完的判据 → **在最后一块还在写时就关流**（`Stream Closed` / `write interrupted`）。小文件（整文件就是最后一块）直接落成 0 字节，大文件被截断 |
| 为什么没被发现 | `rx.digest` 算的是**网络收到的字节**，不是磁盘字节 → 落盘被截断照样 `校验=通过`；v2.6 的 `dir_missing` 核对的是「成员有没有到」，也不看大小 |
| 修复（双端同构） | ① 加 `Rx.writeBusy`：取走缓冲置真、写完置假并 `notifyAll`，`finishRx` 改为等 `pending == null && !writeBusy` 才关流（**真·写完**）；② 关流后 `diskSizeOf()` 实测磁盘字节数，与 `received` 不符即判失败并打日志（content:// 走 `fd.statSize`，量不到返回 -1 不误判） |

修复后同一文件夹（75 个文件 / 73.3 MB）重传：**逐文件字节级完全一致，接收端 0 条「落盘失败」**。
回归测试可以无人值守地跑：`pc/tools/FolderPushTest`（无界面驱动真实的 `Link.sendFolder`，用法见该文件头注释）。

## 重名文件落盘：序号一律插在扩展名**前**（1.0.6，2026-09-22）

**症状**：同名文件重传后，手机「下载/手机电脑互传」里出现 `Scene-sample.apk.1`、`285803dd4fc60acba8af4.jpeg (1)`
这类名字 —— 数字被追加在**整个名字末尾**，扩展名退到中间，系统按扩展名认类型时直接认不出：
APK 装不上、图片/视频打不开。

**根因**：安卓端原来把唯一名交给 **MediaStore 去重**（`insert(DISPLAY_NAME=原名)` 后由系统改名）。
MediaStore 的改名是往整个名字后面接 `.1` / ` (1)`，**不认扩展名**。PC 端一直是自己算名字（`LinkNames.createUniqueFile`，
序号插在扩展名前），所以只有手机侧会看到坏名字。

**修复（双端同口径）**：

| 端 | 位置 | 做法 |
|---|---|---|
| 安卓 | `util/FileIO.kt` `uniqueFileName()` | insert **之前**自己算唯一名：`名字 (1).扩展名`。判据是「**落盘位置上到底有没有一个活着的文件**」，不是台账记没记过：本进程占坑 → 坑里那条路径还在才算冲突（文件被删过 = 坑作废、用回原名）；MediaStore 有同名条目 → 条目指向的文件还在才算（文件已被外界删掉、只剩幽灵条目且条目属于本应用 → 清掉条目再用回原名）；下载目录里真有同名文件 → 冲突。**不冲突就一律不改名**，序号只在扩展名前插。应用私有回退目录跟下载目录是两个位置，不再互相挡路：真走到回退时由 `createExclusiveIn` 原子创建、就地换号。insert 后回读真实 `DISPLAY_NAME` 写进气泡文案（万一仍被系统改名，文案也不说谎）|
| PC | `LinkNames.createUniqueFile()` | 本来就是「扩展名前插序号」，本次没改行为，只把 `StoreTest` 的验证补上（并修掉它反射 `Link.createUniqueFile` 已失效的老坑）。它的判据天然就是实况：`Files.createFile` 原子创建，谁建成谁用 —— 名字被占必须是磁盘上真躺着东西；取消接收会删掉半成品（`LinkReceive.cancelRx`），名字随即空闲 |

**为什么安卓要另加一道实况判断（2026-10-14）**：原来三条判据都当真，于是这三种情况会白挨一次改名 ——
① 收完又删了同一个文件，同进程内占坑表还记着 → 第二次变成 `a (1).txt`；② 一次接收失败清掉半成品后重传，
整批成员集体加 `(1)`；③ 用户在系统文件管理器/root 里直接删文件、MediaStore 条目残留（幽灵条目）→ 名字其实空闲。
PC 端没有这三条台账，所以只有手机侧会看到无谓的 `(1)`。

- 规则细节：多段扩展名按**最后一个点**切（`备份.tar.gz` → `备份.tar (1).gz`）；点开头的名字（`.gitignore` → `.gitignore (1)`）不拆扩展名。
- 旧名字仍要能用：`util/OpenFile.kt` 继续把 `.apk.<数字>` 当 APK 处理（历史文件兜底）。

**验证**：

- PC：`java -cp build/classes com.phonepctransfer.pc.StoreTest` → 末行 `NAME PROBE PASS`（9 条断言）。
- 真机端到端：`pc/tools/FilePushTest`（无界面驱动真实 `Link.sendFile`，同一个文件连发 N 次）→
  a12 落盘实测 `命名测试.txt` + `命名测试 (1).txt`（**不是** `命名测试.txt.1`）。

## 数据流量（蜂窝）使用确认（v2.7，2026-09-17）

**用户定的规则**：不想在不知情的情况下为一次传文件/通话花掉蜂窝流量。协议层见 `docs/PROTOCOL.md` §4.9。

| 规则 | 实现口径 |
|---|---|
| **每次使用都提醒一次，不记忆** | 每次操作一个新 mid 发 `data_ask`，不缓存「这个对端同意过」。改造前是「同对端本次运行内只提醒一次」 |
| **双向都要提醒** | 判据是**两端各自的网络环境**（本机是否在运营商数据网络上），不是对端 IP 网段。发起方向提醒本机、由 `data_ask` 让对端提醒它的用户 —— 所以**接听**语音/视频/文件同样会被提醒 |
| **对端不在数据网络 → 零打扰** | 收到 `data_ask` 立即回 `ok=1`，发起方不弹任何框 |
| **被拒时的提示** | 发起方如实提示「**对方拒绝了使用数据流量接受文件 / 接受语音通话 / 接受视频通话**」（斜杠三段分别取值，两端同一套文案） |
| **文件被拒 → 消息留在聊天里** | 该条置 `DENIED`，气泡末尾一枚**红色感叹号**（矢量绘制，等价于红色 SVG 圆+白叹号），点一下重试 |
| **再次拒绝 / 同意** | 再次拒绝 → 保持 `DENIED`（与第一次完全一致）；同意 → 先把这条**移到时间线最末**（`onMessageMoved`）再 `PENDING → SENDING` 开传 |
| **接收端拒绝什么都不记录** | 只回 `data_ans{ok:0}`，不生成气泡、不写历史 —— 不留「没收成却存在于聊天里」的幽灵记录 |
| **通话也以消息记录** | 通话结束记一条 `kind=CALL`：「语音通话 00:12」；未接通记「语音通话 对方拒绝了使用数据流量」。**只记本机，不参与 hsync** |
| **其余情况与 WiFi 完全一致** | 一旦同意，不做任何限速/限功能处理 |
| **超时按「未同意」处理** | `data_ans` 等 90s；超时或发送失败都不偷偷花流量 |

**线程红线**：发起方等 `data_ans` 是**阻塞**的（本机弹框要等用户点），所以确认闸门**绝不能在主线程 /
网络读线程 / `io{}`（pp-send 单线程）里跑** —— 调用点一律专用线程（Android `Thread{}`、PC `daemon(...)`）；
`data_ans` 必须直接投进等待信箱，**不能过 `ui{}`**。

**PC 端恒在局域网** → 本机从不弹框，只发 `data_ask` 去问对端；PC 无视频采集能力，只会发 `kind=file` / `kind=voice`。

**兼容**：`hello` 新增 `dm` 能力位门控。对端 `dm=0`（老端）时两端都不发 `data_ask`，
回落到改造前口径（对端 IP 非局域网段 → 只提醒本机用户）。

## 待发 → 对端上线自动续传：界面刷新（2026-10-05 修，用户报「自动续传后界面不刷新，切一下会话才变」）

**现象**：对方不在线时发文件 → 气泡进「待发送」、聊天区底部横幅写「待发送 1 条，连接后自动补发」；
对方上线后自动续传、文件也确实传完了，但**那条横幅一直赖着不走**（前台通知副标题同理
「已连接 … · 待发 1 条」），**换一次会话才消失**。

**根因**：界面读的是 `AppCore.pendingCount`，而它原先只在「消息新增 / 状态更新 / 消息移除」
时重算；可**待发条目的出队有一半发生在链路层，且那条路径上没有任何消息级回调**——
文件发完之后，条目是等对端的 `ack` 帧才出队的（`LinkSend.onSendAck → store.removePending`），
出队那一刻界面收不到任何通知。更巧的是**发送端的终态帧（OK）比这次出队更早**：
重算时条目还在队列里 → 数字停在 1 再也回不去。而切换会话时 `reloadConversation` 会重算一次，
所以表现为「切一下才刷新」（PC 端没有这个横幅，故只在 Android 端出现）。

**修法**（数据层与协议都不动，只补一条通知通道）：

| 位置 | 改动 |
|---|---|
| `Store.writePending`（待发队列**唯一写盘点**） | 写盘后触发 `onPendingChanged`：addPending / removePending(mid) / removePending(mids) / clearPending 四条路径全部汇聚到这里，不漏也不重复 |
| `AppCore`（启动时注入，与 `store.keyResolver` 同一处） | `store.onPendingChanged = { ui { pendingCount = store.loadPending().size } }`（回调可能在 pp-send / pp-file-send 线程上 → 一律切主线程） |
| `AppCore.updateMessage` | 顺带对齐 PC 端 `ChatWindow.onMessageUpdated`（2026-09-19 治「必须再切一次会话才看得到」的那次）：**mid 在界面列表里没有气泡时按帧补建**。安卓侧的补建要三个前提全中 —— 属于当前设备（`belongsToCurrent`）、本进程见过这个 mid（`midPeer`）、帧里带实质内容（`fileName`/`text`）；另加 `removedMids` 台账，明确删掉/清空的 mid 不许被随后的进度帧复活 |

**验证**：`android$ ./gradlew --offline :app:compileDebugKotlin` ✅ `BUILD SUCCESSFUL`。
回归路径：离线发文件（横幅 = 1）→ 对端上线 → 自动续发 → 收到对端 `ack` 出队 → **横幅当场消失**
（不需要再切换会话）；顺带核对前台通知副标题同步变化。

## 内置文件浏览器：不再用系统选择器选文件（2026-10-05 新增，Android 端）

**需求**（用户原话整理）：把 HybridFileXfer 那套「文件选择页」做成自己的文件管理，**代替由系统选择文件**；
选择文件只能长按进多选、点已选中的取消；也能长按一个文件夹直接选中它（此时忽略已选文件）；
多选里没有文件夹就发送选中的文件；**只要单窗口**（页内页面，不新开窗口）。

**交互定稿**：

| 手势 | 行为 |
|---|---|
| 长按文件 | 进多选 / 切换这一条（再长按 = 取消） |
| 点击（多选态） | **只取消已选中的**；点未选中的不加入（「选择只能靠长按」） |
| 长按文件夹 | 只选这一个文件夹，并**清空已选文件**（两类互斥；再长按一次取消） |
| 点击（选择态） | 点别的 → 忽略并提示先「取消选择」 |
| 点击（非选择态） | 文件夹 → 进目录；文件 → 无动作 |
| 顶栏「↑」 | 返回上级（每层记住滚动位置，返回时恢复，借鉴参考实现的 DirPosition） |
| 底栏发送 | 有文件夹 → 整目录发；无文件夹 → 选中的文件逐个排队发（**一批只确认一次流量**） |

**读手机文件的三条路径**（`util/LocalFs.kt` 是唯一入口）：

1. **普通模式**：`java.io.File` 直读，需要「所有文件访问」（清单早已声明、设置页有入口）。
   Android 11+ 即使有它，`/Android/data`、`/Android/obb` 也读不到（系统 FUSE 限制），列表会如实报错。
2. **root 模式**（设置里手动开，默认关）：**所有枚举与读取都过 `su -c`**，浏览根 = `/data/media/0`
   （KernelSU 与 Magisk 的 su 都支持），于是连 `/Android/data` 也能读。
3. **SAF 目录树**：前两条都不通时的降级（页内给「去系统设置授权 / 选一个目录来用」两条路；
   只在这条降级路径上会出现系统授权对话框 —— SAF 的硬性要求）。

**改动**：

| 文件 | 改动 |
|---|---|
| `util/LocalFs.kt`（新） | 三种后端的目录枚举 + root 探测/读取；`su -c` 一条 shell 命令跑完一个目录（避免每条目一次 fork）；`su -c cat` 的流在 close 时销毁子进程；**枚举失败一律返回 error，绝不冒充空目录** |
| `ui/FileBrowser.kt`（新） | 全屏**页内**浏览器：顶栏（返回上级 / 当前路径 / 刷新 / 关闭）、目录列表（目录在前）、底栏（选择摘要 + 发送）、未授权时的引导页 |
| `util/FileIO.kt` | `scanTree(uri)` 按 scheme 分流；`listTreePath(path)`（本机路径递归，`skipped`/`emptyDirs` 语义与 SAF 版逐条一致）；`openSource(uri)`（`file://` 先本地读、失败回落 `su cat`）；`querySize` 对 root 路径回落 `su stat`；`listTree` 起点改 `getDocumentId`（树 / 树内子文档两种 uri 都认） |
| `net/LinkSend.kt` | `openSource` 改调 `FileIO.openSource`（多一条 root 回落，行为不变） |
| `net/Link.kt`、`net/LinkSync.kt` | 三处文件夹扫描（主动发送 / 气泡重试 / 待发补发）统一改走 `FileIO.scanTree` |
| `AppCore.kt` | 启动注入 `LocalFs.rootEnabled = prefs.rootFs`；新增 `sendFiles(uris)`（一批只弹一次流量确认，然后逐个 `sendFileInternal(preGranted = true)` 进串行队列） |
| `ui/ChatScreen.kt` | 「+」菜单两项都开内置浏览器；浏览器挂在最外层（覆盖聊天界面，**同一个 Activity 内换页**） |
| `MainActivity.kt` | 删除 `OpenDocument` / `OpenDocumentTree` 两个系统选择器 |
| `data/Prefs.kt`、`ui/SettingsSheet.kt` | 新增 `rootFs` 开关（默认关）+「测试 root 权限」按钮 |
| `ui/Icons.kt` | 新增矢量 `Glyph.ARROW_UP`（返回上级；全仓禁 emoji 的延续） |

**待发/重启语义**：内置浏览器选出来的文件是 `file://` 绝对路径，比 SAF 更省事 —— 待发队列重启后
照样能重新枚举与发送，不需要持久化授权（root 模式下要求开关保持开启，否则补发会如实报「打不开源文件」）。

**验证**：`android$ ./gradlew --offline :app:compileDebugKotlin` ✅、`:app:assembleDebug` ✅；
真机回归（授权路径、长按多选、文件夹互斥、批量发送、root 开关）出用户清单，见本轮汇报。

### 配色：随主题自动切换 + 高对比度整改（2026-10-05 补，用户要求「需要高对比度」）

**随主题**：`FileBrowser.kt` **零硬编码颜色**（全文唯一颜色字面量是 `Color.Transparent`，用在未选中行的
透明底上），取色全部来自 `MaterialTheme.colorScheme`（`background` / `surfaceVariant` /
`onSurfaceVariant` / `primaryContainer` / `primary` / `error` / `outline`），行主文本继承
`Surface` 的 `contentColorFor`，矢量图标用 `LocalContentColor`。它挂在 `PPTheme` 之内
（`ChatScreen` → `MainActivity`，`dark` 在**根作用域**读入以保证当帧刷新），
因此**跟随系统 / 浅色 / 深色**三态与界面里的主题按钮都立即生效。

**对比度实测**（WCAG 2.1，浅色/深色各算）：

| 类别 | 结果 |
|---|---|
| 文字（要求 4.5:1） | 页面正文 15.11/15.20、行次文本 15.09/16.37、顶栏文本 14.52/14.80、选中行 13.34/9.47、错误 5.78/11.54、按钮 5.34/7.15 —— **全部达标** |
| 选中行图标（图形，要求 3:1） | 4.17 / 6.67 —— 达标 |
| **结构**（要求 3:1） | 顶/底栏底色 vs 页面底 **1.04 / 1.11**、选中行底色 vs 页面底 **1.13 / 1.61** —— **不达标** |

> 只被本文件覆盖的槽位取 `Theme.kt` 的值；其余槽位是 Material3 的基线默认值，**不用记忆、直接从
> 实际解析到的依赖里读**（本项目 = material3 **1.4.0**）：`javap` 反编译
> `material3.aar` 里 `tokens/PaletteTokens` 的 `<clinit>` 得到 `onSurface/onBackground` = Neutral10
> `#1D1B20`（深色 Neutral90 `#E6E0E9`）、`outline` = NeutralVariant50 `#79747E`（深色
> NeutralVariant60 `#938F99`）、`outlineVariant` = NeutralVariant80 `#CAC4D0`（深色
> NeutralVariant30 `#48464C`）、`onPrimary` 深色 = Primary20 `#381E72`。上表就是按这些实测值算的。

**整改**（只动 `ui/FileBrowser.kt`，**不改** 2026-10-01 定稿的全局配色盘，避免整个应用变样）：

1. 选中行左侧 **3dp `primary` 竖条**（`EntryRow` 的 `drawBehind`）：对页面底 浅 **4.72:1** /
   深 **10.71:1**。用 `drawBehind` 而非铺一个 `Box(width = 3.dp).fillMaxHeight()` —— LazyColumn 给
   item 的高度约束是 unbounded，`fillMaxHeight` 会退化成 0 高（除非再套一层 `IntrinsicSize.Min`）。
   选中态因此有**三条通道**：图形换成对勾（形状）、图标染 `primary`（颜色）、左侧竖条（位置）。
2. 顶栏下缘 / 底栏上缘各加 **1dp 分隔线**，颜色**显式**取 `colorScheme.outline`（对栏底
   浅 **3.88:1** / 深 **5.62:1**）。**不能**用 `HorizontalDivider()` 的默认色 `outlineVariant`
   （它对栏底只有 **1.45:1**（浅）/ **1.91:1**（深），等于没画；`DeviceList`/`SettingsSheet` 用默认色是
   **列表内部**分隔，语境不同）。分隔线放在 `statusBars` 内边距**之下**、`navigationBars` 内边距
   **之上** = 栏的视觉边缘。

**验证**：`android$ ./gradlew --offline :app:compileDebugKotlin` ✅、`:app:assembleDebug` ✅。

### Android 17：整盘访问被系统关闭（2026-10-05 补，用户真机实测）

**现象**（用户原话）：「安卓17设备给了权限，但是系统提醒只能使用自带文件选择工具进行文件选择」；
补充口径：「当前是会自动打开系统文件选择功能，主要是顺序问题，如果是安卓17，就提醒只能使用系统自带
文件选择功能或使用root权限访问」。

**结论：不是权限没给，也不是 API 被删 —— 是 Android 17 在运行期收紧了「所有文件访问」。**
本机 SDK（`platforms/android-37.0`，`Platform.Version=17`）逐项核对：`MANAGE_EXTERNAL_STORAGE` 常量、
`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`、`Environment.isExternalStorageManager()`
**都还在**（`data/api-versions.xml` 里 `since="30"`、无 deprecated/removed 标记）→ 声明、跳转、查询都不报错，
**但授予之后系统仍不放行整盘读取**。（沙箱当时无外网，官方条款原文引用不了；结论来自本地 SDK 证据 + 真机实测。）

⇒ Android 17 上**只有两条路走得通**：**系统自带的文件选择器（SAF 目录树）** 与 **root**。

| 文件 | 改动 |
|---|---|
| `util/LocalFs.kt` | 新增 `isAndroid17Plus()`（API 37 判定 + 为什么单列一条的注释）；`listPath` 的「系统拒绝读取」文案按版本分流：17 上改为指路「系统自带的文件选择器 / root」，**不再**提「去设置页授权」 |
| `ui/FileBrowser.kt` | 新引导态 `Guide.ONLY_SYSTEM_PICKER`（17 专用）；`initLoc()` 顺序调整；系统选择器授权结果持久化；顶栏树模式加「换目录」；授权失效自愈；`ROOT_FAILED` 页在 17 上不再劝「改回所有文件访问」 |
| `data/Prefs.kt` | 记住上次授权的目录树：`safTreeUri` / `safTreeDocId` / `safTreeLabel` + `clearSafTree()` |

**引导顺序（本轮核心）**——`initLoc()` 现在是：
`root 模式（先探 su）` → `「所有文件访问」` → **`上次用系统选择器授权的目录`** → `引导页`。

**两个引导页的分工**：

- `ONLY_SYSTEM_PICKER`（**Android 17+**）：标题「Android 17：只能用系统选择器或 root」，正文按用户口径提醒
  「系统不再让应用整盘浏览文件…只有两条路：① 用系统自带的文件选择器授权一个目录 ② 开启 root 模式」；
  按钮竖排＝①「用系统选择器选目录」（主）②「开启 root 模式」（次，就地开 + 立刻探 su，不通就切到 ROOT_FAILED）。
  **故意不给**「去系统设置授权」按钮 —— 那条路用户已经被系统顶回来过一次，再放就是死路。
- `NO_ALL_FILES`（Android 11~16）：维持原样（先去设置页授权 / 或选一个目录来用）——
  **老系统的行为与文案一字未改**。

**错误页也要留出口**：Android 17 上「权限给了却读不到」时，浏览器会落到**错误页**——那里除了「重试」
再多一个「用系统选择器选目录」（否则用户会卡死在这一页）；同时 `initLoc()` 在 17 上**优先复用已授权的目录**
（`savedTreeLoc()`），避免每次打开都先撞一次"假授权"。

**授权目录持久化**：系统选择器授权成功后把授权 uri / docId / 显示名记进 Prefs，再打开浏览器**直接进那个目录**
（顶栏显示「系统选择器授权目录」并多一个「换目录」）；权限由 `takePersistableUriPermission` 长期持有。
万一被系统回收或目录被删：枚举**如实报错** → 清掉记录 → 回引导页 + toast「上次授权的目录已失效，请重新选一个」
（沿用铁律：失败绝不冒充空目录）。顺带一个好处：SAF 树内选出的文件/文件夹是 `content://`，
授权持久化之后**待发队列重启后也能重读**（树授权覆盖其子文档）。

**验证**：`android$ ./gradlew --offline :app:compileDebugKotlin` ✅、`:app:assembleDebug` ✅；
真机核对清单见本轮汇报。

## 「复制链接」：本应用也能出现在「用浏览器打开」的选择框里（2026-10-05 新增，Android 端）

**需求**（用户原话）：加一个「模拟自己是浏览器」的功能，别的应用调用浏览器打开链接时能选到本应用，
名字叫**复制链接**；选中后把链接复制到剪贴板、给一句提示，然后自动关闭。

**机制**：系统「用浏览器打开」发的是 `ACTION_VIEW`（data = http(s) 链接 + `CATEGORY_BROWSABLE`），
任何声明了 `VIEW + DEFAULT + BROWSABLE + http/https` 的 Activity 都会进候选；
**候选里显示的名字取自该 Activity 的 `android:label`** —— 于是新 Activity 直接写「复制链接」即可
（与真浏览器的注册方式完全一致，只是我们不渲染网页）。

**为什么是独立 Activity**：主界面 `MainActivity` 是 `singleTask` 的聊天窗口，把 VIEW 过滤器挂上去
会把**整个聊天界面**顶到前台；这里用一个**透明主题、无布局**的 `CopyLinkActivity`，`onCreate`
复制完立即 `finish()`，用户看不到任何界面。manifest 里另配 `taskAffinity="" + singleTask`
（**不并入主界面任务**，否则点链接会把后台的聊天界面一起带上来）、`excludeFromRecents`、`noHistory`。

**行为**：走 `AppCore.copyTextToClipboard(text, hint)` —— 与「长按消息 → 复制文本」是同一条路径：
写本机剪贴板；剪贴板同步开着且电脑已连接时**顺手把这个链接推给电脑**；提示一句
「已复制链接：<前 120 字>」；随后 Activity 结束（系统选择框在选中那一刻本来就关了）。
`AppCore.init` 幂等：应用已在跑时几乎零成本，没在跑则顺带把应用链路叫起来
（用户已确认要「链接也能同步到电脑」这个行为）。

| 文件 | 改动 |
|---|---|
| `CopyLinkActivity.kt`（新） | 取链接（先 `intent.data`、再退 `EXTRA_TEXT`）→ 复制 → 提示 → `finish()`；AppCore 起不来时退化为纯本地剪贴板复制 |
| `AndroidManifest.xml` | 注册该 Activity（`label=@string/copy_link_label`、`exported`、`singleTask`、`taskAffinity=""`、`excludeFromRecents`、`noHistory`、透明主题）+ `VIEW/BROWSABLE/http/https` 过滤器 |
| `res/values/themes.xml` | 新增 `Theme.PhonePcTransfer.CopyLink`（全透明、无标题、无转场动画；父级用框架的 Translucent 主题，不引 AppCompat） |
| `res/values/strings.xml` | 新增 `copy_link_label = 复制链接`（选择框里显示的就是它） |
| `AppCore.kt` | `copyTextToClipboard(text, hint = "已复制文本")` 多一个可选提示参数（既有调用方表现不变）；日志文案由「复制消息文本」改成中性的「复制文本」 |

**与「分享」入口的区别**：分享（SEND / SEND_MULTIPLE）是「把文件/文字发给某台设备」，会在界面上弹
「发送到哪台设备」；本入口是 `VIEW`，只做「复制链接」，不弹任何界面。

**验证**：`android$ ./gradlew --offline :app:assembleDebug` ✅；合并后的 manifest 中已确认
`CopyLinkActivity` 带着 `VIEW/BROWSABLE/http/https` 与 `taskAffinity=""` 等属性
（见 `build/intermediates/merged_manifest/debug/.../AndroidManifest.xml`）。真机回归清单见本轮汇报。

## 连接与后台策略（谁能连、什么时候能连）

**前台**：点设备即连（一次尝试，失败不自动重试，要重试就再点一次）。

**一台设备多个地址（2026-09-19）**：同一台设备（`deviceId` 相同 = 同一组）在列表里可能有多个地址（WiFi / 热点 / 换网段）。点它时按「**点的那个地址 → 同设备其它地址（最近在线优先）**」**串行逐个试**：

- 某个地址连上并握手成功 → **立刻停**，同设备剩下的地址一律不再试；
- TCP 通但没握上（地址被别的主机占了 / 对端没响应）→ 关掉换下一个；
- **全部失败 → 队列清空、不再重试**，直到对端被动连过来或用户再点一次设备（与「点一次、试一次」一致）；
- 试的过程中若被动连上（或用户又点了别的设备）→ 立刻让位，剩余候选作废。

连上的是同设备的另一个地址时，两端都会把「当前对话地址」跟到真正连通的那个地址（顶栏 / 地址菜单回显才不会停在不通的地址上）。

**保活长连接（2026-09-16 引入，2026-10-01 定节奏）**：连接成功过的会话断开后（对端 35s 判死、网络抖动、通话/视频/传文件结束后链路异常），由**当初主动发起成功的一方**自动重连：断开后**等 5s/10s/30s 各重试一次**，仍不通**转纯被动**（不再主动敲，等对端连入或用户再点一次设备）。对端发起的入向会话不主动重连——本方向可能被对方防火墙挡住（单向可达场景）由可达的一端负责保持长连接。**例外**：阶梯走完时本机若正在传文件 / 通话（语音或视频），不降级，按每 30s 继续重试直到恢复或忙完。

**文件发送是排队串行的（2026-09-21 用户定调）**：一次只传一个文件 / 文件夹，前一个完成**立刻**进入下一个（`fileSendExec` **单线程队列**，PC 与 Android 同构）。批量发几十个文件时不再同时开出几十条发送线程互相抢 socket 写锁。

- 进队列的只有**顶层发送入口**：用户发送、气泡重试、待发补发、hsync 补图；**文件夹成员在队列线程内顺序发**，一个文件夹 = 一个排队任务（不能再各自入队，否则一个目录被拆成 N 个任务）。
- **通话与消息不受排队影响**：音频 / 视频帧由采集、编码线程直接发，消息 / ack 走 `sendOrDrop` —— 都不进这条队列，只按帧粒度抢同一个 socket 的写锁，所以文件排队再长也不会让语音 / 视频 / 消息「排在文件后面」。
- 排队中的气泡停在 `PENDING`（等待中），真正开传才变 `SENDING` + 进度；日志会打 `已排队（前面还有 N 个在等）`。

**本地源不可读 ≠ 网络故障（2026-09-21 修）**：源文件打不开、读一半失败（SAF / 第三方 FileProvider 授权失效、文件被删）一律抛 `SourceError`（Kotlin）/ `SourceException`（Java），**绝不关会话**，并把该条**移出待发队列**、气泡标失败（FAILED）。

> 老逻辑把它当网络故障：关会话 → 双方保活重连 → 握手补发同一条 → 又打不开 → 每 5s 一轮。批量发几十个文件时只要有一个 `content://` 失效，整批都会被拖进「不停断开重连」的死循环（2026-09-21 真机日志实测：手机端 `打不开源文件: content://bin.mt.plus.fp/...` 与会话结束交替出现）。

**「连上就断」防护（2026-09-21 引入，2026-10-01 简化）**：旧的「连续 3 次快速失败 → 15s→60s 退避」已随保活节奏改版取消——现在主动重试最多 **5s/10s/30s 共三次**，打完仍不通即转纯被动，本身就封死了「不停断开重连」刷屏的上限。

**后台**（应用不可见 / 熄屏）—— 2026-09-15 用户定调，替换掉旧的「后台一律拒绝新建连接」：

| 对端 | 能否建立连接 |
|---|---|
| **已知设备** = 与本机**连接过**（成功握手）**且**有过**消息或文件**往来 | ✅ 允许（自动重连、进程被系统重启后的回连都走这条） |
| 其它设备 | ❌ 拒绝，日志 `后台建连被拒：<ip> 不在已知设备名单` |

- 名单落盘在 `filesDir/trusted_peers.json`；应用「设置」里有 **后台可连接的设备** 一栏可查看与清空。
- **清空聊天记录不会清掉这份名单** —— 「这台设备是不是自己人」与聊天内容**解耦**
  （`Store.clearHistory()` 只删 `history/<组键>.jsonl` 里的条目，`TrustedPeers` 独立一份存储）。
- **入向同规则（2026-10-01 用户定调）**：后台**不接受陌生设备主动连入**，日志 `后台拒接陌生设备入向连接：<ip>`；已知设备不受限（PC 推文件、对端保活重连都属这一类，前台不受限）。
- 回到前台会自愈一次：选定的设备若没有健康会话（被系统掐断过），自动重扫并连回同一台。

## 局域网发现与扫描（2026-09-21）

设备发现走 UDP 28766：**启动时广播一次** beacon，之后纯被动接收（谁广播就把谁写进设备列表）。
**主动扫描**只在启动时（Android 还有「回前台自愈」时）跑一次，窗口 3s，窗口内**两条路径合并成一次结果**：

| 路径 | 发到哪 | 为什么两条都要 |
|---|---|---|
| ① 广播询问 | 各网卡广播地址 + `255.255.255.255` | 一次发包覆盖整个广播域（老路径） |
| ② **网段扫描** | 本机地址所在 **/24** 的 `.1`~`.254`，逐地址**单播**同一个询问帧 | 广播在 AP 隔离 / 交换机 IGMP 抑制 / 大子网（如 /16）里常到不了对端；单播直接命中 |

- **只扫最后一段**：本机 `10.1.2.3` → 只扫 `10.1.2.1`~`10.1.2.254`（跳过自己；`.0` 网络号与 `.255` 广播不发）。掩码只影响广播地址 —— 就算子网是 `/16`，也只扫自己那 `/24`。
- **两道闸门**（不满足就跳过，并在界面与日志如实说明）：
  1. **Android 端：本机没连 WiFi 就不扫**（热点 / 有线以太网同样算「有局域网」）。手机走运营商数据网时自身地址常常就是 `10.x`（CGNAT 内网地址），照扫会白打 254 个运营商侧地址。
  2. 本机 IPv4 必须落在私网段（`10/8`、`172.16/12`、`192.168/16`）；公网地址、`169.254/16`、IPv6 一律不扫 —— 逐个扫 254 个不属于自己的地址既扫不到对端，也容易被对端网络当成端口扫描。
- **发包节流 + 补扫**：每 16 个地址歇 8ms（瞬时打满发送队列 + ARP 未解析队列会丢包）；窗口过半时对**还没回应的地址**补扫一轮。扫描期间不整段持发送锁，对端询问的回应路径不会被卡住。
- **协议零改动**：网段扫描用的就是原有的 `q:1` 询问帧，对端逻辑完全不变 ⇒ **老版本对端照样能被扫到**。
- 状态文案：`正在扫描 10.1.2.1-254…` / `发现 N 台设备` / `无新设备` / `WiFi 未连接，已跳过扫描` / `本机不在局域网私有网段（x），已跳过扫描`（Android 显示在设备列表顶部，PC 显示在顶栏副标题）。
- 判据实现：两端同一个口径 `Discovery.subnetPrefixOf(ip)`（PC `pc/src/.../Discovery.java`、Android `net/Discovery.kt`），PC 侧有无界面自检 `pc/build/probe/SubnetCheck.java`（20 个用例，末行 `SUBNET CHECK PASS`）。

## 界面（Android 与 PC 同构，2026-09-15 改版）

两边布局一致：**左侧设备列表 + 右侧聊天**，顶部「左备注 / 中 地址▼」一行 + 连接状态一行。

顶栏（2026-09-16 定版）：

| 位置 | 内容 |
|---|---|
| 左 | 当前对话设备的**备注**（只认备注，没备注就留白）。**Android 端左侧设备列表展开时不显示** —— 列表里本来就列着备注，顶栏再写一遍是重复 |
| 中 | **当前连接的地址 + 紧跟其后的 ▼**（一个紧凑整体）：点它展开该设备的全部地址，当前选中的那条**高亮**，点一条即切换（见「设备身份与分组」）。单地址设备也显示 ▼（随时能确认「现在连的是谁」） |
| 右 | 留白（左区与右区**等权重**，中段那个整体才是真居中） |
| 第 2 行 | 连接状态（`● 已连接` / `○ 正在连接…` / `○ 未连接（点设备连接）` / `○ 未运行`），已连接时为绿色，居中 |

**IPv6 中间省略**（2026-09-16 用户要求「地址中间一部分不显示，不然太长了」）：
顶栏中段只给 20 字符的预算（`IP_HEADER_BUDGET`，PC 端按像素给 ≈130px），超出即走
`middleShorten()` —— 保留头 10 + 尾 9、中间换 `…`：`2001:db8:614:…:58c4:e458` → **`2001:db8:…54:58c4:e458`**。
头段认网段前缀、尾段认接口 ID，中间那截纯填充的十六进制没有信息量。IPv4（12 字符）永不触发；
**完整地址**仍在节点的 `contentDescription` 与展开的地址列表里逐条可见。设备列表里的地址行用同一套策略。

> 居中口径（2026-09-16 **三改**定稿，反复过三轮，值得留着当教训）：
> **左区与右区都用 `weight(1f)`**（不用「左侧固定 + 右侧补等宽」——`TextButton` 还有最小触摸尺寸，
> 写死的补偿值随 density / 系统字号失配）。三轮的差异只在**箭头放哪、量谁**：
> ① 箭头跟 IP 一组居中 → 量出来 IP 文字中心比屏幕中心偏左 29px，用户报「a16 显示是歪的」；
> ② 把箭头甩到行的最右端、中段只留 IP → IP 文字数学上正中（719/720）但用户仍报「不居中」，
> 因为「IP+▼」这个整体被拆开后，右端孤零零一个箭头反而更扎眼；
> ③ **按用户口径把 ▼ 收回 IP 后面、左右各 `weight(1f)`** → 居中的是「地址 ▼」这个看得见的整体。
> 真机实测：a16（全宽 1440）中段 534..898 中心 **716** / 屏幕中心 720；
> a12（列表展开，聊天列 = 1080−138dp 侧栏）中心 **729** / 聊天列中心 729.75；
> PC（920 宽窗口，聊天列 = 920−200 左栏）中心 **560** / 聊天列中心 559.5 ✅
>
> 教训（可复用）：判「居中」要先定**量的是哪个墨迹块**——用户看的是「地址 ▼」这一整块，
> 不是里头的 IP 文字，也不是被拆散的箭头。三次反复都出在「量错了对象」上。
>
> PC 端另有一个坑：`▼`（U+25BC）在 Windows 默认 Dialog 字体下**没有合适字形**，
> 直接写会渲染成贴基线的小逗号状残迹。PC 端改用 `DownTriangle`（Graphics2D 画 11×6 实心三角）
> 作为按钮图标；安卓端系统字体有完整字形，照常写 `▼` 即可。

底部输入区（Android，2026-09-16 定版：**只有一行**）：

| 位置 | 内容 |
|---|---|
| 输入框 | **向左压缩**（只吃左侧余量 `weight(1f)`），占位文案「输入内容」（文案必须短：侧栏展开时 a16 只剩约 125dp 文本宽，长文案会折成 3 行把输入框顶高） |
| 发送按钮 | 紧跟在输入框后面（8dp 间隙）：绿底 `0xFF2E7D32`、**小圆角 8dp**、白字、高 44dp、宽≈64dp（宽度不写死，只给左右内边距 18dp，大字体不裁字）；与输入框**底对齐**（输入框最多 4 行会长高，居中会让按钮飘在中间） |
| 输入框内「+」 | 原地展开四项菜单：**发送文件 / 发送文件夹 / 语音通话 / 视频通话**（「通话」「视频」两个按钮已从底栏撤走，收进这里；「视频通话」仍先弹画质选择框） |

> 真机实测：a12（density 440）绿钮 178×121px = 64.7×44dp；a16（density 640 = **4.0x**）
> 258×176px = 64.5×44dp ✅（注意 a16 是 640dpi = 4.0 倍，不是 3.5 倍，量图时会差 12%）


设备列表栏顶部一行：

| 位置 | 内容 |
|---|---|
| 左 | **「清空」**（主按钮）— 清空**当前对话设备**的聊天 + 文件记录（带二次确认） |
| 右 | **「设置」**（次要小字）— 打开设置页；设置页仍**独立成页**，端口 / 剪贴板 / 后台保活 / 保存位置 / 已知设备名单 / 日志 / 清空 各项自上而下排列 |

> **PC 端（2026-09-21 用户定调；2026-09-23 二次调整）**：「清空」在**聊天界面右上角**
> （顶栏 `SIDE_SLOT` 右段，EAST 对齐）—— 它清的是「当前对话设备」的记录，贴着聊天区才顺手。
> 「设置」入口 2026-09-23 起**从左栏顶部挪到左栏底部**：底部一行 = **左边「接收目录」路径 + 右边「设置」按钮**。
> 左栏同轮瘦身：删掉「单击设备 = 切到它并连接 / 右键改备注/IP · 断线后点一下重连」
> 两行小字（在线数 / 候选 IP / 剪贴板缓冲全部撤掉）；
> 聊天区底部那行「也可以直接把文件 / 文件夹拖进窗口…」删掉；「N 台在线」不再出现在任何界面上。
>
> **主题切换（2026-09-27 新增；2026-09-28 扩展）**：左栏底部**最左**一枚「深色 / 浅色」按钮（`ChatDevices.toggleTheme`），
> 点击即切换并写入 `config.json` 的 `darkTheme`（下次启动沿用）。深色 = 背景纯黑 + 气泡灰 + 字白，
> **弹菜单 / 托盘菜单 / 按钮均为「跟主题」，深色 = 黑底白字**
> （2026-09-27 的「按钮之类的颜色不用改」口径**已作废**）。弹菜单外框色 `MENU_BORDER`
> **独立于 `DIVIDER`**（顶/底栏分割线色，不可合并）：浅色 #C8CDD4 / 深色 #4A4E54，
> 保证与相邻底色亮度差 ≥45（旧值 DIVIDER 复用时差仅 19/44，就是「边框太浅 / 看不见」的根因）。
> 机制：`Ui` 调色板为可切换的动态字段 + `applyDark(d, root)` 对已建组件树做「旧色→新色」重着色
> （含 CompoundBorder 里嵌的分割线），自定义 paint（设备行 / 圆角气泡 / 窗口底）一律 paint 时现取 `Ui.XXX`；
> 托盘菜单这类**长期存活、不在组件树里**的弹菜单靠 `Ui.addThemeListener` 监听刷新
> （`PcMain.Tray.refreshTheme`），运行期切主题后重刷，不会再停在启动时那套色。

「清空」的边界（两端**同一套语义**，PC 端 `ChatWindow.clearCurrentConversation()` / Android 端 `AppCore.clearHistory()`）：

- **逐条删除，不是整删文件**（2026-09-21 定调，见「界面改版第八/九轮」）—— 枚举本会话全部 mid
  （历史全量 + 待发），按 mid 删；其中**文本 + 图片**按协议 §4.7 发 `msg_del` 通知在线对端一起删。
  整删历史文件只清了本机 → 对端一连上 hsync 就把它们补回来（用户看到的就是「清空没用」）
- 只清**这一台** —— 别的设备的历史完全不动（Android 没选设备时点设置页入口 = 清**全部设备**，逐台走同一条路径）
- 只清**记录** —— 已经传完的文件落在接收目录里，不会被删；未发出的待发条目一并移除
- **不动「已知设备」名单** —— 「谁能后台连我」的判定与聊天记录解耦
- **有传输在途时拒绝执行**（枚举出来的 mid 里就有正在传的那条）
- 对端不在线时**如实说明**：确认框与结果提示都会写「本次只清本机，对方上线后可能被同步回来」
- PC 是**没选设备就拒绝**（弹窗提示先选设备）；Android 的设置页入口没选设备 = 清全部设备

两端差异：

| | Android | PC |
|---|---|---|
| 设备列表 | 可收起（手势 / 三横），左栏 138dp | **常驻展开**，不做收起（200px） |
| 设备列表顶部 | 「清空」+「设置」 | **无按钮**（「设置」2026-09-23 挪到左栏底部；**「清空」在聊天区右上角**，2026-09-21） |
| 设备行 | **一行 = 一台设备**（不是一行一个 IP）；副标题显示当前地址，多地址时追加「N 个地址」 | 同左 |
| 操作设备 | 长按某行 → 编辑（备注 + 地址列表） | 右键某行 → 编辑 / 切换地址 / 删除 |
| 「本机ip：」块 | 顶栏按钮行下面（原「○ 未连接…」那行的位置） | 同位置（左栏按钮行下面） |
| 手动剪贴板 | 在设置页（「立即推送本机剪贴板」） | 在设置页的「剪贴板」分区（拉取 / 推送两个按钮） |
| 左栏底部 | 扫描结果（发现 N 台设备 / 无新设备） | **左「接收目录」路径 + 右「设置」按钮**（2026-09-23 起；路径最多两行，仍放不下走中间省略，完整路径在提示里） |
| 在线数 | 不出现在界面 | **不出现在界面**（含顶栏第 2 行与左栏；2026-09-21） |

### 设备身份与分组（2026-09-16）

**这是本轮改动的核心**：列表、备注、聊天记录、待发、旋转角全部从「按 IP」改成「按设备」。

| 概念 | 规则 |
|---|---|
| 设备 id | **首次启动应用时随机生成**并落盘，卸载前不变（Android `android-xxxxxxxx` / PC `pc-xxxxxxxx`）；没有就自动生成 |
| 绑定 | 每次握手 / 收到 UDP 广播时，对端都会自报一次自己的 id → 本机把它绑到**当前这个来源 IP** 上（`ip → deviceId`），用户不用做任何操作 |
| 分组 | 同一个 id 绑到多个 IP ⇒ 这些 IP 是**同一台设备的多个地址**（WiFi / 热点 / USB 网卡…），自动归成一组；**未绑定过的地址**回退按 IP 当组键（= 改造前行为） |
| 一行 = 一台设备 | 设备列表不再出现「同一台机器好几行」；聊天记录 / 待发 / 备注都按组共用一份 |
| 切换当前地址 | 聊天顶栏**中段**的 `当前地址 ▼`（PC 同位置也有）：点它列出该设备全部地址（●当前高亮 / ○其它 / 已连接），点一条即切换并尝试连一次 |
| 同组回落 | 切到某个地址后若它连不上、而同设备的另一个地址已有会话，收发与通话仍走那条可达的会话 —— 不会因为「点错地址」就发不出去 |
| 备注 | 备注 = **对「对方设备 id」的备注**；同设备的任何地址读到的是同一个备注 |
| 备注仅自己可见 | 备注只存在本机，**绝不出现在任何出网字段**（`hello.name` / `beacon.name` / `call_invite.name` 一律用本机 `deviceName`）；通话界面显示的对端名字优先本机备注，没备注才用对端自报名 |
| **新地址自动并入** | 手工加的地址（或刚连上、还没握手的行）一旦握手时对端自报的 id **本机早就认识** → 该地址自动并进那台设备（不再单独成行），**并自动重新打开这台设备的聊天界面**（历史 / 待发 / 旋转角换成设备那一份），避免「两台各有一半记录」的分裂 |
| 并入时带上本机状态 | 并入时把旧「地址组」上的备注 / 当前地址 / 画面旋转角搬到目标设备（目标已有值则不覆盖），用户之前写过的备注不会凭空消失 |
| **启动清理陈旧地址** | 每次启动应用清一次：**只有 3 个地址以上的设备**才可能被清（两条及以下整体跳过），删掉 **5 天没用过**的地址，且**保底留最近使用的两条 + 当前使用的那条**；删地址同时解除它的身份绑定（避免之后手动加回同一地址时又并进老设备） |
| 旋转角记忆 | 每台设备分别记「对方画面 / 本地小窗」两个角度（PC 端只有「对方画面」），切换设备时各自装回，只要不卸载客户端就一直沿用（旧版的全局角只在首次升级时播种给上次选中的那台设备） |

手动把两个地址并成一台设备：编辑设备 → 地址列表里「＋添加地址」（写入 `ip → 该设备 id` 绑定）。
反过来「✕ 移除地址」只影响本机列表，不影响对端；同时**解除该地址与设备的绑定** ——
这样以后再把同一个地址加回来时，它是一台**新设备**，不会被并回老那台的聊天记录里
（想并回去就照上面那样重新「＋添加地址」）。

#### 修复：串台与分组失效（2026-09-21，用户报「不同设备的聊天窗口混在一起」）

两个独立缺陷，表现都是「设备之间没分开」：

| # | 缺陷 | 根因 | 修法 |
|---|---|---|---|
| 1 | **别的设备的往来画进了当前会话** | 界面归属判据写的是「peerIp **非空**且不是本设备 → 丢」，于是**空 peerIp 一律放行**；而 `LinkReceive` 里「普通文件接收的第一条气泡」恰好漏了 `peerIp`，任何一台设备发来文件都会弹在当前打开的会话里（进度/失败/完成帧同理） | ① `LinkReceive` file_meta 气泡补 `s.remoteIp()`；② 进度/失败/校验帧全带上归属（`rx.peerIp` / `dir.peerIp` / `item.peerIp`）；③ `PcMain` 送界面之前先用 `peerOf(mid)` 反查一次归属；④ `ChatWindow` 把**归属不明的消息直接丢弃**（宁可少显示，绝不串台），判据从「不是别人的就画」改成「必须是本设备的才画」 |
| 2 | **同一台设备的多个地址被拆成多台**（列表里一台设备出现好几行，点不同行进不同会话） | `Store.load()` 里 `seedGroupKeys()`（用 `peer.lastId` 播种 `ip → deviceId` 缓存）被写在**填 peerList 之前**，那时表还是空的 → `ckeys` 恒空 → `convKeyOf()` 全部退回 IP，config 里的 `bindings` 形同废纸（`bindDevice` 见绑定没变就 early-return，连补都不补） | ① 播种挪到 peerList 填好**之后**；② `bindDevice` **无条件**维护 `ckeys`（与 `bindings` 保持同步），组键真的动了就把备注/当前地址/旋转角搬过去 |

回归工具：`pc/tools/ChatSepProbe.java`（无 GUI、无 socket；把真实 `pc/` 的配置与历史复制到临时目录再读）
—— 三查：数据层每组历史两两无交集、界面层每组气泡数 = 本组历史、串台探针（本设备进 / 别的设备不进 / 空 peerIp 不进）。
期望末行 `CHAT SEP PROBE PASS`。修复后真实配置的分组从 7 组收敛到 **5 组**（Android 16 机型、PC-B 各并掉一个重复地址）。

⚠️ 遗留（同类隐患，安卓端目前没有触发路径）：安卓 `AppCore.belongsToCurrent` 也是「空 peerIp 放行」；
安卓接收侧气泡都带了 `peerIp`（`LinkReceive.kt` 的 `r.peerIp` / `dir.peerIp` / `rx.peerIp`），所以还没暴雷。
要对齐成「空即丢弃」说一声。

### 通话界面的按钮（2026-09-16 调整）

| 位置 | 按钮 | 说明 |
|---|---|---|
| 通话中顶栏横幅 | **「切换声音」** | 在**媒体通道 ⇄ 电话通道**之间切换（旧文案是当前通道名「媒体 / 电话」，看着像状态标签而不像按钮，已改） |
| 上一条右侧 | **「免提 / 听筒」** | 只在电话通道出现（媒体通道的输出跟随系统路由） |
| 最右 | **红色圆形挂断/取消**（电话话筒图标） | —— |
| 视频全屏上排 | **「切换声音」**胶囊 + 切换摄像头 + 旋转对方画面 + 旋转小窗 | 胶囊与右侧圆钮之间的间距**特意拉开**（原来「媒体」圆钮紧贴挂断钮，窄屏上几乎贴成一片） |

按钮尺寸：呼叫横幅里的「切换声音」= 76×34dp 胶囊，与右侧 36dp 圆钮间距 16dp；
视频全屏里的同款 = 76×56dp 胶囊，与右侧圆钮间距 28dp。

PC 端（视频通话窗口，2026-09-16）：

| 位置 | 控件 | 说明 |
|---|---|---|
| 画面**下中** | **红色圆形挂断**（直径 60） | 与安卓端同款；下中固定位，随窗口尺寸重排 |
| 挂断**左侧 16px** | **旋转**圆钮（44） | 每点一次顺时针 +90°；**按设备记住**（`config.json` 的 `rotRemote`），下次和这台设备通话自动装回。最终角 = 对端给的基线 + 本机手调角 |
| 左下 / 右下 | 静音 / 全屏 | 与安卓端同构 |

- 这些画面浮动控件**默认隐藏**：鼠标移进窗口才出现（`mouseEntered`），停 5s 无鼠标活动又自动隐藏（用户定调「鼠标移到窗口内才显示」）。
- PC 端**新加的界面文案一律英文**（用户 2026-09-16 定调），所以旋转钮提示是 `Rotate`；既有按钮（静音 / 全屏 / 挂断）保持中文文本不变。


### 锁屏来电：语音/视频可在锁屏上直接接听 / 挂断（2026-09-22，Android 端）

呼入时（`AppCore` 的 `CallManager.Listener.onIncomingCall`）除了应用内的来电弹窗，还会发一条
**独立的「来电」通知**（`CallNotif.kt`，通道 `pp_call`，ID `0x5051`，与前台服务的 `pp_transfer/0x5050` 分开）。
三条入口一起给，尽量保证任何 ROM 上都能接：

| 入口 | 机制 | 备注 |
|---|---|---|
| **全屏意图** | `setFullScreenIntent` + `MainActivity.setShowWhenLocked/setTurnScreenOn` | 息屏/锁屏时由系统把来电界面顶到**锁屏之上**，用户按应用里那对「拒绝 / 接听」大圆钮；接听后通话界面继续盖在锁屏上（呼入结束或通话结束才撤销） |
| **通知按钮「挂断 / 接听」** | 广播 → `CallActionReceiver` | **锁屏上点一下即生效、不需要先解锁**（Activity 型 PendingIntent 会先弹解锁框）。全屏意图被 ROM 拦掉时靠这条路兜底 |
| 点通知本体 | `PendingIntent` → MainActivity | 打开应用（锁屏上按 `showWhenLocked` 直接盖在锁屏之上） |

- 权限：新增 `USE_FULL_SCREEN_INTENT`（Android 10+ 的全屏意图资格）。**Android 14+** 起非「通话/闹钟」类
  应用的该项会被系统默认收回 → `MainActivity.ensureFullScreenIntent()` 检测到未允许就**引导一次**去
  「全屏通知」设置页（`Prefs.fsiPrompted` 只记一次，不反复骚扰）。实测 a16(ColorOS16/API36) 上
  `canUseFullScreenIntent()` 直接为真，不会弹引导。
- 通道特征：`IMPORTANCE_HIGH`（全屏意图的硬前提）+ `VISIBILITY_PUBLIC`（锁屏「隐藏敏感内容」时也照原样显示
  来电人）+ **通道不出声**（铃声仍由 `CallManager.startRinger` 用系统铃声播，避免双响）。
- **视频来电 + 相机权限未授予** 时不能直接接听（动态权限只能在 Activity 里申请）→ 通知按钮改为拉起界面，
  由 `MainActivity.applyCallIntent` 走完整的授权 → 接听流程。
- ⚠️ `applyCallIntent` 取到 `EXTRA_CALL_ACTION` 后**立刻 `removeExtra`**：Activity 重建会拿同一个 Intent
  再走一遍 `onCreate`，不清就会「重建一次 = 又接听/又拒绝一次」。
- 国产 ROM 另有「后台弹出界面」开关可能拦住全屏界面（只能手动开）。拦掉时功能退化但**不失效**：
  锁屏上仍可点通知的「挂断 / 接听」。
- 常驻通知（前台服务）正文改成 **「保留通知显示，避免被意外杀后台」**（提醒用户别划掉），
  连接状态与待发条数下沉到 `setSubText`。


### 「本机ip：」地址块（点击复制，2026-09-15 新增）

两端都有，位置都在「设备列表栏顶部按钮下面」，**标题与每一行都居中、每行一个地址、点一下复制**。
目的是「在这台机器上复制这一台的地址，去对面「＋设备」里粘贴」——所以复制的永远是**完整地址**，
而且**当场就推给已连接的对端**（安卓端 `AppCore.copyForAdd` 直接 `sendClip`；PC 端本来就有剪贴板
监听会自动推送），不用等焦点切换。

| 顺序 | 内容 |
|---|---|
| 1 | **局域网 IPv4**：`192.168.*` → `10.*` → `172.16-31.*` → 其它 |
| 2 | **`169.254.*`** 链路本地 IPv4（拿不到 DHCP 时的兜底地址，用户要求列出，故排最后） |
| 3 | **IPv6 内网地址 1 个**（`fc00::/7` ULA 优先，否则 `fe80::/10`） |
| 4 | **IPv6 公网地址**（`2000::/3`，即俗称 2xxx / 3xxx 开头），升序 |

- 实现两端同源：`android/.../util/LocalIps.kt` 与 `pc/src/.../LocalIps.java`，规则逐条对齐。
- **只统计非蜂窝 / 非隧道网卡**（`rmnet` / `ccmni` / `v4-*` / `p2p` / `tun` / `dummy` / `sit` / `ip6tnl` 全部排除）——
  蜂窝地址拿给对端连不上，列出来只会误导。
- IPv6 一律先归一到「8 组零填充展开式」再存：**天然去重**（同一地址的不同写法只留一个）+ 可当排序键；
  显示前压成 RFC 5952 惯用写法（JVM 在 Windows 上 `getHostAddress()` 返回的是**不压缩**的
  `2001:db8:614:3ba0:0:0:0:12`，压完是 `2001:db8:614:3ba0::12`）。
- 太长的一行**中间省略**（头尾都留着，网段前缀与后缀仍认得出）：安卓端按「可用宽度 ÷ 字号」估字符预算
  （比值故意取偏保守的 0.62，另有 `TextOverflow.Ellipsis` 兜底）；PC 端用 `FontMetrics` 逐像素收紧。
  屏幕上是短写法，但**无障碍描述与点击复制拿到的都是完整地址**。

Android 设备列表的展开 / 收起（三条路径等价）：

| 操作 | 结果 |
|---|---|
| 聊天区**右滑**（≥56dp 横向位移） | 展开列表 |
| 列表展开时**左滑**聊天区 | 收起列表 |
| 列表展开时**点聊天区空白处** | 收起列表（气泡里的按钮/图片自己消费点击，不会被误当空白） |
| 点左上角**三横** | 展开 / 收起切换 |

- 左上角只有一枚**加长的三横**（抽屉开关），左右各一个等宽占位，所以中间那组（`当前地址 ▼`）是**真居中**。
  用画出来的三条横线而不是 `☰` 字形 —— 字形宽度被字体写死且随字号变。
- 顶栏里**没有任何按钮**（原来那排「◀ 列表 / ☰ 设备」「设置」全撤），PC 顶栏原来的一排按钮也全部撤走。
- 手势是**一次性阈值判定、不做动画**（本机 `animator_duration_scale=0` 时动画会瞬跳）。
- 聊天记录**上下滚动不受影响**：竖直方向由 `LazyColumn` 优先消费，横向手势检测会自动取消。

> ⚠️ Android 上点「清空 / 设置」这类文字按钮时，Compose 会按 **48dp 最小触摸目标**向外扩，
> 视觉上没有焦点的空白处其实仍落在按钮上。adb 脚本点击要用**按钮文字节点的中心**，
> 别拿面板内的空 x 坐标当"空白"（实测 232,158 落在「设置」的扩展目标里，把设置页点开了）。

### 聊天气泡的手势与按钮（2026-09-19）

长按气泡这一轮统一改成**弹小菜单**（原来是直接弹「删除消息」确认框）：

| 消息类型 | 长按菜单（安卓端） |
|---|---|
| 纯文本（`Kind.TEXT`） | **复制文本** + 删除 |
| 其余（图片 / 文件 / 文件夹 / 剪贴板 / 通话记录） | 只有删除 |

- 「删除」**仍然二次确认**（`DeleteMessageDialog`），不是点了就删。
- 「复制文本」= 写**本机剪贴板** + 一句 toast；随后**复用既有的剪贴板同步链路**
  （`pushClipIfChanged(force = true)`）—— 与「用户自己在别的应用里复制」表现完全一致：
  同步模式开着就推给已连接的对端、关着就只写本机。
  ⚠ 这里必须 `force`：`Clip.write` 会把这份内容的 sha1 记进 `lastPulledSha1`（该字段本意是
  「刚从对端拉回来、别回推」），不 force 会被当成回声吃掉、**永远推不出去**。
- 实现上给气泡套了一层 `Box`：`DropdownMenu` 的位置相对**父容器**算，不套这层菜单会跑到整条
  消息（含末尾那行时间）的左上角去。

PC 端（2026-09-21 改版：**气泡上不再常驻任何按钮，功能全部收进右键菜单，文案中文**）：

| 消息类型 | 右键菜单 | 行为 |
|---|---|---|
| 文本（`KIND_TEXT`） | **复制文本** | 写系统剪贴板（`StringSelection`）——复制的是**完整原文**，不受「展开全文」折叠影响 |
| 图片（已落盘且非传输中/失败） | **查看大图** / **复制图片** / **在文件夹中显示** | 全屏查看；写剪贴板（`DataFlavor.imageFlavor`，Windows 上即 CF_DIB）；定位选中 |
| 普通文件（同上前提） | **打开文件** / **在文件夹中显示** | 交给系统默认程序；定位选中 |
| 文件夹（收到且已落盘） | **在文件夹中显示** | 定位选中 |
| 任何一条 | **删除** | 二次确认；文本/图片会通知在线对端同步删（见协议 §4.7） |

- **为什么不再常驻按钮**：2026-09-19 那版给每条气泡下面挂一排 `Copy / Copy image / Show in folder`，
  气泡被撑长、每行都拖着一串动作，用户 2026-09-21 要求全部撤掉换成右键菜单。
  ⚠ **菜单文案是「PC 新文案一律英文」的例外**：这一处用户点名要中文（与「展开全文 / 取消发送」一致）。
  由此派生的失败弹窗（复制/打开/定位）也一并改回中文，免得一个功能里中英混着。
- 悬停/短按行为不变：**短按 = 打开内容**（图片全屏、文件交系统）、**长按 600ms = 删除**、**右键 = 菜单**。
- 图片复制走**后台线程**解码（webp / avif / heic 要过系统 WIC 或 ffmpeg，在 EDT 上跑会卡窗口），
  长边上限 4096：够「粘到聊天 / 文档」，也不会为一张 50MP 的图吃掉几百 MB 内存；
  **故意不用 `readCached`** —— 那个缓存文件就是气泡缩略图用的，借它落盘会把缩略图顶掉。
- 「在文件夹中显示」：Windows 用 `explorer /select,<路径>`（JDK 的 `browseFileDirectory` 在 Windows 上
  不保证可用）、macOS 用 `open -R`、其余平台退化为打开父目录。
- 文件类菜单项**只在文件真的可用时出现**：接收中（progress 0..99）/ 失败都不给 ——
  正在收的文件是不完整的，复制出去只有半张图、定位过去也没有意义（原来是「置灰」，现在直接不出现）。
- 两端文件名**一律中间省略**（长名不折行、扩展名始终可见）：文件 / 文件夹名 20 字预算
  （bodyMedium 14sp + 14dp 矢量图标 + 5dp 间距 ≈ 气泡 310dp 内宽），图片名 26 字预算（labelSmall 11sp）；
  另有 `maxLines = 1 + Ellipsis` 兜底，系统大字号下宁可尾部截断也不折行。

安卓端底部输入区还加了一段**空白垫高**（高 = 「发送」二字所在字体的行高，M3 `labelLarge.lineHeight`
≈ 20dp）：圆角屏（a16 那类）会把屏幕四角物理切掉一块，而 `navigationBars` 只让开手势条、**不含圆角**，
不垫的话最右下的「发送」会有一段压在圆角上。取行高而不是写死 20dp，是为了系统字号调大时垫高跟着变
（`sendTextLineHeight()`）；与列表展开与否无关，两种态都垫。

### 界面图标一律矢量绘制，不用 emoji（2026-09-19）

**为什么换掉**：界面上原来写的是 `📋 📁 📄 ✅ 🖼 ⚠ ✕ ▼` 这类字符，两个毛病：

1. **emoji（📋📁📄✅🖼）在跨 ROM / 跨字体下宽度、颜色、基线全不一样** —— ColorOS 上是彩色贴图，
   某些精简字体 / PC 端 YaHei 下直接渲染成**方框（tofu）**；
2. `✕ ▼ ✓` 这类几何符号虽然覆盖率高，但**字号一变就与旁边文字对不齐**（不在同一条视觉中线上）。

**做法**：`android/app/.../ui/Icons.kt` 提供 `GlyphIcon(kind, size, color)`，全部用 Compose `Canvas`
手绘（**等价于一张 SVG**），与 PC 端 `CallIcons.java` 同构：

- **9 种图标**：`CLIPBOARD / FOLDER / FILE / CHECK / WARN / IMAGE / CLOSE / TRIANGLE_DOWN / GEAR`；
- 设计稿坐标固定 **24×24**，实际绘制乘 `u = minDimension / 24` → 任意尺寸都清晰、缩放无损；
- 不引 drawable 资源、不依赖任何字体覆盖，颜色默认取 `LocalContentColor`（跟随按钮/气泡文字色），
  语义色（绿√ / 红⚠ / 白✕）显式传；
- **镂空靠 `PathFillType.EvenOdd`**（`WARN` 的叹号、`GEAR` 的中心孔）：三角/齿轮轮廓与叹号/圆孔两个
  子路径重叠部分被挖空，于是「孔」呈现为底色 —— 不用知道背景是什么颜色，比「先画形状再盖一个背景色
  的小形状」稳。齿轮用极坐标逐点算：8 个齿（齿根宽、齿顶窄）+ 齿间沿齿根圆 6 段折线近似圆弧。
- **图标必须与文字同行 `Row(verticalAlignment = CenterVertically)`**，绝不能拼进字符串 ——
  塞进字符串就是老路的病根（各按各的基线走）。

| 位置 | 原字符 | 现在 |
|---|---|---|
| 剪贴板气泡标签 | `📋 剪贴板` | `GlyphIcon(CLIPBOARD, 12dp)` + 文字 |
| 文件夹气泡 / 文件气泡 | `📁` / `📄` 前缀 | `GlyphIcon(FOLDER / FILE, 14dp)` + 5dp 间距 + 文字 |
| 「已保存」（绿色） | `✅ 已保存` | `GlyphIcon(CHECK, 12dp, 绿)` + 文字 |
| 流量被拒原因（红色） | `⚠ <原因>` | `GlyphIcon(WARN, 12dp, 红)` + 文字 |
| 缩略图占位 | `🖼 载入中…` | `GlyphIcon(IMAGE, 13dp)` + 文字 |
| 全屏看图关闭钮 | `✕`（20sp 字形） | `GlyphIcon(CLOSE, 18dp, 白)` |
| 顶栏地址下拉箭头 | `▼` | `GlyphIcon(TRIANGLE_DOWN, 10dp, primary)` |
| 输入区 Banner（网络不可用） | 调用方拼 `"⚠ $detail"` | `Banner(detail, warn = true)`，图标由参数控制 |
| 分享弹窗无网络提示 | `⚠ …` | `GlyphIcon(WARN, 13dp)` + 文字（Row 顶对齐，多行时图标留在第一行） |
| 设置页「无障碍已开启」 | 文案尾部 `✓` | `GlyphIcon(CHECK, 14dp)` 提到**行首**（它是状态标记不是标点，提到行首不会在换行时被甩走） |

**左侧面板底部的「设置」入口**：文字左边加一枚**齿轮矢量图标**（`Glyph.GEAR`，16dp，间距 6dp），
整块仍然**居中** —— 外层 `Row(Arrangement.Center)` + `TextButton` 宽度自适应内容，加图标只是整块变宽、
中线不动。文案「设  置」的两字间距与 17sp 字号保持不变。

**保留的字符**（都不属于 emoji、且系统字体必备）：

- `● / ○` —— 连接状态点（满/空圆）。它在两端都是**纯几何字形**，跨设备表现一致；且 PC 端它是
  `JButton` / `JLabel` 文案的一部分，改成图标要动设备按钮、状态标签、托盘提示、菜单项一大片，
  收益与风险不成比例。
- `＋` —— 全角加号（「＋设备」），CJK 字体必备。
- 日志里的 `→ ← ≠ ≥` —— 只出现在日志/注释，用户看不到。

**PC 端本轮无需改动**：PC 早在 2026-09-16 通话按钮改造时就已「禁 emoji」（YaHei 会把 emoji 渲染成方框），
界面图标一律走 `CallIcons` / `AppIcon` 手绘；本轮扫描确认 PC 侧可见字符只剩下 `● ○ ＋`（保留项）。

## 实测验证状态

设备代号：**a12** = Android 12 手机（MIUI，USB + root）；**a16** = Android 16 手机（ColorOS 16，无线 adb `192.168.1.13:5555`）。

### a12（Android 12 / MIUI，有线 adb）

| 项 | 结果 |
|---|---|
| 手机 → PC 文本 | ✅ PC `history.jsonl` 收到 `kind=TEXT` |
| 手机 → PC 剪贴板 | ✅ 复制后 1s 内自动推送，PC 收到 `kind=CLIP` |
| 手机 → PC 文件 | ✅ 200KB 分片传输，落 `pc/received/`，md5 双向一致 |
| 发送后自动同步一次 | ✅ 日志可见紧随其后的「手动同步」 |
| PC → 手机剪贴板 | ✅ 手机左侧出现剪贴板气泡 |
| 电脑复制 → 只留最新一条 | ✅ PC 端缓冲只保留最新 |
| WiFi 门禁 | ✅ 关 WiFi 后 9s 内链路停止 + 前台服务停止；恢复后自动重连 |
| UDP 发现自动加候选 IP | ✅ 候选列表自动出现 PC 地址 |
| 剪贴板防回环 / 防回声 | ✅ 手机刚推过的内容被回读时不重复写入；冷启动后首次同步也不重复（哈希持久化） |
| **同步模式：手机在后台收电脑剪贴板** | ✅ 手机切到桌面后改 PC 剪贴板，4s 内出现 `已接收电脑剪贴板并写入本机（后台，18 字）`；回到前台后键盘上方的剪贴板条显示的就是该内容（`docs/pp_ui_14_clip_bg_recv.png`） |
| **同步模式关闭** | ✅ 同样场景只出现「电脑剪贴板更新（N 字）」通知，不再自动拉取写入 |
| **PC 保存目录** | ✅ 无界面自测通过（`StoreTest`：改目录 / 持久化 / 非法目录回落 / 同名加序号）；⚠️ 界面与落盘需**重启 PC 端**后人工确认 |
| **全格式分享注册** | ✅ `dumpsys package` 可见 `SEND` / `SEND_MULTIPLE` 两条 filter（`StaticType: "*"`），即任意 MIME 类型都能在分享面板里选到本应用 |
| **分享 → 选设备弹窗** | ✅ 纯文本分享（`EXTRA_TEXT`）与图片分享（`EXTRA_STREAM` → `1 个文件 · 3.5 MB · pp_check.png`）两条路径均在真机弹出「发送到哪台设备？」，单选设备列表已连接的那台默认选中（`docs/pp_ui_16_share_text.png`、`docs/pp_ui_17_share_file.png`）；应用已在运行时走 `onNewIntent` 复用界面不重建 |
| **点通知回前台** | ✅ `dumpsys notification` 显示常驻通知的 `contentIntent` 已带上 `act=MAIN cat=LAUNCHER flg=0x34200000`（NEW_TASK \| CLEAR_TOP \| SINGLE_TOP \| RESET_TASK_IF_NEEDED），与点桌面图标同形态 |
| ⚠️ 待真机回归 | 分享选定设备后的**实际传输**（含"未连上就先转存再排队"那条分支）、PC 端拖拽发送的确认框与传输 |

### 界面改版第二轮（清空 / 设置入口 + PC 同构，a12 实测）

| 项 | 结果 |
|---|---|
| 「清空」按钮落位 | ✅ dump 节点 `[47,144][125,199]`（= 原「设置」的位置，字号同为 labelLarge），中心 (86,171) |
| 「设置」降为次要入口 | ✅ `[259,147][329,195]`（labelMedium，同行靠右）；点击 → 设置页正常打开 |
| 清空二次确认 | ✅ 弹窗标题「清空聊天记录」+「清空与 192.168.1.13 的全部聊天与文件传输记录？」+ 取消 / 清空（`shots/ui_clear_confirm.png`） |
| 取消不动数据 | ✅ 取消后聊天区记录原样（文件气泡「✅ 已保存」、缩略图都在） |
| 设置页「清空」分区 | ✅ 滚动到底可见「清空」标题 + 说明 + 清空聊天记录 / 清空待发（`shots/ui_settings_clear.png`） |
| 「已知设备」名单不受清空影响 | ✅ 设置页仍显示「当前：2 台：192.168.1.13、192.168.1.12」 |
| 顶栏居中不回归 | ✅ 标题 `[612,127][848,182]` 中心 730（= 1080 − 138dp 侧栏后的聊天列中心）；收起态 `[422,127][658,182]` 中心 540 = 1080/2 |
| 两端无崩溃 | ✅ a12 / a16 `logcat` 均无 `FATAL EXCEPTION` |
| PC 端界面 | ✅ 同 JVM 离屏渲染截图确认：顶栏居中两行、左栏「清空 / 设置」、左栏底部信息行（`shots/pc_ui.png`）；顺带修掉左栏底部的横向滚动条 |

### 界面改版第三轮（「本机ip：」地址块，a12 + PC 实测）

| 项 | 结果 |
|---|---|
| 「○ 未连接…」那行已删 | ✅ dump 里设备列表只剩「清空 / 设置 / 本机ip: / 地址行…」；连接状态只在顶栏第 2 行显示 |
| 地址行内容与顺序 | ✅ a12 `本机地址列表更新（4 个）：192.168.1.14 \| fe80::1111:2222:3333:4444 \| 2001:db8:614:3ba0:7dc7:b4a5:211a:27c5 \| 2001:db8:614:3ba0:f722:9d54:58c4:e458`；a16 同构（`192.168.1.13` + `fe80::aaaa:bbbb:cccc:dddd` + 两条 2001:db8:…） |
| 一 IP 一行 + 居中 | ✅ 四个 clickable 节点 `[11,254][369,320]` / `[11,320][369,386]` / … 等宽等高；标题 `[129,243][252,254]` 中心 190.5 ≈ 侧栏中心 189.75 |
| 太长中间省略 | ✅ `fe80::1111:…4444`、`2001:db8…11a:27c5`（IPv4 12 字符不省略） |
| 点击复制 = 完整地址 | ✅ 点第 3 行 → 日志 `复制本机地址 2001:db8:614:3ba0:7dc7:b4a5:211a:27c5`（**不是**屏幕上的短写法） |
| 复制内容可直接添加设备 | ✅ 打开「＋设备」→ `input keyevent 279`（KEYCODE_PASTE）→ dump 出 IP 框内容就是上面那条完整 IPv6 |
| 复制即同步对端 | ✅ a12 点 `192.168.1.14` → Toast「已复制并同步给对端」→ a16 日志 `已接收对端剪贴板并写入本机（前台，12 字）` |
| 无障碍描述 | ✅ 节点的 `content-desc` 是完整地址（屏幕上是省略写法） |
| PC 端界面 | ✅ 同 JVM 离屏渲染 + 组件树 dump：五行统一宽度 179、`x=0`、行内居中（`BoxLayout` 按最宽行的轴心对齐，不统一宽度会整体左偏 5.5px）；像素扫描墨迹 `x=24..193` 全在左栏（右边框 206）内，无裁切（`shots/pc_ui.png`） |
| 两端无崩溃 | ✅ a12 / a16 `logcat` 无 `FATAL EXCEPTION` |

> 踩坑：Android 端第一版把地址行直接放进 `BoxWithConstraints` —— 它本质是 **Box**，子项**叠放**，
> 五条地址全画在同一行上（截图里一坨糊字，而 a11y 树只报出一个合并节点，从 dump 上看不出来）。
> 里面必须再套一层 `Column`，`BoxWithConstraints` 只用来量可用宽度。
>
> PC 端搞清楚了「文本有没有被容器裁掉」不能靠肉眼看截图：`FontMetrics.stringWidth` ==
> `JLabel.getPreferredSize().width` == 实际墨迹宽度（实测 12 字符 67px、25 字符 140px，完全一致），
> 所以用 `pc/tools/png_ink_scan.py` 量墨迹范围就有定论；`pc/tools/UiTree.java` 则直接 dump
> 每个 label 的 `text / prefW / 实际 x、宽` 来查对齐。

### a16（ColorOS 16 / Android 16，无线 adb）

Android 16 起 edge-to-edge 强制生效（`windowOptOutEdgeToEdgeEnforcement` 已失效），系统栏适配必须在应用内自己消费 `WindowInsets`——这是首轮在 a16 上暴露的问题，详见下节「已知限制」。

| 项 | 结果 |
|---|---|
| 顶部不再被状态栏压住 | ✅ 标题 `top` 从 80px → 240px（=160px 状态栏 + 32px 让位 + 居中），`enableEdgeToEdge()` 生效 |
| 底栏不跟随系统黑边 | ✅ `isNavigationBarContrastEnforced = false` 后，导航栏区域颜色 `#2C3036`（= `surfaceVariant`），与输入栏同色（`docs/pp_ui_15_a16_android16.png`） |
| 底栏 4 个按钮单行不换行 | ✅ 360dp 宽屏下「发送 / 文件 / 同步 / 立即推送剪贴板」同一行 |
| 文本 a16 → a12 | ✅ a12 `history.jsonl` 收到 `{"kind":"TEXT","text":"a16text20260912"}` |
| 剪贴板自动推送 a16 → a12 | ✅ 前台复制后 `已推送剪贴板 11 字` → a12 `已接收电脑剪贴板并写入本机（前台，11 字）` |
| 剪贴板接收 PC → a16 | ✅ `已接收电脑剪贴板并写入本机（前台，17 字）`，粘贴读回内容一致 |
| 文件 PC → a16 | ✅ 153600 B，md5 `2c75581756e7ee23690eee869f865c9a` 一致 |
| 文件 a16 → a12 | ✅ a12 `文件接收完成 a16send_test.bin 81920 B 校验=通过`，落盘 md5 `56f7baae4a53e3b95eac2ccf566713a2` 与源一致 |
| 手机 ↔ 手机剪贴板互拉 | ✅ 双方均回 `已应答对端剪贴板请求 N 字`，不再刷「未知帧类型」（见下） |
| 后台保活 | 不要求。a16 退后台后 ColorOS 会掐断应用的网络（`oappnetcontrol`，socket 仍在 LISTEN 但外部连不上）——**只保证前台可用**；熄屏同理，见「已知限制」里的熄屏统一冻结 |

### 界面改版第四轮（顶栏地址▼ + 底部输入区 + 「+」菜单，a12 / a16 / PC 实测）

| 项 | 结果 |
|---|---|
| 顶栏中段 =「地址 ▼」整体居中（a16，列表收起全宽） | ✅ 蓝字墨迹「`192.168.1.14` + ▼」跨 `x=534..898` 中心 **716** / 屏幕中心 **720**；▼ 紧跟在 IP 后面（`x=867..898`） |
| 顶栏中段整体居中（a12，列表展开） | ✅ 跨 `x=594..864` 中心 **729** / 聊天列中心（1080 − 138dp 侧栏 = 379.5 起）**729.75** |
| 顶栏中段整体居中（PC） | ✅ 离屏渲染：跨 `x=512..609` 中心 **560** / 聊天列（920−200 左栏）中心 **559.5** |
| 三横位置未受挤压 | ✅ a16 `x=56..167`、a12 `x=419..495`（= 栏内 52dp 槽的居中位） |
| **IPv6 中间省略** | ✅ a16 选中 `2001:db8:771:1c70:d8fb:de74:fce9:88c` 后顶栏显示 **`2001:db8:…:fce9:88c ▼`**（`docs/pp_ui_23_a16_ipv6_short.png`）；设备列表里的地址行同策略（`fe80::aaaa:…dddd` 等） |
| PC 箭头字形 | ✅ 用 `DownTriangle`（Graphics2D）替代 `▼` 字形；离屏放大确认是一个清晰的实心下三角（原先写字形是贴基线的小逗号残迹） |
| 输入框向左压缩 | ✅ a16 输入框 `x=34..1116`（1082px = 270dp，只占左侧）；a12（列表展开）`x=403..856`（453px = 165dp） |
| 发送按钮（绿底小圆角白字） | ✅ a16 `x=1150..1407 / y=2724..2899` = **64.5 × 44dp**；a12 `x=880..1057 / y=2180..2300` = **64.7 × 44dp**；角 8dp；底色 `#2E7D32`；字白色 |
| 发送按钮跟在输入框后面 | ✅ 输入框右缘 + 8dp 间隙 = 按钮左缘；按钮右缘 + 8dp = 屏幕右缘（a16：1116→1150→1407，1080→1148→1408 ✅） |
| 底栏只剩一行 | ✅ 原「通话 / 视频」两枚按钮已撤；`uiautomator dump` 底栏只剩「输入内容 / + / 发送」 |
| 「+」菜单四项 | ✅ dump 出菜单文本正好是 **发送文件 / 发送文件夹 / 语音通话 / 视频通话**（`docs/pp_ui_20_a16_newbar_menu.png`） |
| 占位文案不折行 | ✅ 改成「输入内容」后单行显示（原「输入要发送的内容」在 a16 侧栏展开时折成 3 行） |
| 两端无崩溃 | ✅ a12 / a16 启动与操作后 `logcat` 无 `FATAL EXCEPTION` |
| 无障碍服务写回 | ✅ 重装后 a12（`su -c`，MIUI 普通 shell 被拒 `WRITE_SECURE_SETTINGS`）/ a16（`su -c`）均写回，`dumpsys accessibility` 里 `ClipCaptureService` 在 Enabled services |
| 端到端连通 | ✅ 重装后 a16 选 a12 → 状态「● 已连接」，两侧历史/消息正常（`docs/pp_ui_21_a16_newbar_connected.png`） |

> 量图提示：**a16 的 density 是 640 = 4.0×**（不是 3.5×）；a12 是 440 = 2.75×。
> 换算 dp 时按 4.0 / 2.75 算，否则会得出「按钮比设计大 12%」的错误结论。
>
> 验证时的取色判据：顶栏地色 `#2C3036`、地址用主色（蓝）、发送钮 `#2E7D32`（`±12` 容差）——
> 但**别把聊天区一起扫进去**：聊天里的图片（例如一张带绿植/深绿背景的照片）会污染绿色阈值，
> 按 y 分带或先定位顶栏带（找 `#2C3036` 的连续行）再扫。

### 界面改版第五轮（背景铺满 + 只避顶部通知栏，a12 / a16 实测）

**口径定稿（2026-09-16，修正第四轮的理解）**：**背景色铺满整个窗口，只有顶部必须让开通知栏。**

第四轮曾按「上下各加一份通知栏高度的边距」去做（`EXTRA_VPAD = 1f`，给 `Row` 加
`padding(top/bottom = 通知栏高度)`），**这是错的**：`Surface(fillMaxSize)` 已经把 `background`
铺到整个窗口，再给内容加内边距，只会露出两条**与主界面不同色的空带**（a16 实测：顶、底各多
160px 纯 `#14161A`），肉眼看就是「背景没铺满」。而用户要的「边距」本意是**避开圆角屏的物理切口**——
圆角屏切掉的是四个角的**像素**，只要背景色填满就不会有缺口，跟内容边距无关。

| 项 | 结果 |
|---|---|
| 顶部空带消失 | ✅ 修正前 a16 `y=0..319` 全是 `#14161A`（状态栏 160px + EXTRA 空带 160px）；修正后 `y=0..159` = 状态栏区、**`y=160` 起就是顶栏 `#2C3036`**（`docs/pp_ui_28_a16_fillbg.png`） |
| 顶部仍避让通知栏 | ✅ a16 内容起点 `y=160` = 状态栏高度（40dp × 4.0）不重叠；a12 起点 `y=99` = 36dp × 2.75 |
| 底部铺满 | ✅ 修正前 a16 底部 `y=2976..3135` 是 `#14161A` 空带；修正后**最后一行 `y=3135` 仍是 `#2C3036`**（输入区 Surface 的底色一直铺到手势条底下） |
| 左侧列表铺满 | ✅ a16 `x=40` 从 `y=160` 到 `y=3135` 全为 `#2C3036`，无黑带 |
| a12 同口径 | ✅ 顶部 `y=0..98` 状态栏区、`y=99` 起顶栏；底部到 `y=2339` 仍 `#2C3036`（`docs/pp_ui_29_a12_fillbg.png`） |
| 实现 | 删掉 `Row` 上的 `padding(top/bottom = barPad * EXTRA_VPAD)` 与 `EXTRA_VPAD` 常量（连 `asPaddingValues` import 一起清）；**保留** `.windowInsetsPadding(WindowInsets.statusBars)`；底部不再加任何额外边距，交给 `BottomArea` 自带的 `navigationBars` inset |
| 两端无崩溃 | ✅ a12 / a16 均正常启动，`logcat` 无 `FATAL EXCEPTION` |

### 界面改版第六轮（通知栏同色 + 列表态压矮输入区 + 顶部留白收紧，a12 / a16 实测）

三项用户反馈 → 三项改动：

1. **通知栏那一条的底色要跟软件一致**：第五轮把 `statusBars` inset 加在根 `Row` 上，于是状态栏
   区域露的是页面底色 `#14161A`，与顶栏 `#2C3036` 之间出现一条异色带。**改法：inset 下沉到各面板
   自己的 `Surface` 内部**（顶栏 `HeaderBar` 的 `Column`、左侧 `DeviceListPanel` 的 `Column`），
   面板底色因此一直铺到屏幕最顶边，内容仍被顶下来。
2. **左侧列表展开时底部输入区要压矮**：输入框 56dp → **40dp**（`Modifier.height(40.dp)`）、
   「+」钮 40×36dp、发送钮 44→36dp、输入区上下内边距 8dp→3dp → 整条从 **62dp 压到 46dp**。
3. **顶部留白收紧**（用户："a16 设备顶部空的比较多；顶栏的高度可以再小一些"）：顶栏第一行
   48dp → **36dp**（`TOP_ROW_H`），顶栏自身 4dp 垂直内边距去掉。

| 项 | 结果 |
|---|---|
| 通知栏区域同色 | ✅ a16 `y=0` 起即 `#2C3036`（左栏 x=200、中列 x=720、最右 x=1438 三处一致）；a12 `y=0..130+` 全 `#2C3036`（`docs/pp_ui_32/33_*`） |
| 输入区压矮（列表展开态） | ✅ `uiautomator` 实测 `EditText` 布局高由 224px→**160px(40dp)**；底部区块由 62dp→**46dp**（a16：`View [552,2952][1440,3136]` = 184px）；绿钮 144px = **36dp**；「+」钮 40×36dp |
| 完整聊天页不受影响 | ✅ 非紧凑态仍是 56dp 输入框 / 44dp 发送钮（第四轮实测的 `64.5×44dp` 规格只对完整页生效） |
| 顶栏第一行收高 | ✅ `TOP_ROW_H = 36.dp`，顶栏 + 左栏两处**同高**（否则第一行错位）；顶栏块 a16 由 248px→**208px** |
| a16 顶部留白 | ✅「系统状态栏图标墨迹底 → 应用首行墨迹」由 **120px(30dp) → 83px(20.8dp)**；a12 同口径 **16.4dp** |
| 两端无崩溃 / a11y | ✅ 均正常；重装后 a12、a16 的 `ClipCaptureService` 都在 |

**a16 顶部为什么天生比 a12 空（实测数据，别再当成 bug 查）**

```
a16: InsetsSource type=statusBars frame=[0,0][1440,160]   → 状态栏带 160px = 40dp
     系统时钟/电量墨迹 y=72..117   → 带子底部还剩 43px(10.75dp) 是「系统自己留的白」
a12: mDisplayCutout/statusBars = 99px(36dp)，系统墨迹 y=46..82 → 带底只余 17px(6.2dp)
```

也就是说：**两家 OEM 把状态栏图标画在带子里的位置不同**（ColorOS 偏上、MIUI 偏下），
这一段空白**属于系统状态栏**，应用不能把内容压进去（顶部必须让开通知栏是硬规则），
能在应用侧拧的只有自己的那部分留白：

| 应用侧留白组成 | 改前 | 改后 |
|---|---|---|
| 顶栏自身垂直内边距 | 4dp | **0** |
| 第一行 48dp 最小触摸盒里文字上方的居中留白 | 15.75dp | **9.75dp**（行高 36dp） |
| 合计 | 19.75dp | **9.75dp** |


### 界面改版第七轮（**PC 端**左栏瘦身 + 「清空」迁到聊天右上角，2026-09-21 离屏渲染实测）

用户一次给了 5 条（全在 PC 端，Android 未动）：

1. 「清空」按钮从**左栏左上角**搬到**聊天界面右上角**；
2. 「N 台在线」全部删掉；
3. 左栏「单击设备 = 切到它并连接 / 右键改备注/IP · 断线后点一下重连」两行小字删掉；
4. 左栏左下角原来堆着一大堆（在线数 / 候选 IP / 接收目录 + 剪贴板缓冲）→ **只留一行「接收目录」路径**；
5. 聊天界面底部那行「也可以直接把文件 / 文件夹拖进窗口…」删掉。

| 项 | 结果 |
|---|---|
| 「清空」落位 | ✅ 顶栏 `SIDE_SLOT`（180px）**右段**内 EAST 对齐 —— 左右等宽口径不变，中间「地址 ▼」仍居中；`shots/pc_ui_new.png` 可见它在聊天列右上角（x≈840..905） |
| 左栏顶部 | ✅ 只剩「设置」（右对齐），左上角留白（`ChatDevices.buildDevicePanel` 的 `topRow`） |
| 在线数 | ✅ 从 `ChatWindow.stateShort()`（左栏状态行）、`headerState()`（顶栏第 2 行）、`PcMain.onState` 的 sub 文案三处一起撤掉 —— 界面任何位置都不再出现「N 台在线」（托盘提示保留，窗口收起时才有用） |
| 左栏底部 | ✅ **一行目录**（`ChatDevices.dirLabel` + `refreshSaveDir()`）：只显示路径、长路径中间省略、完整路径在工具提示里；每 5s 跟一次（复用 `ipTicker`），改设置后即时生效 |
| 聊天底部 | ✅ 只剩输入框 + 发送 / 通话 / 发送文件三个按钮 |
| ⚠️ 代价 | 瞬时状态文案（`ChatWindow.setSub` / `setClipBuffer`，如「已复制 / 拖入的内容读不到 / 发现 N 台设备」）**当前没有界面落点** —— 字段与调用点保留（写进不在组件树里的 JLabel），要接回来只改 `setSub` 一处 |
| ⚠️ 顺带改 | 「清空」在**没选设备**时原来只写状态行（现在看不见）→ 改为**弹窗提示**「请先在左侧设备列表点一台设备」 |

**离屏渲染的新姿势（本轮踩出来的，下次照用）**：正式实例开着时 `tools/UiProbe.java`（跑 `PcMain.main`）
会被**单实例闸门**直接劝退（守卫口 127.0.0.1:**8767**，且 28765 也被占），拍不到图。
改用新增的 **`tools/UiWinProbe.java`**：不经 `PcMain`，直接 `new Store(Store.defaultRoot())` + `new Link()`（**不 start**）
+ `new ChatWindow(...)` → `printAll` 离屏渲染。**不抢端口、不发 UDP 广播**（不会在手机上凭空多出一台设备），
可以在用户正在用的实例旁边安心跑（`shots/pc_ui_new.png` 就是这么来的；设备行全是灰点 = 链路没起，属正常）。

| 项 | 结果 |
|---|---|
| 编译 | ✅ `javac --release 17` 仅剩既有的 `LinkHsync` unchecked 注记，无 error（35 个源文件） |
| jar 体检 | ✅ `io/` = **141**、总条目 **290**、无 `io/io/` 冗余；`ChatDevices.class` 里已无「单击设备 / 剪贴板缓冲」字样、有 `refreshSaveDir` |
| 离屏渲染 | ✅ 920×660 全窗口：左上只剩「设置」、右上「清空」、左栏底部只有 `D:\Downloads`、底部只有输入行（`shots/pc_ui_new.png`） |


### 界面改版第八轮（**PC 端**气泡手势收窄 + 右键菜单中文化 + 清空改「逐条删除」，2026-09-21 探针实测）

用户三条要求，都在 PC 端（Android 未动）：

1. **点击判定不能落在空白处** —— 点气泡**旁边那一大片空白**原来也会弹菜单 / 触发删除；
2. **气泡上常驻的 `Copy` / `Copy image` / `Show in folder` 全删**，改成**右键小菜单**，文案**必须中文**；
3. **清空要「逐条执行删除命令」**，不能整文件删 —— 否则对方设备一启动就把整段聊天同步回来。

| 项 | 落点与结果 |
|---|---|
| ① 点击作用域 | `ChatBubbles.bubble()` 里手势从**整行 `row`** 改挂**气泡本体 `body`**。整行是满宽 `BorderLayout`（气泡靠 EAST/WEST 贴边），挂它等于「整行都能点」。现在只有气泡自己响应：署名行、行内留白、上下 6/14px 间距都不再触发 |
| ② 常驻按钮 | `textBubble` 的「Copy」动作行、`fileBubble` 的「Copy image / Show in folder」动作行**整体删除**；`miniButton()` / `actionRow()` 两个 helper 变死代码 → 一并删掉（防长回来） |
| ② 右键菜单 | `showBubblePopup()` 重写为按类型现算的菜单：文本→复制文本；图片→查看大图 / 复制图片 / 在文件夹中显示；普通文件→打开文件 / 在文件夹中显示；文件夹→在文件夹中显示；任何一条都有**删除**。可不可用由 `fileReady()` 现算（传输中/失败不给「打开、复制、定位」）|
| ② 文案 | 菜单与派生的失败弹窗**全中文**（用户点名；是「PC 新文案一律英文」的唯一例外）|
| ③ 清空 | `ChatWindow.clearCurrentConversation()` 重写：枚举本会话（**含该设备其它地址**，历史按组键存）全部 mid + 待发条目 → 分出「文本 + 图片」→ `Link.deleteMessages()` 一次清本地（历史/待发/气泡）并按 200 条分片发 `msg_del` 给在线对端 |
| ③ 数据层 | `Store.removeFromHistory(peerIp, Collection)` / `removePending(Collection)` 新增批量版：**一趟过滤 + 一次落盘**（循环调单条版是 O(N²) 的文件往返）；单条版保留（`cancelFile` 还在用）|
| ③ 提示语 | 确认框与结果提示**看对端在不在线**：在线 = 「会一起清掉」；离线 = 明确告知「只清了本机，对方上线后可能被同步回来」——不允许假装清干净 |
| ⚠ 顺带对齐 | 单条删除的 `msg_del` 也**只对文本/图片发**（协议 §4.7 与安卓端 `AppCore.deleteMessage` 同口径）；原来对所有 kind 都发，会把对端的文件记录一起删掉 |

**证据（`pc/tools/BubbleProbe.java` + `pc/tools/ClearProbe.java`，都不开窗口、不抢端口）**

```
--- 文本气泡 ---
row 宽=800 | 气泡本体=RoundedPanel 宽=314 | row 上的手柄数=0 | 带手柄的组件=2
  气泡内按钮=[]
--- 图片气泡 ---
row 宽=800 | 气泡本体=RoundedPanel 宽=148 | row 上的手柄数=0 | 带手柄的组件=4
  气泡内按钮=[]
菜单[文本] = 复制文本 --- 删除
菜单[图片] = 查看大图 复制图片 在文件夹中显示 --- 删除
菜单[普通文件] = 打开文件 在文件夹中显示 --- 删除
BUBBLE PROBE PASS

清空前：A 历史=3 B 历史=2 待发=4
清空后：A 历史=0（删掉 3） B 历史=2 待发=1
  ok   A 的历史被清空      ok   B 的历史一条不少（2 条，未串台）
  ok   只删掉该设备的 3 条待发（B 的 b4 还在）   ok   删掉的行数 = 3
  ok   单条删历史仍生效（B 剩 1 条）
CLEAR PROBE PASS
```

- 「气泡本体宽 148~314px / 整行 800px」= 旧 bug 的量化对照：**手柄只在这两百多像素里**。
- 气泡外观证明图：`shots/bubble_proof.png`（4 种气泡并排，右侧大片留白**没有**任何按钮）。
  ⚠ 右键菜单**画不进 PNG** —— `JPopupMenu` 是重量级弹出窗口（自带窗口、不随父组件 `printAll`），
  所以菜单只用文字核对（条目组成 + 中文文案）。

| 项 | 结果 |
|---|---|
| 编译 | ✅ `javac --release 17` 无 error（35 个源文件，仅既有 unchecked 注记）|
| jar | ✅ `742442 B` / md5 `ad2d8848…`；`io/`=**141**、总 **290**、无 `io/io/`；`ChatBubbles.class` 已无 `Copy` 字样、有 `buildBubbleMenu`；`ChatWindow.class` 有 `deleteMessages`/`canSendMsgDel` 且不再调 `clearConversation` |
| 类加载冒烟 | ✅ 139/139 `SMOKE PASS`（`build/probe/ClassCheck.java`）|

### 界面改版第九轮（**Android 端**「清空」改逐条删除 + 确认框，2026-09-21 a16 真机实测）

用户要求「把安卓端也补了吧」—— 把第八轮在 PC 上定的「清空 = 逐条执行删除命令」口径搬到安卓。

| # | 改动 | 落点（Android） |
|---|---|---|
| ① | 清空**不再整删历史文件** | `AppCore.clearHistory()` 重写为「枚举 → 确认 → 执行」三段；新增 `planConversation(ip)` / `planAllConversations()` / `confirmClearHistory()` / `cancelClearHistory()` |
| ② | 逐条删除走删除同步 | `Link.deleteMessages(peerIp, mids)`：文本/图片按 `MSG_DEL_CHUNK = 200` 分片发 `msg_del`（帧走 `io{}`，不在主线程写 socket）；新增 `Link.canSendMsgDel(ip)`（确认框要用的「对方在线吗」判据，与真正发送用的是同一口径） |
| ③ | 本地一次清完（不做 O(N²) 磁盘往返） | `Store.removeFromHistory(peerIp, mids)` / `Store.removePending(mids)` 批量版（一趟读、一趟写）+ `Store.historyKeys()`（兜住**不在设备名单里的孤立历史**） |
| ④ | 传输在途拒绝清空 | `Link.hasActiveTransfer(ip)`：收侧看 `rxMap`、发侧用 `inFlight` 的 mid 去待发队列反查归属，全部按**设备组键**比对 |
| ⑤ | 确认框 + 结果提示 | 新增 `ClearHistoryDialog` / `ClearWarnDialog`（`ChatScreen.kt`）；`ClearPlan` / `ClearPart` 由 AppCore 枚举后产出（枚举完才弹，条数与在线状态都是**算出来的**） |
| ⑥ | 设置页文案 | 「清空」分区改为「逐条删除 + 会通知对方一起删 + 点下去会先弹确认框」，并删掉早就不存在的那句「主界面设备列表顶部那枚清空按钮」 |

真机实测（a16 / ColorOS，`192.168.1.13:5555`；**只验证到确认框，没有真按「清空」**，避免动用户真实聊天记录）：

| 项 | 结果 |
|---|---|
| 编译 | ✅ `assembleRelease`（R8）`BUILD SUCCESSFUL in 1m53s`，`compileReleaseKotlin` → `minifyReleaseWithR8` → `packageRelease` |
| APK | 首版 `2841203 B` / md5 `a88aafb5…`；**加设置行垫高后的终版 md5 `7fea3d97…`**（同尺寸 ≈2.84 MB）；`AndroidManifest` 无 `DEBUGGABLE` |
| dex 抽查 | ✅ 新文案 `已逐条删除` / `清空聊天记录` / `对方不在线` / `有传输任务进行中` 都在；mapping 里 `AppCore.clearHistory` 等符号恒等映射 |
| 推送 | ✅ 首版 a12（`su -c pm install -r`）+ a16（streamed install）都 `Success`；**终版（垫高）只推 a12** —— 用户 2026-09-21 明确「只用 a12 设备调试」，a16 停在首版 |
| a11y 回写 | ⚠️ 首版装完 a12 **掉了** `ClipCaptureService`（读出现有列表后追加写回，另两项 `LttService` / `AccessibilitySceneMode` 未被冲掉）；终版再装**没掉**，a16 首版后也保持 |
| 设置页 | ✅ dump 到新文案「聊天记录是逐条删除的：文本和图片会同时通知对方一起删（对方在线时），这样对方连上后不会又同步回来…」 |
| 确认框 | ✅ 点「清空聊天记录」后 dump 到：标题「清空聊天记录」+「清空与「PC-A」的 **22** 条记录？」+ 逐条删除说明 + **「注意：对方当前不在线，本次只清本机 —— 对方上线后这些文本/图片可能被同步回来。」**（PC 没在跑 = 判据正确）|
| 取消 | ✅ 点「取消」后弹窗消失（`清空与` 节点数归 0），历史未被改动；进程存活、`MainActivity` 仍在前台，无 FATAL |

> 与 PC 的两点**有意差异**（不是漏改）：
> 1. PC 是**没选设备就拒绝**（弹窗提示先选设备）；Android 的清空入口在**设置页**，
>    没选设备时语义 = **清全部设备**（逐台走同一条路径，孤立历史文件也兜住）。
> 2. Android 目前**没有**聊天区右上角的「清空」按钮（2026-09-19 用户要求把入口从设备列表顶部撤掉），
>    所以「逐条删除」这套能力只由设置页触发。要在聊天区加按钮说一声。

#### 同轮追加：左栏底部「设置」行按聊天输入框的同一口径垫高（a12 像素实测）

用户要求「安卓端设置按钮需要同聊天框应用垫高」。根因：左栏与聊天区**各自贴屏幕底边**，而只有
聊天区的 `BottomArea` 让开导航栏后又垫了一行「发送」字高（`sendTextLineHeight()`），
左栏的「设置」行原来什么都没有 → 比右侧输入框低约一行（20dp）。

| 项 | 改动 |
|---|---|
| 垫高量同源 | `ChatScreen.sendTextLineHeight()` 从 `private` 提为 **`internal`**，两个面板共用（字号变大时两边一起跟着变） |
| 左栏底部 | `DeviceListPanel` 那行「设置」加 `windowInsetsPadding(WindowInsets.navigationBars)`，行末再加一个 `Spacer(sendTextLineHeight())` —— 与 `BottomArea` 收尾完全同构；**加在 Surface 内部**，面板底色仍铺到屏幕最底边 |

a12（1080×2340 / density 440 = **2.75×**，左栏展开的紧凑态）像素实测：

| 量的是谁 | 结果 |
|---|---|
| 「发送」绿钮盒（36dp） | y `2167..2265`（=99px）→ 加 3dp 输入区下内边距 → **输入行底 ≈2284** |
| 「⚙ 设 置」文字墨迹 | y `2217..2260`，墨迹中心 2238.5 → 按钮盒（36dp）≈ `2189..2288` |
| 结论 | 两行**底边相差 ≈4px（1.5dp）**，即都停在「窗口底 − 一个行高」；改之前设置行是贴窗口最底边的（低一行） |
| 视觉 | `shots/a12_settings_row.png`（底部 260px 裁剪 + 参考线） |

> ⚠️ **a12 上 `uiautomator dump` 的 bounds 不能用来量底部**：`设置`/`发送`/`EditText` 三个节点
> 全被裁到 `bottom=2197`（窗口高 2340 减 143），据它算会得出「两行本来就对齐」的错误结论。
> 底部一律用 `screencap` + PIL 量像素（本工程既有口径）。

### 局域网 /24 网段扫描（2026-09-21）

| 项 | 结果 |
|---|---|
| PC 判据自检 | ✅ `SubnetCheck` 20 用例全过（`10.x`/`172.16-31`/`192.168` → 前三段；`172.15`/`172.32`/公网/`169.254`/IPv6 → null），末行 `SUBNET CHECK PASS` |
| PC 类加载冒烟 | ✅ 139/139 `SMOKE PASS`；jar 290 条目 / `io` 141（`dist/PhonePcTransfer-PC.jar` = 744031 B） |
| PC 新符号抽查 | ✅ `Discovery.class` 含 `subnetPrefixOf` / `sweepSubnet`；`PcMain$7.class`（启动扫描 Runnable）含新文案 |
| Android 构建 | ✅ `assembleRelease` 2.84 MB，dex 内含 `网段扫描` / `WiFi 未连接，已跳过扫描` / `局域网私有网段` |
| a12 装机 | ✅ `lastUpdateTime=2026-09-21 15:24:48`；a11y `ClipCaptureService` 仍在、`accessibility_enabled=1` |
| **待真机回归**（用户侧） | ① `10.x` 网段下能扫到对端（看日志 `网段扫描 10.x.y.1-254` → `发现 N 台设备`）；② 关掉 WiFi 后启动 = 文案 `WiFi 未连接，已跳过扫描`、日志不发包；③ 旧版对端仍能被扫到（协议零改动） |

## 已知限制

- **熄屏会被 ColorOS 冻结，并销毁应用的全部 socket**（2026-09-15 a16 实测，电源键后约 12s）：
  `OplusHansManager: notify state machine event=SM_EVENT_LCD_OFF_UNIFIED_FREEZE` →
  `freeze uid: 10520 com.phonepctransfer scene: LcdOff` → `destroyAllSocketForUid:10520`
  → 对端立刻读到 `Connection reset`（a12 侧日志 `PP-Link 读循环异常: SocketException`）。
  这条路径**不看前台服务类型**（实测 FGS 已是 `dataSync|mediaPlayback|camera`，`types=0x43`），
  **也挡不住无障碍绑定**（`importance=accessibility` 只能拦住普通的后台迁移 R→M，
  拦不住熄屏统一冻结 —— 日志里两种情况都能看到）。应用侧能做的只有：
  1. 回到前台 / 进程被系统重启后**自动重连**（见「连接与后台策略」；已知设备在后台也放行）；
  2. 用户侧把应用加进 ColorOS 的「电池 → 应用耗电管理 → 允许完全后台行为 / 忽略电池优化」白名单。
- **Android 15+ 强制 edge-to-edge**：`targetSdk 35` 起系统不再给应用留状态栏/导航栏内边距，Android 16 上
  `windowOptOutEdgeToEdgeEnforcement` 也彻底失效。应用侧必须自己做两件事，否则会出现「顶栏被状态栏压住」
  这类问题：
  1. `MainActivity.onCreate` 调 `enableEdgeToEdge()`，并把 `isNavigationBarContrastEnforced` /
     `isStatusBarContrastEnforced` 置 `false`（API 29+）——否则系统会在导航栏区域叠一层半透明黑，
     看起来像「底部有一条不属于应用的黑边」；
  2. 顶栏、底栏**各自**消费自己的 `WindowInsets`（`windowInsetsPadding(WindowInsets.statusBars)` /
     `navigationBars` / `ime`），同时把 `Scaffold(contentWindowInsets = WindowInsets(0,0,0,0))` 清空，
     避免中间内容区被重复加高一次。
  > **不要再给根布局加"额外的上下边距"**（2026-09-16 定稿，见「界面改版第五/六轮」）：edge-to-edge 下
  > `Surface(fillMaxSize)` 就该铺满全屏（圆角屏的四个角靠背景色填满），只有**顶部**需要让开通知栏，
  > 而且**通知栏那一条要显示顶栏自己的底色**——所以 `statusBars` inset 要**下沉到各面板的 `Surface`
  > 内部**（顶栏 / 左栏各自的 `Column`），不能挂在根 `Row` 上（挂根上会露出页面底色 `#14161A`）。
  > 给根 `Row` 再叠一份 padding 则会在顶部/底部各留一条异色空带，看起来像"背景没铺满"。
  > **顶部留白要收紧就拧 `TOP_ROW_H`（顶栏 + 左栏第一行的行高，现 36dp）**，不要动 inset ——
  > 动 inset 会把内容压进系统状态栏（各 OEM 图标位置不同，a16 实测图标画到 117px、a12 只到 82px）。
  > 踩坑记录：底栏当时让位正常、顶栏却重叠——原因是顶栏那处修改**没有真正落盘**（编辑未持久化）。
  > 改完务必回读校验（`grep` 一下那行还在不在），不要只信"编辑成功"的提示。
- **剪贴板读取需要窗口焦点**（Android 10+ 的系统限制）：失焦后 `hasPrimaryClip()` 静默返回 false。
  因此「复制完立刻离开应用」这种亚秒级场景，是靠剪贴板**变化回调**（复制瞬间应用仍有焦点）抢到的；
  而 `onWindowFocusChanged(false)` / `onPause` / `onUserLeaveHint` 里再读已经来不及，那条补推是尽力而为。
  内容不会丢——回到前台会立刻补推。
  > 注意这是**读**的限制。**写**剪贴板没有这个限制，所以同步模式下手机在后台照样能收到电脑复制的内容
  > （这正是「挂后台也能同步」能成立的原因）。
- **同步模式默认开启 = 电脑复制的内容会立刻写进手机剪贴板**，会覆盖手机上原有的剪贴板内容。
  这是「零手动操作」的代价；不想被覆盖就把它关掉，改用细项/手动按钮。
- 手机 → 电脑方向在手机**完全后台**时读不到剪贴板（系统限制，见上），只能靠「离开前补推」与「回到前台补推」。
- **PC 端改完代码要重启程序（`run_admin.bat`）才生效**——它跑的是 `dist/PhonePcTransfer-PC.jar`。
- **同一时刻只有一条活跃会话**：新握手会挤掉旧会话（去重规则见 `docs/PROTOCOL.md` §3）。
- 未做端到端加密：仅适用于可信局域网。
- Android 12 上 `hasPrimaryClip()` 在失焦时静默失败，不抛异常也不打日志，排查时容易误判成"没复制成功"。
- **手机 ↔ 手机也能连**（同一套协议、同样的去重规则），但不是主场景：剪贴板互拉靠双方互相应答 `clip_pull`，
  而"读剪贴板"仍受焦点限制——对端此刻不在前台时它只能回空，日志会显示「缓冲为空」。
  另外 `clip_notify`（PC 侧"只通知不带正文"的省流量优化）只有 PC 端会发，手机端不发。

## 调试

- 手机日志：`adb logcat -s PP-Link PP-AppCore PP-Service PP-LanGate`
- PC 日志：`pc/log.txt`（滚动日志）
  > ⚠️ PC 聊天窗底部那行状态小字 2026-09-21 已去掉（左栏底部只剩接收目录），
  > `ChatWindow.setSub(...)` / `setClipBuffer(...)` 仍在调用，但**写进的是不在组件树里的 JLabel**
  > —— 瞬时提示（复制成功 / 拖入失败 / 扫描结果）当前**没有界面落点**，只能看日志。
  > 要把它们接回界面就改 `setSub` 一处（顶栏第 3 行或状态栏）。
- 无界面协议自测：`java -cp pc/build/classes com.phonepctransfer.pc.TestClient <host> 8765 <idle|text|clip|file|pull|sync> [arg] [myId]`
  - 第 5 个参数是本端 `deviceId`。会话去重规则是 `preferOutgoing = (myId < peerId)`，
    要让手机保留你的连接，这个 id 必须排在手机 id（形如 `android-xxxxxxxx`）**之前**。

### 开发期抓 PC 界面截图（`pc/tools/`）

本机沙箱**禁止 PowerShell 的 `Add-Type`**（编译并加载 .NET 代码）→ System.Drawing 抓屏走不通；
跨进程 `SetWindowPos(HWND_TOPMOST)` 实测被系统拒绝（返回 0），窗口抬不到最前，
而本机 WorkBuddy 客户端是全屏窗口，普通抓屏只能拍到它。改用**同 JVM 内离屏渲染**：

```bash
JBR="C:/Program Files/Android/Android Studio/jbr"
# 1) 编译到**一次性 staging 目录**（⚠️ 先 rm -rf；用固定 build/classes 时忘了清 → io/ 被重复拷成 io/io/）
rm -rf build/jarstage && mkdir -p build/jarstage
"$JBR/bin/javac.exe" -encoding UTF-8 --release 17 -Xlint:none -cp lib/concentus-1.0.2.jar \
  -d build/jarstage @build/sources.txt
#    ⚠️ build/sources.txt 是显式清单，**新增 .java 必须重新生成**：
#    ls src/com/phonepctransfer/pc/*.java | sed 's|\\|/|g' | sort > build/sources.txt
# 2) 打进自包含类目录（concentus 无 JNI，直接展开进来）
mkdir -p build/concentus_tmp && "$JBR/bin/jar.exe" --extract --file lib/concentus-1.0.2.jar -C build/concentus_tmp
cp -r build/concentus_tmp/io build/jarstage/io && rm -rf build/concentus_tmp
"$JBR/bin/jar.exe" --create --file dist/PhonePcTransfer-PC.jar \
  --main-class com.phonepctransfer.pc.PcMain -C build/jarstage .
# 3) 打包后数条目：own=99 / concentus=141 / nested_io_io=0（出现 io/io/ 就是没清干净）
"$JBR/bin/jar.exe" --list --file dist/PhonePcTransfer-PC.jar | grep -c '^io/io/'
# 4) 同进程起 App 并把主窗口画进 PNG（被遮挡也拍得到），拍完自己退出
"$JBR/bin/javac.exe" -encoding UTF-8 --release 17 -cp "build/jarstage;lib/concentus-1.0.2.jar" \
  -d build/probe tools/UiProbe.java tools/UiTree.java
"$JBR/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "build/jarstage;build/probe;lib/concentus-1.0.2.jar" \
  com.phonepctransfer.pc.UiProbe 11000 ../shots/pc_ui.png
# 5) 查「文字有没有被容器裁掉 / 有没有对齐」——不看截图看数字
"$JBR/bin/java.exe" -Dfile.encoding=UTF-8 \
  -cp "build/jarstage;build/probe;lib/concentus-1.0.2.jar" \
  com.phonepctransfer.pc.UiTree 9000 | grep 'JLabel'   # 每个 label 的 text / prefW / 实际 x、宽
python tools/png_ink_scan.py                          # 量某种颜色文字的实际墨迹 x 范围
```

| 文件 | 用途 |
|---|---|
| `tools/UiProbe.java` | 同 JVM 起 App → `Frame.printAll(g)` 离屏渲染 → PNG。**不进**发布 jar（打包清单只收 `src/**`） |
| `tools/BubbleProbe.java` | 气泡自检：断言手势**只挂气泡本体**（整行 800px 而气泡仅 ~300px）、气泡内无常驻按钮；并打印右键菜单条目文案。传输出路径可出 `shots/bubble_proof.png` |
| `tools/ClearProbe.java` | 「清空」数据层自检（临时目录）：批量删历史**不串台**、待发只删命中项、单条版不回归 |
| `tools/ClassCheck.java` | 无 GUI 冒烟：`Class.forName(...,false,...)` 遍历 jar 里自家类（不跑静态块 → 不起 GUI），抓缺类/漏打 |
| `tools/UiWinProbe.java` | **同上但不跑 `PcMain`**：直接构造 `Store`/`Link`(不 start)/`ChatWindow` 离屏渲染 → PNG。**正式实例还开着时也能拍**（不抢守卫口 8767 / 不绑 28765 / 不发 UDP 广播；代价是设备行全灰点）。只看布局时用它 |
| `tools/UiTree.java` | 同 JVM 起 App 后 **dump 左栏组件树的真实尺寸**（`text / prefW / 实际 x·宽 / 字体`）。查「字被切了」「没对齐」这类问题靠它定性 |
| `tools/png_ink_scan.py` | 纯标准库解 PNG，量「某种颜色文字」的墨迹 x 范围 —— 判断有没有被容器裁掉。**肉眼看截图会误判**（实测 `stringWidth == prefW == 墨迹宽`，数字才作数） |
| `tools/InkProbe.java` | 单例验证：同一个字符串在 `JLabel` 里的 `stringWidth / prefW / 墨迹宽` 三者是否一致 |
| `tools/PcShot.java` | 备选：Java `Robot` 抓屏矩形（只在窗口可见且在顶层时有用，实测本机抬不起来） |
| `tools/FontProbe.java` | 字体探针（离屏、可无头）：可用族名清单 + `Fonts.FAMILY` 解析 + S2 各字号档度量 + 4 张 {宋体/雅黑}×{AA on/off} 对比 PNG |
| `tools/find_win.py` / `raise_win.py` / `win_dump.py` | ctypes 枚举 / 抬窗 / 诊断顶层窗口（已设 DPI 感知 → 返回物理像素，本机**系统**缩放 125%；程序自身钉 100%，见 §1.2） |

