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
  maxGapPeriods: 1.5,
  minSlotRoundCoverageRatio: 0.8,
  minInterClusterSeparationDeg: 1,
  maxSlotBearingStdDeg: 1.5
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

function targetCenterBearing(t) {
  const pts = t.points || [];
  if (!pts.length) return null;
  return circularMeanDeg(
    pts
      .map((p) => (Array.isArray(p) ? p[1] : p.b))
      .filter((b) => Number.isFinite(b))
  );
}

function sourceMetaFromTarget(t) {
  return {
    key: t.key,
    networkId: t.networkId,
    targetId: t.targetId,
    targetType: t.targetType,
    targetTypeLabel: t.targetTypeLabel,
    networkFreqMhz: t.reportNetworkFreqMhz ?? t.networkFreqMhz
  };
}

/** 各 lane 与信号分析目标按方位一对一匹配，避免多 lane 共用同一目标类型。 */
function matchAllLanesToSourceTargets(pendingLanes, sourceTargets, freqMhz) {
  const out = new Map();
  if (!pendingLanes?.length || !sourceTargets?.length) return out;

  const pairs = [];
  for (const lane of pendingLanes) {
    const center = circularMeanDeg(lane.rounds.map((r) => r.bearing));
    if (center == null) continue;
    for (const t of sourceTargets) {
      const f = Number(t.reportNetworkFreqMhz ?? t.networkFreqMhz);
      if (!Number.isFinite(f) || Math.abs(f - freqMhz) > 0.02) continue;
      const targetCenter = targetCenterBearing(t);
      if (targetCenter == null) continue;
      const gap = Math.abs(shortestDelta(center, targetCenter));
      if (gap <= 12) pairs.push({ slot: lane.slot, target: t, gap });
    }
  }
  pairs.sort((a, b) => a.gap - b.gap);

  const usedSlot = new Set();
  const usedTarget = new Set();
  for (const { slot, target, gap } of pairs) {
    const targetKey = `${target.networkId}:${target.targetId}`;
    if (usedSlot.has(slot) || usedTarget.has(targetKey)) continue;
    usedSlot.add(slot);
    usedTarget.add(targetKey);
    out.set(slot, sourceMetaFromTarget(target));
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

const MAX_DUPLICATE_MERGE_DEG = 0.12;

function duplicateMergeDeg(gapDeg) {
  return Math.min(gapDeg ?? 0.5, MAX_DUPLICATE_MERGE_DEG);
}

function clusterPoints(points, gapDeg) {
  return clusterPointsWithMerge(points, duplicateMergeDeg(gapDeg));
}

function clusterPointsWithMerge(points, mergeDeg) {
  if (!points.length) return [];
  const sorted = [...points].sort((a, b) => norm360(pointBearing(a)) - norm360(pointBearing(b)));
  const clusters = [];
  let cur = [sorted[0]];
  for (let i = 1; i < sorted.length; i++) {
    if (Math.abs(shortestDelta(pointBearing(sorted[i - 1]), pointBearing(sorted[i]))) <= mergeDeg) {
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

/** 同 burst 内方位差大于抖动阈值的测向点各自成簇（不同并发目标）。 */
function clusterPointsPerDevice(points, gapDeg) {
  return clusterPoints(points, gapDeg);
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
    const clusters = clusterPointsPerDevice(group, gapDeg);
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

function minRoundHits(roundCount, coverageRatio) {
  if (roundCount <= 0) return 0;
  const ratio = Math.max(0.5, Math.min(1, coverageRatio));
  return Math.max(2, Math.ceil(roundCount * ratio));
}

function alignBurstsToWindow(bursts, windowStartMs, windowEndMs, periodSec, tolRatio) {
  const periodMs = Math.max(1, Math.round(periodSec * 1000));
  const tolMs = periodSec * tolRatio * 1000;
  const sorted = [...bursts].sort((a, b) => a.centerMs - b.centerMs);
  const aligned = [];
  const used = new Set();
  for (let targetMs = windowStartMs; targetMs <= windowEndMs + tolMs; targetMs += periodMs) {
    const hit = findBurstNear(sorted, targetMs, tolMs, used);
    if (hit) {
      aligned.push(hit);
      used.add(hit);
    }
  }
  return aligned;
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

function circularMeanDeg(bearings) {
  let sin = 0;
  let cos = 0;
  for (const b of bearings) {
    const r = (norm360(b) * Math.PI) / 180;
    sin += Math.sin(r);
    cos += Math.cos(r);
  }
  return norm360((Math.atan2(sin, cos) * 180) / Math.PI);
}

function nearestCenterWithin(reference, clusters, maxDeltaDeg) {
  let best = null;
  let bestGap = Infinity;
  for (const cluster of clusters) {
    const gap = Math.abs(shortestDelta(reference, cluster.center));
    if (gap <= maxDeltaDeg && gap < bestGap) {
      bestGap = gap;
      best = cluster.center;
    }
  }
  return best;
}

function stdDev(values) {
  if (values.length < 2) return 0;
  const mean = values.reduce((s, v) => s + v, 0) / values.length;
  let variance = 0;
  for (const v of values) {
    const d = v - mean;
    variance += d * d;
  }
  return Math.sqrt(variance / values.length);
}

function identifyPersistentSlotCenters(aligned, minRoundHits, o) {
  const matchDeg = Math.max(2, o.minInterClusterSeparationDeg ?? 1);
  const maxStd = o.maxSlotBearingStdDeg ?? 1.5;
  const seedBurst = aligned.reduce(
    (best, b) => (b.clusters.length > best.clusters.length ? b : best),
    aligned[0]
  );
  const seedSlots = seedBurst.clusters.map((c) => c.center).sort((a, b) => a - b);
  const persistent = [];
  for (const slot of seedSlots) {
    const hits = [];
    for (const burst of aligned) {
      const matched = nearestCenterWithin(slot, burst.clusters, matchDeg);
      if (matched != null) hits.push(matched);
    }
    if (hits.length >= minRoundHits && stdDev(hits) <= maxStd) {
      persistent.push(circularMeanDeg(hits));
    }
  }
  persistent.sort((a, b) => a - b);
  return persistent;
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

  let windowStartMs = o.windowStartMs;
  let windowEndMs = o.windowEndMs;
  if (windowStartMs == null || windowEndMs == null) {
    const times = points.map((p) => pointTime(p)).filter((t) => Number.isFinite(t));
    if (times.length) {
      windowStartMs = Math.min(...times);
      windowEndMs = Math.max(...times);
    }
  }

  let aligned;
  if (windowStartMs != null && windowEndMs != null && windowEndMs >= windowStartMs) {
    aligned = alignBurstsToWindow(bursts, windowStartMs, windowEndMs, o.periodSec, o.periodToleranceRatio);
  } else {
    aligned = pickAlignedRun(bursts, o.periodSec, o.periodToleranceRatio);
  }

  if (aligned.length < 2) {
    return { lanes: [], laneCount: 0, alignedRounds: 0 };
  }

  const requiredRoundHits = minRoundHits(aligned.length, o.minSlotRoundCoverageRatio);
  const persistentSlots = identifyPersistentSlotCenters(aligned, requiredRoundHits, o);
  if (persistentSlots.length < o.minDevices) {
    return { lanes: [], laneCount: 0, alignedRounds: aligned.length };
  }

  const laneRounds = persistentSlots.map(() => []);
  const laneSources = persistentSlots.map(() => []);
  const slotRoundHits = new Array(persistentSlots.length).fill(0);

  for (const burst of aligned) {
    const assignment = assignClusters(persistentSlots, burst.clusters, persistentSlots.length);
    for (let slot = 0; slot < persistentSlots.length; slot++) {
      const idx = assignment[slot];
      if (idx < 0 || idx >= burst.clusters.length) continue;
      slotRoundHits[slot]++;
      laneRounds[slot].push({
        ms: burst.centerMs,
        bearing: burst.clusters[idx].center,
        cluster: burst.clusters[idx]
      });
      for (const p of burst.clusters[idx].points) {
        const src = pointSrc(p);
        if (src) laneSources[slot].push(src);
      }
    }
  }

  const periodMs = Math.max(1, Math.round(o.periodSec * 1000));
  const maxConnectGap = periodMs * (o.maxGapPeriods ?? 1.5);
  const colors = ["#D95319", "#0072BD", "#77AC30", "#4DBEEE", "#A2142F", "#7E2F8E"];
  const freqMhz = Number(o.reportNetworkFreqMhz ?? o.networkFreqMhz);
  const pendingLanes = [];

  for (let li = 0; li < laneRounds.length; li++) {
    if (slotRoundHits[li] < requiredRoundHits) continue;
    const rounds = laneRounds[li];
    if (rounds.length < 2) continue;
    rounds.sort((a, b) => a.ms - b.ms);
    const line = [];
    let ref = norm360(rounds[0].bearing);
    let prevMs = null;
    for (const r of rounds) {
      if (prevMs != null && r.ms - prevMs > maxConnectGap) {
        line.push([r.ms, null]);
      }
      const y = unwrapToward(ref, r.bearing);
      ref = y;
      line.push([r.ms, y]);
      prevMs = r.ms;
    }
    pendingLanes.push({
      slot: li,
      rounds,
      line,
      laneSources: laneSources[li]
    });
  }

  const srcBySlot = matchAllLanesToSourceTargets(pendingLanes, o.sourceTargets, freqMhz);
  const lanes = [];
  let laneIndex = 0;
  for (const pending of pendingLanes) {
    laneIndex++;
    const srcMeta =
      srcBySlot.get(pending.slot) || dominantSourceMeta(pending.laneSources);
    const typeLabel = resolveTypeLabel(srcMeta);
    lanes.push({
      laneIndex,
      label: typeLabel,
      targetType: srcMeta?.targetType ?? null,
      targetTypeLabel: typeLabel,
      sourceTargetId: srcMeta?.targetId,
      sourceNetworkId: srcMeta?.networkId,
      sourceNetworkFreqMhz: srcMeta?.networkFreqMhz ?? srcMeta?.reportNetworkFreqMhz,
      color: colors[(laneIndex - 1) % colors.length],
      points: pending.line
    });
  }

  return { lanes, laneCount: lanes.length, alignedRounds: aligned.length };
}

/**
 * 将同一目标的方位点按时序连成折线（方位 unwrap，不因空隙插入断点）。
 * 用于「已判定为同一分析目标」的展示连线。
 */
export function connectSameTargetBearingLine(points) {
  const norm = [];
  for (const p of points || []) {
    const t = Array.isArray(p) ? p[0] : p.t;
    const b = Array.isArray(p) ? p[1] : (p.b ?? p.v);
    if (!Number.isFinite(t) || b == null || !Number.isFinite(b)) continue;
    norm.push({ t, b: Number(b) });
  }
  norm.sort((a, b) => a.t - b.t);
  if (!norm.length) return [];
  if (norm.length === 1) return [[norm[0].t, norm360(norm[0].b)]];

  const line = [];
  let ref = norm360(norm[0].b);
  for (const p of norm) {
    const y = unwrapToward(ref, p.b);
    line.push([p.t, y]);
    ref = y;
  }
  return line;
}

/**
 * 单目标轮询连线：每轮 burst 取一个代表方位，跨轮连成一条目标轨迹。
 * 用于信号分析方位图，与分析目标 T1/T2/T3 一一对应。
 * @param {object} [opts.breakOnGap=false] 为 true 时，轮次间隔超过 maxGapPeriods 个周期则断线
 */
export function buildPerTargetPollingLine(points, opts = {}) {
  const o = { ...DEFAULT_OPTS, minDevices: 1, breakOnGap: false, ...opts };
  const norm = [];
  for (const p of points || []) {
    const t = Array.isArray(p) ? p[0] : p.t;
    const b = Array.isArray(p) ? p[1] : (p.b ?? p.v);
    if (!Number.isFinite(t) || b == null || !Number.isFinite(b)) continue;
    norm.push({ t, b });
  }
  if (norm.length < 2) {
    return connectSameTargetBearingLine(norm.map((p) => [p.t, p.b]));
  }

  const coalesceMs = Math.max(1, (o.burstCoalesceSec ?? 0.5) * 1000);
  const bursts = detectBursts(norm, coalesceMs, o.bearingGapDeg ?? 0.5, 1);
  if (bursts.length < 2) {
    return connectSameTargetBearingLine(norm);
  }

  let aligned = bursts;
  if (
    o.windowStartMs != null
    && o.windowEndMs != null
    && o.periodSec > 0
    && o.windowEndMs >= o.windowStartMs
  ) {
    const windowAligned = alignBurstsToWindow(
      bursts,
      o.windowStartMs,
      o.windowEndMs,
      o.periodSec,
      o.periodToleranceRatio ?? 0.18
    );
    if (windowAligned.length >= 2) aligned = windowAligned;
  }

  const roundPoints = aligned.map((burst) => {
    const bearings = burst.clusters.flatMap((c) => c.points.map(pointBearing));
    const bearing = bearings.length
      ? circularMeanDeg(bearings)
      : circularMeanDeg(burst.clusters.map((c) => c.center));
    return { ms: burst.centerMs, bearing };
  });
  roundPoints.sort((a, b) => a.ms - b.ms);

  const periodMs = o.periodSec > 0 ? Math.max(1, Math.round(o.periodSec * 1000)) : null;
  const maxConnectGap = periodMs != null ? periodMs * (o.maxGapPeriods ?? 1.5) : Infinity;
  const breakOnGap = o.breakOnGap === true && Number.isFinite(maxConnectGap);

  const line = [];
  let ref = norm360(roundPoints[0].bearing);
  let prevMs = null;
  for (const r of roundPoints) {
    // 仅在显式要求时断线；信号分析同目标默认连续连接
    if (breakOnGap && prevMs != null && r.ms - prevMs > maxConnectGap) {
      line.push([prevMs + 1, null]);
    }
    const y = unwrapToward(ref, r.bearing);
    ref = y;
    line.push([r.ms, y]);
    prevMs = r.ms;
  }
  return line;
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
