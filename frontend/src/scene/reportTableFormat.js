import { formatSceneLabel } from "./sceneFilters.js";

/** 为明细行附加合并列元数据（场景、起止时间、网络、网络频率），不改动原有行数据。 */

function formatDateTimeFull(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const pad = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} `
    + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export function formatTimeRangeParts(start, end) {
  return {
    start: formatDateTimeFull(start),
    end: formatDateTimeFull(end)
  };
}

function sceneByRank(sceneResult) {
  const map = new Map();
  for (const s of sceneResult?.scenes || []) {
    const rank = Number(s.rank);
    if (Number.isFinite(rank)) map.set(rank, s);
  }
  return map;
}

export function resolveSceneLabel(sceneRank, sceneType, sceneResult) {
  const rank = Number(sceneRank);
  const scene = sceneByRank(sceneResult).get(rank);
  const type = sceneType || scene?.sceneType;
  return formatSceneLabel(rank, type);
}

function networkGroupKey(row) {
  return `${row.analysisId}|${row.networkId}|${row.networkFreqMhz}`;
}

function channelKey(row) {
  return row.commLinkChannelLabel || row.commLinkChannel || "—";
}

/** 在 list[start, end) 范围内计算合并单元格 */
export function applyMergeRowspans(list, start = 0, end = list.length) {
  for (let idx = start; idx < end; idx++) {
    list[idx].showScene = false;
    list[idx].showTime = false;
    list[idx].showNetwork = false;
    list[idx].showNetworkFreq = false;
    list[idx].showChannel = false;
  }

  let i = start;
  while (i < end) {
    const rank = list[i].sceneRank;
    let j = i + 1;
    while (j < end && list[j].sceneRank === rank) j++;
    const sceneSpan = j - i;
    list[i].spanScene = sceneSpan;
    list[i].spanTime = sceneSpan;
    list[i].showScene = true;
    list[i].showTime = true;

    let k = i;
    while (k < j) {
      const netKey = networkGroupKey(list[k]);
      let m = k + 1;
      while (m < j && networkGroupKey(list[m]) === netKey) m++;
      const netSpan = m - k;
      list[k].spanNetwork = netSpan;
      list[k].spanNetworkFreq = netSpan;
      list[k].showNetwork = true;
      list[k].showNetworkFreq = true;
      k = m;
    }

    let c = i;
    while (c < j) {
      const ch = channelKey(list[c]);
      let d = c + 1;
      while (d < j && channelKey(list[d]) === ch) d++;
      const chSpan = d - c;
      list[c].spanChannel = chSpan;
      list[c].showChannel = true;
      for (let e = c + 1; e < d; e++) {
        list[e].showChannel = false;
        list[e].spanChannel = 1;
      }
      c = d;
    }
    i = j;
  }
  return list;
}

export function applySceneRowspans(rows, sceneResult) {
  const scenes = sceneByRank(sceneResult);
  const list = (rows || []).map((r) => {
    const rank = Number(r.sceneRank);
    const scene = scenes.get(rank);
    const timeParts = formatTimeRangeParts(r.windowStart, r.windowEnd);
    return {
      ...r,
      sceneRank: rank,
      sceneType: r.sceneType || scene?.sceneType,
      sceneLabel: resolveSceneLabel(rank, r.sceneType || scene?.sceneType, sceneResult),
      timeStartLabel: timeParts.start,
      timeEndLabel: timeParts.end
    };
  });

  // 与场景 Tab / 预筛选序号一致：按 sceneRank 升序，不按类型重排
  list.sort((a, b) => {
    if (a.sceneRank !== b.sceneRank) return a.sceneRank - b.sceneRank;
    if (a.networkId !== b.networkId) return Number(a.networkId) - Number(b.networkId);
    return String(a.targetId || "").localeCompare(String(b.targetId || ""));
  });

  return applyMergeRowspans(list);
}

export function buildFormattedTable(rows, sceneResult) {
  return applySceneRowspans(rows, sceneResult);
}
