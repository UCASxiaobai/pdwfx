<template>
  <section class="import-scatter" :class="{ embedded }">
    <div class="head">
      <h3>{{ title }}</h3>
      <span v-if="meta" class="meta">{{ meta }}</span>
    </div>
    <p class="hint">
      本次导入 CSV 的检测点（经频段筛选后）：横轴时间、纵轴方位。点击频点标签即可只标绘该频率；按住 Ctrl 可多选叠加。默认显示数据量最多的
      {{ MAX_IMPORT_SCATTER_FREQS }} 个频点。
    </p>
    <p v-if="loading" class="status">正在加载并标绘散点…</p>
    <p v-else-if="error" class="status error">{{ error }}</p>
    <p v-else-if="!hasSourceData" class="status">暂无散点数据。</p>

    <div v-if="hasSourceData && !loading" class="freq-plugin">
      <div class="plugin-head">
        <strong>频率筛选</strong>
        <span class="plugin-meta">
          已标绘 {{ plottedFreqKeys.length }} / 共 {{ allFreqOptions.length }} 个频点
        </span>
        <button type="button" class="text-btn" @click="pluginOpen = !pluginOpen">
          {{ pluginOpen ? "收起" : "展开" }}
        </button>
      </div>
      <div v-show="pluginOpen" class="plugin-body">
        <div class="plugin-row">
          <label>
            下限 MHz
            <input v-model.number="filterMin" type="number" step="0.001" />
          </label>
          <label>
            上限 MHz
            <input v-model.number="filterMax" type="number" step="0.001" />
          </label>
          <label class="grow">
            搜索
            <input v-model.trim="filterQuery" type="text" placeholder="如 306.925" />
          </label>
        </div>
        <div class="plugin-actions">
          <button type="button" class="chip-btn" @click="selectDefaultTop">默认 Top{{ MAX_IMPORT_SCATTER_FREQS }}</button>
          <button type="button" class="chip-btn" @click="selectVisible">全选可见并标绘</button>
          <button type="button" class="chip-btn" @click="clearSelection">清空</button>
          <button type="button" class="chip-btn primary" @click="applyAndRender">标绘到时间-方位图</button>
        </div>
        <div class="freq-chips">
          <button
            v-for="opt in visibleFreqOptions"
            :key="opt.key"
            type="button"
            class="freq-chip"
            :class="{ on: isPlotted(opt.key), top: opt.defaultPlot }"
            :title="`${opt.label} · ${opt.totalPoints} 点（单击只看该频；Ctrl+单击多选）`"
            @click="onFreqChipClick($event, opt.key)"
          >
            <i class="swatch" :style="{ background: opt.color }" />
            {{ opt.label }}
            <span class="cnt">{{ opt.totalPoints }}</span>
          </button>
          <p v-if="!visibleFreqOptions.length" class="empty-filter">当前筛选条件下无频点</p>
        </div>
      </div>
    </div>

    <p v-if="hasSourceData && !loading && !hasPlottedData" class="status">请点击频点标签进行标绘。</p>
    <div
      v-show="hasPlottedData && !loading"
      ref="chartEl"
      class="chart-box"
      :style="{ height: IMPORT_SCATTER_CHART_HEIGHT + 'px' }"
    />
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import {
  AZIMUTH_Y_AXIS,
  IMPORT_FREQ_COLORS,
  IMPORT_SCATTER_CHART_HEIGHT,
  IMPORT_SCATTER_GRID,
  MAX_IMPORT_SCATTER_FREQS,
  roundFreq3,
  timeAxisPad
} from "../scene/importScatterChart.js";

const props = defineProps({
  scatter: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  error: { type: String, default: "" },
  title: { type: String, default: "全量数据概览" },
  /** 嵌在外层卡片内时去掉重复边框 */
  embedded: { type: Boolean, default: false }
});

const chartEl = ref(null);
let chartInst = null;

const pluginOpen = ref(true);
const filterMin = ref(null);
const filterMax = ref(null);
const filterQuery = ref("");
/** @type {import('vue').Ref<string[]>} */
const selectedFreqKeys = ref([]);
/** Applied selection used by the chart (updated on 标绘 / auto defaults) */
const plottedFreqKeys = ref([]);

function freqKey(mhz) {
  const f = roundFreq3(mhz);
  return f == null ? "" : String(f);
}

function ensureSeriesColors(raw) {
  if (!raw?.series?.length) return raw;
  const series = raw.series.map((s, i) => ({
    ...s,
    color: s.color || IMPORT_FREQ_COLORS[i % IMPORT_FREQ_COLORS.length]
  }));
  return { ...raw, series };
}

const sourceScatter = computed(() => ensureSeriesColors(props.scatter));

