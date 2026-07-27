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

/** 场景筛选/表格合并列：#1 轮询 */
export function formatSceneLabel(rank, sceneType) {
  return `#${rank} ${sceneTypeLabel(sceneType)}`;
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

export function filterTrajectoryViews(visualization, allowedRanks) {
  const views = visualization?.trajectoryViews || [];
  if (!allowedRanks || !allowedRanks.size) return [];
  return views.filter((v) => allowedRanks.has(v.sceneRank));
}

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
