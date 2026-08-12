import {
  loadCachedNetwork,
  analysisViewCacheKey,
  resolveCachedSession,
  viewByRank
} from "./analysisViewCache.js";
import { formatFreq, sceneTypeLabel, sortScenesForDisplay, targetTypeLabel, buildDisplaySceneTabs } from "./sceneFilters.js";
import { isPollingSceneType } from "./pollingBurstMark.js";
import { buildPerTargetPollingLine, connectSameTargetBearingLine } from "./pollingLaneTracks.js";
import { asArray } from "./signalUi.js";

export const TARGET_COLORS = [
  "#ef4444", "#2563eb", "#16a34a", "#f59e0b", "#7c3aed", "#0f766e",
  "#db2777", "#0891b2", "#ca8a04", "#4f46e5", "#059669", "#dc2626"
];

function toEpochMs(raw) {
  const t = Number(raw);
  if (!Number.isFinite(t) || t <= 0) return 0;
  if (t < 1e11) return t * 1000;
  if (t < 1e14) return t;
  if (t < 1e15) return t;
  if (t >= 1e9 && t < 1e12) return t * 1000;
  return 0;
}

export function resolveTargetTypeLabel(t) {
  const raw = t?.targetTypeLabel || targetTypeLabel(t?.targetType);
  if (!raw || raw === "—") return "未知";
  return raw;
}

/** 从明细表构建目标类型索引（以研判结果为准）。 */
export function buildReportTypeIndex(reportRows, sceneRank = null) {
  const index = new Map();
  for (const row of reportRows || []) {
    if (sceneRank != null && row.sceneRank !== sceneRank) continue;
    const type = row.targetType;
    if (!type) continue;
    const freqKey = formatFreq(row.networkFreqMhz);
    const keys = [
      `${row.sceneRank}|${row.networkId}|${row.targetId}|${freqKey}`,
      `${row.sceneRank}|${row.networkId}|${row.targetId}`,
      `${row.networkId}|${row.targetId}|${freqKey}`,
      `${row.networkId}|${row.targetId}`
    ];
    for (const key of keys) {
      index.set(key, type);
    }
  }
  return index;
}

export function lookupReportTargetType(typeIndex, sceneRank, networkId, targetId, freqMhz) {
  if (!typeIndex?.size || networkId == null || targetId == null) return null;
  const freqKey = formatFreq(freqMhz);
  return (
    typeIndex.get(`${sceneRank}|${networkId}|${targetId}|${freqKey}`)
    ?? typeIndex.get(`${sceneRank}|${networkId}|${targetId}`)
    ?? typeIndex.get(`${networkId}|${targetId}|${freqKey}`)
    ?? typeIndex.get(`${networkId}|${targetId}`)
  );
}

/**
 * 将明细表中的目标类型同步到方位轨迹图（以 networkId+targetId 对齐分析目标）。
 */
export function applyReportTargetTypes(view, reportRows, sceneRank) {
  if (!view) return view;
  const typeIndex = buildReportTypeIndex(reportRows, sceneRank);
  if (!typeIndex.size) return view;

  const patchTarget = (t) => {
    const freq = t.reportNetworkFreqMhz ?? t.networkFreqMhz;
    const reported = lookupReportTargetType(typeIndex, sceneRank, t.networkId, t.targetId, freq);

    if (reported) {
      const meta = { targetType: reported, targetTypeLabel: targetTypeLabel(reported) };
      return {
        ...t,
        targetType: reported,
        targetTypeLabel: resolveTargetTypeLabel(meta)
      };
    }
    return t;
  };

  const rebuildGroups = (targetList) => {
    const patched = assignTargetDisplayLabels((targetList || []).map(patchTarget));
    return enrichFreqGroups(groupTargetsByFreq(patched));
  };

  const pollingOpts = { polling: !!view.pollingLaneMode };
  let bearingChart = view.bearingChart;
  if (bearingChart) {
    const freqGroups = rebuildGroups(bearingChart.targets);
    const plotTargets = freqGroups.flatMap((g) => g.targets);
    bearingChart = {
      ...bearingChart,
      targets: plotTargets,
      freqGroups,
      ...axisBounds(plotTargets, [], pollingOpts),
      ySpan: axisBoundsTight(plotTargets, [], pollingOpts).ySpan
    };
  }

  const freqGroups = bearingChart?.freqGroups?.length
    ? bearingChart.freqGroups
    : rebuildGroups(view.targets);
  const targets = freqGroups.flatMap((g) => g.targets);
  const bounds = axisBounds(targets, [], pollingOpts);

  return {
    ...view,
    bearingChart,
    pollingLaneMode: view.pollingLaneMode,
    freqGroups,
    targets,
    targetCount: targets.length,
    freqCount: freqGroups.length,
    ...bounds,
    ySpan: axisBoundsTight(targets, [], pollingOpts).ySpan
  };
}

