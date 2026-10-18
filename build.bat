@echo off
rem 构建 BoardLive.jar(需要 JDK 17+)
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
if errorlevel 1 (
    echo.
    echo 构建失败。
    exit /b 1
)
endlocal
