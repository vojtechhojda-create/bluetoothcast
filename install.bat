@echo off
setlocal
cd /d "%~dp0"
echo === ProxyLink - instalace ===
set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY where python >nul 2>nul && set "PY=python"
if not defined PY goto nopython

%PY% -m pip install --upgrade pip
%PY% -m pip install -r requirements.txt
if errorlevel 1 goto pipfail

%PY% proxylink_pc.py --write-settings
powershell -NoProfile -Command "$s=(New-Object -ComObject WScript.Shell).CreateShortcut([Environment]::GetFolderPath('Desktop')+'\ProxyLink.lnk'); $s.TargetPath='%~dp0start.bat'; $s.WorkingDirectory='%~dp0'; $s.WindowStyle=7; $s.Save()"
echo.
echo Hotovo. Na plose je zastupce ProxyLink, spoustim aplikaci...
call "%~dp0start.bat"
exit /b 0

:nopython
echo Python nenalezen. Nainstaluj Python 3.10 nebo novejsi z https://www.python.org/downloads/
echo a zaskrtni "Add python.exe to PATH". Pak spust install.bat znovu.
pause
exit /b 1

:pipfail
echo Instalace baliku selhala. Zkontroluj pripojeni k internetu.
pause
exit /b 1
