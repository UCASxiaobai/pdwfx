/**
 * 换频研判后：目标（行）× 波道（列）占用矩阵。
 * 横轴固定 D01–D06，纵轴为目标，占用格打勾；不按场景拆分。
 */

import { formatFreq } from "./sceneFilters.js";

export const FIXED_CHANNEL_CODES = ["D01", "D02", "D03", "D04", "D05", "D06"];

export const HOP_TYPE_FILTERS = [
  { value: "", label: "全部" },
  { value: "AWACS", label: "预警机" },
  { value: "GROUND", label: "地面站" },
  { value: "AIR", label: "飞机" }
];

export function hopTrackTypeCodes(track, reportRows, freqTol = 0.01) {
  const codes = [];
  const add = (raw) => {
    const c = normalizeHopType(raw);
    if (c && codes.indexOf(c) < 0) codes.push(c);
  };
  add(track?.targetType);
  const extra = track?.targetTypes;
  if (Array.isArray(extra)) {
    for (let i = 0; i < extra.length; i++) add(extra[i]);
  }
  add(typeCodeFromLabel(track?.targetTypeLabel));
  const freqs = collectTrackFreqs(track);
  const mean = Number(track?.meanBearing);
  for (const r of reportRows || []) {
    if (!reportRowMatchesTrack(r, freqs, mean, freqTol)) continue;
    add(r.targetType);
    add(typeCodeFromLabel(r.targetTypeLabel));
  }
  return codes;
}

export function hopTrackMatchesType(track, typeFilter) {
  if (!typeFilter) return true;
  const primary = normalizeHopType(track?.targetType) || typeCodeFromLabel(track?.targetTypeLabel);
  return primary === typeFilter;
}

function normalizeHopType(raw) {
  const u = String(raw || "").trim().toUpperCase();
  if (u === "GROUND" || u === "AWACS" || u === "AIR") return u;
  return typeCodeFromLabel(raw);
}

function typeCodeFromLabel(label) {
  const s = String(label || "");
  if (/预警/.test(s)) return "AWACS";
  if (/地面|固定/.test(s)) return "GROUND";
  if (/飞机/.test(s)) return "AIR";
  return "";
}

function reportRowMatchesTrack(row, freqs, mean, freqTol) {
  const rf = Number(row?.networkFreqMhz ?? row?.freqMhz);
  let freqHit = false;
  if (Number.isFinite(rf) && freqs.length) {
    for (let i = 0; i < freqs.length; i++) {
      if (Math.abs(rf - freqs[i]) <= freqTol) {
        freqHit = true;
        break;
      }
    }
  }
  if (!freqHit) return false;
  const az = Number(row?.meanAzimuthDeg);
  if (Number.isFinite(mean) && Number.isFinite(az) && azimuthDelta(mean, az) > 8) {
    return false;
  }
  return true;
}

export function normalizeChannelCode(text) {
  const m = String(text || "").match(/D0?(\d+)/i);
  if (!m) return null;
  const n = Number(m[1]);
  if (!Number.isFinite(n) || n < 1 || n > 6) return null;
  return `D${String(n).padStart(2, "0")}`;
}

function splitChannels(text) {
  if (!text) return [];
  return String(text)
    .split(/[、,；;]/)
    .map((s) => s.trim())
    .filter(Boolean);
}

function codesFromText(text) {
  const codes = [];
  const seen = {};
  for (const part of splitChannels(text)) {
    const code = normalizeChannelCode(part);
    if (code && !seen[code]) {
      seen[code] = true;
      codes.push(code);
    }
  }
  if (!codes.length) {
    const one = normalizeChannelCode(text);
    if (one) codes.push(one);
  }
  return codes;
}

function uniqueFreqLabels(values) {
  const seen = {};
  const labels = [];
  for (let i = 0; i < (values || []).length; i++) {
    const f = formatFreq(values[i]);
    if (!f || f === "—" || seen[f]) continue;
    seen[f] = true;
    labels.push(f);
  }
  return labels;
}

function labelWithFreq(base, freqValues) {
  const freqs = uniqueFreqLabels(Array.isArray(freqValues) ? freqValues : [freqValues]);
  if (!freqs.length) return base;
  const missing = freqs.filter((f) => String(base).indexOf(f) < 0);
  if (!missing.length) return base;
  return `${base} ${missing.join("/")}`;
}

/** 报告行中已做信号分析的网络频点（格式化字符串集合）。 */
function analyzedFreqLabelSet(reportRows) {
  const set = {};
  for (const r of reportRows || []) {
    const f = formatFreq(r.networkFreqMhz ?? r.freqMhz);
    if (!f || f === "—") continue;
    set[f] = true;
  }
  return set;
}

/**
 * 换频目标行标签：已入场景/分析的频点正常标注；
 * 仅换频衔接、未进 TOP-K 场景分析的频点加「未入场景」以免与研判结果混淆。
 */
