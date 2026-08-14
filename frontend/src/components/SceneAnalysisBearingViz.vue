<template>
  <div class="bearing-viz">
    <div class="viz-head">
      <h4>信号分析方位轨迹 · 分频视图</h4>
    </div>

    <div v-if="!sceneTabs.length" class="viz-empty">当前筛选条件下无可绘制的分析轨迹</div>

    <template v-else>
      <p class="viz-meta">{{ metaLine }}</p>

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
          <strong>{{ currentView?.title || "—" }}</strong>
          <span v-if="currentView?.freqLabel" class="freq-badge">{{ currentView.freqLabel }}</span>
        </div>

        <div v-if="currentView?.freqGroups?.length > 1" class="freq-summary">
          <button
            type="button"
            class="freq-chip"
            :class="{ active: !selectedFreqKey }"
            @click="selectedFreqKey = ''"
          >
            全部 {{ currentView.freqGroups.length }} 频点（合图）
          </button>
          <button
            v-for="g in currentView.freqGroups"
            :key="g.freqKey"
            type="button"
            class="freq-chip"
            :class="{ active: selectedFreqKey === g.freqKey }"
            @click="selectedFreqKey = g.freqKey"
          >
            <strong>{{ g.freqKey }} MHz</strong>
            <span class="chip-meta">{{ g.targetCount }} 目标</span>
          </button>
        </div>

        <div
          v-if="!loading && displayGroups.length > 1 && !selectedFreqKey && freqLineLegend.length"
          class="encoding-row"
        >
          <span class="encoding-label">线型 = 通信频率</span>
          <span v-for="f in freqLineLegend" :key="f.freq" class="line-legend-chip">
            <i class="line-sample" :class="lineSampleClass(f.lineType)" />
            {{ f.freq }} MHz
          </span>
        </div>

        <p v-if="loadError" class="load-error">{{ loadError }}</p>
        <p v-else-if="loading" class="load-hint">正在加载方位序列…</p>
        <div
          v-show="!loading && displayGroups.length"
          class="chart-scroll"
          :style="{ maxHeight: chartScrollMaxPx + 'px' }"
        >
          <div
            ref="chartEl"
            class="chart-box"
            :style="{ height: chartHeightPx + 'px' }"
          />
        </div>
        <p v-if="!loading && currentView && !currentView.targets?.length" class="viz-empty inline">
          该场景下暂无目标方位数据
        </p>
        <p v-if="currentView?.timeRange" class="time-foot">{{ currentView.timeRange }}</p>
        <p class="hint">
          默认同一场景内全部频点绘制在一张时间-方位图（颜色区分目标、线型区分频率）；
          可点选单一频点放大查看。轮询场景按目标分色展示各轮 burst 点位或槽位轨迹。
        </p>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import {
  analysisViewCacheKey,
  forwardItemsSignature
} from "../scene/analysisViewCache.js";
import {
  buildCombinedSceneChartOption,
  buildFacetChartOption,
  combinedChartHeight,
  facetChartHeight,
  findForwardItemForRank,
  getOrLoadSceneAnalysisView,
  applyReportTargetTypes,
  reportRowsSignature
} from "../scene/analysisBearing.js";
import { formatSceneLabel } from "../scene/sceneFilters.js";

const props = defineProps({
  sceneTabs: { type: Array, default: () => [] },
  activeRank: { type: Number, default: null },
  forwardItems: { type: Array, default: () => [] },
  sceneResult: { type: Object, default: null },
  reportRows: { type: Array, default: () => [] }
});

const emit = defineEmits(["update:activeRank"]);

const chartEl = ref(null);
const selectedFreqKey = ref("");
const loading = ref(false);
const loadError = ref("");
const tabViewCache = ref(new Map());
const freqLineLegend = ref([]);
let chartInst = null;
let loadSeq = 0;
let abortCtrl = null;

function selectTab(rank) {
  emit("update:activeRank", rank);
}

