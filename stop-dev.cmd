@echo off
REM Stop the backend (:8080) and frontend (:5173) started by start-dev.cmd.

setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-dev.ps1" %*
pause
endlocal