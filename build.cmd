@echo off
rem ---------------------------------------------------------------
rem 凭物 构建脚本（cmd / PowerShell / 双击 都能用）
rem   build.cmd                -> 打 debug 包
rem   build.cmd assembleRelease -> 打 release 包
rem   build.cmd testDebugUnitTest -> 跑单测
rem ---------------------------------------------------------------
setlocal
cd /d "%~dp0"

set "JAVA_HOME=%~dp0.toolchain\jdk-17.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if "%~1"=="" (
  set "TASK=:app:assembleDebug"
) else (
  set "TASK=%*"
)

echo [build] JAVA_HOME = %JAVA_HOME%
echo [build] TASK      = %TASK%
echo.

call gradlew.bat %TASK%
set "RC=%ERRORLEVEL%"

echo.
if "%RC%"=="0" (echo [build] OK) else (echo [build] FAILED exit=%RC%)
exit /b %RC%
