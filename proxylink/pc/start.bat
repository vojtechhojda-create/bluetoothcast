@echo off
cd /d "%~dp0"
if not exist dist\index.js (
  echo run install.bat first
  pause
  exit /b 1
)
wscript "%~dp0scripts\start-hidden.vbs"
echo proxylink is starting in the background.
echo look for the green icon in the tray: bottom right, click the arrow up.
echo log: %%APPDATA%%\proxylink\log.txt
timeout /t 6 >nul
