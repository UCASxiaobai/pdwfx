export const TARGET_TYPE_OPTIONS = [
  { value: "", label: "全部目标类型" },
  { value: "GROUND", label: "地面站" },
  { value: "AWACS", label: "预警机" },
  { value: "AIR", label: "飞机" }
];

export function sceneTypeLabel(t) {
  if (t === "TRACK_CONTINUOUS") return "连续轨迹";
  if (t === "MULTI_DEVICE_POLLING" || t === "POLLING_MULTI_DEVICE") return "轮询";
  return t || "—";
}

export function isPollingSceneType(sceneType) {
  return sceneType === "MULTI_DEVICE_POLLING" || sceneType === "POLLING_MULTI_DEVICE";
}

/** 展示顺序：严格按预筛选序号（rank）升序，信号分析后也不重排类型 */
export function compareScenesForDisplay(a, b) {
  const rankA = Number(a.rank ?? a.sceneRank ?? 0);
  const rankB = Number(b.rank ?? b.sceneRank ?? 0);
  return rankA - rankB;
}

export function sortScenesForDisplay(list) {
  return [...(list || [])].sort(compareScenesForDisplay);
}

/**
 * 各可视化面板统一的场景 Tab 列表（按预筛选 rank 升序）。
 * @param {object} [opts]
 * @param {Set<number>} [opts.allowedRanks] 仅保留这些 rank
 * @param {Set<number>} [opts.requireAnalyzedRanks] 仅保留已完成信号分析的 rank
 */
export function buildDisplaySceneTabs(scenes, opts = {}) {
  const { allowedRanks = null, requireAnalyzedRanks = null } = opts;
  let list = (scenes || []).map((s) => ({
    rank: Number(s.rank ?? s.sceneRank),
    sceneType: s.sceneType,
    freqCenterMhz: s.freqCenterMhz ?? s.freqCenter ?? null,
    freqMinMhz: s.freqMinMhz ?? s.freqMin ?? null,
    freqMaxMhz: s.freqMaxMhz ?? s.freqMax ?? null
  }));
  if (allowedRanks?.size) {
    list = list.filter((t) => allowedRanks.has(t.rank));
  }
  if (requireAnalyzedRanks?.size) {
    list = list.filter((t) => requireAnalyzedRanks.has(t.rank));
  }
  return sortScenesForDisplay(list);
}

/** 按统一 Tab 顺序重排轨迹视图，保证与 buildDisplaySceneTabs 一一对应。 */
export function alignTrajectoryViewsToSceneTabs(trajectoryViews, sceneTabs) {
  const byRank = new Map();
  for (const v of trajectoryViews || []) {
    const rank = Number(v?.sceneRank);
    if (Number.isFinite(rank)) byRank.set(rank, v);
  }
  return (sceneTabs || [])
    .map((t) => {
      const rank = Number(t.rank);
      const view = byRank.get(rank);
      if (!view) return null;
      return {
        ...view,
        sceneRank: rank,
        sceneType: t.sceneType,
        freqCenterMhz: view.freqCenterMhz ?? t.freqCenterMhz ?? null,
        freqMinMhz: view.freqMinMhz ?? t.freqMinMhz ?? null,
        freqMaxMhz: view.freqMaxMhz ?? t.freqMaxMhz ?? null
      };
    })
    .filter(Boolean);
}

/** 轨迹视图按 sceneRank 索引（与 buildDisplaySceneTabs 配合）。 */
export function trajectoryViewsByRank(trajectoryViews) {
  const map = new Map();
  for (const v of trajectoryViews || []) {
    const rank = Number(v?.sceneRank);
    if (Number.isFinite(rank)) map.set(rank, v);
  }
  return map;
}

export function filterTrajectoryViews(visualization, allowedRanks, scenes = []) {
  if (!allowedRanks?.size) return [];
  const tabs = buildDisplaySceneTabs(scenes, { allowedRanks });
  const views = (visualization?.trajectoryViews || []).filter((v) =>
    allowedRanks.has(Number(v.sceneRank))
  );
  return alignTrajectoryViewsToSceneTabs(views, tabs);
}

function compareTrajectoryViewsForDisplay(a, b) {
  return Number(a.sceneRank ?? 0) - Number(b.sceneRank ?? 0);
}

export function sortTrajectoryViewsForDisplay(list) {
  return [...(list || [])].sort(compareTrajectoryViewsForDisplay);
}

