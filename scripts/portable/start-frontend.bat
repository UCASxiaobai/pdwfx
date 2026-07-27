@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo 正在启动前端 http://localhost:5173 ...
echo 请确保已先运行后端的 start-backend.bat
echo.

start "" "http://localhost:5173"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0serve-frontend.ps1"
