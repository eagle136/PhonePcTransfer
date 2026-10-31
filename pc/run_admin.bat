@echo off
rem ============================================================
rem  PhonePcTransfer PC - firewall setup ONLY (admin).
rem  The tray app must NEVER run elevated: Windows UIPI silently
rem  blocks Explorer drag-and-drop into an elevated process, so
rem  the drag-drop feature would appear completely dead.
rem
rem  This script adds/refreshes the inbound firewall rules, then
rem  starts the app NON-elevated via run.bat (through explorer,
rem  which strips the admin token). If it fails to launch, just
rem  double-click run.bat yourself.
rem  NOTE: keep this file ASCII-only (no Chinese).
rem ============================================================
setlocal
cd /d "%~dp0"

net session >nul 2>&1
if %errorlevel% neq 0 (
  echo Requesting administrator privileges...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

echo [1/2] Administrator OK - refreshing the inbound firewall rule ...
rem  2.13 (2026-10-02): the rule shape lives in exactly ONE place - run.bat's
rem  "addrules" branch (program-level, ports covered by program=..., old port rules
rem  deleted first). Duplicating the netsh lines here is how the two scripts would
rem  drift apart, and this branch is already elevated, so the delegation costs nothing.
rem  The image path therefore has a single definition too: run.bat's JBR variable.
call "%~dp0run.bat" addrules
if %errorlevel% neq 0 (
  echo [ERROR] Rule refresh failed - run.bat addrules returned %errorlevel%.
  pause
  exit /b 1
)
echo       Program-level inbound rule in place (all ports for the launched image).

echo [2/2] Handing launch over to run.bat (non-admin) ...
rem explorer.exe launches its argument at MEDIUM integrity even
rem when called from an elevated process - that de-elevates it.
start "" explorer.exe "%~dp0run.bat"
echo.
echo Done. If no tray icon appeared, double-click run.bat directly.
timeout /t 3 >nul
exit /b 0