/** 场景筛选/表格合并列：#1 轮询 · 123.456 MHz */
export function formatSceneLabel(rank, sceneType, freqInfo = null) {
  const base = `#${rank} ${sceneTypeLabel(sceneType)}`;
  const freq = formatSceneFreq(freqInfo);
  return freq ? `${base} · ${freq}` : base;
}

/** 与全局散点图例一致的场景频点标注 */
export function formatSceneFreq(s) {
  if (s == null) return "";
  if (typeof s === "number") {
    return Number.isFinite(s) && s > 0 ? `${formatFreq(s)} MHz` : "";
  }
  const center = Number(s.freqCenterMhz ?? s.freqCenter);
  const min = Number(s.freqMinMhz ?? s.freqMin);
  const max = Number(s.freqMaxMhz ?? s.freqMax);
  if (Number.isFinite(min) && Number.isFinite(max) && Math.abs(max - min) > 0.001) {
    return `${formatFreq(min)}–${formatFreq(max)} MHz`;
  }
  if (Number.isFinite(center) && center > 0) {
    return `${formatFreq(center)} MHz`;
  }
  if (Number.isFinite(min) && min > 0) {
    return `${formatFreq(min)} MHz`;
  }
  if (Number.isFinite(max) && max > 0) {
    return `${formatFreq(max)} MHz`;
  }
  return "";
}

export function targetTypeLabel(t) {
  if (t === "GROUND") return "地面站";
  if (t === "AWACS") return "预警机";
  if (t === "AIR") return "飞机";
  return t || "—";
}

export function commModeLabel(mode) {
  if (mode === "MULTI_FREQ") return "异频";
  if (mode === "HOPPING") return "定跳频";
  return mode === "SAME_FREQ" ? "同频" : mode || "—";
}

export function formatFreq(v, digits = 3) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(digits) : "—";
}

/**
 * 将信号分析得到的目标类型叠到场景方位轨迹（目标1 → 目标1-飞机）。
 * 优先按平均方位匹配；否则按频点；再退化为同场景顺序。
 */
export function applyStreamTargetTypeLabels(view, labels) {
  if (!view) return view;
  const rank = Number(view.sceneRank);
  const sceneLabels = (labels || []).filter((l) => Number(l.sceneRank) === rank);
  if (!sceneLabels.length) return view;

  const used = new Set();
  const pickLabel = (series, index) => {
    const mean = Number(series?.meanBearing);
    const freq = Number(series?.freqMhz);
    let best = null;
    let bestScore = Infinity;
    for (let i = 0; i < sceneLabels.length; i++) {
      if (used.has(i)) continue;
      const L = sceneLabels[i];
      const az = Number(L.meanAzimuthDeg);
      const lf = Number(L.freqMhz);
      let score;
      if (Number.isFinite(mean) && Number.isFinite(az)) {
        score = Math.abs(((mean - az + 540) % 360) - 180);
      } else if (Number.isFinite(freq) && Number.isFinite(lf)) {
        score = Math.abs(freq - lf) * 100;
      } else {
        score = Math.abs(i - index) + 500;
      }
      if (score < bestScore) {
        bestScore = score;
        best = { idx: i, label: L };
      }
    }
    return best;
  };

  const rename = (series, index, prefix) => {
    const hit = pickLabel(series, index);
    if (!hit) return series;
    used.add(hit.idx);
    const typeText =
      hit.label.targetTypeLabel
      || targetTypeLabel(hit.label.targetType)
      || "未知";
    return {
      ...series,
      targetType: hit.label.targetType || series.targetType,
      targetTypeLabel: typeText,
      label: `${prefix}${index + 1}-${typeText}`
    };
  };

  const next = { ...view };
  if (Array.isArray(view.tracks) && view.tracks.length) {
    next.tracks = view.tracks.map((t, i) => rename(t, i, "目标"));
  }
  if (Array.isArray(view.pollingTargets) && view.pollingTargets.length) {
    used.clear();
    next.pollingTargets = view.pollingTargets.map((t, i) => rename(t, i, "轮询目标"));
  }
  return next;
}

export function applyStreamTargetTypeLabelsToViews(views, labels) {
  return (views || []).map((v) => applyStreamTargetTypeLabels(v, labels));
}

export function formatTime(iso) {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString("zh-CN", { hour12: false });
  } catch {
    return iso;
  }
}

