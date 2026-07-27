import { appendDfMatchParams } from "./dfMatchConfig.js";
import { apiUrl } from "./apiBase.js";

export function parseApiError(text, status) {
  try {
    const j = JSON.parse(text);
    if (j.error) return j.error;
  } catch {
    /* ignore */
  }
  if (status === 0 || text === "") {
    return "网络连接中断（可能处理时间过长）。请减少场景数量或取消「加载全部网络详情」后重试。";
  }
  return text || `请求失败 (${status})`;
}

export async function analyzeScenesUpload(files, params, signal) {
  const fd = new FormData();
  for (const f of files) {
    fd.append("files", f, f.name);
  }
  appendOptional(fd, "freqMin", params.freqMin);
  appendOptional(fd, "freqMax", params.freqMax);
  appendOptional(fd, "freqTolerance", params.freqTolerance);
  appendOptional(fd, "windowSeconds", params.windowSeconds);
  appendOptional(fd, "windowStepSeconds", params.windowStepSeconds);
  appendOptional(fd, "minTracksInScene", params.minTracksInScene);
  appendOptional(fd, "topKScenes", params.topKScenes);
  appendOptional(fd, "topKTrackScenes", params.topKTrackScenes);
  appendOptional(fd, "topKPollingScenes", params.topKPollingScenes);
  appendOptional(fd, "outputDir", params.outputDir);
  if (params.enableImportScatter) {
    fd.append("enableImportScatter", "true");
  }
  const res = await fetch(apiUrl("/api/scenes/analyze-upload"), { method: "POST", body: fd, signal });
  const text = await res.text();
  if (!res.ok) throw new Error(parseApiError(text, res.status));
  return JSON.parse(text);
}

export async function fetchVisualizationData(outputDir, signal) {
  const q = new URLSearchParams({ outputDir: outputDir || "./output" });
  const res = await fetch(apiUrl(`/api/scenes/visualization-data?${q}`), { signal });
  const text = await res.text();
  if (!res.ok) throw new Error(parseApiError(text, res.status));
  return JSON.parse(text);
}

function appendOptional(fd, key, value) {
  if (value !== null && value !== undefined && value !== "") {
    fd.append(key, String(value));
  }
}

/** 单场景：导出同频数据 → 信号分析 → 汇总 */
export async function processScene(body, signal) {
  const res = await fetch(apiUrl("/api/scenes/process-scene"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal
  });
  const text = await res.text();
  if (!res.ok) throw new Error(parseApiError(text, res.status));
  return JSON.parse(text);
}

/** 逐场景同频分析（每个场景独立 analysisId） */
export async function processScenesPipeline(
  { sourceCsvPath, outputDir, sceneRanks, freqTolerance, preloadAll },
  { signal, onProgress, onPartialRows }
) {
  const ranks = sceneRanks || [];
  const forwardItems = [];
  const allRows = [];
  let totalMs = 0;

  for (let i = 0; i < ranks.length; i++) {
    const rank = ranks[i];
    onProgress?.(i + 1, ranks.length, rank);
    const result = await processScene(
      {
        sourceCsvPath,
        outputDir,
        sceneRank: rank,
        freqTolerance,
        preloadAll
      },
      signal
    );
    forwardItems.push({
      rank: result.rank,
      sceneType: result.sceneType,
      exportedRowCount: result.exportedRowCount,
      exportedCsvPath: result.exportedCsvPath,
      session: result.session
    });
    if (result.reportRows?.length) {
      allRows.push(...result.reportRows);
      onPartialRows?.(allRows, i + 1, ranks.length);
    }
    totalMs += result.elapsedMs || 0;
  }

  return {
    forwardItems,
    rows: allRows,
    buildTimeMs: totalMs,
    networkDetailCount: forwardItems.length
  };
}

/** 会话被驱逐时从场景导出 CSV 恢复（返回新的或现有的 analysisId） */
export async function ensureAnalysisSession(
  { analysisId, exportedCsvPath, freqTolerance },
  signal
) {
  const res = await fetch(apiUrl("/api/scenes/ensure-session"), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      analysisId: analysisId || "",
      exportedCsvPath,
      freqTolerance: freqTolerance ?? 0.01
    }),
    signal
  });
  const text = await res.text();
  if (!res.ok) throw new Error(parseApiError(text, res.status));
  return JSON.parse(text);
}

export async function fetchNetworkDetail(analysisId, networkId, signal) {
  const res = await fetch(
    apiUrl(`/api/signals/analysis/${analysisId}/networks/${networkId}`),
    { signal }
  );
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `加载失败 (${res.status})`);
  }
  return res.json();
}

/**
 * 测向 CSV + 外源定位 CSV 关联匹配。
 * @param {File} bearingFile PDW / PrcFf 测向文件
 * @param {File} locateFile 雷情 Lq 定位文件
 * @param {object} params 见 dfMatchConfig.DEFAULT_DF_MATCH_PARAMS
 */
export async function fetchDirectionFindingMatch(bearingFile, locateFile, params, signal) {
  const fd = new FormData();
  fd.append("bearingFile", bearingFile, bearingFile.name);
  fd.append("locateFile", locateFile, locateFile.name);
  appendDfMatchParams(fd, params);
  const res = await fetch(apiUrl("/api/signals/df-match"), { method: "POST", body: fd, signal });
  const text = await res.text();
  if (!res.ok) throw new Error(parseApiError(text, res.status));
  return JSON.parse(text);
}
