@echo off
rem ============================================================
rem  Stop the running Sokoban server.
rem  ASCII-only for the same reason as run-sokoban.cmd.
rem ============================================================
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0run-sokoban.ps1" -Stop %*
echo.
pause
endlocal
