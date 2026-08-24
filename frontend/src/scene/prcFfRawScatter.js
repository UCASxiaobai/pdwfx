/**
 * 本地解析 PrcFf / PDW CSV：只取 pl、xhfw、zcsj。
 * 除频率归桶外不做驻留窗、频段上下限、抽样等过滤。
 */

export function roundFreq3(mhz) {
  const n = Number(mhz);
  if (!Number.isFinite(n) || n <= 0) return null;
  return Math.round(n * 1000) / 1000;
}

export function parseCsvLine(line) {
  const out = [];
  let cur = "";
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (inQuotes) {
      if (c === '"') {
        if (line[i + 1] === '"') {
          cur += '"';
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        cur += c;
      }
    } else if (c === '"') {
      inQuotes = true;
    } else if (c === ",") {
      out.push(cur);
      cur = "";
    } else {
      cur += c;
    }
  }
  out.push(cur);
  return out;
}

function headerIndex(headerLine) {
  const cols = parseCsvLine(headerLine).map((h) => String(h || "").trim().toLowerCase());
  const map = {};
  cols.forEach((name, i) => {
    if (name) map[name] = i;
  });
  return {
    pl: map.pl,
    xhfw: map.xhfw,
    zcsj: map.zcsj
  };
}

function parseTimeMs(text) {
  if (!text) return NaN;
  const t = Date.parse(String(text).trim());
  return Number.isFinite(t) ? t : NaN;
}

function parseAzimuth(text) {
  const n = Number(text);
  if (!Number.isFinite(n)) return NaN;
  const az = ((n % 360) + 360) % 360;
  return az;
}

/**
 * @param {string} text CSV 全文
 * @returns {{
 *   fileName: string,
 *   rowCount: number,
 *   skipped: number,
 *   bands: Array<{ freqMhz: number, key: string, points: number[][] }>
 * }}
 */
export function parsePrcFfTimeAzimuth(text) {
  const raw = String(text || "").replace(/^\uFEFF/, "");
  const lines = raw.split(/\r\n|\n|\r/);
  let headerLine = "";
  let start = 0;
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].trim()) {
      headerLine = lines[i];
      start = i + 1;
      break;
    }
  }
  const idx = headerIndex(headerLine);
  if (idx.pl == null || idx.xhfw == null || idx.zcsj == null) {
    throw new Error("不是 PrcFf/PDW 表：需要列 pl、xhfw、zcsj");
  }

  const byFreq = new Map();
  let rowCount = 0;
  let skipped = 0;
  for (let i = start; i < lines.length; i++) {
    const line = lines[i];
    if (!line || !line.trim()) continue;
    rowCount++;
    const cells = parseCsvLine(line);
    const freq = roundFreq3(cells[idx.pl]);
    const az = parseAzimuth(cells[idx.xhfw]);
    const t = parseTimeMs(cells[idx.zcsj]);
    if (freq == null || !Number.isFinite(az) || !Number.isFinite(t)) {
      skipped++;
      continue;
    }
    const key = String(freq);
    let band = byFreq.get(key);
    if (!band) {
      band = { freqMhz: freq, key, points: [] };
      byFreq.set(key, band);
    }
    band.points.push([t, az]);
  }

  const bands = Array.from(byFreq.values());
  bands.sort((a, b) => b.points.length - a.points.length);
  return { rowCount, skipped, bands };
}
