<template>
  <div class="bearing-viz">
    <div class="viz-head">
      <h4>场景方位轨迹</h4>
      <span class="viz-meta">{{ sceneTabs.length }} 个场景 · 检测 {{ summary.detections }} · 轨迹 {{ summary.tracks }}</span>
    </div>
    <div v-if="!sceneTabs.length" class="viz-empty">当前筛选条件下无场景轨迹可显示</div>
    <template v-else>
      <div class="scene-tabs">
        <button
          v-for="tab in sceneTabs"
          :key="tab.rank"
          type="button"
          class="tab"
          :class="{ active: tab.rank === activeRank }"
          @click="selectTab(tab.rank)"
        >
          {{ formatSceneLabel(tab.rank, tab.sceneType, tab) }}
        </button>
      </div>
      <div class="panel">
        <div class="panel-title">
          <strong>{{ current?.title || panelTitleFallback }}</strong>
          <span v-if="currentFreqLabel" class="freq-badge">{{ currentFreqLabel }}</span>
          <span>{{ panelMeta }}</span>
        </div>
        <div v-if="!current" class="viz-empty inline">该场景暂无预筛轨迹数据</div>
        <div v-else ref="chartEl" class="chart-box" />
        <p v-if="current?.dateLabel" class="date-foot">{{ current.dateLabel }}</p>
        <p v-if="current?.note" class="scene-note">{{ current.note }}</p>
        <p class="hint">{{ chartHint }}</p>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import { formatPeriodSecMsUs } from "../scene/sceneFormat.js";
import { formatSceneFreq, formatSceneLabel } from "../scene/sceneFilters.js";

const props = defineProps({
  sceneTabs: { type: Array, default: () => [] },
  trajectoryViews: { type: Array, default: () => [] },
  activeRank: { type: Number, default: null },
  summary: {
    type: Object,
    default: () => ({ detections: 0, tracks: 0 })
  }
});

const emit = defineEmits(["update:activeRank"]);

const chartEl = ref(null);
let chartInst = null;

const viewByRank = computed(() => {
  const map = new Map();
  for (const v of props.trajectoryViews || []) {
    if (v?.sceneRank == null) continue;
    const rank = Number(v.sceneRank);
    if (!Number.isFinite(rank)) continue;
    const tab = props.sceneTabs.find((t) => Number(t.rank) === rank);
    map.set(
      rank,
      tab
        ? {
            ...v,
            sceneRank: rank,
            sceneType: tab.sceneType,
            freqCenterMhz: v.freqCenterMhz ?? tab.freqCenterMhz,
            freqMinMhz: v.freqMinMhz ?? tab.freqMinMhz,
            freqMaxMhz: v.freqMaxMhz ?? tab.freqMaxMhz
          }
        : { ...v, sceneRank: rank }
    );
  }
  return map;
});

const current = computed(() => {
  const want = Number(props.activeRank);
  if (!Number.isFinite(want)) return null;
  return viewByRank.value.get(want) || null;
});

const currentTab = computed(() => {
  const want = Number(props.activeRank);
  if (!Number.isFinite(want)) return null;
  return props.sceneTabs.find((t) => Number(t.rank) === want) || null;
});

const currentFreqLabel = computed(() => {
  const v = current.value;
  const fromView = formatSceneFreq(v);
  if (fromView) return fromView;
  return formatSceneFreq(currentTab.value);
});

const panelTitleFallback = computed(() => {
  const tab = currentTab.value;
  if (!tab) return "—";
  const freq = formatSceneFreq(tab);
  return freq ? `场景 #${tab.rank} · ${freq}` : `场景 #${tab.rank}`;
});

function selectTab(rank) {
  emit("update:activeRank", rank);
}

const panelMeta = computed(() => {
  const v = current.value;
  if (!v) return currentTab.value ? "预筛轨迹数据缺失" : "";
  if (v.viewMode === "polling") {
    const total = v.scatterTotal ?? v.displayedTracks;
    const down = v.scatterDownsampled ? `（抽样显示 ${v.displayedTracks}/${total}）` : "";
    const targetNote = v.pollingParticipantsOnly
      ? ` · ${v.pollingTargetCount ?? "?"} 个轮询目标`
      : "";
    const windowNote =
      v.pollingParticipantsOnly && v.windowPointTotal > total
        ? `（窗内 ${v.windowPointTotal} 点已过滤）`
        : "";
    return `${v.timeRange} · 轮询点 ${total}${down}${targetNote}${windowNote} · 轮次 ${v.trackCount}`;
  }
  return `${v.timeRange} · 显示 ${v.displayedTracks} / ${v.trackCount} 条轨迹`;
});

const chartHint = computed(() => {
  const v = current.value;
  if (!v) return "与下方「信号分析方位轨迹」共用场景 Tab；此处为预筛散点/轨迹。";
  if (v.viewMode === "polling") {
    return v.pollingParticipantsOnly
      ? "仅显示已识别轮询目标的 burst 点位（按目标分色）。"
      : "散点为场景窗内原始检测方位。";
  }
  return "按时间帧聚合目标方位（xhfw）；有信号分析结果时图例显示「目标N-类型」。";
});

