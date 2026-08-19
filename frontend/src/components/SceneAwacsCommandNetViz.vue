<template>
  <div v-if="panels.length" class="awacs-cn-viz">
    <div class="viz-head">
      <h4>预警机指挥网</h4>
      <span class="viz-meta">
        {{ panels.length }} 个占用频点 · 同频同时段建轨（指挥网二次）
      </span>
    </div>
    <div class="seed-tabs">
      <button
        v-for="p in panels"
        :key="panelKey(p)"
        type="button"
        class="tab"
        :class="{ active: panelKey(p) === activePanelKey }"
        @click="activePanelKey = panelKey(p)"
      >
        {{ p.label || panelKey(p) }}
      </button>
    </div>
    <div v-if="!activePanel" class="viz-empty">请选择预警机</div>
    <template v-else>
      <div class="panel">
        <div class="panel-title">
          <strong>{{ activePanel.label }}</strong>
          <span v-if="activePanel.freqMhz" class="freq-badge">{{ formatFreq(activePanel.freqMhz) }} MHz</span>
          <span>{{ trackCount }} 条轨迹 · {{ rowCount }} 个目标</span>
        </div>
        <div v-if="!trackCount" class="viz-empty inline">该预警机占用窗内暂无建轨结果</div>
        <div v-else ref="chartEl" class="chart-box" />
        <p class="hint">
          仅显示与所选预警机在相同时间、相同频率占用窗内的建轨与类型；其他场景/换频模块不受影响。
        </p>
      </div>
      <div v-if="tableRows.length" class="table-wrap">
        <h5>同频同时段目标</h5>
        <table class="mini-table">
          <thead>
            <tr>
              <th>目标</th>
              <th>类型</th>
              <th>频率</th>
              <th>波道</th>
              <th>角色</th>
              <th>侦获次数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in tableRows" :key="`${r.targetId}-${i}`">
              <td>{{ r.targetId || "—" }}</td>
              <td>{{ r.targetTypeLabel || targetTypeLabel(r.targetType) }}</td>
              <td class="num">{{ formatFreq(r.networkFreqMhz) }}</td>
              <td>{{ r.commLinkChannelLabel || r.commLinkChannel || "—" }}</td>
              <td>{{ r.role || "—" }}</td>
              <td class="num">{{ r.detectCount ?? "—" }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import { applyStreamTargetTypeLabels } from "../scene/sceneFilters.js";
import { formatFreq, targetTypeLabel } from "../scene/sceneFilters.js";

const props = defineProps({
  commandNetPass: { type: Object, default: null }
});

const chartEl = ref(null);
let chartInst = null;
const activePanelKey = ref("");

const panels = computed(() => {
  const pass = props.commandNetPass;
  if (!pass || pass.skipped) return [];
  return pass.awacsPanels || [];
});

const activePanel = computed(() => {
  const list = panels.value;
  if (!list.length) return null;
  return list.find((p) => panelKey(p) === activePanelKey.value) || list[0];
});

function panelKey(p) {
  if (!p) return "";
  if (p.panelId) return p.panelId;
  return `${p.seedTargetId || ""}@${formatFreq(p.freqMhz)}`;
}

const labeledView = computed(() => {
  const panel = activePanel.value;
  if (!panel?.trajectoryView) return null;
  const labels = (panel.reportRows || []).map((r) => ({
    sceneRank: r.sceneRank,
    targetType: r.targetType,
    targetTypeLabel: r.targetTypeLabel || targetTypeLabel(r.targetType),
    freqMhz: r.networkFreqMhz,
    meanAzimuthDeg: r.meanAzimuthDeg ?? null,
    channel: r.commLinkChannel,
    channelLabel: r.commLinkChannelLabel,
    targetChannelsUsed: r.targetChannelsUsed
  }));
  return applyStreamTargetTypeLabels({ ...panel.trajectoryView, unified: true }, labels);
});

const trackCount = computed(() => (labeledView.value?.tracks || []).length);
const rowCount = computed(() => (activePanel.value?.reportRows || []).length);
const tableRows = computed(() => activePanel.value?.reportRows || []);

watch(
  () => [activePanel.value && panelKey(activePanel.value), trackCount.value, rowCount.value],
  () => {
    const p = activePanel.value;
    // #region agent log
    fetch("http://127.0.0.1:7901/ingest/e16fb981-fe8c-4a2f-8b90-e593d79414a3", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Debug-Session-Id": "0cb39e" },
      body: JSON.stringify({
        sessionId: "0cb39e",
        runId: "post-fix",
        hypothesisId: "F",
        location: "SceneAwacsCommandNetViz.vue:counts",
        message: "chart vs table counts",
        data: {
          panelId: p && (p.panelId || p.seedTargetId),
          freqMhz: p && p.freqMhz,
          nPanels: panels.value.length,
          trackCount: trackCount.value,
          rowCount: rowCount.value,
          rowFreqs: (p && p.reportRows ? p.reportRows : []).map((r) => r.networkFreqMhz)
        },
        timestamp: Date.now()
      })
    }).catch(() => {});
    // #endregion
  }
);