const allFreqOptions = computed(() => {
  const s = sourceScatter.value;
  const fromSeries = (s?.series || []).map((ser, i) => {
    const f = roundFreq3(ser.freqMhz);
    return {
      key: freqKey(ser.freqMhz),
      freqMhz: f,
      label: ser.label || (f != null ? `${f} MHz` : "—"),
      color: ser.color || IMPORT_FREQ_COLORS[i % IMPORT_FREQ_COLORS.length],
      totalPoints: ser.totalPoints || ser.points?.length || 0,
      defaultPlot: ser.defaultPlot === true || i < MAX_IMPORT_SCATTER_FREQS,
      series: ser
    };
  }).filter((o) => o.key);
  if (fromSeries.length) return fromSeries;

  return (s?.freqCatalog || []).map((c, i) => {
    const f = roundFreq3(c.freqMhz);
    return {
      key: freqKey(c.freqMhz),
      freqMhz: f,
      label: f != null ? `${Number(f).toFixed(3)} MHz` : "—",
      color: IMPORT_FREQ_COLORS[i % IMPORT_FREQ_COLORS.length],
      totalPoints: c.totalPoints || 0,
      defaultPlot: c.defaultPlot === true || i < MAX_IMPORT_SCATTER_FREQS,
      series: null
    };
  }).filter((o) => o.key);
});

const visibleFreqOptions = computed(() => {
  const q = String(filterQuery.value || "").trim();
  const min = Number(filterMin.value);
  const max = Number(filterMax.value);
  const hasMin = Number.isFinite(min);
  const hasMax = Number.isFinite(max);
  return allFreqOptions.value.filter((o) => {
    if (hasMin && o.freqMhz != null && o.freqMhz < min) return false;
    if (hasMax && o.freqMhz != null && o.freqMhz > max) return false;
    if (q && String(o.label).indexOf(q) < 0 && String(o.freqMhz).indexOf(q) < 0) return false;
    return true;
  });
});

const hasSourceData = computed(() =>
  (sourceScatter.value?.series || []).some((s) => (s.points || []).length)
);

const plottedSeries = computed(() => {
  const want = new Set(plottedFreqKeys.value);
  if (!want.size) return [];
  return allFreqOptions.value
    .filter((o) => want.has(o.key) && o.series && (o.series.points || []).length)
    .map((o) => o.series);
});

const hasPlottedData = computed(() => plottedSeries.value.length > 0);

const meta = computed(() => {
  const s = sourceScatter.value;
  if (!s) return "";
  const total = s.totalPoints ?? 0;
  const shown = plottedSeries.value.reduce(
    (n, ser) => n + (ser.displayedPoints ?? ser.points?.length ?? 0),
    0
  );
  const totalFreq = s.totalFreqCount ?? allFreqOptions.value.length;
  const selected = plottedFreqKeys.value.length;
  const sampled = total > 0 && shown < total ? ` · 抽样约 ${shown} 点` : "";
  return `标绘 ${selected} / 共 ${totalFreq} 频点 · 源数据 ${total} 点${sampled}`;
});

function isPlotted(key) {
  return plottedFreqKeys.value.indexOf(key) >= 0;
}

/** 单击：只标绘该频点；Ctrl/Meta+单击：多选叠加并立即标绘 */
function onFreqChipClick(event, key) {
  const multi = !!(event && (event.ctrlKey || event.metaKey));
  if (multi) {
    const i = selectedFreqKeys.value.indexOf(key);
    if (i >= 0) {
      selectedFreqKeys.value = selectedFreqKeys.value.filter((k) => k !== key);
    } else {
      selectedFreqKeys.value = [...selectedFreqKeys.value, key];
    }
  } else {
    selectedFreqKeys.value = [key];
  }
  applyAndRender();
}

function selectDefaultTop() {
  selectedFreqKeys.value = allFreqOptions.value
    .filter((o) => o.defaultPlot)
    .slice(0, MAX_IMPORT_SCATTER_FREQS)
    .map((o) => o.key);
  if (!selectedFreqKeys.value.length) {
    selectedFreqKeys.value = allFreqOptions.value
      .slice(0, MAX_IMPORT_SCATTER_FREQS)
      .map((o) => o.key);
  }
  applyAndRender();
}

function selectVisible() {
  selectedFreqKeys.value = visibleFreqOptions.value.map((o) => o.key);
  applyAndRender();
}

function clearSelection() {
  selectedFreqKeys.value = [];
  applyAndRender();
}

function applyAndRender() {
  plottedFreqKeys.value = [...selectedFreqKeys.value];
  render();
}