watch(
  () => [props.activeRank, props.trajectoryViews, props.sceneTabs],
  () => {
    renderChart();
  },
  { deep: true }
);

function renderChart() {
  if (!chartEl.value || !current.value) {
    chartInst?.dispose();
    chartInst = null;
    return;
  }
  if (!chartInst) {
    chartInst = echarts.init(chartEl.value);
  }
  const v = current.value;
  const option =
    v.viewMode === "polling" ? buildPollingOption(v) : buildTrackOption(v);
  chartInst.setOption(option, true);
}

function buildTrackOption(v) {
  const series = (v.tracks || []).map((t) => ({
    name: `${t.label} (#${t.trackId})`,
    type: "line",
    showSymbol: true,
    symbolSize: 6,
    lineStyle: { width: 1.5, color: t.color },
    itemStyle: { color: t.color },
    data: (t.points || []).map((p) => [p.x, p.y])
  }));
  return {
    tooltip: {
      trigger: "axis",
      formatter(params) {
        const p = params?.[0];
        if (!p) return "";
        return `${formatClock(p.value[0])}<br/>${p.seriesName}: ${Number(p.value[1]).toFixed(1)}°`;
      }
    },
    legend: { top: 4, right: 8, type: "scroll" },
    grid: { left: 56, right: 16, top: 40, bottom: 48 },
    xAxis: {
      type: "value",
      min: v.xMin,
      max: v.xMax,
      name: "时间",
      axisLabel: { formatter: (val) => formatClock(val) }
    },
    yAxis: {
      type: "value",
      min: v.yMin,
      max: v.yMax,
      name: "方位 (°)"
    },
    series
  };
}

function buildPollingOption(v) {
  const ann = v.chartAnnotations || {};
  const graphics = [];
  const periodLabel = ann.periodLabel || formatPeriodSecMsUs(ann.periodSec);
  if (periodLabel && periodLabel !== "—") {
    graphics.push({
      type: "text",
      left: "center",
      bottom: 56,
      style: { text: `轮询周期 ${periodLabel}`, fill: "#0072BD", fontSize: 12 }
    });
  }

  const targets = v.pollingTargets?.length ? v.pollingTargets : null;
  const series = targets
    ? targets.map((t) => ({
        name: `${t.label} (#${t.trackId})`,
        type: "scatter",
        symbolSize: 6,
        itemStyle: { color: t.color },
        data: (t.points || []).map((p) => [p.x, p.y])
      }))
    : [
        {
          name: "检测点",
          type: "scatter",
          symbolSize: 5,
          itemStyle: { color: "rgba(26,58,92,0.65)" },
          data: (v.scatterPoints || []).map((p) => [p.x, p.y])
        }
      ];

  return {
    tooltip: {
      trigger: "item",
      formatter(p) {
        return `${formatClock(p.value[0])}<br/>${p.seriesName}: ${Number(p.value[1]).toFixed(1)}°`;
      }
    },
    legend: { top: 4, right: 8, type: "scroll" },
    grid: { left: 56, right: 16, top: 40, bottom: ann.periodSec ? 72 : 48 },
    xAxis: {
      type: "value",
      min: v.xMin,
      max: v.xMax,
      name: "时间",
      axisLabel: { formatter: (val) => formatClock(val) }
    },
    yAxis: {
      type: "value",
      min: v.yMin,
      max: v.yMax,
      name: "方位 (°)"
    },
    graphic: graphics,
    series
  };
}

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
}

function onResize() {
  chartInst?.resize();
}

onMounted(() => {
  renderChart();
  window.addEventListener("resize", onResize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
  chartInst?.dispose();
  chartInst = null;
});
</script>

<style scoped>
.bearing-viz { margin-top: 0; }
.viz-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.viz-head h4 { margin: 0; font-size: 14px; }
.viz-meta { font-size: 12px; color: #6b7280; }
.scene-tabs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.tab {
  border: 1px solid #d1d5db;
  background: #fff;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}
.tab.active { border-color: #2563eb; color: #1d4ed8; font-weight: 600; background: #eff6ff; }
.panel {
  background: #fff;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 10px 12px 12px;
}
.panel-title {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 12px;
}
.panel-title strong { font-size: 13px; color: #111827; }
.freq-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  background: #eff6ff;
  color: #1d4ed8;
  font-size: 12px;
  font-weight: 600;
}
.chart-box { width: 100%; height: 360px; }
.date-foot { text-align: right; font-size: 11px; color: #6b7280; margin: 4px 0 0; }
.scene-note { font-size: 11px; color: #4b5563; margin: 4px 0 0; }
.hint { font-size: 11px; color: #6b7280; margin: 8px 0 0; }
.viz-empty {
  padding: 24px;
  text-align: center;
  color: #9ca3af;
  background: #f9fafb;
  border-radius: 8px;
}
.viz-empty.inline { padding: 16px; margin-bottom: 8px; }
</style>
