/**
 * 轮询场景 lane 建轨（前端）：识别 burst → 对齐轮次 → 槽位匹配 → 仅跨相邻轮次连线。
 * 与后端 {@code PollingTrackBuilderService} 逻辑一致，用于分析视图等无后端 lane 轨迹时。
 */

import { targetTypeLabel } from "./sceneFilters.js";

const DEFAULT_OPTS = {
  burstCoalesceSec: 0.5,
  bearingGapDeg: 0.5,
  periodToleranceRatio: 0.18,
  minDevices: 2,
  maxGapPeriods: 1.5
};

function norm360(b) {
  let v = b % 360;
  if (v < 0) v += 360;
  return v;
}

function shortestDelta(from, to) {
  let d = norm360(to) - norm360(from);
  if (d > 180) d -= 360;
  if (d < -180) d += 360;
  return d;
}

function unwrapToward(ref, bearing) {
  const base = norm360(ref);
  let c = norm360(bearing);
  let d = c - base;
  if (d > 180) c -= 360;
  else if (d < -180) c += 360;
  return c;
}

function resolveTypeLabel(meta) {
  if (!meta) return "未知";
  const raw = meta.targetTypeLabel || targetTypeLabel(meta.targetType);
  return raw && raw !== "—" ? raw : "未知";
}

function pointTime(p) {
  return Array.isArray(p) ? p[0] : p.t;
}

function pointBearing(p) {
  return Array.isArray(p) ? p[1] : p.b;
}

function pointSrc(p) {
  return Array.isArray(p) ? null : p.src || null;
}

/** 将分析目标点位展开为带源目标信息的采样点，供 lane 建轨后回填目标类型。 */
export function flattenTargetsWithSource(targets) {
  const out = [];
  for (const t of targets || []) {
    for (const p of t.points || []) {
      if (p == null || p[1] == null || !Number.isFinite(p[0]) || !Number.isFinite(p[1])) continue;
      out.push({
        t: p[0],
        b: p[1],
        src: {
          key: t.key,
          targetId: t.targetId,
          targetType: t.targetType,
          targetTypeLabel: t.targetTypeLabel,
          networkId: t.networkId,
          networkFreqMhz: t.networkFreqMhz,
          reportNetworkFreqMhz: t.reportNetworkFreqMhz
        }
      });
    }
  }
  return out;
}

