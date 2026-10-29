@echo off
rem Telegram /launch and non-interactive fork start.
rem Do not kill the Telegram watcher from this script.
cd /d "%~dp0"

set "FG_WS=C:\Users\tacki\.frostguard\workspaces\nightly\default-repaired"
set "FROSTGUARD_WORKSPACE=%FG_WS%"
set "FROSTGUARD_CHANNEL=nightly"

tasklist /FI "IMAGENAME eq Frostguard Nightly.exe" 2>nul | find /I "Frostguard Nightly.exe" >nul
if not errorlevel 1 (
  echo Nightly is still running. Close it before launching the fork.
  exit /b 1
)

rem Do not pass frostguard.watcher.launcher here: the Nightly path has spaces and
rem javafx:run splits it into a fake main class. Watcher is started separately;
rem TelegramWatcherLauncher falls back to workspace / source launcher.
call mvnw.cmd javafx:run ^
  "-Dfrostguard.run.workspace=%FG_WS%" ^
  "-Dfrostguard.run.channel=nightly" ^
  %*
exit /b %ERRORLEVEL%
