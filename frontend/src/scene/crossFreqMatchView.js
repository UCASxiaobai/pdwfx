import {
  buildOverlapGroupMatchedView,
  findAllOverlapGroups,
  trackReportFreqKey
} from "./bearingMatch.js";
import { combinedChartHeight, loadAllSceneAnalysisViews } from "./analysisBearing.js";
import { formatFreq } from "./sceneFilters.js";

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
}

/** 当前面板实际用到的频率颜色图例 */
export function panelFreqColorLegend(view) {
  const used = new Set();
  for (const f of view?.fusedTargets || []) {
    for (const s of f.segments || []) {
      if (s.freqKey) used.add(s.freqKey);
    }
  }
  return (view?.freqColorLegend || []).filter((f) => used.has(f.freq));
}

export function panelFusedTargets(view) {
  return (view?.fusedTargets || []).filter((f) => f.matched);
}

export function panelMatchClusters(view) {
  return (view?.matchClusters || []).filter((c) =>
    (view?.targets || []).some((t) => t.highlighted && t.matchClusterId === c.id)
  );
}

export function panelHighlightedFreqLegend(view) {
  const freqs = new Set(
    (view?.targets || [])
      .filter((t) => t.highlighted)
      .map((t) => trackReportFreqKey(t) || formatFreq(t.networkFreqMhz))
  );
  return (view?.freqColorLegend || []).filter((f) => freqs.has(f.freq));
}

export function panelFreqLineLegend(view) {
  const used = new Set((view?.targets || []).map((t) => formatFreq(t.networkFreqMhz)));
  return (view?.freqLineLegend || []).filter((f) => used.has(f.freq));
}

function fusedAxisTooltipFormatter(params) {
  if (!params?.length) return "";
  const byName = new Map();
  for (const p of params) {
    if (p?.value?.[1] == null) continue;
    if (!byName.has(p.seriesName)) byName.set(p.seriesName, p);
  }
  const rows = [...byName.values()];
  const lines = rows.map((p) => {
    const y = Number(p.value[1]).toFixed(1);
    const fk = p.data?.freqKey;
    const freqPart = fk ? ` · ${fk} MHz` : "";
    return `${p.marker || ""}${p.seriesName}: ${y}°${freqPart}`;
  });
  const t0 = rows[0]?.value?.[0];
  const head = t0 != null ? formatClock(t0) : "";
  return lines.length ? `${head}<br/>${lines.join("<br/>")}` : "";
}

function tracksAxisTooltipFormatter(params) {
  if (!params?.length) return "";
  const rows = params.filter((p) => p?.value?.[1] != null);
  const lines = rows.map((p) => {
    const y = Number(p.value[1]).toFixed(1);
    return `${p.marker || ""}${p.seriesName}: ${y}°`;
  });
  const t0 = rows[0]?.value?.[0];
  const head = t0 != null ? formatClock(t0) : "";
  return lines.length ? `${head}<br/>${lines.join("<br/>")}` : "";
}

function buildGroupsFromViews(sceneViews) {
  const groups = findAllOverlapGroups(sceneViews);
  if (!groups.length && sceneViews.length) {
    for (const v of sceneViews) {
      const tr = v.targets?.flatMap((t) => (t.points || []).map((p) => p[0])) || [];
      const start = tr.length ? Math.min(...tr) : 0;
      const end = tr.length ? Math.max(...tr) : 0;
      groups.push({ ranks: [v.sceneRank], spanStart: start, spanEnd: end });
    }
  }
  return groups;
}

/** 纯计算：已有 sceneViews 时按匹配参数生成面板（不请求 API） */
export function buildCrossFreqPanelsFromViews(sceneViews, matchOptions) {
  const groups = buildGroupsFromViews(sceneViews);
  return groups.map((group) => buildOverlapGroupMatchedView(group, sceneViews, matchOptions));
}

/**
 * 加载全部优质场景分析视图，按时间重合分组并做跨频/跨场景方位关联。
 */
export async function loadCrossFreqMatchPanels(
  forwardItems,
  sceneByRank,
  signal,
  matchOptions,
  allowedRanks,
  freqTolerance = 0.01
) {
  const sceneViews = await loadAllSceneAnalysisViews(
    forwardItems,
    sceneByRank,
    signal,
    freqTolerance,
    allowedRanks
  );
  return buildCrossFreqPanelsFromViews(sceneViews, matchOptions);
}

export function buildFusedChartOption(view) {
  const series = [];

  for (const fused of view.fusedTargets || []) {
    const segs = fused.segments || [];
    const seriesName = fused.shortLabel || fused.label;
    for (let si = 0; si < segs.length; si++) {
      const seg = segs[si];
      let data = (seg.points || []).map(([t, b]) => ({
        value: [t, b],
        freqKey: seg.freqKey
      }));
      if (si > 0 && segs[si - 1].points?.length) {
        const junction = segs[si - 1].points[segs[si - 1].points.length - 1];
        data = [
          { value: junction, freqKey: segs[si - 1].freqKey },
          ...data
        ];
      }
      series.push({
        name: seriesName,
        type: "line",
        showSymbol: data.length <= 80,
        symbolSize: 4,
        connectNulls: false,
        lineStyle: {
          color: seg.freqColor,
          width: fused.matched ? 2.5 : 1.5,
          opacity: fused.matched ? 1 : 0.55
        },
        itemStyle: {
          color: seg.freqColor,
          opacity: fused.matched ? 1 : 0.55
        },
        data
      });
    }
  }

  const legendData = panelFusedTargets(view).map((f) => f.shortLabel || f.label);

  return {
    tooltip: {
      trigger: "axis",
      formatter: fusedAxisTooltipFormatter,
      confine: true
    },
    legend: {
      top: 4,
      right: 8,
      type: "scroll",
      data: legendData
    },
    grid: { left: 56, right: 16, top: 40, bottom: 52 },
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
      name: "方位 (°)",
      splitNumber: 7
    },
    series
  };
}

export function crossFreqChartHeight(view) {
  const pseudo = (view?.fusedTargets || []).flatMap((f) =>
    (f.segments || []).flatMap((s) => s.points || [])
  );
  if (pseudo.length) {
    const ys = pseudo.map((p) => p[1]).filter((y) => y != null);
    if (ys.length) return combinedChartHeight(Math.max(...ys) - Math.min(...ys));
  }
  return combinedChartHeight(view?.targets || []);
}

export function buildMatchChartOption(view) {
  const series = (view.targets || []).map((t) => ({
    name: t.label,
    type: "line",
    showSymbol: (t.points || []).length <= 80,
    symbolSize: 4,
    connectNulls: true,
    lineStyle: {
      color: t.color,
      type: t.lineType || "solid",
      width: t.lineWidth || (t.role === "MASTER" ? 2.5 : 1.5),
      opacity: t.opacity ?? 1
    },
    itemStyle: { color: t.color, opacity: t.opacity ?? 1 },
    data: t.points
  }));

  const legendData = (view.targets || [])
    .filter((t) => t.highlighted)
    .map((t) => t.label);

  return {
    tooltip: {
      trigger: "axis",
      formatter: tracksAxisTooltipFormatter,
      confine: true
    },
    legend: {
      top: 4,
      right: 8,
      type: "scroll",
      data: legendData
    },
    grid: { left: 56, right: 16, top: 40, bottom: 52 },
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
      name: "方位 (°)",
      splitNumber: 7
    },
    series
  };
}
