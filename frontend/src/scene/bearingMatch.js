import { axisBoundsTight, resolveTargetTypeLabel } from "./analysisBearing.js";
import { formatFreq } from "./sceneFilters.js";
import { TARGET_COLORS } from "./analysisBearing.js";

const FREQ_LINE_TYPES = ["solid", "dashed", "dotted", [8, 4], [2, 6], [12, 4, 2, 4]];
const UNMATCHED_COLOR = "#9ca3af";

export const KNOWN_TARGET_TYPES = new Set(["GROUND", "AWACS", "AIR"]);

export const DEFAULT_MATCH_OPTIONS = {
  gateDeg: 0.1,
  timeToleranceMs: 6000,
  minMatchPoints: 10,
  minOverlapMs: 300,
  maxRateDiffDegPerSec: 2.5,
  requireMotionConsistent: false,
  /** 仅合并信号分析已判为相同平台类型（GROUND/AWACS/AIR）的轨迹 */
  requireSameTargetType: true,
  showOthers: true,
  topColoredTracks: 30,
  /** 频率占用矩阵：按目标数排序后保留的频点列数 */
  matrixTopFreqs: 12
};

/** 轨迹已判平台类型；未识别返回 null */
export function trackTargetTypeKey(track) {
  const raw = track?.targetType;
  if (raw == null || raw === "") return null;
  const key = String(raw).trim().toUpperCase();
  return KNOWN_TARGET_TYPES.has(key) ? key : null;
}

function normalizeMatchOptions(options = {}) {
  const opts = { ...DEFAULT_MATCH_OPTIONS, ...options };
  if (options.showUnmatched !== undefined && options.showOthers === undefined) {
    opts.showOthers = options.showUnmatched;
  }
  opts.topColoredTracks = Math.max(1, Math.round(Number(opts.topColoredTracks) || 12));
  opts.matrixTopFreqs = Math.max(1, Math.round(Number(opts.matrixTopFreqs) || 12));
  return opts;
}

function trackPointCount(track) {
  return (track?.points || []).filter((p) => p[1] != null && Number.isFinite(p[0])).length;
}

function pickHighlightedTrackIndices(tracks, clusterSize, topN) {
  const matchedIndices = [];
  for (let i = 0; i < tracks.length; i++) {
    if ((clusterSize.get(i) || 0) > 1) matchedIndices.push(i);
  }
  matchedIndices.sort((a, b) => trackPointCount(tracks[b]) - trackPointCount(tracks[a]));
  return new Set(matchedIndices.slice(0, topN));
}

export function normalize360(bearing) {
  let v = bearing % 360;
  if (v < 0) v += 360;
  return v;
}

/** 最短有符号角差，范围 (-180, 180] */
export function shortestDelta(fromDeg, toDeg) {
  let delta = normalize360(toDeg) - normalize360(fromDeg);
  if (delta > 180) delta -= 360;
  if (delta < -180) delta += 360;
  return delta;
}

export function sceneTimeRange(view) {
  const times = (view?.targets || []).flatMap((t) =>
    (t.points || []).map((p) => p[0]).filter((x) => Number.isFinite(x))
  );
  if (!times.length) return null;
  return {
    sceneRank: view.sceneRank,
    start: Math.min(...times),
    end: Math.max(...times)
  };
}

function rangesOverlap(a, b) {
  return a.start < b.end && b.start < a.end;
}

/**
 * 时间有重合的场景做传递闭包：A∩B、B∩C 则 A、B、C 同组。
 */
export function findOverlappingSceneRanks(sceneViews, anchorRank) {
  const ranges = new Map();
  for (const v of sceneViews || []) {
    const r = sceneTimeRange(v);
    if (r) ranges.set(v.sceneRank, r);
  }
  if (!ranges.size) return anchorRank != null ? [anchorRank] : [];

  const ranks = new Set();
  if (anchorRank != null && ranges.has(anchorRank)) {
    ranks.add(anchorRank);
  } else {
    for (const rank of ranges.keys()) ranks.add(rank);
  }

  let changed = true;
  while (changed) {
    changed = false;
    for (const [rank, r] of ranges) {
      if (ranks.has(rank)) continue;
      for (const ar of ranks) {
        const or = ranges.get(ar);
        if (or && rangesOverlap(r, or)) {
          ranks.add(rank);
          changed = true;
          break;
        }
      }
    }
  }
  return [...ranks].sort((a, b) => a - b);
}