export function reportRowsSignature(rows) {
  return (rows || [])
    .map(
      (r) =>
        `${r.sceneRank}|${r.networkId}|${r.targetId}|${r.targetType}|${r.networkFreqMhz}`
    )
    .join(";");
}

/** 图例/序列名：目标1-飞机 */
export function buildTargetDisplayLabel(index, t) {
  return `目标${index}-${resolveTargetTypeLabel(t)}`;
}

/** 同场景内按 networkId+targetId 分配稳定序号与展示名 */
export function assignTargetDisplayLabels(targets) {
  const idToIndex = new Map();
  for (const t of targets) {
    const key = `${t.networkId}:${t.targetId}`;
    if (!idToIndex.has(key)) {
      idToIndex.set(key, idToIndex.size + 1);
    }
  }
  return targets.map((t) => {
    const targetDisplayIndex = idToIndex.get(`${t.networkId}:${t.targetId}`);
    const displayLabel = buildTargetDisplayLabel(targetDisplayIndex, t);
    return { ...t, targetDisplayIndex, displayLabel };
  });
}

function azimuthLinePoints(azimuthSeries) {
  // 同一分析目标：按时序连线并用 unwrap，避免因方位跳变插入断点
  const pts = asArray(azimuthSeries).map((p) => {
    if (Array.isArray(p)) return [Number(p[0]), Number(p[1])];
    return [toEpochMs(p?.t), Number(p?.v ?? p?.b)];
  }).filter((p) => Number.isFinite(p[0]) && p[0] > 0 && Number.isFinite(p[1]));
  return connectSameTargetBearingLine(pts);
}

const FREQ_LINE_TYPES = ["solid", "dashed", "dotted", [8, 4], [2, 6], [12, 4, 2, 4]];

function collectPointBounds(targets, extraPoints = []) {
  let xMin = Infinity;
  let xMax = -Infinity;
  let yMin = Infinity;
  let yMax = -Infinity;
  const ingest = (x, y) => {
    if (y == null || !Number.isFinite(x) || !Number.isFinite(y)) return;
    if (x < xMin) xMin = x;
    if (x > xMax) xMax = x;
    if (y < yMin) yMin = y;
    if (y > yMax) yMax = y;
  };
  for (const t of targets) {
    for (const [x, y] of t.points || []) {
      ingest(x, y);
    }
  }
  for (const p of extraPoints) {
    if (Array.isArray(p)) ingest(p[0], p[1]);
  }
  return { xMin, xMax, yMin, yMax };
}

function axisBounds(targets, extraPoints = [], opts = {}) {
  const { xMin, xMax, yMin, yMax } = collectPointBounds(targets, extraPoints);
  if (!Number.isFinite(xMin)) {
    return { xMin: 0, xMax: 1, yMin: 0, yMax: 360 };
  }
  const span = Math.max(0, yMax - yMin);
  const yPad = opts.polling
    ? Math.max(3, span * 0.1)
    : Math.max(4, span * 0.12);
  let yLo = Math.max(0, yMin - yPad);
  let yHi = Math.min(360, yMax + yPad);
  const minSpan = opts.polling ? 10 : 24;
  if (yHi - yLo < minSpan) {
    const mid = (yHi + yLo) / 2;
    yLo = Math.max(0, mid - minSpan / 2);
    yHi = Math.min(360, mid + minSpan / 2);
  }
  return {
    xMin,
    xMax: xMax === xMin ? xMax + 1000 : xMax,
    yMin: yLo,
    yMax: yHi
  };
}