export function formatHopTrackFreqLabel(baseLabel, track, reportRows) {
  const base = baseLabel || "目标";
  const all = uniqueFreqLabels(collectTrackFreqs(track));
  if (!all.length) return base;
  const analyzed = analyzedFreqLabelSet(reportRows);
  const inAnalysis = all.filter((f) => analyzed[f]);
  const hopOnly = all.filter((f) => !analyzed[f]);
  let label = base;
  if (inAnalysis.length) {
    const miss = inAnalysis.filter((f) => String(label).indexOf(f) < 0);
    if (miss.length) label = `${label} ${miss.join("/")}`;
  }
  if (hopOnly.length) {
    label = `${label}${inAnalysis.length ? " · " : " "}换频${hopOnly.join("/")}(未入场景)`;
  } else if (!inAnalysis.length) {
    // 无报告行可对齐时仍标全部频点，但标明来自换频链
    const miss = all.filter((f) => String(label).indexOf(f) < 0);
    if (miss.length) label = `${label} ${miss.join("/")}`;
  }
  return label;
}

function collectTrackFreqs(track) {
  const freqs = [];
  for (const p of track?.points || []) {
    const f = Number(p.freqMhz);
    if (Number.isFinite(f)) freqs.push(f);
  }
  const seed = Number(track?.freqMhz ?? track?.seedFreqMhz);
  if (Number.isFinite(seed)) freqs.push(seed);
  return freqs;
}

function azimuthDelta(a, b) {
  const d = Math.abs(((Number(a) - Number(b) + 540) % 360) - 180);
  return Number.isFinite(d) ? d : Infinity;
}

function channelsForTrack(track, reportRows, freqTol) {
  const chs = new Set();
  for (const c of codesFromText(track.channelLabel || track.channel || track.targetChannelsUsed)) {
    chs.add(c);
  }
  if (chs.size) return chs;

  const freqs = collectTrackFreqs(track);
  const mean = Number(track.meanBearing);
  for (const r of reportRows || []) {
    const rf = Number(r.networkFreqMhz);
    let freqHit = false;
    if (Number.isFinite(rf) && freqs.length) {
      for (let i = 0; i < freqs.length; i++) {
        if (Math.abs(rf - freqs[i]) <= freqTol) {
          freqHit = true;
          break;
        }
      }
    }
    if (!freqHit) continue;
    const az = Number(r.meanAzimuthDeg);
    if (Number.isFinite(mean) && Number.isFinite(az) && azimuthDelta(mean, az) > 8) {
      continue;
    }
    const used = codesFromText(r.targetChannelsUsed);
    if (used.length) {
      used.forEach((c) => chs.add(c));
    } else {
      const one = normalizeChannelCode(r.commLinkChannelLabel || r.commLinkChannel);
      if (one) chs.add(one);
    }
  }
  return chs;
}

function emptyMatrix() {
  return {
    columns: FIXED_CHANNEL_CODES.map((id) => ({ id, label: id })),
    rows: []
  };
}

function toMatrixRows(rowsMeta, occupied) {
  const columns = FIXED_CHANNEL_CODES.map((id) => ({ id, label: id }));
  const rows = rowsMeta.map((r) => ({
    key: r.key,
    label: r.label,
    cells: columns.map((c) => occupied.has(`${r.key}|${c.id}`))
  }));
  return { columns, rows };
}

export function buildHopTargetChannelMatrix(view, reportRows, freqTol = 0.01) {
  const tracks = (view?.tracks || []).filter((t) => !t.noiseCandidate);
  if (!tracks.length) return emptyMatrix();
  const occupied = new Set();
  const rowsMeta = [];
  tracks.forEach((t, i) => {
    const base = t.label || `目标${i + 1}`;
    const label = formatHopTrackFreqLabel(base, t, reportRows);
    const key = String(t.trackId ?? t.seedTrackId ?? i);
    rowsMeta.push({ key, label });
    channelsForTrack(t, reportRows, freqTol).forEach((ch) => {
      occupied.add(`${key}|${ch}`);
    });
  });
  return toMatrixRows(rowsMeta, occupied);
}

/**
 * 本批全部目标 × D01–D06，不按场景拆分。
 */
export function buildTargetChannelMatrixFromRows(reportRows) {
  const occupied = new Set();
  const rowsMeta = [];
  const seen = {};
  for (const r of reportRows || []) {
    if (!r || !r.targetId) continue;
    const type = r.targetTypeLabel || r.targetType || "";
    const id = String(r.targetId);
    const base = type && type !== "—" && id.indexOf(type) < 0 ? `${id}-${type}` : id;
    const freqKey = formatFreq(r.networkFreqMhz);
    const label = labelWithFreq(base, r.networkFreqMhz);
    const key = `${r.targetId}|${r.targetType || ""}|${freqKey}`;
    if (!seen[key]) {
      seen[key] = true;
      rowsMeta.push({ key, label });
    }
    const used = codesFromText(r.targetChannelsUsed);
    if (used.length) {
      used.forEach((c) => occupied.add(`${key}|${c}`));
    } else {
      const one = normalizeChannelCode(r.commLinkChannelLabel || r.commLinkChannel);
      if (one) occupied.add(`${key}|${one}`);
    }
  }
  if (!rowsMeta.length) return emptyMatrix();
  return toMatrixRows(rowsMeta, occupied);
}
