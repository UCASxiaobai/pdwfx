/** 流式服务 API 基址：开发走 vue.config proxy /stream-api；生产用 runtime-config */
export function streamApiUrl(path) {
  const configured =
    typeof window !== "undefined" && window.__STREAM_API_BASE__
      ? String(window.__STREAM_API_BASE__).replace(/\/$/, "")
      : "";
  const base = configured || "/stream-api";
  const p = path.startsWith("/") ? path : `/${path}`;
  return `${base}${p}`;
}
