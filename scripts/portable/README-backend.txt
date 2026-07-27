通信侦获信号分析系统 - 后端便携包
================================

【运行环境】
- Windows 10/11（64 位）
- Java 8 或更高版本（JRE/JDK 均可）
  检查: 在命令行运行 java -version

【启动步骤】
1. 解压本压缩包到任意目录（路径尽量不要含中文或空格）
2. 双击 start-backend.bat
3. 再解压并运行前端便携包中的 start-frontend.bat
4. 浏览器访问 http://localhost:5173

【停止服务】
- 双击 stop-backend.bat

【目录说明】
- signal-analysis.jar   可执行程序（已包含全部 Java 依赖）
- application.yml       配置文件（端口、算法参数等）
- output/               场景分析输出目录（CSV、可视化等）
- logs/                 运行日志（启动后自动生成）

【修改端口】
编辑 application.yml 中 server.port（默认 18080），重启后端。

【常见问题】
- 端口 18080 被占用：修改 application.yml 中的 server.port
- 内存不足：编辑 start-backend.bat 中的 -Xmx6g 调小
- 大文件上传失败：确认 application.yml 中 multipart 限制足够大
