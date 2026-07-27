/** 图表统计抽样，避免上万行拖慢 ECharts */
export function sampleRowsForCharts(rows, maxPoints = 2500) {
  const list = rows || [];
  if (list.length <= maxPoints) return list;
  const step = Math.ceil(list.length / maxPoints);
  const out = [];
  for (let i = 0; i < list.length; i += step) {
    out.push(list[i]);
  }
  return out;
}

export function debounce(fn, ms = 280) {
  let t;
  return (...args) => {
    clearTimeout(t);
    t = setTimeout(() => fn(...args), ms);
  };
}
