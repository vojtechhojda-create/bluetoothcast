@echo off
cd /d "%~dp0"
set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY where python >nul 2>nul && set "PY=python"
if defined PY %PY% proxylink_pc.py --write-settings >nul 2>nul
if not exist "%APPDATA%\ProxyLink\settings.json" goto missing
start "" notepad "%APPDATA%\ProxyLink\settings.json"
exit /b 0

:missing
echo Soubor s nastavenim zatim neexistuje. Spust nejdriv install.bat.
pause
exit /b 1
