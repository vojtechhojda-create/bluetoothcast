@echo off
where py >nul 2>nul && (set PY=py) || (set PY=python)
echo Instaluji zavislosti...
%PY% -m pip install --upgrade pip
%PY% -m pip install pyaudiowpatch numpy
echo.
echo Hotovo. Ted spust start.bat
pause
