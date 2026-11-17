@echo off
rem ============================================================
rem  PhonePcTransfer - one-click build: Android APK + PC jar + PC MSI
rem
rem  Artifacts - everything lands flat in <root>\out:
rem    app-release.apk            Android release APK
rem    PhonePcTransfer-PC.jar     runnable jar - same file pc\run.bat starts
rem    *.msi                      Windows installer
rem    build-msi.log              MSI build log
rem
rem  Usage (run from project root, NORMAL user - NOT administrator):
rem    build-all.bat              jar + APK + MSI, APP_VER = make-msi.sh default
rem    build-all.bat 1.1.7        same, MSI external version = 1.1.7
rem
rem  NOTE: keep this file ASCII-only (cmd.exe GBK mojibake pitfall).
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "ROOT=%~dp0"
set "APP_VER="
if not "%~1"=="" set "APP_VER=%~1"

rem ----- refuse to run elevated: Git Bash / jpackage break under UAC elevation -----
net session >nul 2>&1
if %errorlevel%==0 (
  echo [WARN] Running as ADMINISTRATOR - Git Bash / jpackage path resolution may fail.
  echo        Close this window and run again as a normal user.
  pause
  exit /b 1
)

if not exist "%ROOT%out" mkdir "%ROOT%out"

rem ================= 1/3 PC jar =================
rem  Runs first on purpose: it is the step that fails when the PC app is running,
rem  so a locked jar stops the build before the slow steps.
echo.
echo [1/3] PC jar ...
call "%ROOT%build-jar.bat"
set "RC=!errorlevel!"
cd /d "%ROOT%"
if not "!RC!"=="0" (
  echo.
  echo [ERROR] jar build failed.
  echo         Most likely pc\dist\PhonePcTransfer-PC.jar is locked by the running
  echo         PC app. Right-click the tray icon, choose Exit, then run again.
  echo         The freshly built jar is already at out\PhonePcTransfer-PC.jar.
  echo         Compile errors, if any, are shown above.
  pause
  exit /b 1
)
if not exist "%ROOT%pc\dist\PhonePcTransfer-PC.jar" (
  echo [ERROR] pc\dist\PhonePcTransfer-PC.jar missing after a successful build.
  pause
  exit /b 1
)
copy /y "%ROOT%pc\dist\PhonePcTransfer-PC.jar" "%ROOT%out\PhonePcTransfer-PC.jar" >nul
if errorlevel 1 (
  echo [ERROR] Could not copy the jar into out\.
  pause
  exit /b 1
)

rem ================= 2/3 Android APK =================
echo.
echo [2/3] Android APK ...
call "%ROOT%build-apk.bat"
set "RC=!errorlevel!"
cd /d "%ROOT%"
if not "!RC!"=="0" (
  echo.
  echo [ERROR] APK build failed - see the messages above.
  echo         Hint: if Kotlin reports "Unresolved reference" for queuePending /
  echo         flushPending, the release incremental cache is stale. Delete these
  echo         two dirs and run this script again:
  echo           android\app\build\kotlin\compileReleaseKotlin
  echo           android\app\build\intermediates\built_in_kotlinc\release
  pause
  exit /b 1
)
if not exist "%ROOT%out\app-release.apk" (
  echo [ERROR] out\app-release.apk missing after a successful build.
  pause
  exit /b 1
)

rem ================= 3/3 PC MSI =================
echo.
echo [3/3] PC MSI - jpackage + WiX via Git Bash ...
set "GITROOT="
if exist "C:\Program Files\Git\bin\bash.exe" set "GITROOT=C:\Program Files\Git\"
if not defined GITROOT (
  for /f "delims=" %%G in ('where git 2^>nul') do (
    if not defined GITROOT if exist "%%~dpG..\bin\bash.exe" set "GITROOT=%%~dpG.."
  )
)
if not defined GITROOT (
  echo [ERROR] Git Bash not found. Install Git for Windows, then run again.
  pause
  exit /b 1
)
rem Git's usr\bin first, otherwise System32 date/sort/find win and mangle the build.
set "PATH=!GITROOT!usr\bin;%PATH%"

set "LOG=%ROOT%out\build-msi.log"
echo [%date% %time%] build-all.bat: MSI step > "%LOG%"
if defined APP_VER echo [i] MSI external version: APP_VER=%APP_VER%
if not defined APP_VER echo [i] MSI external version: make-msi.sh default
"!GITROOT!bin\bash.exe" "%ROOT%make-msi.sh" >> "%LOG%" 2>&1
set "RC=!errorlevel!"
cd /d "%ROOT%"
if not "!RC!"=="0" (
  echo.
  echo [ERROR] MSI build failed - log tail:
  type "%LOG%"
  pause
  exit /b 1
)

rem ================= summary =================
echo.
echo ============================================================
echo  Build OK - artifacts in out\
for %%F in ("%ROOT%out\app-release.apk") do echo   %%~nxF   [%%~zF bytes]
for %%F in ("%ROOT%out\PhonePcTransfer-PC.jar") do echo   %%~nxF   [%%~zF bytes]
if exist "%ROOT%out\*.msi" (
  for %%F in ("%ROOT%out\*.msi") do echo   %%~nxF   [%%~zF bytes]
)
echo ============================================================
echo  Folder: %ROOT%out
start "" explorer.exe "%ROOT%out"
pause
exit /b 0
