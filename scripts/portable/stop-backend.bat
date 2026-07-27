@echo off
chcp 65001 >nul
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":18080" ^| findstr "LISTENING"') do (
    echo 正在停止后端进程 PID=%%a
    taskkill /PID %%a /F >nul 2>&1
)
echo 后端服务已停止（若仍有进程请手动结束 java.exe）
pause
