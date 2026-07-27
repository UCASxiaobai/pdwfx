@echo off
chcp 65001 >nul
cd /d "%~dp0"

where node >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Node.js，请安装 Node.js 18+ 并加入 PATH
    pause
    exit /b 1
)

if not exist "node_modules" (
    echo [错误] 未找到 node_modules，请确认已完整解压前端开发包
    pause
    exit /b 1
)

echo 启动前端开发服务 http://localhost:5173 ...
echo API 代理到 http://localhost:18080
echo.

npm run dev
