/** 便携部署时由 runtime-config.js 设置 window.__API_BASE__ */
export function apiUrl(path) {
  const base =
    typeof window !== "undefined" && window.__API_BASE__
      ? String(window.__API_BASE__).replace(/\/$/, "")
      : "";
  const p = path.startsWith("/") ? path : `/${path}`;
  return `${base}${p}`;
}
