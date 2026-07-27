import { fetchNetworkDetail } from "./sceneApi.js";
import { formatFreq, targetTypeLabel } from "./sceneFilters.js";
import { asArray, normalizeNetwork } from "./signalUi.js";
import { TARGET_COLORS } from "./analysisBearing.js";

const FREQ_LINE_TYPES = ["solid", "dashed", "dotted", [8, 4], [2, 6], [12, 4, 2, 4]];

function toEpochMs(raw) {
  const t = Number(raw);
  if (!Number.isFinite(t) || t <= 0) return 0;
  if (t < 1e11) return t * 1000;
  if (t < 1e14) return t;
  if (t >= 1e9 && t < 1e12) return t * 1000;
  return 0;
}

function azimuthLinePoints(azimuthSeries) {
  const pts = asArray(azimuthSeries)
    .map((p) => [toEpochMs(p.t), Number(p.v)])
    .filter((p) => Number.isFinite(p[0]) && p[0] > 0 && Number.isFinite(p[1]))
    .sort((a, b) => a[0] - b[0]);
  if (pts.length < 2) return pts;
  const out = [];
  for (let i = 0; i < pts.length; i++) {
    if (i > 0 && Math.abs(pts[i][1] - pts[i - 1][1]) > 15) {
      out.push([pts[i][0], null]);
    }
    out.push(pts[i]);
  }
  return out;
}

function clipPoints(points, start, end) {
  return (points || []).filter(([x]) => x >= start && x <= end);
}

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
}

function resolveTargetTypeLabel(t) {
  return t?.targetTypeLabel || targetTypeLabel(t?.targetType);
}

function axisBounds(targets) {
  let xMin = Infinity;
  let xMax = -Infinity;
  let yMin = Infinity;
  let yMax = -Infinity;
  for (const t of targets) {
    for (const [x, y] of t.points || []) {
      if (y == null) continue;
      if (x < xMin) xMin = x;
      if (x > xMax) xMax = x;
      if (y < yMin) yMin = y;
      if (y > yMax) yMax = y;
    }
  }
  if (!Number.isFinite(xMin)) {
    return { xMin: 0, xMax: 1, yMin: 0, yMax: 360 };
  }
  const yPad = Math.max(2, (yMax - yMin) * 0.05);
  return {
    xMin,
    xMax: xMax === xMin ? xMax + 1000 : xMax,
    yMin: Math.max(0, yMin - yPad),
    yMax: Math.min(360, yMax + yPad)
  };
}

/**
 * 按分析结果 networkId + targetId 着色（异频网内同一 T 同色）。
 */
function assignAnalysisColors(targets) {
  const colorMap = new Map();
  let colorIdx = 0;
  const freqLineMap = new Map();
  for (const t of targets) {
    const key = `${t.networkId}:${t.targetId}`;
    if (!colorMap.has(key)) {
      colorMap.set(key, TARGET_COLORS[colorIdx % TARGET_COLORS.length]);
      colorIdx += 1;
    }
    t.color = colorMap.get(key);
    const fk = formatFreq(t.networkFreqMhz);
    if (!freqLineMap.has(fk)) {
      freqLineMap.set(fk, FREQ_LINE_TYPES[freqLineMap.size % FREQ_LINE_TYPES.length]);
    }
    t.lineType = freqLineMap.get(fk);
    const typeLabel = t.targetTypeLabel || "未知";
    const role = t.role ? ` · ${t.role}` : "";
    t.label = `${typeLabel}·${t.targetId}${role} @ ${fk}MHz`;
  }
  const clusterMeta = new Map();
  for (const t of targets) {
    const key = `${t.networkId}:${t.targetId}`;
    if (!clusterMeta.has(key)) {
      clusterMeta.set(key, { color: t.color, label: `${t.targetTypeLabel}·${t.targetId}` });
    }
  }
  return {
    matchClusters: [...clusterMeta.entries()].map(([key, meta], i) => ({
      id: i,
      color: meta.color,
      label: meta.label
    })),
    freqLineLegend: [...freqLineMap.entries()].map(([freq, lineType]) => ({ freq, lineType }))
  };
}