watch(
  panels,
  (list) => {
    if (!list.length) {
      activePanelKey.value = "";
      return;
    }
    if (!list.some((p) => panelKey(p) === activePanelKey.value)) {
      activePanelKey.value = panelKey(list[0]);
    }
  },
  { immediate: true }
);

watch(
  () => [activePanelKey.value, labeledView.value],
  async () => {
    await nextTick();
    renderChart();
  }
);

function buildOption(v) {
  const tracks = v?.tracks || [];
  const series = tracks.map((t) => ({
    name: legendName(t),
    type: "line",
    showSymbol: true,
    symbolSize: 5,
    lineStyle: { width: 1.5, color: t.color },
    itemStyle: { color: t.color },
    data: (t.points || []).map((p) => [p.x, p.y])
  }));
  return {
    tooltip: {
      trigger: "item",
      formatter(p) {
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

function legendName(t) {
  const id = t.trackId != null ? ` (#${t.trackId})` : "";
  const base = t.label || "目标";
  const type = t.targetTypeLabel;
  if (type && type !== "—" && !String(base).includes(type)) {
    return `${base}-${type}${id}`;
  }
  return `${base}${id}`;
}

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
}

function renderChart() {
  const v = labeledView.value;
  if (!chartEl.value || !v || !(v.tracks || []).length) {
    chartInst?.clear();
    return;
  }
  if (!chartInst) {
    chartInst = echarts.init(chartEl.value);
  }
  chartInst.setOption(buildOption(v), true);
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
.awacs-cn-viz {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #e5e7eb;
}
.viz-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.viz-head h4 {
  margin: 0;
  font-size: 14px;
}
.viz-meta {
  font-size: 12px;
  color: #6b7280;
}
.seed-tabs {
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
  background: #eff6ff;
  color: #1d4ed8;
}
.panel {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fafafa;
}
.panel-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  font-size: 12px;
  color: #4b5563;
  margin-bottom: 6px;
}
.panel-title strong {
  color: #111827;
  font-size: 13px;
}
.freq-badge {
  background: #e0e7ff;
  color: #3730a3;
  padding: 1px 6px;
  border-radius: 4px;
}
.chart-box {
  width: 100%;
  height: 320px;
  background: #fff;
  border-radius: 6px;
}
.viz-empty {
  font-size: 13px;
  color: #9ca3af;
  padding: 12px 0;
}
.viz-empty.inline {
  padding: 24px 0;
  text-align: center;
}
.hint {
  margin: 8px 0 0;
  font-size: 11px;
  color: #9ca3af;
}
.table-wrap {
  margin-top: 10px;
}
.table-wrap h5 {
  margin: 0 0 6px;
  font-size: 13px;
}
.mini-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  background: #fff;
}
.mini-table th,
.mini-table td {
  border: 1px solid #e5e7eb;
  padding: 4px 8px;
  text-align: left;
}
.mini-table th {
  background: #f3f4f6;
}
.num {
  font-variant-numeric: tabular-nums;
}
</style>
