@echo off
rem ============================================================
rem  PhonePcTransfer PC 免安装运行包（app-image -> zip）。
rem  不依赖 WiX，绕开 MSI 的字符集/ICE 问题；解压即运行。
rem  需先 build.bat / build-jar.bat 产出 pc\dist\PhonePcTransfer-PC.jar。
rem  用法:
rem    build-appimage.bat         默认对外版本 1.0.8
rem    build-appimage.bat 1.0.8   指定对外版本
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

if not exist "pc\dist\PhonePcTransfer-PC.jar" (
  echo [ERROR] pc\dist\PhonePcTransfer-PC.jar not found. Run build.bat / build-jar.bat first.
  exit /b 1
)

rem ----- locate Git Bash -----
set "GITROOT="
if exist "C:\Program Files\Git\bin\bash.exe" set "GITROOT=C:\Program Files\Git\"
if not defined GITROOT (
  for /f "delims=" %%G in ('where git 2^>nul') do (
    if not defined GITROOT if exist "%%~dpG..\bin\bash.exe" set "GITROOT=%%~dpG.."
  )
)
if not defined GITROOT (
  echo [ERROR] Git Bash not found. Install Git for Windows, or run manually:
  echo         "C:\Program Files\Git\bin\bash.exe" make-appimage.sh
  exit /b 1
)

set "PATH=!GITROOT!usr\bin;%PATH%"
if "%~1"=="" (set "APP_VER=1.0.8") else (set "APP_VER=%~1")
set "MSI_SH=%~dp0make-appimage.sh"
"!GITROOT!bin\bash.exe" "!MSI_SH:\=/!"
if errorlevel 1 (
  echo [ERROR] app-image build failed
  exit /b 1
)
echo.
echo app-image build OK -^> out\
exit /b 0
