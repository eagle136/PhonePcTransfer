@echo off
rem ============================================================
rem  PhonePcTransfer PC build script (plain jar, NO MSI)
rem  Compile with Android Studio JBR, target Java 17.
rem  Deliverable: pc\dist\PhonePcTransfer-PC.jar - the run.bat execution
rem  location. Jar is built into out\ first, then moved there, replacing
rem  any old jar.
rem  NOTE: keep this file ASCII-only (no Chinese).
rem  Usage: run from project root.
rem ============================================================
setlocal enabledelayedexpansion
cd /d "%~dp0"

set "ROOT=%~dp0"
set "OUTDIR=%ROOT%out"
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

rem ----- package into <root>\out\ (build output) ----- 
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

echo [2/2] Packaging jar to out\ (bundling concentus classes) ...
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
"%JBR%\bin\jar.exe" --create --file "%OUTDIR%\PhonePcTransfer-PC.jar" ^
  --main-class com.phonepctransfer.pc.PcMain ^
  -C "build\classes" .
if errorlevel 1 (
  echo [ERROR] Package failed
  exit /b 1
)

rem ----- move to the run.bat execution location and replace old jar -----
move /y "%OUTDIR%\PhonePcTransfer-PC.jar" "%ROOT%pc\dist\PhonePcTransfer-PC.jar" >nul
if errorlevel 1 (
  echo [ERROR] Move jar to execution location failed
  exit /b 1
)
for %%F in ("%ROOT%pc\dist\PhonePcTransfer-PC.jar") do echo [MOVE] %%~dpnxF  (%%~zF bytes)

echo.
echo Build OK -^> pc\dist\PhonePcTransfer-PC.jar  (moved into run.bat execution location, old jar replaced)
echo Start it with: pc\run.bat
exit /b 0
