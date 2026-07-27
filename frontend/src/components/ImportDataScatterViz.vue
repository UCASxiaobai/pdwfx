<template>
  <section class="import-scatter">
    <div class="head">
      <h3>全量数据概览</h3>
      <span v-if="meta" class="meta">{{ meta }}</span>
    </div>
    <p class="hint">
      本次导入 CSV 的检测点（经频段筛选后）：横轴时间、纵轴方位。仅标绘数据量最多的若干频点（最多 12 种可区分颜色），其余频点不绘制。
    </p>
    <p v-if="loading" class="status">正在加载并标绘散点…</p>
    <p v-else-if="error" class="status error">{{ error }}</p>
    <p v-else-if="!hasData" class="status">暂无散点数据。</p>
    <div v-show="hasData && !loading" ref="chartEl" class="chart-box" />
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";

const props = defineProps({
  scatter: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: "" }
});

const chartEl = ref(null);
let chartInst = null;

const MAX_PLOT_FREQS = 12;
const FREQ_COLORS = [
  "#D95319", "#0072BD", "#77AC30", "#4DBEEE", "#A2142F", "#7E2F8E",
  "#EDB120", "#636363", "#00A9CE", "#8C564B", "#9467BD", "#17BECF"
];

function normalizeScatter(raw) {
  if (!raw?.series?.length) return raw;
  if (raw.plottedFreqCount != null && raw.series.length <= MAX_PLOT_FREQS) return raw;
  const sorted = [...raw.series].sort(
    (a, b) => (b.totalPoints || b.points?.length || 0) - (a.totalPoints || a.points?.length || 0)
  );
  const top = sorted.slice(0, MAX_PLOT_FREQS).map((s, i) => ({
    ...s,
    color: s.color || FREQ_COLORS[i % FREQ_COLORS.length]
  }));
  const totalFreq = raw.totalFreqCount ?? raw.series.length;
  return {
    ...raw,
    series: top,
    plottedFreqCount: top.length,
    totalFreqCount: totalFreq,
    omittedFreqCount: Math.max(0, totalFreq - top.length)
  };
}

const normalizedScatter = computed(() => normalizeScatter(props.scatter));

const hasData = computed(() =>
  (normalizedScatter.value?.series || []).some((s) => (s.points || []).length)
);

const meta = computed(() => {
  const s = normalizedScatter.value;
  if (!s) return "";
  const total = s.totalPoints ?? 0;
  const shown = s.displayedPoints ?? 0;
  const plotted = s.plottedFreqCount ?? (s.series || []).length;
  const totalFreq = s.totalFreqCount ?? s.freqCount ?? plotted;
  const omitted = s.omittedFreqCount ?? Math.max(0, totalFreq - plotted);
  const sampled = total > shown ? ` · 抽样 ${shown}/${total} 点` : "";
  const omitNote = omitted > 0 ? ` · 未绘制 ${omitted} 个低频点` : "";
  return `标绘 Top${plotted} / 共 ${totalFreq} 频点${omitNote}${sampled}`;
});

function buildSeriesAndRange() {
  let xMin = Number.POSITIVE_INFINITY;
  let xMax = Number.NEGATIVE_INFINITY;
  const series = (normalizedScatter.value?.series || []).map((s) => {
    const data = (s.points || [])
      .map((p) => {
        const x = Number(p[0]);
        const y = Number(p[1]);
        if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
        xMin = Math.min(xMin, x);
        xMax = Math.max(xMax, x);
        return [x, y];
      })
      .filter(Boolean);
    return {
      name: s.label || `${s.freqMhz} MHz`,
      type: "scatter",
      symbolSize: 5,
      itemStyle: { color: s.color || "#2563eb", opacity: 0.8 },
      emphasis: { focus: "series" },
      data
    };
  }).filter((s) => s.data.length > 0);
  return { series, xMin, xMax };
}

async function render() {
  await nextTick();
  if (!chartEl.value || !hasData.value) {
    chartInst?.dispose();
    chartInst = null;
    return;
  }
  if (!chartInst) {
    chartInst = echarts.init(chartEl.value);
  }
  const { series, xMin, xMax } = buildSeriesAndRange();
  if (!series.length) return;

  const pad = Math.max(60000, (xMax - xMin) * 0.02);
  chartInst.setOption({
    tooltip: {
      trigger: "item",
      formatter(params) {
        const t = new Date(params.value[0]);
        const time = Number.isNaN(t.getTime())
          ? params.value[0]
          : t.toLocaleString("zh-CN", { hour12: false });
        return `${params.seriesName}<br/>时间: ${time}<br/>方位: ${Number(params.value[1]).toFixed(1)}°`;
      }
    },
    legend: {
      type: "plain",
      top: 4,
      left: "center",
      textStyle: { fontSize: 11 }
    },
    grid: { left: 58, right: 20, top: 52, bottom: 64 },
    xAxis: {
      type: "time",
      name: "时间",
      nameLocation: "middle",
      nameGap: 38,
      min: xMin - pad,
      max: xMax + pad,
      axisLabel: { fontSize: 10, hideOverlap: true }
    },
    yAxis: {
      type: "value",
      name: "方位 (°)",
      min: 0,
      max: 360,
      interval: 15,
      nameTextStyle: { fontSize: 11 },
      axisLabel: { fontSize: 11 },
      splitLine: { lineStyle: { color: "#e5e7eb", type: "dashed" } }
    },
    dataZoom: [
      { type: "inside", xAxisIndex: 0 },
      { type: "slider", xAxisIndex: 0, height: 20, bottom: 10 }
    ],
    series
  }, true);
  chartInst.resize();
}

watch(
  () => [props.scatter, props.loading],
  () => {
    if (!props.loading) render();
  },
  { deep: true }
);

function onResize() {
  chartInst?.resize();
}

onMounted(() => {
  if (!props.loading) render();
  window.addEventListener("resize", onResize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
  chartInst?.dispose();
  chartInst = null;
});
</script>

<style scoped>
.import-scatter {
  margin-bottom: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px 14px;
  background: #fff;
}
.head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
}
.head h3 {
  margin: 0;
  font-size: 1rem;
}
.meta {
  font-size: 12px;
  color: #6b7280;
}
.hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.45;
}
.status {
  font-size: 13px;
  color: #2563eb;
  margin: 0;
}
.status.error {
  color: #b91c1c;
}
.chart-box {
  width: 100%;
  height: 760px;
}
</style>
