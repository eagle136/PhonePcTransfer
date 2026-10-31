@echo off
chcp 65001 >nul
setlocal
rem Generate the dual-platform IPv6 blocklist matcher + keyword table.
rem Products are machine-generated; never edit them by hand.
rem
rem usage: gen.bat [blocklist.txt] [cn-reference.txt]
rem   defaults: C:\ipdata\highrisk_ipv6_blocklist.txt (read-only source)
rem             C:\ipdata\cn_ipv6.txt (reverse assertion, optional)

set "PROJ=%~dp0..\.."
set "JBR=C:\Program Files\Android\Android Studio\jbr"
set "LIST=C:\ipdata\highrisk_ipv6_blocklist.txt"
if not "%~1"=="" set "LIST=%~1"
set "CN=C:\ipdata\cn_ipv6.txt"
if not "%~2"=="" set "CN=%~2"

if not exist "%JBR%\bin\javac.exe" (
  echo [ERROR] JDK not found: %JBR%\bin\javac.exe
  echo         Edit the JBR variable in this script to point to your JDK.
  exit /b 1
)
if not exist "%LIST%" (
  echo [ERROR] blocklist not found: %LIST%
  exit /b 1
)

echo [1/4] compile generator...
if not exist "%~dp0build" mkdir "%~dp0build"
"%JBR%\bin\javac.exe" -J-Duser.language=en -encoding UTF-8 -d "%~dp0build" "%~dp0GenBlocklist.java"
if errorlevel 1 exit /b 1

echo [2/4] write products for android + pc...
"%JBR%\bin\java.exe" -Dstdout.encoding=UTF-8 -cp "%~dp0build" GenBlocklist "%LIST%" "%PROJ%\android\app\src\main\java\com\phonepctransfer\net" com.phonepctransfer.net "%PROJ%\pc\src\com\phonepctransfer\pc" com.phonepctransfer.pc
if errorlevel 1 exit /b 1

echo [3/4] compile pc products...
if not exist "%~dp0build\pc" mkdir "%~dp0build\pc"
"%JBR%\bin\javac.exe" -J-Duser.language=en -encoding UTF-8 -d "%~dp0build\pc" "%PROJ%\pc\src\com\phonepctransfer\pc\BlocklistIpv6.java" "%PROJ%\pc\src\com\phonepctransfer\pc\TransferKeywords.java" "%PROJ%\pc\src\com\phonepctransfer\pc\LocalIps.java"
if errorlevel 1 exit /b 1

if not exist "%CN%" (
  echo [WARN] cn reference not found, skip verification: %CN%
  exit /b 0
)

echo [4/4] compile and run verifier...
"%JBR%\bin\javac.exe" -J-Duser.language=en -encoding UTF-8 -d "%~dp0build" -cp "%~dp0build\pc" "%~dp0VerifyBlocklist.java"
if errorlevel 1 exit /b 1
"%JBR%\bin\java.exe" -Dstdout.encoding=UTF-8 -cp "%~dp0build;%~dp0build\pc" VerifyBlocklist "%LIST%" "%CN%"
endlocal
