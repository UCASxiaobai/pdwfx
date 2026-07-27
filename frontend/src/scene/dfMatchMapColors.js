/** 地图上未关联测向批的外源定位点颜色 */
export const EXTERNAL_FIX_NEUTRAL_COLOR = "#9ca3af";

/** 测向射线默认可视长度（km） */
export const AZIMUTH_RAY_KM = 300;

/** 平台到定位点最大合理距离（km），与后端匹配门限一致 */
export const MAX_LOCATE_DIST_KM = 400;

const DEFAULT_PALETTE = [
  "#ef4444", "#2563eb", "#16a34a", "#f59e0b", "#7c3aed", "#0f766e",
  "#db2777", "#0891b2", "#ca8a04", "#4f46e5", "#059669", "#dc2626"
];

/**
 * 已锁定测向批 → 航迹名（mbmc）→ 地图颜色。
 * @param {Record<string, string>|null|undefined} batchToDevice
 * @param {string[]} palette
 * @returns {Map<string, string>}
 */
export function buildMatchedDeviceColors(batchToDevice, palette = DEFAULT_PALETTE) {
  const map = new Map();
  if (!batchToDevice) return map;
  let i = 0;
  for (const deviceId of Object.values(batchToDevice)) {
    const name = (deviceId || "").trim();
    if (!name || map.has(name)) continue;
    map.set(name, palette[i % palette.length]);
    i += 1;
  }
  return map;
}

/**
 * @param {object} fix external target fix
 * @param {Map<string, string>} matchedDeviceColors
 */
export function resolveExternalFixColor(fix, matchedDeviceColors) {
  const name = (fix?.targetName || "").trim();
  if (name && matchedDeviceColors?.has(name)) {
    return matchedDeviceColors.get(name);
  }
  return EXTERNAL_FIX_NEUTRAL_COLOR;
}

/**
 * 测向批射线颜色：优先取锁定航迹（mbmc）色，否则按批号哈希。
 */
export function resolveBatchRayColor(batchId, batchToDevice, matchedDeviceColors, palette = DEFAULT_PALETTE) {
  const device = (batchToDevice?.[batchId] || "").trim();
  if (device && matchedDeviceColors?.has(device)) {
    return matchedDeviceColors.get(device);
  }
  const id = String(batchId || "");
  let hash = 0;
  for (let i = 0; i < id.length; i++) {
    hash = (hash * 31 + id.charCodeAt(i)) | 0;
  }
  return palette[Math.abs(hash) % palette.length];
}
