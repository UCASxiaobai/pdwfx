import { ensureAnalysisSession, fetchNetworkDetail } from "./sceneApi.js";
import { normalizeNetwork } from "./signalUi.js";

/** 按「导出路径|analysisId」缓存会话 */
export const sessionByCsv = new Map();
/** 按 analysisId-networkId 缓存网络详情 */
export const networkByKey = new Map();
/** 按场景分析键缓存已构建视图（由 analysisBearing 写入） */
export const viewByRank = new Map();

/** 单次信号分析的唯一键（rank + analysisId + 导出路径） */
export function analysisViewCacheKey(item) {
  if (!item) return "";
  const rank = item.rank ?? "";
  const aid = item.session?.analysisId ?? "";
  const csv = item.exportedCsvPath ?? "";
  return `${rank}|${aid}|${csv}`;
}

export function sessionCacheKey(item) {
  const csv = item?.exportedCsvPath ?? "";
  const aid = item?.session?.analysisId ?? "";
  if (!csv) return aid || "";
  return aid ? `${csv}|${aid}` : csv;
}

export function forwardItemsSignature(items) {
  return (items || [])
    .map((it) => analysisViewCacheKey(it))
    .join("|");
}

export function allowedRanksSignature(allowed) {
  if (!allowed?.size) return "*";
  return [...allowed].sort((a, b) => a - b).join(",");
}

export function clearAnalysisViewCache() {
  sessionByCsv.clear();
  networkByKey.clear();
  viewByRank.clear();
}

export async function resolveCachedSession(item, signal, freqTolerance) {
  if (!item?.exportedCsvPath) {
    return item?.session || null;
  }
  const cacheKey = sessionCacheKey(item);
  if (cacheKey && sessionByCsv.has(cacheKey)) {
    return sessionByCsv.get(cacheKey);
  }
  const session = await ensureAnalysisSession(
    {
      analysisId: item.session?.analysisId,
      exportedCsvPath: item.exportedCsvPath,
      freqTolerance
    },
    signal
  );
  const storeKey = session?.analysisId
    ? `${item.exportedCsvPath}|${session.analysisId}`
    : item.exportedCsvPath;
  sessionByCsv.set(storeKey, session);
  return session;
}

export async function loadCachedNetwork(analysisId, networkId, item, signal, freqTolerance) {
  const key = `${analysisId}-${networkId}`;
  if (networkByKey.has(key)) {
    return networkByKey.get(key);
  }
  let raw;
  try {
    raw = await fetchNetworkDetail(analysisId, networkId, signal);
  } catch (e) {
    const msg = String(e?.message || e);
    if (!msg.includes("404") || !item?.exportedCsvPath) {
      throw e;
    }
    sessionByCsv.delete(sessionCacheKey(item));
    const refreshed = await resolveCachedSession(item, signal, freqTolerance);
    raw = await fetchNetworkDetail(refreshed.analysisId, networkId, signal);
  }
  const net = normalizeNetwork(raw);
  networkByKey.set(key, net);
  return net;
}