/** 方位轴收紧留白，使单位角度占更多纵向像素 */
export function axisBoundsTight(targets, extraPoints = [], opts = {}) {
  const { xMin, xMax, yMin, yMax } = collectPointBounds(targets, extraPoints);
  if (!Number.isFinite(xMin)) {
    return { xMin: 0, xMax: 1, yMin: 0, yMax: 360, ySpan: 360 };
  }
  const span = Math.max(6, yMax - yMin);
  const yPad = opts.polling
    ? Math.max(3, span * 0.1)
    : Math.max(1.5, span * 0.03);
  let yLo = Math.max(0, yMin - yPad);
  let yHi = Math.min(360, yMax + yPad);
  const minSpan = opts.polling ? 10 : 8;
  if (yHi - yLo < minSpan) {
    const mid = (yHi + yLo) / 2;
    yLo = Math.max(0, mid - minSpan / 2);
    yHi = Math.min(360, mid + minSpan / 2);
  }
  return {
    xMin,
    xMax: xMax === xMin ? xMax + 1000 : xMax,
    yMin: yLo,
    yMax: yHi,
    ySpan: yHi - yLo
  };
}

export const COMBINED_CHART_MIN_HEIGHT = 480;
export const COMBINED_CHART_MAX_HEIGHT = 760;
export const PX_PER_DEGREE = 20;

export function combinedChartHeight(targetsOrSpan) {
  const span =
    typeof targetsOrSpan === "number"
      ? targetsOrSpan
      : axisBoundsTight(targetsOrSpan || []).ySpan || 30;
  return Math.min(
    COMBINED_CHART_MAX_HEIGHT,
    Math.max(COMBINED_CHART_MIN_HEIGHT, Math.round(span * PX_PER_DEGREE + 140))
  );
}

export const FACET_PANEL_HEIGHT = 200;
export const FACET_PANEL_GAP = 40;
export const FACET_TOP_PAD = 16;
export const FACET_BOTTOM_PAD = 56;

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
}

function parseSceneTimeMs(value) {
  if (value == null || value === "") return null;
  if (typeof value === "number" && Number.isFinite(value)) return value;
  const t = new Date(value).getTime();
  return Number.isFinite(t) ? t : null;
}

function groupTargetsByFreq(targets) {
  const map = new Map();
  for (const t of targets) {
    const key = formatFreq(t.networkFreqMhz);
    if (!map.has(key)) {
      map.set(key, { freqKey: key, freqMhz: t.networkFreqMhz, targets: [] });
    }
    map.get(key).targets.push(t);
  }
  return [...map.values()].sort((a, b) => a.freqMhz - b.freqMhz);
}

function enrichFreqGroups(groups) {
  const colorMap = new Map();
  let colorIdx = 0;
  return groups.map((g) => {
    const targets = g.targets.map((t) => {
      const key = `${t.networkId}:${t.targetId}`;
      if (!colorMap.has(key)) {
        colorMap.set(key, TARGET_COLORS[colorIdx % TARGET_COLORS.length]);
        colorIdx += 1;
      }
      return {
        ...t,
        color: colorMap.get(key),
        label: t.displayLabel || buildTargetDisplayLabel(0, t)
      };
    });
    return {
      ...g,
      targets,
      targetCount: targets.length,
      ...axisBounds(targets)
    };
  });
}

function pointsForFreqBand(azimuthSeries, freqSeries, freqMhz, tol = 0.015) {
  const az = asArray(azimuthSeries);
  const fq = asArray(freqSeries);
  if (!az.length) return [];
  const out = [];
  for (let i = 0; i < az.length; i++) {
    const t = toEpochMs(az[i]?.t);
    const bearing = Number(az[i]?.v);
    const f = Number(fq[i]?.v ?? fq.find((p) => toEpochMs(p.t) === t)?.v);
    if (!Number.isFinite(t) || t <= 0 || !Number.isFinite(bearing)) continue;
    if (Number.isFinite(f) && Math.abs(f - freqMhz) <= tol) {
      out.push([t, bearing]);
    }
  }
  return connectSameTargetBearingLine(out);
}

