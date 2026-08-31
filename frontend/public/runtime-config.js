// 开发环境留空，走 vue-cli proxy /api；便携包可覆盖为 http://localhost:18080
window.__API_BASE__ = window.__API_BASE__ || "";
// 流式：开发留空走 /stream-api 代理；生产可设为 http://localhost:19080
window.__STREAM_API_BASE__ = window.__STREAM_API_BASE__ || "";
