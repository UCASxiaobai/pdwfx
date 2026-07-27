@echo off
chcp 65001 >nul
cd /d "%~dp0"

where mvn >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Maven，请安装 Maven 3.6+ 并加入 PATH
    pause
    exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到 Java，请安装 JDK 8+ 并加入 PATH
    pause
    exit /b 1
)

if not exist "offline-m2" (
    echo [错误] 未找到 offline-m2 依赖目录，请确认已完整解压开发包
    pause
    exit /b 1
)

echo 使用离线 Maven 仓库启动后端（端口 18080）...
echo 依赖目录: %CD%\offline-m2
echo.

mvn -Dmaven.repo.local="%CD%\offline-m2" -o spring-boot:run
