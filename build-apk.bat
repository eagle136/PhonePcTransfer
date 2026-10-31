@echo off
rem ============================================================
rem  PhonePcTransfer Android build script (R8 release)
rem  One click: gradle :app:assembleRelease + copy APK to <root>\out\
rem  NOTE: keep this file ASCII-only (no Chinese, GBK log pitfall).
rem  Usage (run from project root):
rem    build-release.bat         R8 release (default, ~2.8 MB)
rem    build-release.bat no-r8   no R8 (on-device regression, ~39 MB)
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "ROOT=%~dp0"
set "OUTDIR=%ROOT%out"
set "R8FLAG="
if /i "%~1"=="no-r8" set "R8FLAG=-Pr8=false"

rem ----- JDK: prefer a valid $JAVA_HOME, else fall back to Android Studio JBR -----
set "JDKOK=0"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JDKOK=1"
if "%JDKOK%"=="1" goto havejdk
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [ERROR] JDK not found. Set JAVA_HOME to a JDK 17+ or install Android Studio.
  exit /b 1
)
:havejdk

cd /d "%~dp0android"

rem ----- sdk.dir self-check -----
rem local.properties is machine-local (Android Studio auto-generates it).
rem Only rewrite sdk.dir when the current value points to a missing directory.
set "SDKPATH="
for /f "tokens=2 delims==" %%a in ('findstr /b "sdk.dir=" local.properties 2^>nul') do set "SDKPATH=%%a"
if defined SDKPATH set "SDKPATH=%SDKPATH:\\=\%"
if defined SDKPATH (
  if exist "%SDKPATH%\" goto sdkok
)

set "CAND="
if defined ANDROID_HOME if exist "%ANDROID_HOME%\" set "CAND=%ANDROID_HOME%"
if not defined CAND if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\" set "CAND=%ANDROID_SDK_ROOT%"
if not defined CAND if exist "%LOCALAPPDATA%\Android\Sdk\" set "CAND=%LOCALAPPDATA%\Android\Sdk"
if not defined CAND (
  echo [ERROR] sdk.dir in local.properties is invalid and no SDK found.
  echo         Point sdk.dir to a real SDK dir, or set ANDROID_HOME, then re-run.
  exit /b 1
)

rem Rewrite the sdk.dir line (properties format: backslashes must be doubled)
set "ESC=%CAND:\=\\%"
> local.properties.tmp (
  for /f "usebackq delims=" %%i in ("local.properties") do @(
    set "LINE=%%~i"
    if "!LINE:~0,8!"=="sdk.dir=" (echo sdk.dir=!ESC!) else (echo !LINE!)
  )
)
move /y local.properties.tmp local.properties >nul
echo [FIX] sdk.dir was invalid, rewritten to: %CAND%

:sdkok

rem ----- build (R8 release by default; -Pr8=false for a non-minified APK) -----
call gradlew.bat :app:assembleRelease --console=plain %R8FLAG%
if errorlevel 1 (
  echo [ERROR] Gradle build failed
  exit /b 1
)

rem ----- deliver to <root>\out\ -----
if not exist "%OUTDIR%" mkdir "%OUTDIR%"
copy /y "app\build\outputs\apk\release\app-release.apk" "%OUTDIR%\app-release.apk" >nul
if errorlevel 1 (
  echo [ERROR] Could not copy APK to %OUTDIR%
  exit /b 1
)

for %%F in ("%OUTDIR%\app-release.apk") do echo [OK] APK -^> %%~dpnxF  (%%~zF bytes)
findstr /c:"versionCode" /c:"versionName" "app\build\outputs\apk\release\output-metadata.json"
echo Build OK. Install with: adb install -r "%OUTDIR%\app-release.apk"
exit /b 0
