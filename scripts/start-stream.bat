@echo off
setlocal
cd /d "%~dp0..\stream"
if not exist "pom.xml" (
  echo [错误] 未找到 stream\pom.xml
  echo 请确认本地存在独立模块目录: pdwfx\stream
  exit /b 1
)
echo Starting pdwfx-stream (HTTP 19080 / TCP 19090^)...
mvn spring-boot:run
