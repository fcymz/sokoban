@echo off
rem ============================================================
rem  Sokoban one-click launcher.
rem  This file is intentionally ASCII-only: cmd.exe reads .cmd
rem  files with the OEM code page, so non-ASCII text here would
rem  break on some locales. All user-facing messages (Chinese)
rem  come from run-sokoban.ps1, which is UTF-8 with BOM.
rem
rem  Usage:  run-sokoban.cmd [-NoBrowser] [-Port 9000]
rem ============================================================
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-sokoban.ps1" %*
if errorlevel 1 (
  echo.
  echo [!] Startup failed - see the message above.
  pause
)
endlocal
