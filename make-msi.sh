#!/bin/bash
# 打 PC 端 MSI 安装包（含运行时/快捷方式/目录选择）。
# ⚠️ 必须用 Git Bash 执行：/usr/bin/bash make-msi.sh
#    （裸 `bash` 会解析到 system32\bash.exe 即 WSL，PATH/路径全不对）
# 内部版本号按构建时刻（YYMMDDHHMM）编码递增 —— Windows Installer 相同 ProductVersion 不做
# 覆盖安装、低版本直接拒装，所以每次构建都必须比上一次大，jpackage 生成的 MSI 自带
# MajorUpgrade 会自动卸旧装新。对外版本号由 APP_VER 控制（默认 1.0.1）。
# 用法：先把最新 jar 放好（pc/dist/PhonePcTransfer-PC.jar），再跑本脚本；产物拷进根目录 out/。
set -e
# 自动适配执行环境：Git Bash 下 pwd=/c/...，WSL bash 下 pwd=/mnt/c/...（bash 命令可能解析到 WSL）
ROOT="$(cd "$(dirname "$0")" && pwd)"
# ⚠️ 父进程 cwd 是 Windows 形式时（从 IDE/命令行拉起的 shell 常见），pwd 会返回
#    `C:/Users/...`。这种带**盘符冒号**的路径拼进 PATH，cygwin 反向转换 POSIX PATH 时
#    按 `:` 拆条目，会把这一条切成 `C` + `/Users/...` 两段垃圾 → jpackage 报
#    「Can not find WiX tools」(2026-09-30 实测)。用 cygpath 归一回 /c/... 形式。
if command -v cygpath >/dev/null 2>&1; then
  ROOT="$(cygpath -u "$ROOT")"
fi
PCROOT="$ROOT/pc"
cd "$PCROOT"

# WiX 3.11 工具链：默认用项目内 tools/wix311；CI 可用 PP_WIX_DIR 指定解压目录。
WIX_DIR="${PP_WIX_DIR:-$PCROOT/tools/wix311}"
export PATH="$WIX_DIR:$PATH"

# ===== 版本号（2026-09-21 改版）=====
# 对外版本（交付口径 / 文件名）：APP_VER，默认 1.0.1。
#   覆盖：APP_VER=1.0.2 /usr/bin/bash make-msi.sh
APP_VER="${APP_VER:-1.0.1}"

