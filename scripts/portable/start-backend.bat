@echo off
chcp 65001 >nul
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Java。请安装 JDK/JRE 8 或更高版本，或将 java 加入 PATH。
    echo 下载: https://adoptium.net/
    pause
    exit /b 1
)

if not exist "signal-analysis.jar" (
    echo [错误] 未找到 signal-analysis.jar
    pause
    exit /b 1
)

if not exist "output" mkdir output

echo 正在启动后端服务 http://localhost:18080 ...
echo 日志输出: logs\backend.log
if not exist "logs" mkdir logs

start "pdwfx-backend" /min cmd /c "java -Xms512m -Xmx6g -XX:+UseG1GC -jar signal-analysis.jar --spring.config.additional-location=file:./application.yml >> logs\backend.log 2>&1"

timeout /t 3 /nobreak >nul
echo.
echo 后端已启动。请再运行前端目录中的 start-frontend.bat
echo 关闭本窗口不会停止后端；运行 stop-backend.bat 可停止服务。
pause