export function buildSceneAnalysisView(sceneMeta, item, networks) {
  const rawTargets = [];
  const freqCenter = sceneMeta?.freqCenterMhz ?? sceneMeta?.freqCenter;
  const freqMin = sceneMeta?.freqMinMhz ?? sceneMeta?.freqMin;
  const freqMax = sceneMeta?.freqMaxMhz ?? sceneMeta?.freqMax;

  for (const net of networks) {
    if (!net) continue;
    for (const t of asArray(net.targets)) {
      const commFreqs = (t.commFreqMhzList || [])
        .map(Number)
        .filter((f) => Number.isFinite(f) && f > 0);
      const freqBands = commFreqs.length > 1 ? commFreqs : [net.freq];
      for (const bandFreq of freqBands) {
        const points =
          commFreqs.length > 1
            ? pointsForFreqBand(t.azimuthSeries, t.freqSeries, bandFreq)
            : azimuthLinePoints(t.azimuthSeries);
        if (!points.length) continue;
        const typeLabel = resolveTargetTypeLabel(t);
        const reportNetworkFreqMhz = Number(net.freq);
        rawTargets.push({
          key: `${item.rank}-${net.networkId}-${t.targetId}-${formatFreq(bandFreq)}`,
          sceneRank: item.rank,
          sceneType: item.sceneType,
          sceneFreqCenterMhz: freqCenter,
          networkId: net.networkId,
          /** 通信频段（异频拆轨时用于取点/着色） */
          networkFreqMhz: bandFreq,
          /** 与明细表「网络频率」一致 */
          reportNetworkFreqMhz,
          commBandFreqMhz: commFreqs.length > 1 ? bandFreq : null,
          commLinkChannel: net.commLinkChannel || "",
          commLinkChannelLabel: net.commLinkChannelLabel || "",
          targetId: t.targetId,
          targetType: t.targetType,
          targetTypeLabel: typeLabel,
          role: t.role,
          points,
          crossFreqMerged: commFreqs.length > 1
        });
      }
    }
  }

  const freqGroups = enrichFreqGroups(
    groupTargetsByFreq(assignTargetDisplayLabels(rawTargets))
  );
  const targets = freqGroups.flatMap((g) => g.targets);
  const bounds = axisBounds(targets);

  let freqLabel = "";
  if (freqCenter != null && Number.isFinite(Number(freqCenter))) {
    freqLabel = `场景频点 ${formatFreq(freqCenter)} MHz`;
    if (freqMin != null && freqMax != null) {
      freqLabel += `（${formatFreq(freqMin)}~${formatFreq(freqMax)}）`;
    }
  }

  const times = targets.flatMap((t) =>
    (t.points || []).map((p) => p[0]).filter((x) => Number.isFinite(x))
  );
  const timeRange =
    times.length >= 2
      ? `${formatClock(Math.min(...times))} ~ ${formatClock(Math.max(...times))}`
      : "";

  const clusteringMethod =
    networks.map((n) => n?.targetClusteringMethod).find((m) => m === "POSITION_MATCH") || "DBSCAN";

  const sceneType = item.sceneType ?? sceneMeta?.sceneType;
  const pollingPeriodSec = Number(sceneMeta?.pollingPeriodSec ?? item?.pollingPeriodSec);
  const pollingOpts = { polling: isPollingSceneType(sceneType) };

  /**
   * 轮询方位图：按信号分析目标分别建轨，每轮取一点并跨轮连成完整目标轨迹。
   * 已判定为同一目标的点默认不断线（breakOnGap=false）。
   */
  let bearingChart = null;
  let pollingLaneMode = false;
  if (isPollingSceneType(sceneType) && targets.length) {
    pollingLaneMode = true;
    const pollingLineOpts = {
      periodSec: pollingPeriodSec > 0 ? pollingPeriodSec : 0,
      windowStartMs: parseSceneTimeMs(sceneMeta?.windowStart ?? item?.windowStart),
      windowEndMs: parseSceneTimeMs(sceneMeta?.windowEnd ?? item?.windowEnd),
      breakOnGap: false
    };
    const sortedTargets = [...targets].sort((a, b) =>
      String(a.targetId || "").localeCompare(String(b.targetId || ""), undefined, { numeric: true })
    );
    const chartTargets = assignTargetDisplayLabels(
      sortedTargets.map((t) => ({
        ...t,
        pollingLane: true,
        points: buildPerTargetPollingLine(t.points, pollingLineOpts)
      }))
    );
    const chartFreqGroups = enrichFreqGroups(groupTargetsByFreq(chartTargets));
    const plotTargets = chartFreqGroups.flatMap((g) => g.targets);
    bearingChart = {
      targets: plotTargets,
      freqGroups: chartFreqGroups,
      preferFacet: chartFreqGroups.length > 1,
      ...axisBounds(plotTargets, [], pollingOpts),
      ySpan: axisBoundsTight(plotTargets, [], pollingOpts).ySpan
    };
  }

  return {
    sceneRank: item.rank,
    sceneType: item.sceneType,
    title: `场景 #${item.rank} ${sceneTypeLabel(item.sceneType)}`,
    freqCenterMhz: freqCenter,
    freqLabel,
    timeRange,
    targetCount: targets.length,
    freqCount: freqGroups.length,
    networkCount: networks.length,
    clusteringMethod,
    pollingLaneMode,
    bearingChart,
    freqGroups,
    targets,
    preferFacet: freqGroups.length > 1,
    ...bounds
  };
}