/**
 * 将全部场景按时间窗重合做传递闭包分组（用于跨场景关联可视化）。
 * @returns {{ ranks: number[], spanStart: number, spanEnd: number }[]}
 */
export function findAllOverlapGroups(sceneViews) {
  const ranges = new Map();
  for (const v of sceneViews || []) {
    const r = sceneTimeRange(v);
    if (r) ranges.set(v.sceneRank, r);
  }
  const ranks = [...ranges.keys()].sort((a, b) => a - b);
  if (!ranks.length) return [];

  const parent = new Map(ranks.map((r) => [r, r]));
  function find(x) {
    let p = parent.get(x);
    while (p !== parent.get(p)) {
      parent.set(p, parent.get(parent.get(p)));
      p = parent.get(p);
    }
    return p;
  }
  function union(a, b) {
    const ra = find(a);
    const rb = find(b);
    if (ra !== rb) parent.set(rb, ra);
  }

  for (let i = 0; i < ranks.length; i++) {
    for (let j = i + 1; j < ranks.length; j++) {
      const ri = ranks[i];
      const rj = ranks[j];
      if (rangesOverlap(ranges.get(ri), ranges.get(rj))) {
        union(ri, rj);
      }
    }
  }

  const components = new Map();
  for (const rank of ranks) {
    const root = find(rank);
    if (!components.has(root)) components.set(root, []);
    components.get(root).push(rank);
  }

  const groups = [];
  for (const memberRanks of components.values()) {
    memberRanks.sort((a, b) => a - b);
    let spanStart = Infinity;
    let spanEnd = -Infinity;
    for (const rank of memberRanks) {
      const r = ranges.get(rank);
      if (r.start < spanStart) spanStart = r.start;
      if (r.end > spanEnd) spanEnd = r.end;
    }
    groups.push({ ranks: memberRanks, spanStart, spanEnd });
  }
  groups.sort((a, b) => a.ranks[0] - b.ranks[0]);
  return groups;
}

function trackTimeRange(points) {
  const times = (points || []).map((p) => p[0]).filter((x) => Number.isFinite(x));
  if (!times.length) return null;
  return { start: Math.min(...times), end: Math.max(...times) };
}

function nearestSample(points, t, maxGapMs) {
  let best = null;
  let bestGap = Infinity;
  for (const [pt, az] of points || []) {
    if (az == null || !Number.isFinite(pt)) continue;
    const gap = Math.abs(pt - t);
    if (gap <= maxGapMs && gap < bestGap) {
      bestGap = gap;
      best = az;
    }
  }
  return best;
}

