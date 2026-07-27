export function asArray(v) {
  return Array.isArray(v) ? v : [];
}

export function normalizeNetwork(raw) {
  if (!raw) return null;
  return {
    ...raw,
    targets: asArray(raw.targets).map((t) => ({
      ...t,
      azimuthSeries: asArray(t?.azimuthSeries),
      signalSeries: asArray(t?.signalSeries),
      freqSeries: asArray(t?.freqSeries),
      trackPoints: asArray(t?.trackPoints)
    })),
    rawAzimuthSeries: asArray(raw.rawAzimuthSeries),
    rawSignalSeries: asArray(raw.rawSignalSeries),
    commLinkEvidence: asArray(raw.commLinkEvidence),
    analysisSummary: raw.analysisSummary || {
      networkConclusions: [],
      targetConclusions: [],
      anomalies: []
    }
  };
}

export function formatFreq(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(3) : "-";
}

export function commModeLabel(mode) {
  if (mode === "MULTI_FREQ") return "异频";
  if (mode === "HOPPING") return "定跳频";
  return "同频";
}

export function convergenceLabel(c) {
  if (c === "CONVERGING") return "收敛";
  if (c === "NOT_CONVERGING") return "不收敛";
  return "未知";
}

export function ellipseLabel(t) {
  if (t.ellipseStatus === "NO_ELLIPSE") return "无椭圆";
  if (t.ellipseConverging === true) {
    const spread =
      t.earlySpread != null && t.lateSpread != null
        ? ` (${Number(t.earlySpread).toFixed(4)}→${Number(t.lateSpread).toFixed(4)})`
        : "";
    return `椭圆收敛${spread}`;
  }
  if (t.ellipseConverging === false) {
    const spread =
      t.earlySpread != null && t.lateSpread != null
        ? ` (${Number(t.earlySpread).toFixed(4)}→${Number(t.lateSpread).toFixed(4)})`
        : "";
    return `椭圆不收敛${spread}`;
  }
  return "-";
}

export function formatEmissionShare(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(1) : "-";
}

export function formatMetric(v, digits = 1) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(digits) : "-";
}

export function targetTypeLabel(type) {
  if (type === "GROUND") return "固定站";
  if (type === "AWACS") return "预警机";
  if (type === "AIR") return "飞机";
  return type || "-";
}

export function displayNetworkType(n) {
  return n?.networkType || n?.commLinkChannelLabel || "—";
}

export function uniqueValues(arr) {
  return [...new Set(arr.filter((x) => x && x !== "-"))].sort();
}

export function countBy(list, keyFn) {
  const m = {};
  for (const item of list) {
    const k = keyFn(item) || "—";
    m[k] = (m[k] || 0) + 1;
  }
  return m;
}

export function formatStatMap(m) {
  const keys = Object.keys(m);
  if (!keys.length) return "—";
  return keys.map((k) => `${k}:${m[k]}`).join(" · ");
}