export async function loadMergedGroupView(forwardItem, signal, networkCache) {
  const analysisId = forwardItem?.session?.analysisId;
  const summaries = forwardItem?.session?.networks || [];
  const spanStart = forwardItem.spanStartEpochMs || 0;
  const spanEnd = forwardItem.spanEndEpochMs || 0;
  const memberRanks = forwardItem.memberRanks || [forwardItem.rank];

  if (!analysisId || !summaries.length) {
    return emptyView(memberRanks, spanStart, spanEnd);
  }

  const cache = networkCache || new Map();
  const targets = [];
  for (const sum of summaries) {
    const key = `${analysisId}-${sum.networkId}`;
    if (!cache.has(key)) {
      const raw = await fetchNetworkDetail(analysisId, sum.networkId, signal);
      cache.set(key, normalizeNetwork(raw));
    }
    const net = cache.get(key);
    for (const t of asArray(net.targets)) {
      const points = clipPoints(
        azimuthLinePoints(t.azimuthSeries),
        spanStart || -Infinity,
        spanEnd || Infinity
      );
      if (!points.length) continue;
      targets.push({
        key: `${analysisId}-${net.networkId}-${t.targetId}`,
        networkId: net.networkId,
        networkFreqMhz: net.freq,
        commMode: net.commMode,
        targetId: t.targetId,
        targetType: t.targetType,
        targetTypeLabel: resolveTargetTypeLabel(t),
        role: t.role,
        points
      });
    }
  }

  const { matchClusters, freqLineLegend } = assignAnalysisColors(targets);
  const bounds = axisBounds(targets);
  const ranksLabel = memberRanks.map((r) => `#${r}`).join("、");
  const bandHint = (forwardItem.memberBands || [])
    .map((b) => `#${b.rank} ${formatFreq((b.freqMinMhz + b.freqMaxMhz) / 2)}MHz`)
    .join(" · ");
  const timeTitle =
    spanStart && spanEnd
      ? `${formatClock(spanStart)} ~ ${formatClock(spanEnd)}`
      : bounds.xMin && bounds.xMax
        ? `${formatClock(bounds.xMin)} ~ ${formatClock(bounds.xMax)}`
        : "";

  return {
    title: timeTitle,
    subtitle: `场景 ${ranksLabel}${bandHint ? `（${bandHint}）` : ""} · ${matchClusters.length} 个分析目标 · ${targets.length} 条轨迹`,
    memberRanks,
    spanStartEpochMs: spanStart,
    spanEndEpochMs: spanEnd,
    matchClusters,
    freqLineLegend,
    targets,
    empty: !targets.length,
    ...bounds
  };
}

function emptyView(memberRanks, spanStart, spanEnd) {
  return {
    title: spanStart && spanEnd ? `${formatClock(spanStart)} ~ ${formatClock(spanEnd)}` : "—",
    subtitle: `场景 ${(memberRanks || []).map((r) => `#${r}`).join("、")}`,
    memberRanks: memberRanks || [],
    targets: [],
    matchClusters: [],
    freqLineLegend: [],
    empty: true,
    ...axisBounds([])
  };
}

export function buildMergedChartOption(view) {
  const series = (view.targets || []).map((t) => ({
    name: t.label,
    type: "line",
    showSymbol: (t.points || []).length <= 80,
    symbolSize: 4,
    connectNulls: true,
    lineStyle: {
      color: t.color,
      type: t.lineType || "solid",
      width: t.role === "MASTER" ? 2.5 : 1.5
    },
    itemStyle: { color: t.color },
    data: t.points
  }));

  return {
    tooltip: {
      trigger: "axis",
      formatter(params) {
        const p = params?.[0];
        if (!p) return "";
        const y = p.value?.[1];
        const yStr = y == null ? "—" : `${Number(y).toFixed(1)}°`;
        return `${formatClock(p.value[0])}<br/>${p.seriesName}: ${yStr}`;
      }
    },
    legend: { top: 4, right: 8, type: "scroll" },
    grid: { left: 56, right: 16, top: 36, bottom: 48 },
    xAxis: {
      type: "value",
      min: view.xMin,
      max: view.xMax,
      name: "时间",
      axisLabel: { formatter: (val) => formatClock(val) }
    },
    yAxis: {
      type: "value",
      min: view.yMin,
      max: view.yMax,
      name: "方位 (°)"
    },
    series
  };
}
