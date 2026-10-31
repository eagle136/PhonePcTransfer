@echo off
rem ============================================================
rem  PhonePcTransfer PC build script (one click: jar + MSI)
rem  Compile with Android Studio JBR, target Java 17, then run
rem  make-msi.sh via Git Bash to produce the MSI in out\.
rem  NOTE: keep this file ASCII-only (no Chinese).
rem  Usage: run from project root (sources live in pc\src).
rem    build.bat            jar + MSI (APP_VER defaults to 1.0.1)
rem    build.bat 1.0.8      jar + MSI, override APP_VER
rem  For jar-only build use build-jar.bat instead.
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ----- 拒绝管理员运行（sh/Git Bash 在 UAC elevation 下路径解析会失败，导致"看不到任何输出"）-----
net session >nul 2>&1
if %errorlevel% == 0 (
  echo [WARN] 检测到以管理员身份运行 —— 会导致 Git Bash/sh 路径解析失败、看不到输出。
  echo         请关闭本窗口，改用普通用户运行: .\build.bat 1.0.8
  pause
  exit /b 1
)

rem Optional APP_VER override passed through to make-msi.sh
set "APP_VER="
if not "%~1"=="" set "APP_VER=%~1"
cd /d "%~dp0pc"

set "JBR=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JBR%\bin\javac.exe" (
  echo [ERROR] JDK not found: %JBR%\bin\javac.exe
  echo         Edit the JBR variable in this script to point to your JDK.
  exit /b 1
)

if not exist "build\classes" mkdir "build\classes"
if not exist "dist" mkdir "dist"

rem Voice call dependency: pure-Java Opus codec (concentus).
set "CONCENTUS=lib\concentus-1.0.2.jar"
if not exist "%CONCENTUS%" (
  echo [ERROR] Missing %CONCENTUS%
  exit /b 1
)

del /q "build\sources.txt" 2>nul
dir /s /b "src\*.java" > "build\sources.txt"

echo [1/2] Compiling Java sources ...
rem -J-Duser.language=en: force English javac messages (zh-CN locale prints
rem GBK Chinese notes that show as garbled text in non-GBK terminals/logs)
"%JBR%\bin\javac.exe" -J-Duser.language=en -J-Duser.country=US -encoding UTF-8 --release 17 -Xlint:none ^
  -cp "%CONCENTUS%" -d "build\classes" @"build\sources.txt"
if errorlevel 1 (
  echo [ERROR] Compile failed
  exit /b 1
)

echo [2/2] Packaging jar (bundling concentus classes) ...
rem Extract concentus classes into a temp dir, then copy only io/ into build\classes,
rem so the runnable jar is self-contained and our manifest stays intact.
if exist "build\concentus_tmp" rmdir /s /q "build\concentus_tmp"
mkdir "build\concentus_tmp"
"%JBR%\bin\jar.exe" --extract --file "%CONCENTUS%" -C "build\concentus_tmp"
if errorlevel 1 (
  echo [ERROR] Extract concentus failed
  exit /b 1
)
xcopy /e /i /y "build\concentus_tmp\io" "build\classes\io" >nul
if errorlevel 1 (
  echo [ERROR] Copy concentus classes failed
  exit /b 1
)
rmdir /s /q "build\concentus_tmp"
"%JBR%\bin\jar.exe" --create --file "dist\PhonePcTransfer-PC.jar" ^
  --main-class com.phonepctransfer.pc.PcMain ^
  -C "build\classes" .
if errorlevel 1 (
  echo [ERROR] Package failed
  exit /b 1
)

echo.
echo [3/3] Packaging MSI installer (jpackage + WiX, via Git Bash) ...
cd /d "%~dp0"

rem ----- locate Git Bash (must NOT be WSL bash; see make-msi.sh header) -----
set "GITROOT="
if exist "C:\Program Files\Git\bin\bash.exe" set "GITROOT=C:\Program Files\Git\"
if not defined GITROOT (
  for /f "delims=" %%G in ('where git 2^>nul') do (
    if not defined GITROOT if exist "%%~dpG..\bin\bash.exe" set "GITROOT=%%~dpG.."
  )
)
if not defined GITROOT (
  echo [ERROR] Git Bash not found. Install Git for Windows, or build MSI manually:
  echo         "C:\Program Files\Git\bin\bash.exe" make-msi.sh
  exit /b 1
)

rem Prepend Git usr\bin so msys `date` etc. win over System32 tools.
set "PATH=!GITROOT!usr\bin;%PATH%"
if not exist out mkdir out
set "LOG=%~dp0out\build-msi.log"
echo [%date% %time%] build.bat MSI step >> "%LOG%"
set "MSI_SH=%~dp0make-msi.sh"
"!GITROOT!bin\bash.exe" "!MSI_SH:\=/!" >> "%LOG%" 2>&1
if errorlevel 1 (
  echo [ERROR] MSI build failed
  echo ----- 日志末尾 -----
  type "%LOG%"
  pause
  exit /b 1
)

echo.
echo Build OK -^> pc\dist\PhonePcTransfer-PC.jar  +  out\ MSI installer
echo Start it with: pc\run_admin.bat
exit /b 0
