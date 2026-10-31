@echo off
rem ============================================================
rem  PhonePcTransfer PC launcher - THE one-click entry point.
rem  The app ALWAYS starts non-elevated (Explorer drag-and-drop
rem  is silently blocked by Windows UIPI when the app runs as
rem  admin). If firewall rules are missing, a separate elevated
rem  one-shot helper (this script with "addrules") adds them via
rem  a UAC prompt - the app itself never gets admin.
rem
rem  Usage:  run.bat           - start the app
rem          run.bat addrules  - (internal) add firewall rules
rem  NOTE: keep this file ASCII-only (no Chinese) to avoid
rem        codepage mojibake issues.
rem ============================================================
setlocal
cd /d "%~dp0"

set "JBR=C:\Program Files\Android\Android Studio\jbr"
set "JAR=dist\PhonePcTransfer-PC.jar"

rem ---------- internal elevated mode: add rules and exit ----------
rem  2.13 (2026-10-02): ONE program-level rule replaces the two port-level ones.
rem  Why: tiers 1-4 bind a NEW listen port every call (UDP/TCP 443 and random
rem  4000-50000), so rules added per port can never catch up (U11 (1)/(5)).
rem  program= MUST be the image this script actually starts - the single
rem  `start ""` line near the bottom of this file runs "%JBR%\bin\javaw.exe" -jar.
rem  This round ships a jar only, so it is NOT a jpackage exe and NOT
rem  java.exe (S26) - pointing elsewhere yields a rule that never matches.
rem  Side effect, by design: inbound is now allowed for ANY Java program launched
rem  with this same JBR. Narrowing candidate: append  remoteip=LocalSubnet  -
rem  whether netsh accepts it and whether the LAN still works is a task-6 item;
rem  if it does not, this rule stays as wide as written here (no silent half-measure).
rem  profile=any is explicit: the old port rules carried no profile, so whether they
rem  were hit at all on a Public network was never verified (S26 (2)).
if /i "%~1"=="addrules" (
  rem delete the stale port-level rules FIRST - a leftover same-shape rule shadows this one.
  rem Measured on this machine: FOUR port rules are actually installed - 28765/28766 plus the
  rem pre-v2.1 8765/8766 pair, so deleting only the current two would leave the old hole open.
  netsh advfirewall firewall delete rule name="PhonePcTransfer TCP 28765" >nul 2>&1
  netsh advfirewall firewall delete rule name="PhonePcTransfer UDP 28766" >nul 2>&1
  netsh advfirewall firewall delete rule name="PhonePcTransfer TCP 8765" >nul 2>&1
  netsh advfirewall firewall delete rule name="PhonePcTransfer UDP 8766" >nul 2>&1
  rem delete our own rule name too, so re-running refreshes instead of stacking duplicates
  netsh advfirewall firewall delete rule name="PhonePcTransfer" >nul 2>&1
  set "ADD_OK="
  netsh advfirewall firewall add rule name="PhonePcTransfer" dir=in action=allow program="%JBR%\bin\javaw.exe" enable=yes profile=any >nul
  rem read it back: netsh reports success on things it then refused to apply, and a rule
  rem pointing at some other image would leave every call silently landing inband
  netsh advfirewall firewall show rule name="PhonePcTransfer" 2>nul | findstr /i /c:"%JBR%\bin\javaw.exe" >nul 2>&1 && set "ADD_OK=1"
  if defined ADD_OK (
    echo Firewall rule added - program-level, all inbound ports.
  ) else (
    echo [ERROR] Rule NOT in place after add - netsh failed or points elsewhere.
  )
  echo Image: "%JBR%\bin\javaw.exe"
  echo ^(One-shot helper: waiting 3 seconds, then exiting.^)
  timeout /t 3 >nul
  if defined ADD_OK exit /b 0
  exit /b 1
)

rem ---------- refuse to double-start ----------
netstat -ano | findstr /c:":28765" | findstr /c:"LISTENING" >nul 2>&1
if %errorlevel% equ 0 (
  echo [ERROR] TCP 28765 is already in use - the app is probably
  echo         already running. Close the existing instance first
  echo         ^(tray icon - Exit^), then run this script again.
  pause
  exit /b 1
)

if not exist "%JAR%" (
  echo [ERROR] %JAR% not found. Run build.bat first.
  pause
  exit /b 1
)

if not exist "%JBR%\bin\javaw.exe" (
  echo [ERROR] JBR not found: %JBR%\bin\javaw.exe
  pause
  exit /b 1
)

rem ---------- video decoder check (non-fatal) ----------
rem  Video calls need a bundled minimal ffmpeg (no system HEVC decoder MFT on
rem  Windows by default). Missing it only disables video receiving - audio and
rem  file transfer keep working, so this is a warning, not an error.
if not exist "bin\ffmpeg.exe" (
  echo [warn] bin\ffmpeg.exe not found - the app will advertise "no video"
  echo        and the phone side video button will be greyed out.
  echo        To enable video, copy the minimal ffmpeg.exe into bin\.
)

rem ---------- first run: add firewall rules via UAC ----------
rem  2.13: the check follows the NEW shape - rule name AND the program path it points
rem  at. The old one (show rule name="PhonePcTransfer TCP 28765") would pass forever on
rem  a machine that still holds the stale port rule but has no working program rule,
rem  i.e. it stopped being a check at all the moment the shape changed (S26).
rem  Matching the PATH and not the "Program" label is deliberate: on a zh-CN Windows
rem  netsh prints the label localized, only the path value stays ASCII.
netsh advfirewall firewall show rule name="PhonePcTransfer" 2>nul | findstr /i /c:"%JBR%\bin\javaw.exe" >nul 2>&1
if %errorlevel% neq 0 (
  echo [i] No inbound rule for this program - accept the UAC prompt to add it.
  echo     ^(Only needed once. The app itself stays non-admin.^)
  echo     If you decline, calls still connect but the media tier silently falls back
  echo     to the main TCP port, and files are not sent while a call is up.
  echo     Add the rule later by running:  run.bat addrules   ^(needs admin^)
  powershell -NoProfile -Command "Start-Process -FilePath cmd.exe -ArgumentList '/c','\"%~f0\" addrules' -Verb RunAs"
)

rem ---------- start the app NON-elevated ----------
echo Starting tray app (non-elevated - Explorer drag-drop works) ...
rem  -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false : text AA off (SimSun bitmap strikes,
rem  100% pinned scale) - must stay in sync with PcMain.main setProperty and make-msi.sh/make-appimage.sh
start "" "%JBR%\bin\javaw.exe" -Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false -jar "%JAR%" %*

rem --- verify it really came up (javaw is silent on crash) ---
timeout /t 3 >nul
netstat -ano | findstr /c:":28765" | findstr /c:"LISTENING" >nul 2>&1
if %errorlevel% neq 0 (
  echo.
  echo [ERROR] App did not start within 3 seconds. Possible causes:
  echo         - corrupted jar ^(run build.bat again^)
  echo         - JBR path wrong ^(edit JBR variable above^)
  echo.
  echo To see the actual error, run in console mode:
  echo   "%JBR%\bin\java.exe" -Dfile.encoding=UTF-8 -Dawt.useSystemAAFontSettings=off -Dswing.aatext=false -jar "%JAR%"
  echo.
  pause
  exit /b 1
)
echo.
echo Started (background tray). Log: logs\pc.log
exit /b 0
