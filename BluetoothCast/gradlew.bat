@ECHO OFF
set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
java -jar "%DIRNAME%\gradle\wrapper\gradle-wrapper.jar" %*
