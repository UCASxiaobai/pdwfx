<template>
  <div class="bearing-viz">
    <div class="viz-head">
      <h4>场景方位轨迹</h4>
      <span class="viz-meta">{{ views.length }} 个场景 · 检测 {{ summary.detections }} · 轨迹 {{ summary.tracks }}</span>
    </div>
    <div v-if="!views.length" class="viz-empty">当前筛选条件下无场景轨迹可显示</div>
    <template v-else>
      <div class="scene-tabs">
        <button
          v-for="(v, idx) in views"
          :key="v.sceneRank"
          type="button"
          class="tab"
          :class="{ active: idx === activeIdx }"
          @click="activeIdx = idx"
        >
          {{ tabLabel(v) }}
        </button>
      </div>
      <div class="panel">
        <div class="panel-title">
          <strong>{{ current?.title || "—" }}</strong>
          <span>{{ panelMeta }}</span>
        </div>
        <div ref="chartEl" class="chart-box" />
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

const props = defineProps({
  views: { type: Array, default: () => [] },
  summary: {
    type: Object,
    default: () => ({ detections: 0, tracks: 0 })
  }
});

const chartEl = ref(null);
const activeIdx = ref(0);
let chartInst = null;

const current = computed(() => props.views[activeIdx.value] || null);

const panelMeta = computed(() => {
  const v = current.value;
  if (!v) return "";
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
  if (!v) return "";
  if (v.viewMode === "polling") {
    return v.pollingParticipantsOnly
      ? "仅显示已识别轮询目标的 burst 点位（按目标分色）。"
      : "散点为场景窗内原始检测方位。";
  }
  return "按时间帧聚合目标方位（xhfw）：目标1~N 为各代表轨迹。";
});

watch(
  () => props.views,
  (list) => {
    if (activeIdx.value >= list.length) activeIdx.value = 0;
    renderChart();
  },
  { deep: true }
);

watch(activeIdx, () => renderChart());

function tabLabel(v) {
  const tag = v.viewMode === "polling" ? "轮询" : "轨迹";
  return `#${v.sceneRank} ${tag}`;
}

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
}

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
    legend: { top: 4, right: 8, type: targets ? "scroll" : "plain" },
    grid: { left: 56, right: 16, top: 40, bottom: 56 },
    graphic: graphics,
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
.bearing-viz {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #e5e7eb;
}
.viz-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 8px;
}
.viz-head h4 { margin: 0; font-size: 14px; color: #111827; }
.viz-meta { font-size: 12px; color: #6b7280; }
.scene-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.tab {
  border: 1px solid #d1d5db;
  background: #fff;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}
.tab.active {
  border-color: #2563eb;
  color: #1d4ed8;
  font-weight: 600;
  background: #eff6ff;
}
.panel {
  background: #fff;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 10px 12px 12px;
}
.panel-title {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: #4b5563;
  margin-bottom: 6px;
}
.panel-title strong { color: #111827; font-size: 13px; }
.chart-box { height: 420px; width: 100%; }
.date-foot { text-align: right; font-size: 11px; color: #6b7280; margin: 4px 0 0; }
.scene-note {
  font-size: 12px;
  color: #374151;
  background: #fffde7;
  border: 1px solid #e6d98a;
  padding: 6px 8px;
  border-radius: 4px;
  margin: 8px 0 0;
  line-height: 1.45;
}
.hint { font-size: 11px; color: #6b7280; margin: 8px 0 0; }
.viz-empty {
  padding: 24px;
  text-align: center;
  color: #9ca3af;
  font-size: 13px;
  background: #f9fafb;
  border-radius: 8px;
}
</style>