export function findForwardItemForRank(forwardItems, rank) {
  const want = Number(rank);
  if (!Number.isFinite(want)) return undefined;
  return (forwardItems || []).find((x) => Number(x.rank) === want);
}

export function findForwardItem(forwardItems, unitKey) {
  return (forwardItems || []).find((x) => (x.unitKey || String(x.rank)) === unitKey);
}

const inflightByKey = new Map();

export async function getOrLoadSceneAnalysisView(
  item,
  sceneMeta,
  signal,
  freqTolerance = 0.01
) {
  const key = analysisViewCacheKey(item);
  if (key && viewByRank.has(key)) {
    return viewByRank.get(key);
  }
  if (key && inflightByKey.has(key)) {
    return inflightByKey.get(key);
  }

  const loadPromise = loadSceneAnalysisViewOnce(item, sceneMeta, signal, freqTolerance);
  if (key) inflightByKey.set(key, loadPromise);
  try {
    return await loadPromise;
  } finally {
    if (key) inflightByKey.delete(key);
  }
}

async function loadSceneAnalysisViewOnce(
  item,
  sceneMeta,
  signal,
  freqTolerance
) {
  const key = analysisViewCacheKey(item);
  const session = await resolveCachedSession(item, signal, freqTolerance);
  const analysisId = session?.analysisId;
  const summaries = session?.networks || [];
  if (!analysisId || !summaries.length) {
    const empty = buildSceneAnalysisView(sceneMeta, item, []);
    if (key) viewByRank.set(key, empty);
    return empty;
  }

  const networks = [];
  for (const sum of summaries) {
    if (signal?.aborted) {
      throw new DOMException("Aborted", "AbortError");
    }
    networks.push(
      await loadCachedNetwork(analysisId, sum.networkId, item, signal, freqTolerance)
    );
  }

  const view = buildSceneAnalysisView(sceneMeta, item, networks);
  if (key) viewByRank.set(key, view);
  return view;
}

/** @deprecated 使用 getOrLoadSceneAnalysisView */
export async function loadSceneAnalysisView(
  item,
  sceneMeta,
  signal,
  _networkCache,
  freqTolerance = 0.01
) {
  return getOrLoadSceneAnalysisView(item, sceneMeta, signal, freqTolerance);
}

export function filterForwardItems(forwardItems, allowedRanks) {
  const list = forwardItems || [];
  if (!allowedRanks || allowedRanks.size === 0) return sortScenesForDisplay(list);
  return sortScenesForDisplay(list.filter((it) => allowedRanks.has(it.rank)));
}

export async function loadAllSceneAnalysisViews(
  forwardItems,
  sceneByRank,
  signal,
  freqTolerance,
  allowedRanks,
  onProgress
) {
  const analyzed = new Set(
    (forwardItems || []).map((f) => Number(f.rank)).filter(Number.isFinite)
  );
  const tabs = buildDisplaySceneTabs([...(sceneByRank?.values?.() || [])], {
    allowedRanks,
    requireAnalyzedRanks: analyzed.size ? analyzed : null
  });
  const itemByRank = new Map(
    (forwardItems || [])
      .map((f) => [Number(f.rank), f])
      .filter(([r]) => Number.isFinite(r))
  );
  const views = [];
  for (let i = 0; i < tabs.length; i++) {
    if (signal?.aborted) {
      throw new DOMException("Aborted", "AbortError");
    }
    const tab = tabs[i];
    const item = itemByRank.get(tab.rank);
    if (!item) continue;
    onProgress?.(views.length + 1, tabs.length, tab.rank);
    const view = await getOrLoadSceneAnalysisView(
      item,
      sceneByRank?.get(tab.rank),
      signal,
      freqTolerance
    );
    if (view.targets?.length) views.push(view);
  }
  return views;
}

