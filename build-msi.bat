@echo off
rem ============================================================
rem  PhonePcTransfer PC MSI build.
rem  用法:
rem    build-msi.bat          默认 APP_VER=1.0.1
rem    build-msi.bat 1.0.8    指定对外版本号
rem
rem  ?? 必须以「普通用户」身份运行（不要右键"以管理员身份运行"）：
rem     管理员 UAC elevation 会让 Git Bash/sh 的盘符/路径解析失败，
rem     表现为"双击后一闪而过、看不到任何输出/报错"。本脚本会主动拒绝管理员运行。
rem
rem  全部输出同时写入 out\build-msi.log，窗口结束前会 pause 保持，便于排查。
rem  本构建不触碰 C:\Program Files、不写服务，普通用户权限足够。
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem ----- 拒绝管理员运行（sh/Git Bash 在 UAC elevation 下路径解析会失败）-----
net session >nul 2>&1
if %errorlevel% == 0 (
  echo [WARN] 检测到以管理员身份运行 —— 这会导致 Git Bash/sh 路径解析失败、"看不到任何输出"。
  echo         请关闭本窗口，改用普通用户运行：
  echo           1^) 打开普通 PowerShell / CMD（不要选"以管理员身份运行"）
  echo           2^) cd 到本目录: cd /d "%~dp0"
  echo           3^) 执行: .\build-msi.bat 1.0.8
  echo         或直接双击本文件（普通用户即可）。
  pause
  exit /b 1
)

if not exist "pc\dist\PhonePcTransfer-PC.jar" (
  echo [ERROR] pc\dist\PhonePcTransfer-PC.jar 不存在。先运行 build-jar.bat 或 build.bat。
  pause
  exit /b 1
)

rem ----- 定位 Git Bash（NOT WSL bash；见 make-msi.sh 头部说明）-----
set "GITROOT="
if exist "C:\Program Files\Git\bin\bash.exe" set "GITROOT=C:\Program Files\Git\"
if not defined GITROOT (
  for /f "delims=" %%G in ('where git 2^>nul') do (
    if not defined GITROOT if exist "%%~dpG..\bin\bash.exe" set "GITROOT=%%~dpG.."
  )
)
if not defined GITROOT (
  echo [ERROR] 未找到 Git Bash。请安装 Git for Windows，或手动运行：
  echo         "C:\Program Files\Git\bin\bash.exe" make-msi.sh
  pause
  exit /b 1
)

rem 把 Git 的 usr\bin 提前，避免 System32 的 date/sort 等抢走
set "PATH=!GITROOT!usr\bin;%PATH%"

if not exist out mkdir out
set "LOG=%~dp0out\build-msi.log"
echo [%date% %time%] build-msi start ^(APP_VER=%1^) > "%LOG%"

if not "%~1"=="" set "APP_VER=%~1"

echo [*] 运行 make-msi.sh ...（完整日志写入 %LOG%）
"!GITROOT!bin\bash.exe" "%~dp0make-msi.sh" >> "%LOG%" 2>&1
set RC=%errorlevel%

echo.
echo ===== build-msi 输出已保存到 %LOG% =====
if %RC% == 0 (
  echo [OK] MSI 构建成功 -^> out\
) else (
  echo [ERROR] MSI 构建失败 ^(rc=%RC%^)
  echo ----- 日志末尾 -----
  type "%LOG%"
)
pause
exit /b %RC%
