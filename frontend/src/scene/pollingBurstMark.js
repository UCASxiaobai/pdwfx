import { normalize360, shortestDelta } from "./bearingMatch.js";

const DEFAULT_OPTS = {
  burstCoalesceSec: 0.5,
  bearingGapDeg: 0.5,
  minDevices: 2
};

function countBearingClusters(bearings, mergeGapDeg) {
  if (!bearings.length) return 0;
  const sorted = bearings.map(normalize360).sort((a, b) => a - b);
  let clusters = 1;
  for (let i = 1; i < sorted.length; i++) {
    if (Math.abs(shortestDelta(sorted[i - 1], sorted[i])) > mergeGapDeg) {
      clusters += 1;
    }
  }
  return clusters;
}

/**
 * 从场景内全部方位点中识别参与轮询的第一轮（首个多点位 burst）。
 * @param {Array<[number, number|null]>} allPoints
 * @returns {{ points: Array<[number, number]>, centerTime: number, bearings: number[], deviceCount: number }|null}
 */
export function detectFirstPollingBurst(allPoints, options = {}) {
  const opts = { ...DEFAULT_OPTS, ...options };
  const minDevices = Math.max(2, Math.round(Number(opts.minDevices) || 2));
  const coalesceMs = Math.max(1, opts.burstCoalesceSec * 1000);
  const gap = Math.min(opts.bearingGapDeg ?? 0.5, 0.12);

  const rows = (allPoints || [])
    .filter((p) => p && Number.isFinite(p[0]) && p[1] != null && Number.isFinite(p[1]))
    .map((p) => [p[0], normalize360(p[1])])
    .sort((a, b) => a[0] - b[0]);
  if (rows.length < minDevices) return null;

  let i = 0;
  while (i < rows.length) {
    const start = rows[i][0];
    const bucket = [rows[i]];
    let j = i + 1;
    while (j < rows.length && rows[j][0] - start <= coalesceMs) {
      bucket.push(rows[j]);
      j += 1;
    }
    i = j;

    const bearings = bucket.map((p) => p[1]);
    const deviceCount = countBearingClusters(bearings, gap);
    if (deviceCount >= minDevices) {
      const centerTime = bucket.reduce((s, p) => s + p[0], 0) / bucket.length;
      return {
        points: bucket,
        centerTime,
        bearings: [...new Set(bearings.map((b) => Math.round(b * 10) / 10))].sort((a, b) => a - b),
        deviceCount
      };
    }
  }
  return null;
}

/**
 * 将第一轮 burst 转为 ECharts graphic（红色椭圆 + 点位高亮）。
 */
export function buildPollingBurstGraphics(chart, burst, gridIndex = 0) {
  if (!chart || !burst?.points?.length) return [];

  const xs = burst.points.map((p) => p[0]);
  const ys = burst.points.map((p) => p[1]);
  const cx = xs.reduce((a, b) => a + b, 0) / xs.length;
  const yMin = Math.min(...ys);
  const yMax = Math.max(...ys);
  const xMin = Math.min(...xs);
  const xMax = Math.max(...xs);

  const center = chart.convertToPixel({ gridIndex }, [cx, (yMin + yMax) / 2]);
  const pxMin = chart.convertToPixel({ gridIndex }, [xMin, yMax]);
  const pxMax = chart.convertToPixel({ gridIndex }, [xMax, yMin]);
  if (!center || !pxMin || !pxMax) return [];

  const rx = Math.max(22, Math.abs(pxMax[0] - pxMin[0]) / 2 + 14);
  const ry = Math.max(14, Math.abs(pxMax[1] - pxMin[1]) / 2 + 10);

  const graphics = [
    {
      type: "ellipse",
      shape: { cx: center[0], cy: center[1], rx, ry },
      style: {
        fill: "rgba(239,68,68,0.06)",
        stroke: "#ef4444",
        lineWidth: 2
      },
      z: 90,
      silent: true
    },
    {
      type: "text",
      left: center[0] + rx + 8,
      top: center[1] - 8,
      style: {
        text: "第一轮轮询",
        fill: "#ef4444",
        fontSize: 11,
        fontWeight: 600
      },
      z: 92,
      silent: true
    }
  ];

  for (const p of burst.points) {
    const pt = chart.convertToPixel({ gridIndex }, p);
    if (!pt) continue;
    graphics.push({
      type: "circle",
      shape: { cx: pt[0], cy: pt[1], r: 5 },
      style: { fill: "#ef4444", stroke: "#fff", lineWidth: 1 },
      z: 91,
      silent: true
    });
  }
  return graphics;
}

export function applyPollingBurstOverlay(chart, burst, gridIndex = 0) {
  if (!chart) return;
  const graphic = buildPollingBurstGraphics(chart, burst, gridIndex);
  chart.setOption({ graphic }, { replaceMerge: ["graphic"] });
}

export function isPollingSceneType(sceneType) {
  return sceneType === "MULTI_DEVICE_POLLING" || sceneType === "POLLING_MULTI_DEVICE";
}