# 内部 ProductVersion 必须严格递增（Windows Installer 同版本不覆盖安装、低版本直接拒装），
# 而三段都有限制：major/minor <= 255、build <= 65535 —— 10 位时刻戳「YYMMDDHHMM」
# （如 2609211547）塞不进三段，所以按**构建时刻**编码：
#     YYMMDDHHMM = 2609211547  →  26 . 9 . 31187
#                                    └YY  └MM   └ DD*1440 + HH*60 + MM（当月第几分钟）
# 三段天然严格递增：月变 → minor 变大；同月内 build 单调变大（31 日上限 46079 < 65535）。
# 且 26.x.x 远大于历史 1.0.6281 → jpackage 自带 MajorUpgrade 覆盖升级照常工作。
# ⚠️ 同一分钟内重建会算出同一个号（= 不覆盖安装）→ 再兜一道：取 output 里已有最大号 +1。
ts=$(date +%y%m%d%H%M)
PY=$(( 10#${ts:0:2} )); PM=$(( 10#${ts:2:2} ))
PB=$(( 10#${ts:4:2} * 1440 + 10#${ts:6:2} * 60 + 10#${ts:8:2} ))
# ⚠️ 不要用 `sort -n`：Git Bash 里 sort 会被 C:\Windows\System32\sort.exe 抢走
#    （和 find / timeout 同一类陷阱），Windows sort 不认 -n，管道静默变空。
#    这里用纯 bash 循环取最大值。
lastA=0; lastB=0; lastC=0
for f in installer/output/*.msi; do
  [ -f "$f" ] || continue
  base="${f##*/}"; v="${base%.msi}"; v="${v##*-}"
  case "$v" in *[!0-9.]*) continue ;; esac
  [ "$(printf '%s' "$v" | tr -cd '.' | wc -c)" = 2 ] || continue
  a="${v%%.*}"; rest="${v#*.}"; b="${rest%%.*}"; c="${rest#*.}"
  if [ "$a" -gt "$lastA" ] \
     || { [ "$a" -eq "$lastA" ] && [ "$b" -gt "$lastB" ]; } \
     || { [ "$a" -eq "$lastA" ] && [ "$b" -eq "$lastB" ] && [ "$c" -gt "$lastC" ]; }; then
    lastA=$a; lastB=$b; lastC=$c
  fi
done
if [ "$lastA" -gt "$PY" ] \
   || { [ "$lastA" -eq "$PY" ] && [ "$lastB" -gt "$PM" ]; } \
   || { [ "$lastA" -eq "$PY" ] && [ "$lastB" -eq "$PM" ] && [ "$lastC" -ge "$PB" ]; }; then
  echo "已有最大版本 $lastA.$lastB.$lastC 不低于本次时刻号 $PY.$PM.$PB → 递增"
  PY=$lastA; PM=$lastB; PB=$(( lastC + 1 ))
fi
# ⚠️ INTERNAL_VER：强制内部 ProductVersion 与对外版本号同号（如 1.1），
#   仅在交付要求「内外一致」时用：APP_VER=1.1 INTERNAL_VER=1.1 bash make-msi.sh
#   代价 —— Windows Installer 认的是内部号：同号不覆盖安装、低版本直接拒装，
#   所以固定成 1.1 之后 ① 下次改版必须换更大的内部号，
#   ② 装过「内部 26.x」那版 1.1 的机器会被当成降级拒装，需先卸载。
VER="${INTERNAL_VER:-$PY.$PM.$PB}"
echo "对外版本: $APP_VER    MSI ProductVersion: $VER（构建时刻 $ts）"

mkdir -p installer/input
cp -f dist/PhonePcTransfer-PC.jar installer/input/

# ===== 视频解码件：最小化 ffmpeg（PC 端只收视频，必须自带解码器）=====
# 本机无系统 HEVC 解码 MFT，随包带一个裁剪版 ffmpeg.exe（2.89 MB，静态单文件）。
# jpackage 会把 --input 目录的内容摊到应用根目录，因此这里放 bin/ 即应用内的 <app>/bin/。
# ⚠️ 2026-09-21 起数据目录搬到 %APPDATA%\PhonePcTransfer，ffmpeg 不再跟数据目录绑定：
#    FfmpegDecoder.bundledPath() 从**运行时代码所在位置**往上找 bin/ffmpeg.exe
#    （安装版 = <install>/app/bin/，源码版 = pc/bin/）。
if [ ! -f bin/ffmpeg.exe ]; then
  echo "[ERROR] 缺少 bin/ffmpeg.exe（视频解码必需）"
  echo "        从构建产物拷入：put a minimal ffmpeg.exe into pc/bin/"
  exit 1
fi
mkdir -p installer/input/bin
cp -f bin/ffmpeg.exe installer/input/bin/
echo "已包含 ffmpeg: $(ls -l installer/input/bin/ffmpeg.exe | awk '{print $5}') 字节"

# ===== 入站放行：随包带一条"程序级"规则脚本（任务单 2.13 / 方案 §4.6 / S26）=====
# 今天这个脚本一条防火墙规则都没有：MSI 装完后 PC 侧入向全靠运气（run.bat / run_admin.bat
# 是源码/jar 那一侧的入口，装进机器的应用里没有它们）。
# 形状与 pc/run.bat 的 addrules 分支**一致**：一条规则、按程序、不带 protocol/localport、
# profile=any，并且**先 delete 旧的按端口规则**（残留会遮蔽新规则）。
# 本机实测端口规则一共**四条**（28765/28766 + v2.1 端口迁移之前的 8765/8766）⇒ 四条全 delete，
# 只清当前两条会把 8765/8766 那个旧口子留在防火墙里。
# 差别只有一个：这里的 program= 指 jpackage 产出的分发 exe（出 MSI 那一轮才该指 exe，S26），
# 路径由脚本自己的位置推出来（本文件落在 <install>\app\，exe 在 <install>\），不在构建期写死。
# ⚠ "安装阶段静默加规则"要 jpackage --resource-dir 覆盖 main.wxs 加自定义操作，本轮不出 MSI、
#   没法验证 ⇒ 落成"随包一条脚本 + 装完提权跑一次"，形状差异已回写任务单 2.13 块等你点。
FW_CMD="$PCROOT/installer/input/firewall-addrules.cmd"
sed 's/$/\r/' > "$FW_CMD" <<'FWEOF'
@echo off
rem  PhonePcTransfer - one-time program-level inbound firewall rule (installed build).
rem  Same shape as pc\run.bat's "addrules" branch: ONE rule, by program, no
rem  protocol/localport, profile=any, stale port rules deleted first.
rem  program= is the launcher this MSI installs, resolved from this file's own
rem  location: this helper sits in <install>\app\, the launcher is one level up.
rem  Run it ONCE from an elevated window (right-click > Run as administrator).
setlocal
for %%I in ("%~dp0..\PhonePcTransfer.exe") do set "EXE=%%~fI"
if not exist "%EXE%" (
  echo [ERROR] Launcher not found: %EXE%
  echo         Keep this file inside the application's app folder.
  pause
  exit /b 1
)
netsh advfirewall firewall delete rule name="PhonePcTransfer TCP 28765" >nul 2>&1
netsh advfirewall firewall delete rule name="PhonePcTransfer UDP 28766" >nul 2>&1
netsh advfirewall firewall delete rule name="PhonePcTransfer TCP 8765" >nul 2>&1
netsh advfirewall firewall delete rule name="PhonePcTransfer UDP 8766" >nul 2>&1
netsh advfirewall firewall delete rule name="PhonePcTransfer" >nul 2>&1
netsh advfirewall firewall add rule name="PhonePcTransfer" dir=in action=allow program="%EXE%" enable=yes profile=any >nul
rem  read it back - netsh can report success and still leave nothing usable behind
rem  (not elevated / service refused); a silent no-op is exactly what makes every
rem  call fall back to the inband tier without anyone noticing (S27).
netsh advfirewall firewall show rule name="PhonePcTransfer" 2>nul | findstr /i /c:"%EXE%" >nul 2>&1 && set "ADD_OK=1"
if defined ADD_OK (
  echo Inbound rule in place for "%EXE%"
) else (
  echo [ERROR] Rule NOT in place - run this from an ELEVATED window.
)
echo        All inbound ports, allowed by program. Re-running just refreshes it.
pause
if defined ADD_OK exit /b 0
exit /b 1
FWEOF
if [ ! -s "$FW_CMD" ]; then
  echo "[ERROR] 没能生成 installer/input/firewall-addrules.cmd"
  exit 1
fi
echo "已生成入站放行脚本: installer/input/firewall-addrules.cmd ($(wc -c < "$FW_CMD") 字节，装在 <install>\\app\\ 下)"


# ===== 构建临时目录：指到项目内 build/tmp =====
# 本机系统 TEMP（AppData\Local\Temp）下 .NET 的 light.exe 建 .tmp 文件会被拒
# （System.UnauthorizedAccessException），jpackage 自身的暂存目录同理 → 统一改到工程内，
# 构建完可整目录删。
BUILD_TMP="$PCROOT/build/tmp"
mkdir -p "$BUILD_TMP"
export TMP="$BUILD_TMP"
export TEMP="$BUILD_TMP"

# ===== jpackage 用的 JDK =====
# 优先级：PP_JDK_HOME 环境变量（CI 用）> 本机安装的 Adoptium JDK 21 > 项目内置 tools/jdk21。
# 内置版的 NIO 访问检查在本机曾失效（Files.isWritable 恒 false → jpackage 误报
# "Destination directory ... is not writable"），所以本机优先用系统安装版。
if [ -n "${PP_JDK_HOME:-}" ]; then
  JP_HOME="$PP_JDK_HOME"
elif [ -x "/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin/jpackage.exe" ]; then
  JP_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
else
  JP_HOME="$PCROOT/tools/jdk21/jdk-21.0.2"
fi
if [ ! -x "$JP_HOME/bin/jpackage.exe" ]; then
  echo "[ERROR] 找不到 jpackage: $JP_HOME/bin/jpackage.exe"
  echo "        设置 PP_JDK_HOME 指向 JDK 21+，或把 JDK 放到 pc/tools/jdk21/"
  exit 1
fi

# ===== BEGIN wxi-inject（完成页「立即运行」+ 卸载时「是否删用户数据」）=====
# 两条功能都落在 MSI 的 WiX 层，Java 业务代码一行不动：
#   (A) 完成页复选项：WixUI_InstallDir 的 ExitDialog 自带 OptionalCheckBox，只要设了
#       WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT 这条属性就多出一个复选项，再配 WixShellExec
#       点「完成」时拉起 exe。**默认勾选不能白蹭** —— 实测（p12.msi 查表）WiX 只给了控件
#       Attributes=2，Property 表里压根没有 WIXUI_EXITDIALOGOPTIONALCHECKBOX 这条行，
#       所以本段显式写 Value="1"，让「进入完成页即为已勾选」有据可依。
#   (B) 卸载询问：两条 **immediate**（非 deferred）自定义动作拉起同一段内联 PowerShell，
#       区别只在构建期烤进载荷的第一行环境变量 PHONEPCTRANSFER_UNINSTALL_ASK=1/0，
#       以及互斥的调度条件 `UILevel > 2`（有界面：弹窗问「是否删用户数据」）/
#       `UILevel <= 2`（/qn 无人值守：不问，保留数据，只清理快捷方式残留）。
#       内联方式：pc/tools/uninstall-ask.ps1 整份文件 UTF-8 →（去 BOM）→ UTF-16LE → base64
#       塞进 -EncodedCommand。MSI 数据库里因此只有 ASCII，脚本正文的中文注释/弹窗文案
#       不受 codepage 1252 限制（直接写中文会让 light.exe 报 LGHT0311）。
#
# ⚠⚠ 注入点必须在 <Product> **内部** —— 本段因此连 main.wxs 一起生成。实测结论
#   （2026-10-05：最小 MSI + msiexec /l*v 实跑一轮）：
#     · jpackage 默认 main.wxs 把 include 指令放在 <Wix> 里、<Product> **之前**。WiX 的
#       <Wix> 内容模型要求 Product 先出现：先出现 Fragment 再写 Product 直接
#       CNDL0107「'Wix' 的子元素 'Product' 无效」（不经过 include，单文件里把
#       <Fragment/> 写在 <Product/> 前面也是同一条错）。
#     · 就算把 include 挪到 </Product> **之后**（仍在 <Wix> 里，编译能过），light 也不会把
#       Fragment 里的 CustomAction / InstallExecuteSequence 落进表：卸载日志里执行序列从
#       UnpublishFeatures 直接跳到 RemoveFiles，两条 Jp* 动作压根没被调度。
#   ⇒ 可用形状只有一个：include 挪进 <Product>，overrides.wxi 的 <Include> 里**直接**写
#     Property / CustomAction / UI / InstallExecuteSequence（Product 级子元素，外面不包
#     Fragment）。Oracle 自己的 service-install.wxi 也是这个用法（<Include> 里放 Component 级元素）。
# ⚠ .wxi 的根元素必须是 <Include>：写成 <Wix> 文档 candle 报 CNDL0048。
# ⚠ (A) 的启动目标必须**运行时格式化**：Property 表里的值是字面量，直接写
#   Value="[INSTALLDIR]PhonePcTransfer.exe" 不会被 Windows Installer 展开（candle 还顺便报
#   CNDL1077），WixShellExec 拿着字面 "[INSTALLDIR]..." 去 ShellExecute → 静默失败。
#   所以先 Publish 一条 type-51 动作把格式化后的路径写进 WixShellExecTarget，再 Publish
#   启动动作（type-51 的 Value 由 MSI 在运行时格式化 —— [REMOVE]/[LogonUser] 那批已在
#   最小 MSI 上实测过）。
# ⚠ 启动动作 Return="ignore"：Return="asyncNoWait" 只能配 ExeCommand 用，BinaryKey 形式的
#   WixShellExec 写它会报 CNDL0038；WixShellExec 本身走 ShellExecute，启动即返回，不会把
#   完成页卡在应用退出上。
#
# ⚠⚠ (B) 为什么是 immediate + ExeCommand，而不是任务进度文件里原计划的 deferred WixQuietExec
#   （2026-10-05 全部在最小 MSI 上实测，probe2/4/9/10/11，日志见 pc/build/tmp/wxichk/un*.txt）：
#     · deferred WixQuietExec 跑在「custom action server」里：父进程命令行读不到（恒空）、
#       拿不到交互桌面 → **弹不出窗**，「卸载时问一句」这条需求在 deferred 下做不到。
#     · immediate（ExeCommand + Directory + Return="ignore"，MSI 类型 34/98）跑在用户会话里：
#       实测 [Environment]::UserInteractive=True、SessionId=1、桌面=用户真实桌面，
#       脚本里 MessageBox.Show 的窗口能被外部 UIAutomation 枚举到并 BM_CLICK 点掉，
#       动作是**同步**的（未点掉前 MSI 一直等在这条动作上，实测等了 2 分 13 秒）→ 答案拿得到。
#     · WixQuietExec 不能改成 immediate：实测报 0x80070057「Failed to get command line data」
#       （它只认 deferred 的 CustomActionData）。所以用 ExeCommand 型。
#     · 静默判定不能再用父进程命令行 / UILevel 属性值本身：
#         - 自定义动作里 Get-CimInstance 读到的父进程 msiexec 的 CommandLine **恒为空**；
#         - /x（交互）与 /qb 在执行上下文里 UILevel 都=3，看起来分不出；
#         - 但 /qn 的日志里 `Property(S): UILevel = 2`，交互 /x 是 3 → **条件里用 UILevel 可靠**，
#           所以把「问不问」交给 MSI 的条件求值（两条互斥动作），不在脚本里自己猜。
#     · 载荷只能整份内联进 -EncodedCommand：命令行里**不能**挂位置参数（实测 rc=1 报
#       「已使用 -Command 或 -EncodedCommand 指定了命令」），也不能改成 -Command + iex 拼参数，
#       因为命令线里的 `[...]` 会被 MSI 当作属性引用吃掉 —— 实测 `[Text.Encoding]` 被替换成空，
#       命令行变成 `iex(::Unicode.GetString(::FromBase64String('...')))` 直接语法错误；
#       `[[Text.Encoding]]` 也不是转义写法（同样失败）。⇒ 命令行里除了故意写的
#       [System64Folder]/[INSTALLDIR] 这类真属性，别放任何方括号。
RES_DIR="$PCROOT/installer/resource"
ASK_PS="$PCROOT/tools/uninstall-ask.ps1"
if [ ! -f "$ASK_PS" ]; then
  echo "[ERROR] 缺少卸载询问脚本: $ASK_PS"
  echo "        MSI 的卸载自定义动作要把整份脚本 base64 内联，没有它打不出包。"
  exit 1
fi
for t in iconv base64; do
  command -v "$t" >/dev/null 2>&1 || { echo "[ERROR] 需要 $t（把 uninstall-ask.ps1 转成 UTF-16LE base64）"; exit 1; }
done
mkdir -p "$RES_DIR"

# ---- 1) main.wxs 覆盖：从**本次真正使用的 JDK** 现取现改，只挪 include 那一行 ----
# 不把 Oracle 模板抄进仓库（21.0.2 与 Adoptium 21.0.12 的模板可能不同，抄一份就会漂）。
JMOD="$JP_HOME/jmods/jdk.jpackage.jmod"
JMOD_X="$BUILD_TMP/jpmod-x"
if [ ! -f "$JMOD" ] || [ ! -x "$JP_HOME/bin/jmod.exe" ]; then
  echo "[ERROR] 取不到 $JMOD 或 jmod.exe：无法生成 main.wxs 覆盖（注入点挪不进 <Product>）"
  exit 1
fi
rm -rf "$JMOD_X"
"$JP_HOME/bin/jmod.exe" extract --dir "$JMOD_X" "$JMOD" >/dev/null
TMPL_WXS="$JMOD_X/classes/jdk/jpackage/internal/resources/main.wxs"
if [ ! -f "$TMPL_WXS" ]; then
  echo "[ERROR] jmod 解包后没找到 main.wxs：$TMPL_WXS"
  rm -rf "$JMOD_X"; exit 1
fi
awk '
  /overrides\.wxi/ { next }                                   # 丢掉原位置（<Product> 之前）那行
  /<\/Product>/ && !moved {
    print "  <?include $(var.JpConfigDir)/overrides.wxi ?>"   # 改插进 <Product> 结尾
    print
    moved = 1
    next
  }
  { print }
' "$TMPL_WXS" > "$RES_DIR/main.wxs"
rm -rf "$JMOD_X"
n_inc=$(grep -c 'overrides\.wxi' "$RES_DIR/main.wxs")
inc_ln=$(grep -n 'overrides\.wxi' "$RES_DIR/main.wxs" | head -1 | cut -d: -f1)
open_ln=$(grep -n '<Product' "$RES_DIR/main.wxs" | head -1 | cut -d: -f1)
close_ln=$(grep -n '</Product>' "$RES_DIR/main.wxs" | head -1 | cut -d: -f1)
if [ "$n_inc" != "1" ] || [ -z "$open_ln" ] || [ -z "$close_ln" ] \
   || [ "$inc_ln" -le "$open_ln" ] || [ "$inc_ln" -ge "$close_ln" ]; then
  echo "[ERROR] main.wxs 覆盖没做好：overrides.wxi 的 include 没落在 <Product> 内"
  echo "        include 行=$inc_ln <Product> 行=$open_ln </Product> 行=$close_ln include 条数=$n_inc"
  echo "        （JDK 模板形状变了，按 jmod 里的 main.wxs 调整上面的 awk）"
  exit 1
fi
echo "已生成 main.wxs 覆盖: installer/resource/main.wxs（include 挪到第 $inc_ln 行，位于 Product 内 $open_ln–$close_ln）"

# ---- 2) overrides.wxi：Product 级内容（不包 Fragment） ----
# 去 BOM：PowerShell 认 BOM，但 -EncodedCommand 的载荷必须正好是 UTF-16LE 字节。
BODY=$(sed '1s/^\xEF\xBB\xBF//' "$ASK_PS")
# 两条动作跑同一段脚本，只有烤进载荷**第一行**的环境变量不同（命令行挂不了位置参数，见上面的实测）。
PRE_ASK=$(printf '$env:PHONEPCTRANSFER_UNINSTALL_ASK="1";\n%s' "$BODY")
PRE_SIL=$(printf '$env:PHONEPCTRANSFER_UNINSTALL_ASK="0";\n%s' "$BODY")
B64ASK=$(printf "%s" "$PRE_ASK" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)
B64SIL=$(printf "%s" "$PRE_SIL" | iconv -f UTF-8 -t UTF-16LE | base64 -w0)
for v in B64ASK B64SIL; do
  val=${!v}
  case "$val" in
    "" | *[!A-Za-z0-9+/=]*) echo "[ERROR] base64 结果异常（$v 长度 ${#val}）"; exit 1 ;;
  esac
done
# MSI 的 CustomAction 命令行字段上限 32767 字符：载荷超了要在构建期就拦下来，
# 别让 light.exe 静默截断成一条跑不起来的命令行。
if [ "${#B64ASK}" -gt 32000 ] || [ "${#B64SIL}" -gt 32000 ]; then
  echo "[ERROR] 内联载荷过长（ask=${#B64ASK} silent=${#B64SIL}），装不进一条自定义动作命令行"
  exit 1
fi
PS_ASK="&quot;[System64Folder]WindowsPowerShell\\v1.0\\powershell.exe&quot; -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand $B64ASK"
PS_SIL="&quot;[System64Folder]WindowsPowerShell\\v1.0\\powershell.exe&quot; -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -EncodedCommand $B64SIL"
cat > "$RES_DIR/overrides.wxi" <<WXIEOF
<?xml version="1.0" encoding="utf-8"?>
<!--
  GENERATED BY make-msi.sh - DO NOT EDIT THIS FILE.
  Payload source: pc/tools/uninstall-ask.ps1 ($(wc -c < "$ASK_PS") bytes;
  UTF-16LE base64 inline: ask=${#B64ASK} chars, silent=${#B64SIL} chars).
  Spliced by installer/resource/main.wxs INSIDE the Product element (jpackage's stock
  template includes this file before <Product>, where WiX rejects our content), so the
  body holds Product-level children: Property / CustomAction / UI / InstallExecuteSequence.
-->
<Include>
  <!-- (A) Finish page "run now" checkbox (checked by default) -->
  <Property Id="WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT" Value="立即运行 PhonePcTransfer" />
  <Property Id="WIXUI_EXITDIALOGOPTIONALCHECKBOX" Value="1" />
  <CustomAction Id="JpSetLaunchTarget" Property="WixShellExecTarget"
                Value="[INSTALLDIR]PhonePcTransfer.exe" />
  <CustomAction Id="JpLaunchApp" BinaryKey="WixCA" DllEntry="WixShellExec"
                Impersonate="yes" Return="ignore" />
  <UI>
    <Publish Dialog="ExitDialog" Control="Finish" Event="DoAction" Value="JpSetLaunchTarget"
             Order="1">WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 and NOT Installed</Publish>
    <Publish Dialog="ExitDialog" Control="Finish" Event="DoAction" Value="JpLaunchApp"
             Order="2">WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 and NOT Installed</Publish>
  </UI>

  <!-- (B) uninstall: ask whether to delete user data, but only when there is UI.
       Same script twice; the baked PHONEPCTRANSFER_UNINSTALL_ASK flag + these mutually
       exclusive UILevel conditions decide whether a window may pop up.
       Return="ignore": a broken/skipped script must never fail the uninstall. -->
  <CustomAction Id="JpAskData" Directory="TARGETDIR" Execute="immediate" Return="ignore"
                ExeCommand="$PS_ASK" />
  <CustomAction Id="JpSilentResidue" Directory="TARGETDIR" Execute="immediate" Return="ignore"
                ExeCommand="$PS_SIL" />
  <InstallExecuteSequence>
    <Custom Action="JpAskData" Before="RemoveFiles">REMOVE="ALL" AND UILevel &gt; 2 AND NOT UPGRADINGPRODUCTCODE</Custom>
    <Custom Action="JpSilentResidue" Before="RemoveFiles">REMOVE="ALL" AND UILevel &lt;= 2 AND NOT UPGRADINGPRODUCTCODE</Custom>
  </InstallExecuteSequence>
</Include>
WXIEOF
if [ ! -s "$RES_DIR/overrides.wxi" ]; then
  echo "[ERROR] 没能生成 installer/resource/overrides.wxi"
  exit 1
fi
echo "已生成 WiX 覆盖: installer/resource/overrides.wxi ($(wc -c < "$RES_DIR/overrides.wxi") 字节，内联载荷 ask=${#B64ASK} / silent=${#B64SIL} 字符)"
# ===== END wxi-inject =====

# -J-Duser.language=zh / -J-Duser.country=CN：这对参数同时决定两件事 ——
#   ① jpackage 自己的控制台输出语言；② **安装器语言**：jpackage 据此给 light 传 -cultures，
#      并从 jmod 取对应的 MsiInstallerStrings_<lang>.wxl，安装向导的按钮/文案跟着变中文。
# ⚠️ 不能改回 en-US：en-us 的 MsiInstallerStrings_en.wxl 里写着 Codepage="1252"，WiX 用它
#    覆盖 Product/@Codepage（candle 垫片注入的 936），数据库被拉回 1252 → 任何中文都让
#    light 报 LGHT0311（实测 2026-10-30，light 退出码 311）。zh-CN 的 zh_CN.wxl 是 936，
#    中文能进 MSI 数据库。显式钉死语言，构建机本身是英文 locale 也不受影响。
# 产品名/菜单组仍用 ASCII 的 "PhonePcTransfer"（exe/安装目录/脚本引用都不动）。
# --win-shortcut：安装时就落一个**桌面**快捷方式（实测会在 MSI 里加一条 DesktopFolder +
#   JP_INSTALL_DESKTOP_SHORTCUT 组件；不给这条就只剩开始菜单，用户装完在桌面上找不到入口）。
#   名字只能是英文 PhonePcTransfer.lnk，中文名由 PcMain.ensureDesktopShortcut() 在应用
#   **首次启动**时改成「手机电脑互传.lnk」（.lnk 是文件系统对象，与 MSI codepage 无关）。
# --resource-dir：jpackage 会先在资源目录里找同名模板，找到就用它，否则回 jmod 取默认版
#   （OverridableResource 认 main.wxs / overrides.wxi / 各对话框 .wxs / MsiInstallerStrings_*.wxl）。
#   上面 BEGIN wxi-inject 段就是靠它把两份文件塞进去：main.wxs（只把 include 指令挪进
#   <Product>）+ overrides.wxi（Product 级内容）。其余模板继续走 JDK 默认，避免把
#   Oracle 的对话框/字符串资源抄进仓库。
mkdir -p "$PCROOT/installer/output"
"$JP_HOME/bin/jpackage.exe" --type msi --name "PhonePcTransfer" \
  --app-version "$VER" --vendor eagle --description "Phone PC Transfer" --icon tools/app.ico \
  --input installer/input --runtime-image tools/runtime --resource-dir "$PCROOT/installer/resource" \
  --main-jar PhonePcTransfer-PC.jar --dest installer/output \
  --win-dir-chooser --win-shortcut --win-menu --win-menu-group "PhonePcTransfer" \
  --win-per-user-install --java-options "-Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false" \
  -J-Duser.language=zh -J-Duser.country=CN

ls -l installer/output/

# ===== 交付副本：拷进根目录 out/，按对外版本号命名 =====
PKGDIR="$ROOT/out"
mkdir -p "$PKGDIR"
# jpackage 用 --name "PhonePcTransfer" 产出 ASCII 文件名，这里再改名回中文交付名（仅文件名，不进 MSI 数据库）
cp -f "installer/output/PhonePcTransfer-$VER.msi" "$PKGDIR/手机电脑互传-$APP_VER.msi"
echo "分发包: out/手机电脑互传-$APP_VER.msi  ($(ls -l "$PKGDIR/手机电脑互传-$APP_VER.msi" | awk '{print $5}') 字节)"
echo "内部 ProductVersion: $VER（面板「应用」列表里显示的就是它）"