function resetSelectionFromScatter() {
  const opts = allFreqOptions.value;
  if (!opts.length) {
    selectedFreqKeys.value = [];
    plottedFreqKeys.value = [];
    return;
  }
  const defaults = opts.filter((o) => o.defaultPlot).map((o) => o.key);
  const keys = defaults.length
    ? defaults.slice(0, MAX_IMPORT_SCATTER_FREQS)
    : opts.slice(0, MAX_IMPORT_SCATTER_FREQS).map((o) => o.key);
  selectedFreqKeys.value = keys;
  plottedFreqKeys.value = [...keys];

  const freqs = opts.map((o) => o.freqMhz).filter((n) => n != null);
  if (freqs.length) {
    filterMin.value = Math.min(...freqs);
    filterMax.value = Math.max(...freqs);
  } else {
    filterMin.value = null;
    filterMax.value = null;
  }
  filterQuery.value = "";
}

function buildSeriesAndRange() {
  let xMin = Number.POSITIVE_INFINITY;
  let xMax = Number.NEGATIVE_INFINITY;
  const series = plottedSeries.value
    .map((s) => {
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
    })
    .filter((s) => s.data.length > 0);
  return { series, xMin, xMax };
}

async function render() {
  await nextTick();
  if (!chartEl.value || !hasPlottedData.value) {
    chartInst?.dispose();
    chartInst = null;
    return;
  }
  if (!chartInst) {
    chartInst = echarts.init(chartEl.value);
  }
  const { series, xMin, xMax } = buildSeriesAndRange();
  if (!series.length) {
    chartInst.clear();
    return;
  }

  const pad = timeAxisPad(xMin, xMax);
  chartInst.setOption(
    {
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
        type: "scroll",
        top: 4,
        left: "center",
        textStyle: { fontSize: 11 }
      },
      grid: { ...IMPORT_SCATTER_GRID },
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
        min: AZIMUTH_Y_AXIS.min,
        max: AZIMUTH_Y_AXIS.max,
        interval: AZIMUTH_Y_AXIS.interval,
        nameTextStyle: { fontSize: 11 },
        axisLabel: { fontSize: 11 },
        splitLine: { lineStyle: { color: "#e5e7eb", type: "dashed" } }
      },
      dataZoom: [
        { type: "inside", xAxisIndex: 0 },
        { type: "slider", xAxisIndex: 0, height: 20, bottom: 10 }
      ],
      series
    },
    true
  );
  chartInst.resize();
}

watch(
  () => [props.scatter, props.loading],
  () => {
    if (props.loading) return;
    resetSelectionFromScatter();
    render();
  },
  { deep: true }
);

function onResize() {
  chartInst?.resize();
}

onMounted(() => {
  if (!props.loading) {
    resetSelectionFromScatter();
    render();
  }
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
.import-scatter.embedded {
  margin-bottom: 0;
  border: none;
  border-radius: 0;
  padding: 0;
  background: transparent;
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
  margin: 0 0 8px;
}
.status.error {
  color: #b91c1c;
}
.chart-box {
  width: 100%;
}
.freq-plugin {
  margin: 0 0 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #f9fafb;
  padding: 8px 10px;
}
.plugin-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.plugin-head strong {
  font-size: 13px;
  color: #111827;
}
.plugin-meta {
  font-size: 12px;
  color: #6b7280;
  flex: 1;
}
.text-btn {
  border: none;
  background: transparent;
  color: #2563eb;
  cursor: pointer;
  font-size: 12px;
  padding: 0;
}
.plugin-body {
  margin-top: 8px;
}
.plugin-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 8px;
}
.plugin-row label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #4b5563;
}
.plugin-row label.grow {
  flex: 1;
  min-width: 140px;
}
.plugin-row input {
  border: 1px solid #d1d5db;
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 13px;
  min-width: 100px;
}
.plugin-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.chip-btn {
  border: 1px solid #d1d5db;
  background: #fff;
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  color: #374151;
}
.chip-btn:hover {
  border-color: #93c5fd;
  color: #1d4ed8;
}
.chip-btn.primary {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}
.chip-btn.primary:hover {
  background: #1d4ed8;
}
.freq-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 160px;
  overflow: auto;
  padding: 2px 0;
}
.freq-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid #e5e7eb;
  background: #fff;
  border-radius: 999px;
  padding: 3px 10px 3px 6px;
  font-size: 12px;
  color: #374151;
  cursor: pointer;
}
.freq-chip.top {
  border-style: dashed;
}
.freq-chip.on {
  border-color: #2563eb;
  background: #eff6ff;
  color: #1e40af;
  font-weight: 600;
}
.swatch {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.cnt {
  color: #9ca3af;
  font-size: 11px;
}
.freq-chip.on .cnt {
  color: #60a5fa;
}
.empty-filter {
  margin: 4px 0;
  font-size: 12px;
  color: #9ca3af;
}
</style>
