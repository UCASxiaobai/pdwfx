/**
 * 自动验证后端 API（需 Node 18+，本地 18080 已启动）
 * node scripts/verify-api.mjs
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const BASE = process.env.API_BASE || "http://localhost:18080";
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");

const results = [];

function ok(name, msg) {
  results.push({ name, ok: true, msg });
  console.log(`OK   ${name}: ${msg}`);
}
function fail(name, msg) {
  results.push({ name, ok: false, msg });
  console.error(`FAIL ${name}: ${msg}`);
}

async function uploadCsv(relPath, freqTolerance = 0.1) {
  const filePath = path.join(ROOT, relPath);
  const body = fs.readFileSync(filePath);
  const name = path.basename(filePath);
  const boundary = "----VerifyBoundary" + Date.now();
  const parts = [
    `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="${name}"\r\nContent-Type: text/csv\r\n\r\n`,
    body,
    `\r\n--${boundary}\r\nContent-Disposition: form-data; name="freqTolerance"\r\n\r\n${freqTolerance}\r\n--${boundary}--\r\n`,
  ];
  const payload = Buffer.concat(parts.map((p) => (Buffer.isBuffer(p) ? p : Buffer.from(p, "utf8"))));
  const res = await fetch(`${BASE}/api/signals/analyze`, {
    method: "POST",
    headers: { "Content-Type": `multipart/form-data; boundary=${boundary}` },
    body: payload,
  });
  if (!res.ok) throw new Error(`analyze ${relPath} HTTP ${res.status}: ${await res.text()}`);
  return res.json();
}

async function analyzeJson(payload) {
  const res = await fetch(`${BASE}/api/signals/analyze/json`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  if (!res.ok) throw new Error(`analyze/json HTTP ${res.status}: ${await res.text()}`);
  return res.json();
}

async function getNetwork(sessionId, networkId) {
  const res = await fetch(`${BASE}/api/signals/analysis/${sessionId}/networks/${networkId}`);
  if (!res.ok) throw new Error(`network ${networkId} HTTP ${res.status}`);
  return res.json();
}

async function main() {
  console.log(`验证目标: ${BASE}\n`);

  try {
    const ping = await fetch(`${BASE}/api/signals/analysis/ping/networks/1`);
    if (ping.status !== 404 && ping.status !== 200) throw new Error(`HTTP ${ping.status}`);
    ok("backend-reachable", "18080 响应正常");
  } catch (e) {
    fail("backend-reachable", e.message);
    summarize();
    process.exit(1);
  }

  // 1) 表格 CSV + 详情节奏字段
  try {
    const t0 = Date.now();
    const session = await uploadCsv("sample-table-signals.csv");
    const ms = Date.now() - t0;
    const net = session.networks?.[0];
    if (!session.analysisId || session.networkCount < 1) throw new Error("无网络");
    if (net.targetCount !== 0) throw new Error(`导入后列表目标数应为0或—占位，实际 ${net.targetCount}`);
    const detail = await getNetwork(session.analysisId, net.networkId);
    const t = detail.targets?.[0];
    if (!t) throw new Error("无目标");
    if (!(t.periodMs > 0)) throw new Error("缺少 periodMs");
    if (t.burstCount == null) throw new Error("缺少 burstCount");
    ok("csv-table", `session=${session.analysisId.slice(0, 8)}… 目标=${detail.targets.length} 周期=${t.periodMs}ms burst=${t.burstCount} (${ms}ms)`);
  } catch (e) {
    fail("csv-table", e.message);
  }

  // 2) 多频 sample-signals
  try {
    const session = await uploadCsv("sample-signals.csv");
    if (session.networkCount < 2) throw new Error(`多频应≥2网，实际 ${session.networkCount}`);
    ok("csv-multi-freq", `networkCount=${session.networkCount}`);
  } catch (e) {
    fail("csv-multi-freq", e.message);
  }

  // 3) JSON 双频
  try {
    const session = await analyzeJson({
      freqTolerance: 0.1,
      signals: [
        {
          detectTime: "2025-07-03T11:25:06.234350",
          freq: 451.175,
          azimuth: 165,
          signalLevel: 64,
          nSignalTime10us: 1461,
        },
        {
          detectTime: "2025-07-03T11:25:06.062560",
          freq: 458.35,
          azimuth: 153.5,
          signalLevel: 75,
          nSignalTime10us: 13125,
        },
      ],
    });
    if (session.networkCount !== 2) throw new Error(`期望2网，实际 ${session.networkCount}`);
    ok("json-multi-freq", `networkCount=${session.networkCount}`);
  } catch (e) {
    fail("json-multi-freq", e.message);
  }

  // 4) PrcFf 大文件（仅检查能完成分网，限时）
  try {
    const t0 = Date.now();
    const session = await uploadCsv("PrcFf1139.csv");
    const ms = Date.now() - t0;
    if (session.networkCount < 100) throw new Error(`PrcFf 网络数异常: ${session.networkCount}`);
    const top = [...session.networks].sort((a, b) => b.signalCount - a.signalCount)[0];
    const detail = await getNetwork(session.analysisId, top.networkId);
    if (!detail.targets?.length) throw new Error("大网详情无目标");
    const t = detail.targets.find((x) => x.burstCount > 1) || detail.targets[0];
    ok(
      "prcff-large",
      `${ms}ms 网络=${session.networkCount} 最大网#${top.networkId} sig=${top.signalCount} 详情目标=${detail.targets.length} 样例周期=${t.periodMs}ms burst=${t.burstCount} 驻留=${t.burstDurationMeanMs}`
    );
  } catch (e) {
    fail("prcff-large", e.message);
  }

  // 5) 前端代理
  try {
    const r2 = await uploadCsvViaProxy();
    ok("frontend-proxy", `经 5173 代理分析 networkCount=${r2.networkCount}`);
  } catch (e) {
    fail("frontend-proxy", e.message || "5173 未启动");
  }

  summarize();
  process.exit(results.some((r) => !r.ok) ? 1 : 0);
}

async function uploadCsvViaProxy() {
  const filePath = path.join(ROOT, "sample-table-signals.csv");
  const body = fs.readFileSync(filePath);
  const boundary = "----VerifyProxy" + Date.now();
  const parts = [
    `--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample-table-signals.csv"\r\n\r\n`,
    body,
    `\r\n--${boundary}\r\nContent-Disposition: form-data; name="freqTolerance"\r\n\r\n0.1\r\n--${boundary}--\r\n`,
  ];
  const payload = Buffer.concat(parts.map((p) => (Buffer.isBuffer(p) ? p : Buffer.from(p, "utf8"))));
  const res = await fetch("http://localhost:5173/api/signals/analyze", {
    method: "POST",
    headers: { "Content-Type": `multipart/form-data; boundary=${boundary}` },
    body: payload,
  });
  if (!res.ok) throw new Error(`proxy HTTP ${res.status}`);
  return res.json();
}

function summarize() {
  const passed = results.filter((r) => r.ok).length;
  const failed = results.filter((r) => !r.ok).length;
  console.log(`\n========== 合计 ${passed} 通过 / ${failed} 失败 ==========`);
}

main();