const currentTab = computed(() => {
  const want = Number(props.activeRank);
  if (!Number.isFinite(want)) return null;
  return props.sceneTabs.find((t) => Number(t.rank) === want) || null;
});

function tabCacheKey(tab) {
  const forwardItem = findForwardItemForRank(props.forwardItems, tab.rank);
  return forwardItem ? analysisViewCacheKey(forwardItem) : `rank:${tab.rank}`;
}

const currentView = computed(() => {
  const tab = currentTab.value;
  if (!tab) return null;
  return tabViewCache.value.get(tabCacheKey(tab)) || null;
});

/** 方位轨迹图数据：轮询场景按分析目标分轨、跨轮连线；跨频关联读 currentView.targets。 */
const chartView = computed(() => {
  const v = currentView.value;
  if (!v) return null;
  if (v.bearingChart) {
    return { ...v, ...v.bearingChart, pollingLaneMode: v.pollingLaneMode };
  }
  return v;
});

const displayGroups = computed(() => {
  const v = chartView.value;
  if (!v?.freqGroups?.length) return [];
  if (!selectedFreqKey.value) return v.freqGroups;
  const g = v.freqGroups.find((x) => x.freqKey === selectedFreqKey.value);
  return g ? [g] : v.freqGroups;
});

const metaLine = computed(() => {
  const v = currentView.value;
  if (!v) return `${props.sceneTabs.length} 个场景（与上方场景方位轨迹 Tab 一一对应）`;
  let line = `${props.sceneTabs.length} 个场景 · ${v.freqCount} 个通信网频点 · ${v.targetCount} 个分析目标`;
  if (v.clusteringMethod === "POSITION_MATCH") {
    line += " · 编批：位置匹配（异频合批）";
  }
  return line;
});

const chartHeightPx = computed(() => {
  const v = chartView.value;
  if (!v?.targets?.length) return 480;
  if (useCombinedChart.value) {
    return combinedChartHeight(v.ySpan ?? v.targets);
  }
  const n = displayGroups.value.length;
  return n <= 1
    ? combinedChartHeight(v.ySpan ?? (displayGroups.value[0]?.targets || v.targets))
    : facetChartHeight(n);
});

const useCombinedChart = computed(() => {
  const v = chartView.value;
  if (!v?.freqGroups?.length) return false;
  return !selectedFreqKey.value;
});

const chartScrollMaxPx = computed(() => {
  const vh = typeof window !== "undefined" ? window.innerHeight : 800;
  const cap = useCombinedChart.value ? 0.88 : 0.72;
  return Math.min(chartHeightPx.value, Math.round(vh * cap));
});

const sceneByRank = computed(() => {
  const m = new Map();
  for (const s of props.sceneResult?.scenes || []) {
    m.set(s.rank, s);
  }
  return m;
});

watch(
  () => [
    forwardItemsSignature(props.forwardItems),
    props.activeRank,
    reportRowsSignature(props.reportRows),
    props.sceneTabs.map((t) => t.rank).join(",")
  ],
  () => {
    selectedFreqKey.value = "";
    tabViewCache.value = new Map();
    loadCurrentView();
  }
);

watch(selectedFreqKey, async () => {
  await nextTick();
  await renderChart();
});

async function loadCurrentView() {
  abortCtrl?.abort();
  const tab = currentTab.value;
  if (!tab) {
    disposeChart();
    return;
  }
  if (tabViewCache.value.has(tabCacheKey(tab))) {
    await renderChart();
    return;
  }

  const forwardItem = findForwardItemForRank(props.forwardItems, tab.rank);
  if (!forwardItem) {
    loadError.value = "未找到该场景的分析会话";
    return;
  }

  const seq = ++loadSeq;
  loading.value = true;
  loadError.value = "";
  abortCtrl = new AbortController();
  try {
    const baseView = await getOrLoadSceneAnalysisView(
      forwardItem,
      sceneByRank.value.get(tab.rank),
      abortCtrl.signal
    );
    const view = applyReportTargetTypes(baseView, props.reportRows, tab.rank);
    if (seq !== loadSeq) return;
    tabViewCache.value = new Map(tabViewCache.value).set(tabCacheKey(tab), view);
    loading.value = false;
    await renderChart();
  } catch (e) {
    if (e?.name === "AbortError") {
      if (seq === loadSeq) loading.value = false;
      return;
    }
    if (seq !== loadSeq) return;
    loadError.value = `加载失败：${e?.message || e}`;
    loading.value = false;
  }
}

