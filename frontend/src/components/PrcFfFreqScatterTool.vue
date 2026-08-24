<template>
  <div class="ff-tool">
    <header class="head">
      <div>
        <h2>PrcFf 原始频率标绘</h2>
        <p class="sub">
          本地读取 PDW CSV（pl / xhfw / zcsj），按频率点选后画时间-方位散点。
          不做驻留窗、频段上下限、抽样。缺列或无法解析的行会跳过。
          <a href="#/">返回主流程</a>
          ·
          <a href="#/stream">流式态势</a>
        </p>
      </div>
    </header>

    <section class="card">
      <label class="upload-btn">
        选择 CSV
        <input type="file" accept=".csv" hidden @change="onPickFile" />
      </label>
      <span v-if="fileName" class="file-name">{{ fileName }}</span>
      <p class="hint">可直接选工程目录中的 PrcFf1139.csv。大文件解析会在浏览器内完成，请稍候。</p>
      <p v-if="status" class="status">{{ status }}</p>
      <p v-if="error" class="error">{{ error }}</p>
    </section>

    <section v-if="bands.length" class="card">
      <div class="plugin-head">
        <strong>频率筛选</strong>
        <span class="plugin-meta">
          已标绘 {{ plottedKeys.length }} / 共 {{ bands.length }} 个频点 · 源数据 {{ parsedRows }} 行
          <template v-if="skipped"> · 无法解析 {{ skipped }}</template>
        </span>
      </div>
      <div class="plugin-row">
        <label>
          搜索
          <input v-model.trim="query" type="text" placeholder="如 451.175" />
        </label>
      </div>
      <div class="freq-chips">
        <button
          v-for="b in visibleBands"
          :key="b.key"
          type="button"
          class="freq-chip"
          :class="{ on: isPlotted(b.key) }"
          :title="`${b.freqMhz} MHz · ${b.points.length} 点（单击只看该频；Ctrl+单击多选）`"
          @click="onChipClick($event, b.key)"
        >
          <i class="swatch" :style="{ background: colorOf(b.key) }" />
          {{ b.freqMhz.toFixed(3) }} MHz
          <span class="cnt">{{ b.points.length }}</span>
        </button>
        <p v-if="!visibleBands.length" class="empty">当前搜索无频点</p>
      </div>
    </section>

    <section v-if="plottedSeries.length" class="card">
      <h3>时间-方位</h3>
      <p class="meta">{{ plotMeta }}</p>
      <div ref="chartEl" class="chart" />
    </section>
    <p v-else-if="bands.length" class="hint pad">点击频点标签进行标绘。</p>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import {
  AZIMUTH_Y_AXIS,
  IMPORT_FREQ_COLORS,
  IMPORT_SCATTER_GRID,
  timeAxisPad
} from "../scene/importScatterChart.js";
import { parsePrcFfTimeAzimuth } from "../scene/prcFfRawScatter.js";

const fileName = ref("");
const status = ref("");
const error = ref("");
const bands = ref([]);
const parsedRows = ref(0);
const skipped = ref(0);
const query = ref("");
const plottedKeys = ref([]);
const chartEl = ref(null);
let chartInst = null;
const colorMap = new Map();

function colorOf(key) {
  if (!colorMap.has(key)) {
    colorMap.set(key, IMPORT_FREQ_COLORS[colorMap.size % IMPORT_FREQ_COLORS.length]);
  }
  return colorMap.get(key);
}

const visibleBands = computed(() => {
  const q = String(query.value || "").trim();
  if (!q) return bands.value;
  return bands.value.filter(
    (b) => String(b.freqMhz).indexOf(q) >= 0 || b.key.indexOf(q) >= 0
  );
});

const plottedSeries = computed(() => {
  const want = new Set(plottedKeys.value);
  return bands.value.filter((b) => want.has(b.key));
});

const plotMeta = computed(() => {
  const n = plottedSeries.value.reduce((s, b) => s + b.points.length, 0);
  return `图上 ${n} 点（该频全部点，未抽样）`;
});

function isPlotted(key) {
  return plottedKeys.value.indexOf(key) >= 0;
}

