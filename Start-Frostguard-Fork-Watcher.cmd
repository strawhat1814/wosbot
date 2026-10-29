@echo off
rem Always-on Telegram watcher for the fork daily-driver on default-repaired.
rem Requires compiled fork watcher classes so /launch honors botLauncherPath /
rem frostguard.launcher -> Launch-Frostguard-Fork.cmd (not Nightly.exe).
cd /d "%~dp0"

set "FG_WS=C:\Users\tacki\.frostguard\workspaces\nightly\default-repaired"
set "FG_APP=%LOCALAPPDATA%\Frostguard Nightly\app"
set "FG_FORK_LAUNCHER=%CD%\Launch-Frostguard-Fork.cmd"
set "FG_JAVA=%JAVA_HOME%\bin\javaw.exe"
if not exist "%FG_JAVA%" set "FG_JAVA=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\javaw.exe"
if not exist "%FG_JAVA%" set "FG_JAVA=javaw"

if not exist "modules\watcher\target\classes\dev\frostguard\watcher\TelegramWatcher.class" (
  echo Fork watcher classes missing. Build first:
  echo   mvnw.cmd -pl modules/watcher -am package -DskipTests
  exit /b 1
)
if not exist "%FG_FORK_LAUNCHER%" (
  echo Missing "%FG_FORK_LAUNCHER%"
  exit /b 1
)

for %%J in ("%FG_APP%\frostguard-watcher-*.jar") do set "FG_WATCHER_JAR=%%~fJ"
for %%J in ("%FG_APP%\frostguard-desktop-*.jar") do set "FG_DESKTOP_JAR=%%~fJ"
if not defined FG_WATCHER_JAR (
  echo Watcher jar not found under "%FG_APP%"
  exit /b 1
)

set "FG_CP=%CD%\modules\watcher\target\classes;%CD%\modules\api\target\classes;%FG_WATCHER_JAR%;%FG_DESKTOP_JAR%;%FG_APP%\lib\*"
set "FROSTGUARD_WORKSPACE=%FG_WS%"
set "FROSTGUARD_CHANNEL=nightly"

echo Starting fork Telegram watcher.
echo /launch launcher: %FG_FORK_LAUNCHER%
echo Workspace: %FG_WS%

start "FG-Fork-Telegram-Watcher" "%FG_JAVA%" --enable-native-access=ALL-UNNAMED ^
  -Dfrostguard.channel=nightly ^
  "-Dfrostguard.workspace=%FG_WS%" ^
  -Dfrostguard.application.id=dev.frostguard.desktop.nightly ^
  "-Dfrostguard.launcher=%FG_FORK_LAUNCHER%" ^
  -cp "%FG_CP%" ^
  dev.frostguard.watcher.TelegramWatcher
exit /b 0
