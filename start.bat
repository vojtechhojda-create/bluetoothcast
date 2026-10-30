@echo off
cd /d "%~dp0"
where py >nul 2>nul && (set PY=py) || (set PY=python)
set A=%~1
if "%A%"=="" goto plain
if "%A:~0,1%"=="-" goto plain
%PY% pc_audio_sender.py --ip %*
goto end
:plain
%PY% pc_audio_sender.py %*
:end
pause
