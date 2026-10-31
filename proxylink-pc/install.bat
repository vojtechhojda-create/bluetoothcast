@echo off
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo node 20 or newer is required: https://nodejs.org
  pause
  exit /b 1
)
call npm install --no-audit --no-fund
if errorlevel 1 goto fail
where dotnet >nul 2>nul
if errorlevel 1 (
  echo dotnet sdk not found, audio streaming disabled. install .net 8 sdk and run install.bat again
) else (
  call npm run build:loopback
  if errorlevel 1 echo loopback build failed, audio streaming disabled
)
call npm run build
if errorlevel 1 goto fail
echo.
choice /c yn /m "start proxylink automatically with windows"
if not errorlevel 2 node dist\autostart-cli.js on
echo.
choice /c yn /m "start proxylink now"
if not errorlevel 2 call start.bat
exit /b 0
:fail
echo install failed
pause
exit /b 1