function lineSampleClass(lineType) {
  if (lineType === "dashed") return "dashed";
  if (lineType === "dotted") return "dotted";
  return "solid";
}

async function renderChart() {
  const base = currentView.value;
  const v = chartView.value;
  const groups = displayGroups.value;
  if (!base?.targets?.length || !v?.targets?.length || !groups.length) {
    freqLineLegend.value = [];
    disposeChart();
    return;
  }
  await new Promise((r) => requestAnimationFrame(r));
  if (!chartEl.value) return;
  if (!chartInst) chartInst = echarts.init(chartEl.value);
  if (useCombinedChart.value) {
    const built = buildCombinedSceneChartOption(v, v.freqGroups);
    freqLineLegend.value = built.freqLineLegend || [];
    chartInst.setOption({ ...built.option, graphic: [] }, true);
  } else {
    freqLineLegend.value = [];
    chartInst.setOption({ ...buildFacetChartOption(v, groups), graphic: [] }, true);
  }
  chartInst.resize();
}

function disposeChart() {
  chartInst?.dispose();
  chartInst = null;
}

function onResize() {
  chartInst?.resize();
}

onMounted(() => {
  loadCurrentView();
  window.addEventListener("resize", onResize);
});

onBeforeUnmount(() => {
  abortCtrl?.abort();
  window.removeEventListener("resize", onResize);
  disposeChart();
});
</script>

<style scoped>
.bearing-viz { margin-top: 14px; padding-top: 14px; border-top: 1px solid #e5e7eb; }
.viz-head h4 { margin: 0 0 6px; font-size: 14px; }
.viz-meta { font-size: 12px; color: #6b7280; margin: 0 0 8px; }
.scene-tabs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.tab {
  border: 1px solid #d1d5db;
  background: #fff;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
}
.tab.active { border-color: #7c3aed; color: #6d28d9; font-weight: 600; background: #f5f3ff; }
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
.freq-badge { color: #1d4ed8; font-weight: 600; }
.freq-summary { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.freq-chip {
  padding: 4px 10px;
  border-radius: 6px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
.freq-chip.active {
  background: #dbeafe;
  border-color: #2563eb;
  box-shadow: inset 0 0 0 1px #2563eb;
}
.chip-meta { color: #6b7280; font-size: 11px; margin-left: 4px; }
.encoding-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  margin-bottom: 8px;
  font-size: 11px;
}
.encoding-label { color: #6b7280; font-weight: 600; }
.line-legend-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #f9fafb;
  font-size: 11px;
}
.line-sample { width: 22px; border-top: 2px solid #374151; }
.line-sample.dashed { border-top-style: dashed; }
.line-sample.dotted { border-top-style: dotted; }
.chart-scroll {
  overflow-y: auto;
  overflow-x: hidden;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fafafa;
}
.chart-box { width: 100%; min-height: 320px; }
.time-foot { text-align: right; font-size: 11px; color: #6b7280; }
.hint { font-size: 11px; color: #6b7280; margin: 8px 0 0; }
.load-hint, .load-error { font-size: 12px; }
.load-error { color: #b91c1c; }
.viz-empty {
  padding: 24px;
  text-align: center;
  color: #9ca3af;
  background: #f9fafb;
  border-radius: 8px;
}
.viz-empty.inline { padding: 16px; }
</style>