export function facetChartHeight(freqGroupCount) {
  if (freqGroupCount <= 1) return 420;
  return (
    FACET_TOP_PAD
    + freqGroupCount * FACET_PANEL_HEIGHT
    + (freqGroupCount - 1) * FACET_PANEL_GAP
    + FACET_BOTTOM_PAD
  );
}

function formatClockForAxis(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
}

function seriesAxisIndex(param) {
  return param?.axisIndex ?? param?.xAxisIndex ?? 0;
}

/** 分面子图 tooltip：仅展示鼠标所在子图内的序列 */
function facetAxisTooltipFormatter(params) {
  if (!params?.length) return "";
  const axisIdx = seriesAxisIndex(params[0]);
  const rows = params.filter(
    (p) => seriesAxisIndex(p) === axisIdx && p?.value?.[1] != null
  );
  if (!rows.length) return "";
  const lines = rows.map((p) => {
    const y = Number(p.value[1]).toFixed(1);
    return `${p.marker || ""}${p.seriesName}: ${y}°`;
  });
  const t0 = rows[0]?.value?.[0];
  const head = t0 != null ? formatClockForAxis(t0) : "";
  return head ? `${head}<br/>${lines.join("<br/>")}` : lines.join("<br/>");
}

function flattenGroupsToSeries(list) {
  const freqLineMap = new Map();
  const targets = [];
  for (const g of list) {
    for (const t of g.targets || []) {
      if (!freqLineMap.has(g.freqKey)) {
        freqLineMap.set(g.freqKey, FREQ_LINE_TYPES[freqLineMap.size % FREQ_LINE_TYPES.length]);
      }
      targets.push({
        ...t,
        freqKey: g.freqKey,
        seriesName: `${g.freqKey}MHz · ${t.label}`,
        lineType: freqLineMap.get(g.freqKey)
      });
    }
  }
  const freqLineLegend = [...freqLineMap.entries()].map(([freq, lineType]) => ({
    freq,
    lineType
  }));
  return { targets, freqLineLegend };
}

function combinedAxisTooltipFormatter(params) {
  if (!params?.length) return "";
  const rows = params.filter((p) => p?.value?.[1] != null);
  const lines = rows.map((p) => {
    const y = Number(p.value[1]).toFixed(1);
    return `${p.marker || ""}${p.seriesName}: ${y}°`;
  });
  const t0 = rows[0]?.value?.[0];
  const head = t0 != null ? formatClockForAxis(t0) : "";
  return head ? `${head}<br/>${lines.join("<br/>")}` : lines.join("<br/>");
}

/** 同场景多频点合并为单张时间-方位图（线型区分频率） */
export function buildCombinedSceneChartOption(view, groups) {
  const list = groups?.length ? groups : view.freqGroups || [];
  const { targets, freqLineLegend } = flattenGroupsToSeries(list);
  const plotTargets = targets.length ? targets : view.targets || [];
  const pollingOpts = { polling: !!view.pollingLaneMode };
  const bounds = axisBoundsTight(plotTargets, [], pollingOpts);

  return {
    targets: plotTargets,
    freqLineLegend,
    bounds,
    option: {
      tooltip: {
        trigger: "axis",
        formatter: combinedAxisTooltipFormatter,
        confine: true
      },
      legend: {
        top: 4,
        right: 8,
        type: "scroll",
        data: plotTargets.map((t) => t.seriesName || t.label)
      },
      grid: { left: 64, right: 20, top: 52, bottom: 56, containLabel: true },
      xAxis: {
        type: "value",
        min: bounds.xMin,
        max: bounds.xMax,
        name: "时间",
        axisLabel: { formatter: (val) => formatClockForAxis(val) }
      },
      yAxis: {
        type: "value",
        min: bounds.yMin,
        max: bounds.yMax,
        name: "方位 (°)",
        splitNumber: 7
      },
      series: plotTargets.map((t) => ({
        name: t.seriesName || t.label,
        type: "line",
        showSymbol: (t.points || []).length <= 80,
        symbolSize: 4,
        // 同一分析目标始终连线（含轮询跨轮）
        connectNulls: true,
        lineStyle: {
          width: t.role === "MASTER" ? 2.5 : 1.5,
          color: t.color,
          type: t.lineType || "solid"
        },
        itemStyle: { color: t.color },
        data: t.points
      }))
    }
  };
}

