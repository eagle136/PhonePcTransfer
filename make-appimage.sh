#!/bin/bash
# 打 PC 端「免安装运行包」：jpackage --type app-image 直接产出自带 JRE 的可运行目录，
# 再用 tar 压成 zip。完全不碰 WiX（不调 candle/light），绕开 MSI 的字符集/ICE 问题。
# 产物：out/手机电脑互传-<APP_VER>.zip —— 解压后双击 手机电脑互传.exe 即可运行。
# 用法：/usr/bin/bash make-appimage.sh [APP_VER]
set -e
ROOT="$(cd "$(dirname "$0")" && pwd)"
PCROOT="$ROOT/pc"
cd "$PCROOT"

APP_VER="${APP_VER:-1.0.8}"
APPNAME="手机电脑互传"

# 内部版本号（仅写进 .exe 版本资源，app-image 无安装注册表约束，用构建时刻编码即可）
ts=$(date +%y%m%d%H%M)
PY=$(( 10#${ts:0:2} )); PM=$(( 10#${ts:2:2} ))
PB=$(( 10#${ts:4:2} * 1440 + 10#${ts:6:2} * 60 + 10#${ts:8:2} ))
VER="$PY.$PM.$PB"
echo "对外版本: $APP_VER   内部版本: $VER（构建时刻 $ts）"

cp -f dist/PhonePcTransfer-PC.jar installer/input/

if [ ! -f bin/ffmpeg.exe ]; then
  echo "[ERROR] 缺少 bin/ffmpeg.exe（视频解码必需）"
  echo "        从构建产物拷入：put a minimal ffmpeg.exe into pc/bin/"
  exit 1
fi
mkdir -p installer/input/bin
cp -f bin/ffmpeg.exe installer/input/bin/
echo "已包含 ffmpeg: $(ls -l installer/input/bin/ffmpeg.exe | awk '{print $5}') 字节"

# 系统 TEMP 下 .NET/light 建临时文件会被拒；统一指到工程内，构建完可删
BUILD_TMP="$PCROOT/build/tmp"
mkdir -p "$BUILD_TMP"
export TMP="$BUILD_TMP"
export TEMP="$BUILD_TMP"

# jpackage 用的 JDK：优先系统 Adoptium 21，否则回退内置
if [ -x "/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin/jpackage.exe" ]; then
  JP_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot"
else
  JP_HOME="$PCROOT/tools/jdk21/jdk-21.0.2"
fi
echo "使用 JDK: $JP_HOME"

# app-image 不调 WiX，不需要把 wix311 塞进 PATH
"$JP_HOME/bin/jpackage.exe" --type app-image --name "$APPNAME" \
  --app-version "$VER" --vendor eagle --icon tools/app.ico \
  --input installer/input --runtime-image tools/runtime \
  --main-jar PhonePcTransfer-PC.jar --dest installer/output \
  --java-options "-Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false" \
  -J-Duser.language=en -J-Duser.country=US

APPDIR="$PCROOT/installer/output/$APPNAME"
if [ ! -d "$APPDIR" ]; then
  echo "[ERROR] app-image 未生成: $APPDIR"
  exit 1
fi

PKGDIR="$ROOT/out"
mkdir -p "$PKGDIR"
ZIP="$PKGDIR/$APPNAME-$APP_VER.zip"
rm -f "$ZIP"
( cd "$PCROOT/installer/output" && tar -a -cf "$ZIP" "$APPNAME" )
echo "分发包: out/$APPNAME-$APP_VER.zip  ($(ls -l "$ZIP" | awk '{print $5}') 字节)"
echo "解压后运行: $APPNAME/$APPNAME.exe"