function dominantSourceMeta(sources) {
  if (!sources?.length) return null;
  const counts = new Map();
  for (const s of sources) {
    const key = s.key || `${s.networkId}:${s.targetId}`;
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  let bestKey = null;
  let bestN = 0;
  for (const [key, n] of counts) {
    if (n > bestN) {
      bestN = n;
      bestKey = key;
    }
  }
  return sources.find((s) => (s.key || `${s.networkId}:${s.targetId}`) === bestKey) || sources[0];
}

function clusterPoints(points, gapDeg) {
  if (!points.length) return [];
  const sorted = [...points].sort((a, b) => norm360(pointBearing(a)) - norm360(pointBearing(b)));
  const clusters = [];
  let cur = [sorted[0]];
  for (let i = 1; i < sorted.length; i++) {
    if (Math.abs(shortestDelta(pointBearing(sorted[i - 1]), pointBearing(sorted[i]))) <= gapDeg) {
      cur.push(sorted[i]);
    } else {
      clusters.push(cur);
      cur = [sorted[i]];
    }
  }
  clusters.push(cur);
  return clusters
    .map((pts) => {
      let sin = 0;
      let cos = 0;
      for (const p of pts) {
        const r = (norm360(pointBearing(p)) * Math.PI) / 180;
        sin += Math.sin(r);
        cos += Math.cos(r);
      }
      const center = norm360((Math.atan2(sin, cos) * 180) / Math.PI);
      return { center, points: pts };
    })
    .sort((a, b) => a.center - b.center);
}

function detectBursts(points, coalesceMs, gapDeg, minDevices) {
  const sorted = [...points].sort((a, b) => pointTime(a) - pointTime(b));
  const bursts = [];
  let i = 0;
  while (i < sorted.length) {
    const start = pointTime(sorted[i]);
    const group = [sorted[i]];
    let j = i + 1;
    while (j < sorted.length && pointTime(sorted[j]) - start <= coalesceMs) {
      group.push(sorted[j]);
      j++;
    }
    i = j;
    const clusters = clusterPoints(group, gapDeg);
    if (clusters.length >= minDevices) {
      const centerMs = group.reduce((s, p) => s + pointTime(p), 0) / group.length;
      bursts.push({ centerMs, clusters });
    }
  }
  return bursts;
}

function findBurstNear(bursts, targetMs, tolMs, used) {
  let best = null;
  let bestGap = Infinity;
  for (const b of bursts) {
    if (used.has(b)) continue;
    const gap = Math.abs(b.centerMs - targetMs);
    if (gap <= tolMs && gap < bestGap) {
      bestGap = gap;
      best = b;
    }
  }
  return best;
}

function pickAlignedRun(bursts, periodSec, tolRatio) {
  const periodMs = Math.max(1, Math.round(periodSec * 1000));
  const tolMs = periodSec * tolRatio * 1000;
  const sorted = [...bursts].sort((a, b) => a.centerMs - b.centerMs);
  let best = [];
  for (const anchor of sorted) {
    const used = new Set([anchor]);
    const run = [anchor];
    for (let k = 1; k < 64; k++) {
      const hit = findBurstNear(sorted, anchor.centerMs + k * periodMs, tolMs, used);
      if (!hit) break;
      run.push(hit);
      used.add(hit);
    }
    for (let k = 1; k < 64; k++) {
      const hit = findBurstNear(sorted, anchor.centerMs - k * periodMs, tolMs, used);
      if (!hit) break;
      run.unshift(hit);
      used.add(hit);
    }
    if (run.length > best.length) best = run;
  }
  return best;
}

function hungarian(cost) {
  const n = cost.length;
  const bestCol = new Array(n).fill(-1);
  let bestCost = Infinity;
  const cur = new Array(n);
  const used = new Array(n).fill(false);

  function dfs(row, sum) {
    if (row === n) {
      if (sum < bestCost) {
        bestCost = sum;
        for (let i = 0; i < n; i++) bestCol[i] = cur[i];
      }
      return;
    }
    for (let col = 0; col < n; col++) {
      if (used[col]) continue;
      used[col] = true;
      cur[row] = col;
      dfs(row + 1, sum + cost[row][col]);
      used[col] = false;
    }
  }
  dfs(0, 0);
  return bestCol;
}

function assignClusters(prevBearings, clusters, laneCount) {
  const assignment = new Array(laneCount).fill(-1);
  if (!clusters.length) return assignment;
  const size = Math.max(laneCount, clusters.length);
  const high = 1000;
  const cost = Array.from({ length: size }, (_, i) =>
    Array.from({ length: size }, (_, j) => {
      if (i >= laneCount || j >= clusters.length) return high;
      return Math.abs(shortestDelta(prevBearings[i], clusters[j].center));
    })
  );
  const match = hungarian(cost);
  for (let lane = 0; lane < laneCount; lane++) {
    const idx = match[lane];
    if (idx >= 0 && idx < clusters.length && cost[lane][idx] < high / 2) {
      assignment[lane] = idx;
    }
  }
  return assignment;
}

/**
 * @param {Array<[number, number]|{t:number,b:number,src?:object}>} points
 * @param {object} opts - periodSec, burstCoalesceSec, bearingGapDeg, minDevices, ...
 */
export function buildPollingLaneTracks(points, opts = {}) {
  const o = { ...DEFAULT_OPTS, ...opts };
  if (!points?.length || !o.periodSec || o.periodSec <= 0) {
    return { lanes: [], laneCount: 0, alignedRounds: 0 };
  }

  const coalesceMs = Math.max(1, o.burstCoalesceSec * 1000);
  const bursts = detectBursts(points, coalesceMs, o.bearingGapDeg, o.minDevices);
  const aligned = pickAlignedRun(bursts, o.periodSec, o.periodToleranceRatio);
  if (aligned.length < 2) {
    return { lanes: [], laneCount: 0, alignedRounds: 0 };
  }

  const laneCount = Math.max(
    o.minDevices,
    aligned.reduce((m, b) => Math.max(m, b.clusters.length), 0)
  );
  const laneObs = Array.from({ length: laneCount }, () => []);
  const laneSources = Array.from({ length: laneCount }, () => []);

  let prevBearings = null;
  for (const burst of aligned) {
    let assignment;
    if (prevBearings == null) {
      assignment = Array.from({ length: laneCount }, (_, i) => (i < burst.clusters.length ? i : -1));
      prevBearings = assignment.map((idx) => (idx >= 0 ? burst.clusters[idx].center : 0));
    } else {
      assignment = assignClusters(prevBearings, burst.clusters, laneCount);
      for (let lane = 0; lane < laneCount; lane++) {
        const idx = assignment[lane];
        if (idx >= 0) prevBearings[lane] = burst.clusters[idx].center;
      }
    }
    for (let lane = 0; lane < laneCount; lane++) {
      const idx = assignment[lane];
      if (idx < 0 || idx >= burst.clusters.length) continue;
      for (const p of burst.clusters[idx].points) {
        laneObs[lane].push(p);
        const src = pointSrc(p);
        if (src) laneSources[lane].push(src);
      }
    }
  }

  const maxGapMs = Math.max(1000, o.periodSec * o.maxGapPeriods * 1000);
  const colors = ["#D95319", "#0072BD", "#77AC30", "#4DBEEE", "#A2142F", "#7E2F8E"];
  const lanes = [];
  let laneIndex = 0;
  for (let li = 0; li < laneObs.length; li++) {
    const obs = laneObs[li];
    if (obs.length < 2) continue;
    obs.sort((a, b) => pointTime(a) - pointTime(b));
    const line = [];
    let ref = norm360(pointBearing(obs[0]));
    let prevMs = null;
    for (const p of obs) {
      const ms = pointTime(p);
      const b = pointBearing(p);
      if (prevMs != null && ms - prevMs > maxGapMs) {
        line.push([ms, null]);
      }
      const y = unwrapToward(ref, b);
      ref = y;
      line.push([ms, y]);
      prevMs = ms;
    }
    laneIndex++;
    const srcMeta = dominantSourceMeta(laneSources[li]);
    const typeLabel = resolveTypeLabel(srcMeta);
    lanes.push({
      laneIndex,
      label: typeLabel,
      targetType: srcMeta?.targetType,
      targetTypeLabel: typeLabel,
      sourceTargetId: srcMeta?.targetId,
      sourceNetworkId: srcMeta?.networkId,
      sourceNetworkFreqMhz: srcMeta?.networkFreqMhz ?? srcMeta?.reportNetworkFreqMhz,
      color: colors[(laneIndex - 1) % colors.length],
      points: line
    });
  }

  return { lanes, laneCount: lanes.length, alignedRounds: aligned.length };
}

/** 将 lane 结果转为 analysisBearing 方位图用的 target 列表（保留源目标类型）。 */
export function pollingLanesAsTargets(laneResult, freqMhz = null) {
  return (laneResult.lanes || []).map((lane) => ({
    key: `polling-lane-${lane.laneIndex}`,
    networkId: "polling-lane",
    targetId: `lane-${lane.laneIndex}`,
    sourceTargetId: lane.sourceTargetId,
    sourceNetworkId: lane.sourceNetworkId,
    targetType: lane.targetType,
    targetTypeLabel: lane.targetTypeLabel || lane.label || "未知",
    role: "POLLING_LANE",
    networkFreqMhz: lane.sourceNetworkFreqMhz ?? freqMhz,
    reportNetworkFreqMhz: lane.sourceNetworkFreqMhz ?? freqMhz,
    points: lane.points,
    color: lane.color,
    targetDisplayIndex: lane.laneIndex,
    pollingLane: true
  }));
}
