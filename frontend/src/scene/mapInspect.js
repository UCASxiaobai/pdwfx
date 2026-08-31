/**
 * 地图点击：定位点 / 测向批 关联数据查询。
 */

function asArray(v) {
  return Array.isArray(v) ? v : [];
}

export function batchIdsForDeviceName(deviceName, batchToDevice) {
  const name = (deviceName || "").trim();
  if (!name || !batchToDevice) return [];
  return Object.entries(batchToDevice)
    .filter(([, dev]) => (dev || "").trim() === name)
    .map(([batchId]) => batchId);
}

export function collectBearingsFromNetwork(network) {
  const rows = [];
  const netFreq = Number(network?.freq);
  for (const t of asArray(network?.targets)) {
    for (const p of asArray(t.trackPoints)) {
      const timeMs = Number(p.t);
      if (!Number.isFinite(timeMs) || timeMs <= 0) continue;
      rows.push({
        batchId: p.batchId || null,
        signalId: "",
        timeMs,
        bearing: Number(p.azimuth),
        rxLon: p.platformLon,
        rxLat: p.platformLat,
        freqHz: Number.isFinite(Number(p.freq))
          ? Math.round(Number(p.freq) * 1000000)
          : Math.round(netFreq * 1000000),
        signalLevel: p.signalLevel,
        signalDwellMs: p.signalDwellMs,
        targetId: t.targetId
      });
    }
  }
  return rows.sort((a, b) => a.timeMs - b.timeMs);
}

export function rowsForBatchIds(batchIds, dfMatchResult, network) {
  const ids = asArray(batchIds).filter(Boolean);
  if (!ids.length) return [];

  const fromMatch = dfMatchResult?.measurementsByBatch;
  if (fromMatch) {
    const rows = [];
    for (const id of ids) {
      rows.push(...asArray(fromMatch[id]));
    }
    if (rows.length) {
      return rows.sort((a, b) => a.timeMs - b.timeMs);
    }
  }

  const all = collectBearingsFromNetwork(network);
  const set = new Set(ids);
  return all.filter((r) => r.batchId && set.has(r.batchId));
}

export function rowsForBatchId(batchId, dfMatchResult, network) {
  return rowsForBatchIds([batchId], dfMatchResult, network);
}

export function batchTimeBounds(rows) {
  if (!rows?.length) {
    return { startMs: null, endMs: null };
  }
  return { startMs: rows[0].timeMs, endMs: rows[rows.length - 1].timeMs };
}

export function enrichTableRows(rows) {
  const { startMs, endMs } = batchTimeBounds(rows);
  return asArray(rows).map((r) => ({
    ...r,
    isStart: startMs != null && r.timeMs === startMs,
    isEnd: endMs != null && r.timeMs === endMs
  }));
}

export function formatFreqMhzFromHz(freqHz) {
  const hz = Number(freqHz);
  if (!Number.isFinite(hz) || hz <= 0) return "—";
  return (hz / 1000000).toFixed(4);
}

/** 地图检视时测向线过多则均匀抽样，避免卡顿 */
export function downsampleInspectRows(rows, maxPoints = 600) {
  const list = asArray(rows);
  if (list.length <= maxPoints) return list;
  const step = Math.ceil(list.length / maxPoints);
  const out = [];
  for (let i = 0; i < list.length; i += step) {
    out.push(list[i]);
  }
  return out;
}