function onChipClick(event, key) {
  const multi = !!(event && (event.ctrlKey || event.metaKey));
  if (multi) {
    const i = plottedKeys.value.indexOf(key);
    plottedKeys.value =
      i >= 0 ? plottedKeys.value.filter((k) => k !== key) : [...plottedKeys.value, key];
  } else {
    plottedKeys.value = [key];
  }
}

async function onPickFile(e) {
  const file = e.target.files && e.target.files[0];
  e.target.value = "";
  if (!file) return;
  fileName.value = file.name;
  error.value = "";
  status.value = "正在读取并解析…";
  bands.value = [];
  plottedKeys.value = [];
  parsedRows.value = 0;
  skipped.value = 0;
  colorMap.clear();
  try {
    const text = await file.text();
    status.value = "正在按频率归组…";
    await nextTick();
    await new Promise((resolve) => setTimeout(resolve, 0));
    const parsed = parsePrcFfTimeAzimuth(text);
    parsedRows.value = parsed.rowCount;
    skipped.value = parsed.skipped;
    bands.value = parsed.bands;
    parsed.bands.forEach((b) => colorOf(b.key));
    status.value = `已解析 ${parsed.rowCount} 行，${parsed.bands.length} 个频点`;
  } catch (err) {
    error.value = err?.message || String(err);
    status.value = "";
  }
}

async function render() {
  await nextTick();
  if (!chartEl.value || !plottedSeries.value.length) {
    chartInst?.dispose();
    chartInst = null;
    return;
  }
  if (!chartInst) {
    chartInst = echarts.init(chartEl.value);
  }
  let xMin = Number.POSITIVE_INFINITY;
  let xMax = Number.NEGATIVE_INFINITY;
  const series = plottedSeries.value.map((b) => {
    const data = b.points;
    for (let i = 0; i < data.length; i++) {
      const x = data[i][0];
      if (x < xMin) xMin = x;
      if (x > xMax) xMax = x;
    }
    return {
      name: `${b.freqMhz.toFixed(3)} MHz (${b.points.length})`,
      type: "scatter",
      symbolSize: 5,
      large: true,
      largeThreshold: 2000,
      itemStyle: { color: colorOf(b.key), opacity: 0.75 },
      data
    };
  });
  if (!Number.isFinite(xMin) || !Number.isFinite(xMax)) {
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
      legend: { type: "scroll", top: 4, left: "center", textStyle: { fontSize: 11 } },
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

watch(plottedKeys, () => render(), { deep: true });

function onResize() {
  chartInst?.resize();
}

onMounted(() => window.addEventListener("resize", onResize));
onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
  chartInst?.dispose();
  chartInst = null;
});
</script>

<style scoped>
.ff-tool {
  font-family: Arial, sans-serif;
  padding: 16px;
  max-width: 1280px;
  margin: 0 auto;
}
.head h2 {
  margin: 0 0 6px;
  font-size: 1.25rem;
}
.sub {
  margin: 0;
  font-size: 13px;
  color: #6b7280;
  line-height: 1.5;
}
.sub a {
  color: #1d4ed8;
}
.card {
  margin-top: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px 14px;
  background: #fff;
}
.upload-btn {
  display: inline-block;
  background: #2563eb;
  color: #fff;
  border-radius: 6px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
}
.file-name {
  margin-left: 10px;
  font-size: 13px;
  color: #374151;
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #6b7280;
}
.hint.pad {
  padding: 0 14px;
}
.status {
  margin: 8px 0 0;
  font-size: 13px;
  color: #2563eb;
}
.error {
  margin: 8px 0 0;
  font-size: 13px;
  color: #b91c1c;
}
.plugin-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 8px;
}
.plugin-meta {
  font-size: 12px;
  color: #6b7280;
}
.plugin-row label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #4b5563;
  margin-bottom: 8px;
}
.plugin-row input {
  border: 1px solid #d1d5db;
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 13px;
  min-width: 160px;
}
.freq-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 220px;
  overflow: auto;
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
}
.cnt {
  color: #9ca3af;
  font-size: 11px;
}
.empty {
  margin: 4px 0;
  font-size: 12px;
  color: #9ca3af;
}
.card h3 {
  margin: 0 0 4px;
  font-size: 1rem;
}
.meta {
  margin: 0 0 8px;
  font-size: 12px;
  color: #6b7280;
}
.chart {
  width: 100%;
  height: 760px;
}
</style>
