@echo off
REM One-click launcher: starts the Spring Boot backend (:8080) and the Vite frontend (:5173).
REM
REM   start-dev.cmd              start both, then open the browser
REM   start-dev.cmd -NoBrowser   start both without opening the browser
REM   start-dev.cmd -Rebuild     force a rebuild of the backend jar
REM
REM Double-click this file, or run it from a terminal.
REM Stop the services later with:  stop-dev.cmd

setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-dev.ps1" %*
if errorlevel 1 (
  echo.
  echo Startup FAILED. See target\dev-logs\ for details.
  pause
)
endlocal