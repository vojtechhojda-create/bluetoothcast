@echo off
cd /d "%~dp0"
where pyw >nul 2>nul && goto usepyw
where pythonw >nul 2>nul && goto usepythonw
echo Python nenalezen. Spust nejdriv install.bat.
pause
exit /b 1

:usepyw
start "" pyw -3 "%~dp0proxylink_pc.py" %*
exit /b 0

:usepythonw
start "" pythonw "%~dp0proxylink_pc.py" %*
exit /b 0
