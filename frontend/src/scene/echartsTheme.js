import * as echarts from "echarts";

export const CET36_CHART_COLORS = [
  "#51e9ff",
  "#2fd2f1",
  "#01cde0",
  "#5470C6",
  "#91CC75",
  "#EE6666",
  "#FAC858",
  "#73C0DE"
];

export function applyCet36Theme(option) {
  const base = {
    backgroundColor: "#17182c",
    textStyle: { color: "#b3d2d5" },
    title: { textStyle: { color: "#ffffff" } },
    legend: { textStyle: { color: "#ffffff" } },
    tooltip: {
      backgroundColor: "rgba(0, 19, 34, 0.92)",
      borderColor: "#2d6c83",
      textStyle: { color: "#ffffff" }
    }
  };
  if (!option) return base;
  return Object.assign({}, base, option, {
    title: Object.assign({}, base.title, option.title || {}),
    legend: Object.assign({}, base.legend, option.legend || {}),
    tooltip: Object.assign({}, base.tooltip, option.tooltip || {}),
    xAxis: normalizeAxis(option.xAxis),
    yAxis: normalizeAxis(option.yAxis)
  });
}

function normalizeAxis(axis) {
  if (!axis) return axis;
  const style = {
    axisLine: { lineStyle: { color: "#2d6c83" } },
    axisLabel: { color: "#b3d2d5" },
    splitLine: { lineStyle: { color: "rgba(45, 108, 131, 0.35)" } }
  };
  if (Array.isArray(axis)) {
    return axis.map((a) => Object.assign({}, style, a));
  }
  return Object.assign({}, style, axis);
}

export function initCet36Chart(dom) {
  const chart = echarts.init(dom, null, { renderer: "canvas" });
  return chart;
}

export { echarts };
