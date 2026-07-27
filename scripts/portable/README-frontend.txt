通信侦获信号分析系统 - 前端便携包
================================

【运行环境】
- Windows 10/11（64 位）
- 无需 Node.js / npm（已预编译为静态文件）
- 需先启动后端便携包（http://localhost:18080）

【启动步骤】
1. 确保后端已运行（见 backend 包中的 start-backend.bat）
2. 解压本压缩包
3. 双击 start-frontend.bat
4. 浏览器自动打开 http://localhost:5173

【目录说明】
- dist/                 预编译前端资源（Vue + ECharts + OpenLayers）
- runtime-config.js     API 地址配置（默认连 http://localhost:18080）
- serve-frontend.ps1    内置静态文件服务（PowerShell，无需额外安装）

【修改后端地址】
若后端不在本机 18080 端口，编辑 dist/runtime-config.js：
  window.__API_BASE__ = "http://你的地址:端口";

【常见问题】
- 页面能开但接口失败：先确认后端已启动，再检查 runtime-config.js
- 端口 5173 被占用：编辑 serve-frontend.ps1 中的 $Port 参数