function median(nums) {
  if (!nums.length) return Infinity;
  const s = [...nums].sort((a, b) => a - b);
  const m = Math.floor(s.length / 2);
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

function dominantLabel(labels) {
  const counts = new Map();
  for (const l of labels) {
    counts.set(l, (counts.get(l) || 0) + 1);
  }
  let best = labels[0] || "未知";
  let bestN = 0;
  for (const [l, n] of counts) {
    if (n > bestN) {
      best = l;
      bestN = n;
    }
  }
  return best;
}

function overlapWindow(trackA, trackB) {
  const rangeA = trackTimeRange(trackA.points);
  const rangeB = trackTimeRange(trackB.points);
  if (!rangeA || !rangeB) return null;
  const start = Math.max(rangeA.start, rangeB.start);
  const end = Math.min(rangeA.end, rangeB.end);
  if (start >= end) return null;
  return { start, end, overlapMs: end - start };
}

function bearingRateDegPerSec(points, start, end) {
  const pts = (points || []).filter(
    ([t, az]) => t >= start && t <= end && az != null && Number.isFinite(az)
  );
  if (pts.length < 3) return null;
  const dtSec = (pts[pts.length - 1][0] - pts[0][0]) / 1000;
  if (dtSec < 0.5) return null;
  return shortestDelta(pts[0][1], pts[pts.length - 1][1]) / dtSec;
}

function collectMatchDeltas(trackA, trackB, start, end, timeToleranceMs, gateDeg) {
  const deltas = [];
  const sample = (from, to) => {
    for (const [t, az] of from.points || []) {
      if (t < start || t > end || az == null) continue;
      const otherAz = nearestSample(to.points, t, timeToleranceMs);
      if (otherAz == null) continue;
      const d = Math.abs(shortestDelta(az, otherAz));
      if (d <= gateDeg) deltas.push(d);
    }
  };
  sample(trackA, trackB);
  if (deltas.length < 4) sample(trackB, trackA);
  return deltas;
}

function targetTypesCompatible(trackA, trackB, requireSameTargetType) {
  if (!requireSameTargetType) return true;
  const typeA = trackTargetTypeKey(trackA);
  const typeB = trackTargetTypeKey(trackB);
  return typeA != null && typeB != null && typeA === typeB;
}

/** 两条轨迹在重合时段内的匹配统计（用于展示置信度） */
export function bearingTrackMatchStats(trackA, trackB, options = {}) {
  const opts = normalizeMatchOptions(options);
  const win = overlapWindow(trackA, trackB);
  if (!win || win.overlapMs < opts.minOverlapMs) {
    return { agree: false, matchPoints: 0, medianDeltaDeg: null, overlapMs: win?.overlapMs || 0 };
  }

  if (!targetTypesCompatible(trackA, trackB, opts.requireSameTargetType)) {
    return {
      agree: false,
      matchPoints: 0,
      medianDeltaDeg: null,
      overlapMs: win.overlapMs,
      targetTypeMismatch: true
    };
  }

  const deltas = collectMatchDeltas(
    trackA,
    trackB,
    win.start,
    win.end,
    opts.timeToleranceMs,
    opts.gateDeg
  );
  if (deltas.length < opts.minMatchPoints) {
    return { agree: false, matchPoints: deltas.length, medianDeltaDeg: null, overlapMs: win.overlapMs };
  }
  const med = median(deltas);
  if (med > opts.gateDeg) {
    return { agree: false, matchPoints: deltas.length, medianDeltaDeg: med, overlapMs: win.overlapMs };
  }

  if (opts.requireMotionConsistent) {
    const rateA = bearingRateDegPerSec(trackA.points, win.start, win.end);
    const rateB = bearingRateDegPerSec(trackB.points, win.start, win.end);
    if (rateA != null && rateB != null) {
      if (Math.abs(rateA - rateB) > opts.maxRateDiffDegPerSec) {
        return { agree: false, matchPoints: deltas.length, medianDeltaDeg: med, overlapMs: win.overlapMs };
      }
    }
  }

  return { agree: true, matchPoints: deltas.length, medianDeltaDeg: med, overlapMs: win.overlapMs };
}

/**
 * 两条轨迹在重合时段内方位是否一致（忽略频率差异）。
 */
export function bearingTracksAgree(trackA, trackB, options = {}) {
  return bearingTrackMatchStats(trackA, trackB, options).agree;
}

/**
 * 并查集：将方位一致的轨迹归为同一物理目标（可跨频点、跨场景）。
 */
export function matchTracksByBearing(tracks, options = {}) {
  const n = tracks.length;
  if (!n) {
    return { clusters: [], trackClusterId: [], trackColor: [], trackLabel: [] };
  }

  const parent = Array.from({ length: n }, (_, i) => i);
  function find(i) {
    while (parent[i] !== i) {
      parent[i] = parent[parent[i]];
      i = parent[i];
    }
    return i;
  }
  function union(i, j) {
    const ri = find(i);
    const rj = find(j);
    if (ri !== rj) parent[rj] = ri;
  }

  for (let i = 0; i < n; i++) {
    for (let j = i + 1; j < n; j++) {
      if (bearingTracksAgree(tracks[i], tracks[j], options)) {
        union(i, j);
      }
    }
  }

  const groups = new Map();
  for (let i = 0; i < n; i++) {
    const root = find(i);
    if (!groups.has(root)) groups.set(root, []);
    groups.get(root).push(i);
  }

  const clusters = [...groups.values()].map((indices, ci) => {
    const typeLabel = dominantLabel(indices.map((i) => tracks[i].targetTypeLabel || "未知"));
    let bestStats = null;
    if (indices.length > 1) {
      for (let a = 0; a < indices.length; a++) {
        for (let b = a + 1; b < indices.length; b++) {
          const st = bearingTrackMatchStats(tracks[indices[a]], tracks[indices[b]], options);
          if (!st.agree) continue;
          if (!bestStats || (st.matchPoints || 0) > (bestStats.matchPoints || 0)) {
            bestStats = st;
          }
        }
      }
    }
    const confHint =
      bestStats?.medianDeltaDeg != null
        ? ` · 中位角差 ${bestStats.medianDeltaDeg.toFixed(1)}°`
        : "";
    const shortLabel = `目标${ci + 1}-${typeLabel}`;
    return {
      id: ci,
      color: TARGET_COLORS[ci % TARGET_COLORS.length],
      indices,
      trackCount: indices.length,
      matchStats: bestStats,
      shortLabel,
      typeLabel,
      label: `${shortLabel}${confHint}`
    };
  });

  const trackClusterId = new Array(n);
  const trackColor = new Array(n);
  const trackLabel = new Array(n);
  for (const c of clusters) {
    for (const idx of c.indices) {
      trackClusterId[idx] = c.id;
      trackColor[idx] = c.color;
      trackLabel[idx] = c.shortLabel || c.label;
    }
  }

  return { clusters, trackClusterId, trackColor, trackLabel };
}

export function trackReportFreqMhz(track) {
  const raw = track?.reportNetworkFreqMhz ?? track?.networkFreqMhz;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

export function trackReportFreqKey(track) {
  const mhz = trackReportFreqMhz(track);
  return mhz != null ? formatFreq(mhz) : null;
}

/**
 * 跨频关联后：目标（行）× 频率（列）占用矩阵。
 * 统计图中全部轨迹（含非高亮）；列取目标数最多的前 N 个网络频率。
 */
export function buildTargetChannelMatrix(enrichedTracks, matchClusters, options = {}) {
  const opts = normalizeMatchOptions(options);
  const topN = opts.matrixTopFreqs;
  const clusters = (matchClusters || [])
    .slice()
    .sort((a, b) => a.id - b.id);
  if (!clusters.length) {
    return { columns: [], rows: [], totalFreqCount: 0 };
  }

  const clusterIds = new Set(clusters.map((c) => c.id));
  const targetRows = clusters.map((c) => ({
    id: c.id,
    label: c.shortLabel || c.label || `目标${c.id + 1}`,
    typeLabel: c.typeLabel
  }));

  /** freqKey -> Set<clusterId> */
  const targetsByFreq = new Map();
  const occupied = new Set();

  for (const t of enrichedTracks || []) {
    if (t.matchClusterId == null || !clusterIds.has(t.matchClusterId)) continue;
    const freqKey = trackReportFreqKey(t);
    if (!freqKey) continue;

    if (!targetsByFreq.has(freqKey)) {
      targetsByFreq.set(freqKey, new Set());
    }
    targetsByFreq.get(freqKey).add(t.matchClusterId);
    occupied.add(`${t.matchClusterId}|${freqKey}`);
  }

  const rankedFreqs = [...targetsByFreq.entries()]
    .map(([key, clusterSet]) => ({
      key,
      label: `${key} MHz`,
      sortKey: parseFloat(key),
      targetCount: clusterSet.size
    }))
    .sort(
      (a, b) =>
        b.targetCount - a.targetCount
        || a.sortKey - b.sortKey
        || a.label.localeCompare(b.label, "zh-CN")
    );

  const columns = rankedFreqs.slice(0, topN).map((ch) => ({
    id: ch.key,
    label: `${ch.label} (${ch.targetCount})`
  }));

  const rows = targetRows.map((target) => ({
    key: String(target.id),
    label: target.label,
    cells: columns.map((col) => occupied.has(`${target.id}|${col.id}`))
  }));

  return {
    columns,
    rows,
    totalFreqCount: rankedFreqs.length,
    matrixTopFreqs: topN
  };
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

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
}

function bucketMergePoints(raw, binMs) {
  if (!raw.length) return [];
  const out = [];
  let i = 0;
  while (i < raw.length) {
    let j = i + 1;
    while (j < raw.length && raw[j].time - raw[i].time <= binMs) j++;
    const bucket = raw.slice(i, j);
    const byFreq = new Map();
    for (const p of bucket) {
      if (!byFreq.has(p.freqKey)) byFreq.set(p.freqKey, []);
      byFreq.get(p.freqKey).push(p);
    }
    let pickKey = bucket[0].freqKey;
    let maxN = 0;
    for (const [fk, pts] of byFreq) {
      if (pts.length > maxN) {
        maxN = pts.length;
        pickKey = fk;
      }
    }
    const pickPts = byFreq.get(pickKey);
    const avgT = pickPts.reduce((s, p) => s + p.time, 0) / pickPts.length;
    const avgB = pickPts.reduce((s, p) => s + p.bearing, 0) / pickPts.length;
    out.push({
      time: avgT,
      bearing: avgB,
      freqKey: pickKey,
      freqColor: pickPts[0].freqColor
    });
    i = j;
  }
  return out;
}

function fuseTracksToSegments(tracks, freqColorMap, binMs) {
  const raw = [];
  for (const t of tracks) {
    const fk = trackReportFreqKey(t) || formatFreq(t.networkFreqMhz);
    const color = freqColorMap.get(fk);
    for (const [time, bearing] of t.points || []) {
      if (!Number.isFinite(time) || bearing == null) continue;
      raw.push({ time, bearing, freqKey: fk, freqColor: color });
    }
  }
  raw.sort((a, b) => a.time - b.time);
  const timeline = bucketMergePoints(raw, binMs);

  const segments = [];
  for (const p of timeline) {
    const last = segments[segments.length - 1];
    if (!last || last.freqKey !== p.freqKey) {
      segments.push({
        freqKey: p.freqKey,
        freqColor: p.freqColor,
        points: [[p.time, p.bearing]]
      });
    } else {
      const pts = last.points;
      const prev = pts[pts.length - 1];
      if (prev[0] !== p.time || prev[1] !== p.bearing) {
        pts.push([p.time, p.bearing]);
      }
    }
  }
  return segments.filter((s) => s.points.length > 0);
}

/**
 * 将同一关联组内可匹配的轨迹按时间合成一条折线，分段颜色表示频点。
 */
export function buildFusedTrajectories(enriched, linkedClusters, matchOptions = {}) {
  const opts = normalizeMatchOptions(matchOptions);
  const freqColorMap = new Map();
  const fusedTargets = [];

  function ensureFreqColor(fk) {
    if (!freqColorMap.has(fk)) {
      freqColorMap.set(fk, TARGET_COLORS[freqColorMap.size % TARGET_COLORS.length]);
    }
    return freqColorMap.get(fk);
  }

  const binMs = Math.max(
    250,
    Math.min(1000, Math.round((opts.timeToleranceMs || 6000) / 4))
  );

  for (const cluster of linkedClusters) {
    const members = enriched.filter((t) => t.crossMatched && t.matchClusterId === cluster.id);
    if (!members.length || !members.some((m) => m.highlighted)) continue;
    for (const t of members) {
      ensureFreqColor(trackReportFreqKey(t) || formatFreq(t.networkFreqMhz));
    }
    const segments = fuseTracksToSegments(members, freqColorMap, binMs);
    if (!segments.length) continue;
    fusedTargets.push({
      id: cluster.id,
      label: cluster.label,
      shortLabel: cluster.shortLabel || `目标${cluster.id + 1}-${cluster.typeLabel || "未知"}`,
      matched: true,
      segments
    });
  }

  for (const t of enriched) {
    if (t.highlighted) continue;
    if (!opts.showOthers) continue;
    const fk = formatFreq(t.networkFreqMhz);
    ensureFreqColor(fk);
    const pts = (t.points || []).filter((p) => p[1] != null);
    if (!pts.length) continue;
    const typeLbl = resolveTargetTypeLabel(t);
    fusedTargets.push({
      id: `o-${t.key || `${t.sceneRank}-${t.targetId}`}`,
      label: t.label,
      shortLabel: `其他-${typeLbl}`,
      matched: false,
      segments: [{ freqKey: fk, freqColor: UNMATCHED_COLOR, points: pts }]
    });
  }

  const freqColorLegend = [...freqColorMap.entries()]
    .filter(([freq]) =>
      enriched.some(
        (t) =>
          t.highlighted && (trackReportFreqKey(t) || formatFreq(t.networkFreqMhz)) === freq
      )
    )
    .sort((a, b) => parseFloat(a[0]) - parseFloat(b[0]))
    .map(([freq, color]) => ({ freq, color }));

  return { fusedTargets, freqColorLegend };
}

function boundsFromFused(fusedTargets) {
  const pseudo = (fusedTargets || []).flatMap((f) =>
    (f.segments || []).map((s) => ({ points: s.points }))
  );
  return axisBoundsTight(pseudo);
}

function enrichMatchedTracks(tracks, matchOptions = {}) {
  const opts = normalizeMatchOptions(matchOptions);
  if (!tracks.length) {
    return {
      enriched: [],
      linkedClusters: [],
      allMatchClusters: [],
      matrixTracks: [],
      freqLineLegend: [],
      freqColorLegend: [],
      highlightedCount: 0,
      othersCount: 0,
      unmatchedCount: 0,
      totalTracks: 0
    };
  }

  const match = matchTracksByBearing(tracks, opts);
  const matrixTracks = tracks.map((t, i) => ({
    ...t,
    matchClusterId: match.trackClusterId[i]
  }));
  const freqLineMap = new Map();
  const freqColorMap = new Map();
  const clusterSize = new Map();
  for (const c of match.clusters) {
    for (const idx of c.indices) clusterSize.set(idx, c.indices.length);
  }

  const highlightedSet = pickHighlightedTrackIndices(
    tracks,
    clusterSize,
    opts.topColoredTracks
  );

  for (const idx of highlightedSet) {
    const fk = trackReportFreqKey(tracks[idx]);
    if (!fk) continue;
    if (!freqColorMap.has(fk)) {
      freqColorMap.set(fk, TARGET_COLORS[freqColorMap.size % TARGET_COLORS.length]);
    }
  }

  let highlightedCount = 0;
  let othersCount = 0;
  const enriched = [];
  for (let i = 0; i < tracks.length; i++) {
    const t = tracks[i];
    const reportFk = trackReportFreqKey(t);
    const bandFk = formatFreq(t.networkFreqMhz);
    const fk = reportFk || bandFk;
    if (!freqLineMap.has(bandFk)) {
      freqLineMap.set(bandFk, FREQ_LINE_TYPES[freqLineMap.size % FREQ_LINE_TYPES.length]);
    }
    const crossMatched = (clusterSize.get(i) || 0) > 1;
    const highlighted = highlightedSet.has(i);
    if (highlighted) highlightedCount += 1;
    else if (!opts.showOthers) continue;
    else othersCount += 1;

    const typeLbl = resolveTargetTypeLabel(t);
    const sceneTag = t.sceneRank != null ? ` · #${t.sceneRank}` : "";
    const bandSuffix =
      t.commBandFreqMhz != null && reportFk && bandFk !== reportFk ? ` ·段${bandFk}` : "";
    const displayName = highlighted
      ? `${fk} MHz · ${typeLbl}${bandSuffix}`
      : `其他-${typeLbl}`;
    enriched.push({
      ...t,
      crossMatched,
      highlighted,
      matched: highlighted,
      color: highlighted ? freqColorMap.get(fk) : UNMATCHED_COLOR,
      lineType: highlighted ? "solid" : freqLineMap.get(bandFk),
      lineWidth: highlighted ? (t.role === "MASTER" ? 2.5 : 1.8) : 1,
      opacity: highlighted ? 1 : 0.55,
      matchClusterId: match.trackClusterId[i],
      matchLabel: match.trackLabel[i],
      displayName,
      label: `${displayName}${sceneTag}`
    });
  }

  const linkedClusters = match.clusters.filter((c) =>
    c.indices.some((idx) => highlightedSet.has(idx))
  );
  const freqColorLegend = [...freqColorMap.entries()]
    .sort((a, b) => parseFloat(a[0]) - parseFloat(b[0]))
    .map(([freq, color]) => ({ freq, color }));

  return {
    enriched,
    linkedClusters,
    allMatchClusters: match.clusters,
    matrixTracks,
    freqLineLegend: [...freqLineMap.entries()].map(([freq, lineType]) => ({ freq, lineType })),
    freqColorLegend,
    highlightedCount,
    othersCount,
    unmatchedCount: othersCount,
    totalTracks: tracks.length
  };
}

/**
 * 时间重合场景组：跨场景、跨频点方位关联，合并为一张时间-方位图。
 */
export function buildOverlapGroupMatchedView(group, sceneViews, matchOptions = {}) {
  const memberRanks = group?.ranks || [];
  const rankSet = new Set(memberRanks);
  const tracks = [];
  for (const v of sceneViews || []) {
    if (!rankSet.has(v.sceneRank)) continue;
    for (const t of v.targets || []) {
      tracks.push({ ...t });
    }
  }

  if (!tracks.length) {
    const ranksLabel = memberRanks.map((r) => `#${r}`).join("、");
    return {
      memberRanks,
      title: ranksLabel ? `场景 ${ranksLabel}` : "—",
      empty: true,
      emptyReason: "该组无方位轨迹",
      targets: [],
      fusedTargets: [],
      matchClusters: [],
      freqLineLegend: [],
      freqColorLegend: [],
      timeRange: "",
      targetChannelMatrix: { columns: [], rows: [] },
      ...axisBounds([])
    };
  }

  const { enriched, linkedClusters, allMatchClusters, matrixTracks, freqLineLegend, highlightedCount, othersCount, totalTracks } =
    enrichMatchedTracks(tracks, matchOptions);
  const { fusedTargets, freqColorLegend } = buildFusedTrajectories(
    enriched,
    linkedClusters,
    matchOptions
  );
  const trackBounds = axisBoundsTight(enriched);
  const fusedBounds = boundsFromFused(fusedTargets);

  const times = enriched.flatMap((t) =>
    (t.points || []).map((p) => p[0]).filter((x) => Number.isFinite(x))
  );
  const spanStart = group?.spanStart ?? (times.length ? Math.min(...times) : 0);
  const spanEnd = group?.spanEnd ?? (times.length ? Math.max(...times) : 0);
  const timeRange =
    spanStart && spanEnd
      ? `${formatClock(spanStart)} ~ ${formatClock(spanEnd)}`
      : times.length >= 2
        ? `${formatClock(Math.min(...times))} ~ ${formatClock(Math.max(...times))}`
        : "";

  const ranksLabel = memberRanks.map((r) => `#${r}`).join("、");
  const merged = memberRanks.length > 1;

  return {
    memberRanks,
    merged,
    title: merged ? `时间重合 · 场景 ${ranksLabel}` : `场景 ${ranksLabel}`,
    subtitle: `${highlightedCount} 条高亮轨迹 · ${linkedClusters.length} 个关联组 · ${enriched.length}/${totalTracks} 条分轨${othersCount ? `（含 ${othersCount} 条其他）` : ""}`,
    timeRange,
    matchClusters: linkedClusters,
    highlightedCount,
    othersCount,
    unmatchedCount: othersCount,
    freqLineLegend,
    freqColorLegend,
    targets: enriched,
    fusedTargets,
    empty: !enriched.length && !fusedTargets.length,
    isOverlapMatched: true,
    xMin: Math.min(trackBounds.xMin, fusedBounds.xMin),
    xMax: Math.max(trackBounds.xMax, fusedBounds.xMax),
    yMin: Math.min(trackBounds.yMin, fusedBounds.yMin),
    yMax: Math.max(trackBounds.yMax, fusedBounds.yMax),
    targetChannelMatrix: buildTargetChannelMatrix(
      matrixTracks,
      allMatchClusters,
      matchOptions
    )
  };
}

/** 单场景（无时间重合伙伴时仍为独立一组） */
export function buildSceneMatchedView(sceneView, matchOptions = {}) {
  const rank = sceneView?.sceneRank;
  const group = {
    ranks: rank != null ? [rank] : [],
    spanStart: null,
    spanEnd: null
  };
  const tr = sceneTimeRange(sceneView);
  if (tr) {
    group.spanStart = tr.start;
    group.spanEnd = tr.end;
  }
  const view = buildOverlapGroupMatchedView(group, sceneView ? [sceneView] : [], matchOptions);
  view.freqLabel = sceneView?.freqLabel || "";
  return view;
}

/** 以某场景为锚，取其时间重合传递闭包组 */
export function buildMergedMatchedView(anchorRank, sceneViews, matchOptions = {}) {
  const overlappingRanks = findOverlappingSceneRanks(sceneViews, anchorRank);
  const ranges = overlappingRanks
    .map((r) => sceneTimeRange(sceneViews.find((v) => v.sceneRank === r)))
    .filter(Boolean);
  const group = {
    ranks: overlappingRanks,
    spanStart: ranges.length ? Math.min(...ranges.map((r) => r.start)) : 0,
    spanEnd: ranges.length ? Math.max(...ranges.map((r) => r.end)) : 0
  };
  return buildOverlapGroupMatchedView(group, sceneViews, matchOptions);
}