/** 多频点分面子图（仅在选择单一频点筛选时使用） */
export function buildFacetChartOption(view, groups) {
  const list = groups?.length ? groups : view.freqGroups || [];
  const n = list.length;
  if (n <= 1) {
    const targets = list[0]?.targets || view.targets || [];
    const pollingOpts = { polling: !!view.pollingLaneMode };
    const bounds = axisBoundsTight(targets, [], pollingOpts);
    return {
      tooltip: { trigger: "axis", formatter: facetAxisTooltipFormatter, confine: true },
      legend: { top: 4, right: 8, type: "scroll" },
      grid: { left: 64, right: 20, top: 44, bottom: 52, containLabel: true },
      xAxis: {
        type: "value",
        min: bounds.xMin,
        max: bounds.xMax,
        name: "时间",
        axisLabel: { formatter: (val) => formatClockForAxis(val) }
      },
      yAxis: {
        type: "value",
        min: bounds.yMin,
        max: bounds.yMax,
        name: "方位 (°)",
        splitNumber: 7
      },
      series: targets.map((t) => ({
        name: t.label,
        type: "line",
        showSymbol: (t.points || []).length <= 60,
        symbolSize: 4,
        connectNulls: true,
        lineStyle: { width: t.role === "MASTER" ? 2.5 : 1.5, color: t.color },
        itemStyle: { color: t.color },
        data: t.points
      }))
    };
  }

  const xMin = view.xMin;
  const xMax = view.xMax;
  const grid = [];
  const xAxis = [];
  const yAxis = [];
  const title = [];
  const legend = [];
  const series = [];
  const titleBand = 24;
  const legendBand = 18;
  let topPx = FACET_TOP_PAD;

  list.forEach((g, i) => {
    const panelTop = topPx + titleBand + legendBand;
    grid.push({
      left: 72,
      right: 20,
      top: panelTop,
      height: FACET_PANEL_HEIGHT - titleBand - legendBand,
      containLabel: true
    });
    title.push({
      text: `${g.freqKey} MHz · ${g.targetCount} 目标`,
      left: 76,
      top: topPx + 6,
      textStyle: { fontSize: 12, fontWeight: 600, color: "#1d4ed8" }
    });
    const legendNames = g.targets.map((t) => `${g.freqKey}MHz · ${t.label}`);
    legend.push({
      type: "scroll",
      orient: "horizontal",
      left: 76,
      right: 20,
      top: topPx + titleBand - 2,
      height: legendBand,
      itemWidth: 10,
      itemHeight: 8,
      textStyle: { fontSize: 10, color: "#374151" },
      data: legendNames
    });
    xAxis.push({
      gridIndex: i,
      type: "value",
      min: xMin,
      max: xMax,
      show: i === n - 1,
      name: i === n - 1 ? "时间" : "",
      nameGap: 28,
      axisLabel: {
        formatter: (val) => formatClockForAxis(val),
        margin: 10
      }
    });
    yAxis.push({
      gridIndex: i,
      type: "value",
      min: g.yMin,
      max: g.yMax,
      name: "方位°",
      nameGap: 8,
      nameTextStyle: { fontSize: 10, color: "#6b7280" },
      splitNumber: 4,
      axisLabel: { fontSize: 10 }
    });
    for (const t of g.targets) {
      const dense = (t.points || []).length > 80;
      series.push({
        name: `${g.freqKey}MHz · ${t.label}`,
        type: "line",
        xAxisIndex: i,
        yAxisIndex: i,
        legendIndex: i,
        showSymbol: !dense,
        symbolSize: 3,
        connectNulls: true,
        lineStyle: { width: t.role === "MASTER" ? 2.5 : 1.5, color: t.color },
        itemStyle: { color: t.color },
        data: t.points
      });
    }
    topPx += FACET_PANEL_HEIGHT + FACET_PANEL_GAP;
  });

  return {
    title,
    legend,
    tooltip: {
      trigger: "axis",
      formatter: facetAxisTooltipFormatter,
      confine: true
    },
    grid,
    xAxis,
    yAxis,
    series
  };
}
