import fs from "fs";
import path from "path";

const outDir = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, "$1")), "..", "output");
const summaryPath = path.join(outDir, "scene_summary.csv");

function parseCsvLine(line) {
  const out = [];
  let cur = "";
  let inQ = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') {
      inQ = !inQ;
      continue;
    }
    if (c === "," && !inQ) {
      out.push(cur);
      cur = "";
    } else cur += c;
  }
  out.push(cur);
  return out;
}

function readSummary() {
  const lines = fs.readFileSync(summaryPath, "utf8").trim().split(/\r?\n/);
  const hdr = parseCsvLine(lines[0]);
  const idx = (n) => hdr.indexOf(n);
  return lines.slice(1).map((line) => {
    const p = parseCsvLine(line);
    return {
      rank: +p[idx("rank")],
      type: p[idx("scene_type")],
      start: new Date(p[idx("window_start")]),
      end: new Date(p[idx("window_end")]),
      freq: +p[idx("freq_center_mhz")],
      score: +p[idx("score")],
      pollingPeriod: p[idx("polling_period_sec")] ? +p[idx("polling_period_sec")] : null,
      trackCount: +p[idx("track_count")] || 0
    };
  });
}

function overlapSec(a, b) {
  return Math.max(0, (Math.min(a.end, b.end) - Math.max(a.start, b.start)) / 1000);
}

function median(arr) {
  if (!arr.length) return 0;
  const s = [...arr].sort((x, y) => x - y);
  return s[Math.floor(s.length / 2)];
}

function detectionSpan(filePath) {
  const text = fs.readFileSync(filePath, "utf8");
  const lines = text.trim().split(/\r?\n/);
  const hdr = lines[0].split(",");
  const tcol = hdr.findIndex((h) => h === "zcsj" || h.toLowerCase().includes("time"));
  if (tcol < 0) return null;
  const times = [];
  for (const line of lines.slice(1)) {
    const p = line.split(",");
    const t = Date.parse(p[tcol]);
    if (Number.isFinite(t)) times.push(t);
  }
  if (!times.length) return null;
  return {
    n: times.length,
    spanSec: (Math.max(...times) - Math.min(...times)) / 1000
  };
}

const rows = readSummary();
const durs = rows.map((r) => (r.end - r.start) / 1000);
console.log("=== 当前输出场景时间窗长度 ===");
for (const t of ["MULTI_DEVICE_POLLING", "TRACK_CONTINUOUS"]) {
  const ds = rows.filter((r) => r.type === t).map((r) => (r.end - r.start) / 1000);
  console.log(`  ${t}: ${[...new Set(ds)].join(", ")} 秒 (${ds.length} 个场景)`);
}

console.log("\n=== 场景间时间重叠（同类型）===");
for (const t of ["MULTI_DEVICE_POLLING", "TRACK_CONTINUOUS"]) {
  const sub = rows.filter((r) => r.type === t).sort((a, b) => a.start - b.start);
  const ovs = [];
  for (let i = 0; i < sub.length; i++) {
    for (let j = i + 1; j < sub.length; j++) {
      const o = overlapSec(sub[i], sub[j]);
      if (o > 0) ovs.push(o);
    }
  }
  const gaps = sub.slice(1).map((r, i) => (r.start - sub[i].start) / 1000);
  console.log(
    `  ${t}: ${sub.length} 场景, ${ovs.length} 对重叠, `
    + `重叠时长 median=${median(ovs).toFixed(0)}s, 起始间隔 median=${median(gaps).toFixed(0)}s`
  );
}

console.log("\n=== 导出 CSV 内实际检测点时间跨度 ===");
const files = fs.readdirSync(outDir)
  .filter((f) => /^scene_rank\d+_.*_(track|polling)_detections\.csv$/.test(f))
  .sort();
const spans = { TRACK_CONTINUOUS: [], MULTI_DEVICE_POLLING: [] };
for (const f of files) {
  const m = f.match(/^scene_rank(\d+)_/);
  if (!m) continue;
  const rank = +m[1];
  const scene = rows.find((r) => r.rank === rank);
  if (!scene) continue;
  const info = detectionSpan(path.join(outDir, f));
  if (!info) continue;
  spans[scene.type].push({ rank, ...info, windowSec: (scene.end - scene.start) / 1000 });
}
for (const t of ["MULTI_DEVICE_POLLING", "TRACK_CONTINUOUS"]) {
  const list = spans[t];
  if (!list.length) continue;
  const spanSecs = list.map((x) => x.spanSec);
  const ratios = list.map((x) => x.spanSec / x.windowSec);
  console.log(
    `  ${t}: 检测跨度 median=${median(spanSecs).toFixed(1)}s, `
    + `max=${Math.max(...spanSecs).toFixed(1)}s, 占时间窗比例 median=${(median(ratios) * 100).toFixed(0)}%`
  );
}

const pollingPeriods = rows.filter((r) => r.pollingPeriod).map((r) => r.pollingPeriod);
if (pollingPeriods.length) {
  console.log("\n=== 轮询场景周期 ===");
  console.log(`  周期范围 ${Math.min(...pollingPeriods).toFixed(1)} ~ ${Math.max(...pollingPeriods).toFixed(1)} 秒`);
  const minBursts = 5;
  console.log(`  配置要求至少 ${minBursts} 轮 → 最短有效观测约 ${(Math.min(...pollingPeriods) * minBursts).toFixed(0)} ~ ${(median(pollingPeriods) * minBursts).toFixed(0)} 秒`);
}

console.log("\n=== 评分时间窗建议（基于数据）===");
const trackSpans = spans.TRACK_CONTINUOUS.map((x) => x.spanSec);
const pollSpans = spans.MULTI_DEVICE_POLLING.map((x) => x.spanSec);
const trackMed = median(trackSpans);
const pollMed = median(pollSpans);
const trackP90 = [...trackSpans].sort((a, b) => a - b)[Math.floor(trackSpans.length * 0.9)] || trackMed;
const pollP90 = [...pollSpans].sort((a, b) => a - b)[Math.floor(pollSpans.length * 0.9)] || pollMed;

const recTrack = Math.ceil(trackP90 / 10) * 10;
const recPoll = Math.ceil(pollP90 / 10) * 10;
const recUnified = Math.ceil(Math.max(trackP90, pollP90) / 30) * 30;

console.log(`  连续轨迹场景：检测跨度 P90≈${trackP90.toFixed(0)}s → 建议 window-seconds ≈ ${recTrack}s（当前 120s）`);
console.log(`  轮询场景：检测跨度 P90≈${pollP90.toFixed(0)}s → 建议 polling-window-seconds ≈ ${recPoll}s（当前 90s）`);
console.log(`  若统一用一个「评分时间窗」参数：≈ ${recUnified}s`);
console.log(`  同时建议 window-step-seconds ≥ 时间窗的 50%（当前 30s/120s=25% 导致大量重叠）`);
