/**
 * 地图时间回放：从 network / 外源定位构建按时间排序的事件序列。
 */

function asArray(v) {
  return Array.isArray(v) ? v : [];
}

/**
 * @param {object} network
 * @param {object[]} externalFixes
 * @param {(raw: *) => number} toEpochMs
 * @param {{ targetIdFilter?: string|null }} options
 */
export function buildMapTimeline(network, externalFixes, toEpochMs, options = {}) {
  const { targetIdFilter = null } = options;
  const events = [];
  const targets = asArray(network?.targets);

  targets.forEach((t, idx) => {
    if (targetIdFilter && t.targetId !== targetIdFilter) return;
    const colorIdx = idx;

    const lon = Number(t.locateLon);
    const lat = Number(t.locateLat);
    let endMs = toEpochMs(t.detectEndMs);
    if (endMs <= 0) {
      for (const p of asArray(t.trackPoints)) {
        endMs = Math.max(endMs, toEpochMs(p.t));
      }
    }
    if (Number.isFinite(lon) && Number.isFinite(lat) && endMs > 0) {
      events.push({
        type: "targetLocate",
        timeMs: endMs,
        targetId: t.targetId,
        lon,
        lat,
        colorIdx,
        active: false
      });
    }

    for (const p of asArray(t.trackPoints)) {
      const timeMs = toEpochMs(p.t);
      const platLon = Number(p.platformLon);
      const platLat = Number(p.platformLat);
      const az = Number(p.azimuth);
      if (timeMs <= 0 || !Number.isFinite(platLon) || !Number.isFinite(platLat) || !Number.isFinite(az)) {
        continue;
      }
      events.push({
        type: "ray",
        timeMs,
        targetId: t.targetId,
        platLon,
        platLat,
        azimuth: az,
        colorIdx,
        batchId: p.batchId || null
      });
    }
  });

  asArray(externalFixes).forEach((fix) => {
    const timeMs = Number(fix.detectTimeMs);
    const lon = Number(fix.longitude);
    const lat = Number(fix.latitude);
    if (!Number.isFinite(timeMs) || timeMs <= 0 || !Number.isFinite(lon) || !Number.isFinite(lat)) {
      return;
    }
    const label = fix.targetName || fix.targetId || "外源";
    const matchColor = options.matchedDeviceColors?.get(String(label).trim()) || null;
    events.push({
      type: "externalFix",
      timeMs,
      lon,
      lat,
      affiliation: fix.affiliation || "",
      label,
      matchColor,
      targetName: fix.targetName || "",
      targetId: fix.targetId || "",
      detectTimeMs: timeMs
    });
  });

  events.sort((a, b) => a.timeMs - b.timeMs || String(a.type).localeCompare(String(b.type)));
  return events;
}

export function timelineRange(events) {
  if (!events.length) return { minMs: 0, maxMs: 0 };
  return {
    minMs: events[0].timeMs,
    maxMs: events[events.length - 1].timeMs
  };
}

/**
 * 在 replayCurrentMs 时刻，哪些事件应可见（已出现且未过期）。
 * @param {number} ttlSec 0 表示不自动清除
 */
export function visibleEventKeysAt(events, currentMs, ttlSec) {
  const ttlMs = ttlSec > 0 ? ttlSec * 1000 : 0;
  const keys = new Set();
  for (let i = 0; i < events.length; i++) {
    const ev = events[i];
    if (ev.timeMs > currentMs) break;
    if (ttlMs > 0 && ev.timeMs + ttlMs < currentMs) continue;
    keys.add(eventKey(ev, i));
  }
  return keys;
}

export function eventKey(ev, index) {
  switch (ev.type) {
    case "ray":
      return `ray:${ev.targetId}:${ev.timeMs}:${ev.platLon.toFixed(5)},${ev.platLat.toFixed(5)}`;
    case "targetLocate":
      return `loc:${ev.targetId}:${ev.timeMs}`;
    case "externalFix":
      return `ext:${ev.timeMs}:${ev.lon.toFixed(5)},${ev.lat.toFixed(5)}`;
    case "platform":
      return `plat:${ev.platLon.toFixed(5)},${ev.platLat.toFixed(5)}`;
    default:
      return `ev:${index}`;
  }
}

export function formatReplayClock(ms) {
  if (!Number.isFinite(ms) || ms <= 0) return "—";
  const d = new Date(ms);
  if (Number.isNaN(d.getTime())) return "—";
  const pad = (x) => String(x).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getSeconds())}:${pad(d.getMilliseconds()).slice(0, 2)}`;
}