/** 按结果筛选条件过滤场景列表（场景筛选可视化用） */
export function filterScenes(scenes, filters, reportSceneRanks) {
  let list = scenes || [];
  if (filters.sceneRank) {
    list = list.filter((s) => String(s.rank) === String(filters.sceneRank));
  }
  if (filters.freqMin != null && filters.freqMin !== "") {
    const min = Number(filters.freqMin);
    if (Number.isFinite(min)) {
      list = list.filter(
        (s) => (s.freqMaxMhz ?? s.freqMax) >= min || (s.freqCenterMhz ?? s.freqCenter) >= min
      );
    }
  }
  if (filters.freqMax != null && filters.freqMax !== "") {
    const max = Number(filters.freqMax);
    if (Number.isFinite(max)) {
      list = list.filter(
        (s) => (s.freqMinMhz ?? s.freqMin) <= max || (s.freqCenterMhz ?? s.freqCenter) <= max
      );
    }
  }
  if (filters.timeStart) {
    const t0 = new Date(filters.timeStart).getTime();
    if (Number.isFinite(t0)) {
      list = list.filter((s) => {
        const end = s.windowEnd;
        return end && new Date(end).getTime() >= t0;
      });
    }
  }
  if (filters.timeEnd) {
    const t1 = new Date(filters.timeEnd).getTime();
    if (Number.isFinite(t1)) {
      list = list.filter((s) => {
        const start = s.windowStart;
        return start && new Date(start).getTime() <= t1;
      });
    }
  }
  if (reportSceneRanks && reportSceneRanks.size > 0) {
    list = list.filter((s) => reportSceneRanks.has(s.rank));
  }
  return list;
}

/** 按结果筛选条件过滤报表行（结果表格用） */
export function filterReportRows(rows, filters) {
  let list = rows || [];
  if (filters.sceneRank) {
    list = list.filter((r) => String(r.sceneRank) === String(filters.sceneRank));
  }
  if (filters.targetType) {
    list = list.filter((r) => r.targetType === filters.targetType);
  }
  if (filters.commLink) {
    list = list.filter(
      (r) =>
        (r.commLinkChannelLabel || r.commLinkChannel || "") === filters.commLink
    );
  }
  if (filters.freqMin != null && filters.freqMin !== "") {
    const min = Number(filters.freqMin);
    if (Number.isFinite(min)) {
      list = list.filter((r) => r.networkFreqMhz >= min || r.sceneFreqCenterMhz >= min);
    }
  }
  if (filters.freqMax != null && filters.freqMax !== "") {
    const max = Number(filters.freqMax);
    if (Number.isFinite(max)) {
      list = list.filter((r) => r.networkFreqMhz <= max || r.sceneFreqCenterMhz <= max);
    }
  }
  if (filters.timeStart) {
    const t0 = new Date(filters.timeStart).getTime();
    if (Number.isFinite(t0)) {
      list = list.filter((r) => {
        if (!r.windowEnd) return true;
        return new Date(r.windowEnd).getTime() >= t0;
      });
    }
  }
  if (filters.timeEnd) {
    const t1 = new Date(filters.timeEnd).getTime();
    if (Number.isFinite(t1)) {
      list = list.filter((r) => {
        if (!r.windowStart) return true;
        return new Date(r.windowStart).getTime() <= t1;
      });
    }
  }
  return list;
}

export function uniqueCommLinks(rows) {
  const set = new Set();
  for (const r of rows || []) {
    const v = r.commLinkChannelLabel || r.commLinkChannel;
    if (v) set.add(v);
  }
  return [...set].sort();
}

export function aggregateStats(rows) {
  const sceneRanks = new Set();
  const networks = new Set();
  const byTargetType = {};
  const byCommLink = {};
  for (const r of rows) {
    sceneRanks.add(r.sceneRank);
    networks.add(`${r.analysisId}-${r.networkId}`);
    const tt = r.targetType || "UNKNOWN";
    byTargetType[tt] = (byTargetType[tt] || 0) + 1;
    const cl = r.commLinkChannelLabel || r.commLinkChannel || "未研判";
    byCommLink[cl] = (byCommLink[cl] || 0) + 1;
  }
  return {
    sceneCount: sceneRanks.size,
    networkCount: networks.size,
    rowCount: rows.length,
    byTargetType,
    byCommLink
  };
}
