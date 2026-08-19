/**
 * Shared chart geometry / palette with 全量数据概览 (importScatter).
 * Keep in sync with backend VisualizationService.FREQ_COLORS.
 */

export const IMPORT_SCATTER_CHART_HEIGHT = 760;

export const MAX_IMPORT_SCATTER_FREQS = 16;

/** Matches backend VisualizationService.FREQ_COLORS (16) */
export const IMPORT_FREQ_COLORS = [
  "#D95319",
  "#0072BD",
  "#77AC30",
  "#4DBEEE",
  "#A2142F",
  "#7E2F8E",
  "#EDB120",
  "#636363",
  "#00A9CE",
  "#8C564B",
  "#9467BD",
  "#17BECF",
  "#E377C2",
  "#2CA02C",
  "#FF7F0E",
  "#1F77B4"
];

export const AZIMUTH_Y_AXIS = {
  min: 0,
  max: 360,
  interval: 15
};

export const IMPORT_SCATTER_GRID = { left: 58, right: 20, top: 52, bottom: 64 };

/** Frequency not in overview Top-N */
export const OMITTED_FREQ_COLOR = "#9ca3af";

/** Round to 3 decimals — same as backend round3 */
export function roundFreq3(mhz) {
  const n = Number(mhz);
  if (!Number.isFinite(n) || n <= 0) return null;
  return Math.round(n * 1000) / 1000;
}

export function timeAxisPad(xMin, xMax) {
  if (!Number.isFinite(xMin) || !Number.isFinite(xMax)) return 60000;
  return Math.max(60000, (xMax - xMin) * 0.02);
}

/**
 * Build freqMhz → color from importScatter.series.
 * Prefer series marked defaultPlot (Top-N); otherwise first MAX_IMPORT_SCATTER_FREQS by order.
 * @param {object|null} scatter
 * @returns {Map<number, string>}
 */
export function buildFreqColorMapFromScatter(scatter) {
  const map = new Map();
  const series = scatter?.series || [];
  const preferred = series.filter((s) => s.defaultPlot === true);
  const list = preferred.length ? preferred : series.slice(0, MAX_IMPORT_SCATTER_FREQS);
  for (let i = 0; i < list.length; i++) {
    const s = list[i];
    const f = roundFreq3(s.freqMhz);
    if (f == null) continue;
    const color = s.color || IMPORT_FREQ_COLORS[map.size % IMPORT_FREQ_COLORS.length];
    map.set(f, color);
  }
  return map;
}

/**
 * Fallback when no importScatter: rank freqs by point count (Top-N), same palette.
 * @param {Array<{freqMhz?: number, count?: number}>} freqCounts
 * @returns {Map<number, string>}
 */
export function buildFreqColorMapByPointRank(freqCounts) {
  const map = new Map();
  const ranked = [...(freqCounts || [])]
    .filter((e) => roundFreq3(e.freqMhz) != null)
    .sort((a, b) => (b.count || 0) - (a.count || 0))
    .slice(0, MAX_IMPORT_SCATTER_FREQS);
  ranked.forEach((e, i) => {
    map.set(roundFreq3(e.freqMhz), IMPORT_FREQ_COLORS[i % IMPORT_FREQ_COLORS.length]);
  });
  return map;
}

/**
 * X range from scatter series points, with same pad as overview.
 * @returns {{ xMin: number, xMax: number }|null}
 */
export function scatterTimeRange(scatter) {
  let xMin = Number.POSITIVE_INFINITY;
  let xMax = Number.NEGATIVE_INFINITY;
  for (const s of scatter?.series || []) {
    for (const p of s.points || []) {
      const x = Number(p[0]);
      if (!Number.isFinite(x)) continue;
      xMin = Math.min(xMin, x);
      xMax = Math.max(xMax, x);
    }
  }
  if (!Number.isFinite(xMin) || !Number.isFinite(xMax)) return null;
  const pad = timeAxisPad(xMin, xMax);
  return { xMin: xMin - pad, xMax: xMax + pad };
}
