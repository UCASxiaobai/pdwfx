/** 将秒格式化为「X秒 Y毫秒 Z微秒」 */
export function formatPeriodSecMsUs(periodSec) {
  const n = Number(periodSec);
  if (!Number.isFinite(n) || n < 0) return "—";
  const totalMicros = Math.round(n * 1000000);
  const sec = Math.floor(totalMicros / 1000000);
  const ms = Math.floor((totalMicros % 1000000) / 1000);
  const us = totalMicros % 1000;
  return `${sec}s ${ms}ms ${us}us`;
}
