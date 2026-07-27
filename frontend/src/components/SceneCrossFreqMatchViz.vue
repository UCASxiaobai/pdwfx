<template>
  <div class="cross-freq-viz">
    <div class="viz-head">
      <h4>跨频方位关联 · 时间-方位图</h4>
      <span class="viz-meta">分析后推断 · 时间重合场景合并显示</span>
    </div>

    <div class="view-mode">
      <span class="mode-label">显示模式</span>
      <button
        type="button"
        class="mode-btn"
        :class="{ active: viewMode === 'fused' }"
        @click="viewMode = 'fused'"
      >
        合成轨迹
      </button>
      <button
        type="button"
        class="mode-btn"
        :class="{ active: viewMode === 'tracks' }"
        @click="viewMode = 'tracks'"
      >
        分轨视图
      </button>
    </div>

    <details class="match-params" open>
      <summary>匹配参数</summary>
      <div class="param-grid">
        <label>
          方位门限 (°)
          <input v-model.number="matchOpts.gateDeg" type="number" step="0.5" min="1" max="30" />
        </label>
        <label>
          时间对齐 (ms)
          <input v-model.number="matchOpts.timeToleranceMs" type="number" step="500" min="500" />
        </label>
        <label>
          最少匹配点
          <input v-model.number="matchOpts.minMatchPoints" type="number" step="1" min="2" max="20" />
        </label>
        <label>
          最短重合 (ms)
          <input v-model.number="matchOpts.minOverlapMs" type="number" step="500" min="0" />
        </label>
        <label>
          高亮轨迹数
          <input v-model.number="matchOpts.topColoredTracks" type="number" step="1" min="1" max="50" />
        </label>
        <label>
          矩阵频点数
          <input v-model.number="matchOpts.matrixTopFreqs" type="number" step="1" min="1" max="30" />
        </label>
        <label class="check">
          <input v-model="matchOpts.showOthers" type="checkbox" />
          显示其他轨迹（灰色）
        </label>
        <label class="check">
          <input v-model="matchOpts.requireMotionConsistent" type="checkbox" />
          要求运动趋势一致
        </label>
        <label class="check">
          <input v-model="matchOpts.requireSameTargetType" type="checkbox" />
          要求相同目标类型
        </label>
      </div>
    </details>

    <p v-if="!panelItems.length && !loading" class="viz-empty">当前筛选条件下无可关联的场景</p>
    <p v-if="loadError" class="load-error">{{ loadError }}</p>
    <p v-else-if="loading" class="load-hint">
      正在加载轨迹并计算跨频/跨场景关联…
      <span v-if="loadProgress">{{ loadProgress }}</span>
    </p>

    <template v-else>
      <p v-if="panelItems.length" class="summary-line">
        {{ panelItems.length }} 张图（时间重合的场景已合并）·
        <template v-if="viewMode === 'fused'">
          高亮关联组合成 · 线段颜色表示该时段通信频率
        </template>
        <template v-else>
          高亮轨迹按频率着色 · 其余可选灰色显示
        </template>
      </p>

      <section
        v-for="(view, idx) in panelItems"
        :key="panelKey(view, idx)"
        class="chart-panel"
      >
        <h5 class="panel-title">{{ view.title }}</h5>
        <p class="panel-sub">{{ view.subtitle }}</p>
        <p v-if="view.timeRange" class="time-badge">{{ view.timeRange }}</p>

        <div v-if="viewMode === 'fused' && panelFusedTargets(view).length" class="encoding-row">
          <span class="encoding-label">图例 = 合成轨迹</span>
          <span v-for="f in panelFusedTargets(view)" :key="f.id" class="match-chip">
            {{ f.shortLabel }}
          </span>
        </div>
        <div v-if="viewMode === 'fused' && panelFreqColorLegend(view).length" class="encoding-row">
          <span class="encoding-label">颜色 = 通信频率（时段）</span>
          <span v-for="f in panelFreqColorLegend(view)" :key="f.freq" class="freq-chip">
            <i class="swatch" :style="{ background: f.color }" />
            {{ f.freq }} MHz
          </span>
        </div>
        <div v-if="viewMode === 'tracks' && panelHighlightedFreqLegend(view).length" class="encoding-row">
          <span class="encoding-label">颜色 = 通信频率（高亮轨迹）</span>
          <span v-for="f in panelHighlightedFreqLegend(view)" :key="f.freq" class="freq-chip">
            <i class="swatch" :style="{ background: f.color }" />
            {{ f.freq }} MHz
          </span>
        </div>
        <div v-if="viewMode === 'tracks' && panelMatchClusters(view).length" class="encoding-row">
          <span class="encoding-label">关联组</span>
          <span v-for="c in panelMatchClusters(view)" :key="c.id" class="match-chip">
            {{ c.shortLabel || c.label }}
          </span>
        </div>

        <div v-if="viewMode === 'tracks' && panelFreqLineLegend(view).length && matchOpts.showOthers" class="encoding-row">
          <span class="encoding-label">线型 = 通信频率（其他轨迹）</span>
          <span v-for="f in panelFreqLineLegend(view)" :key="f.freq" class="freq-chip">
            <i class="line-sample" :class="lineSampleClass(f.lineType)" />
            {{ f.freq }} MHz
          </span>
        </div>

        <div
          v-if="chartHasData(view)"
          :ref="(el) => setChartEl(idx, el)"
          class="chart-box"
          :style="{ height: chartHeightFor(view) + 'px' }"
        />
        <p v-else class="viz-empty inline">{{ view.emptyReason || "无可绘制轨迹" }}</p>

        <div
          v-if="panelTargetChannelMatrix(view).rows.length && panelTargetChannelMatrix(view).columns.length"
          class="channel-matrix-block"
        >
          <h6 class="matrix-title">目标 ↔ 频率占用</h6>
          <p class="matrix-desc">
            纵列为明细表同款「网络频率」（MHz），按该频点上关联目标数从多到少取前
            {{ matchOpts.matrixTopFreqs }} 个；列标题括号内为目标个数。
            统计本图全部分轨（含灰色非高亮），横行为跨频关联后的物理目标。
          </p>
          <div class="matrix-scroll">
            <table class="channel-matrix">
              <thead>
                <tr>
                  <th class="matrix-corner">目标 \\ 频率</th>
                  <th
                    v-for="col in panelTargetChannelMatrix(view).columns"
                    :key="col.id"
                    class="matrix-col-head"
                  >
                    {{ col.label }}
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in panelTargetChannelMatrix(view).rows"
                  :key="row.key"
                >
                  <th class="matrix-row-head">{{ row.label }}</th>
                  <td
                    v-for="(checked, ci) in row.cells"
                    :key="ci"
                    class="matrix-cell"
                    :class="{ occupied: checked }"
                  >
                    {{ checked ? "✓" : "" }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </template>

    <p class="hint">
      分析仍按优质场景独立进行。时间窗有交集的场景会合并显示。
      跨频匹配要求重合时段内方位一致；默认还要求信号分析已判为相同平台类型（地面站/预警机/飞机），
      避免预警机与战斗机等同频混叠误并。匹配后按采样点数取前 N 条已匹配轨迹高亮，颜色区分通信频率；
      其余轨迹可选灰色显示。「合成轨迹」将含高亮成员的关联组按时间拼接；「分轨视图」逐条展示原始方位线。
    </p>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import * as echarts from "echarts";
import {
  allowedRanksSignature,
  forwardItemsSignature
} from "../scene/analysisViewCache.js";
import { loadAllSceneAnalysisViews } from "../scene/analysisBearing.js";
import { DEFAULT_MATCH_OPTIONS } from "../scene/bearingMatch.js";
import {
  buildCrossFreqPanelsFromViews,
  buildFusedChartOption,
  buildMatchChartOption,
  crossFreqChartHeight,
  panelFreqColorLegend,
  panelFreqLineLegend,
  panelFusedTargets,
  panelHighlightedFreqLegend,
  panelMatchClusters
} from "../scene/crossFreqMatchView.js";

function panelTargetChannelMatrix(view) {
  return view?.targetChannelMatrix || { columns: [], rows: [] };
}

const viewMode = ref("fused");

const props = defineProps({
  forwardItems: { type: Array, default: () => [] },
  sceneResult: { type: Object, default: null },
  allowedRanks: { type: Object, default: null },
  freqTolerance: { type: Number, default: 0.01 }
});

const loading = ref(false);
const loadProgress = ref("");
const loadError = ref("");
const panelItems = ref([]);
const chartEls = ref([]);
const chartInsts = [];
const sceneViewsCache = ref([]);
const matchOpts = reactive({ ...DEFAULT_MATCH_OPTIONS });
let loadSeq = 0;
let abortCtrl = null;
let paramTimer = null;

function sceneByRankMap() {
  const m = new Map();
  for (const s of props.sceneResult?.scenes || []) {
    m.set(s.rank, s);
  }
  return m;
}

function matchOptsKey() {
  return JSON.stringify({
    gateDeg: matchOpts.gateDeg,
    timeToleranceMs: matchOpts.timeToleranceMs,
    minMatchPoints: matchOpts.minMatchPoints,
    minOverlapMs: matchOpts.minOverlapMs,
    showOthers: matchOpts.showOthers,
    topColoredTracks: matchOpts.topColoredTracks,
    matrixTopFreqs: matchOpts.matrixTopFreqs,
    requireMotionConsistent: matchOpts.requireMotionConsistent,
    requireSameTargetType: matchOpts.requireSameTargetType
  });
}

function panelKey(view, idx) {
  return `${(view.memberRanks || []).join("-")}-${idx}-${viewMode.value}-${matchOptsKey()}`;
}

function chartHasData(view) {
  if (view.empty) return false;
  if (viewMode.value === "fused") return (view.fusedTargets || []).length > 0;
  return (view.targets || []).length > 0;
}

function chartHeightFor(view) {
  return crossFreqChartHeight(view);
}

watch(viewMode, () => renderCharts());

function setChartEl(idx, el) {
  chartEls.value[idx] = el;
}

function lineSampleClass(lineType) {
  if (lineType === "dashed") return "dashed";
  if (lineType === "dotted") return "dotted";
  return "solid";
}

watch(
  () => [
    forwardItemsSignature(props.forwardItems),
    allowedRanksSignature(props.allowedRanks)
  ],
  () => loadSceneData()
);

watch(
  matchOpts,
  () => {
    clearTimeout(paramTimer);
    paramTimer = setTimeout(() => applyMatchOnly(), 200);
  },
  { deep: true }
);

function applyMatchOnly() {
  if (!sceneViewsCache.value.length) return;
  panelItems.value = buildCrossFreqPanelsFromViews(sceneViewsCache.value, { ...matchOpts });
  renderCharts();
}

async function loadSceneData() {
  abortCtrl?.abort();
  const items = props.forwardItems || [];
  if (!items.length) {
    sceneViewsCache.value = [];
    panelItems.value = [];
    disposeCharts();
    loading.value = false;
    return;
  }

  const seq = ++loadSeq;
  loading.value = true;
  loadProgress.value = "";
  loadError.value = "";
  abortCtrl = new AbortController();
  try {
    const views = await loadAllSceneAnalysisViews(
      items,
      sceneByRankMap(),
      abortCtrl.signal,
      props.freqTolerance,
      props.allowedRanks,
      (cur, total, rank) => {
        loadProgress.value = `（场景 #${rank}，${cur}/${total}）`;
      }
    );
    if (seq !== loadSeq) return;
    sceneViewsCache.value = views;
    panelItems.value = buildCrossFreqPanelsFromViews(views, { ...matchOpts });
    loading.value = false;
    loadProgress.value = "";
    await renderCharts();
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

async function renderCharts() {
  await nextTick();
  disposeCharts();
  panelItems.value.forEach((view, idx) => {
    const el = chartEls.value[idx];
    if (!el || !chartHasData(view)) return;
    const inst = echarts.init(el);
    chartInsts[idx] = inst;
    const option =
      viewMode.value === "fused"
        ? buildFusedChartOption(view)
        : buildMatchChartOption(view);
    inst.setOption(option, true);
  });
}

function disposeCharts() {
  for (const inst of chartInsts) {
    inst?.dispose();
  }
  chartInsts.length = 0;
}

function onResize() {
  for (const inst of chartInsts) {
    inst?.resize();
  }
}

onMounted(() => {
  loadSceneData();
  window.addEventListener("resize", onResize);
});

onBeforeUnmount(() => {
  clearTimeout(paramTimer);
  abortCtrl?.abort();
  window.removeEventListener("resize", onResize);
  disposeCharts();
});
</script>

<style scoped>
.cross-freq-viz {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #e5e7eb;
}
.viz-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.viz-head h4 { margin: 0; font-size: 14px; }
.viz-meta { font-size: 11px; color: #6b7280; }
.view-mode {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
}
.mode-label { color: #6b7280; font-weight: 600; }
.mode-btn {
  border: 1px solid #d1d5db;
  background: #fff;
  padding: 4px 10px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
.mode-btn.active {
  border-color: #7c3aed;
  color: #6d28d9;
  font-weight: 600;
  background: #f5f3ff;
}
.summary-line { font-size: 12px; color: #4b5563; margin: 0 0 10px; }
.match-params {
  margin-bottom: 10px;
  font-size: 12px;
  color: #374151;
}
.match-params summary { cursor: pointer; font-weight: 600; margin-bottom: 6px; }
.param-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
  margin-top: 6px;
}
.param-grid label { display: flex; flex-direction: column; gap: 3px; font-size: 11px; }
.param-grid input[type="number"] {
  padding: 4px 6px;
  border: 1px solid #d1d5db;
  border-radius: 4px;
}
.check { flex-direction: row !important; align-items: center; gap: 6px !important; }
.chart-panel {
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 10px 12px 12px;
  margin-bottom: 14px;
  background: #fff;
}
.panel-title { margin: 0 0 4px; font-size: 14px; font-weight: 600; color: #111827; }
.panel-sub { margin: 0 0 4px; font-size: 12px; color: #4b5563; }
.time-badge {
  display: inline-block;
  margin: 0 0 8px;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 600;
  color: #1d4ed8;
  background: #eff6ff;
  border-radius: 4px;
}
.encoding-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  margin-bottom: 6px;
  font-size: 11px;
}
.encoding-label { color: #6b7280; font-weight: 600; }
.match-chip, .freq-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #f9fafb;
}
.swatch { width: 10px; height: 10px; border-radius: 2px; }
.line-sample { width: 22px; border-top: 2px solid #374151; }
.line-sample.dashed { border-top-style: dashed; }
.line-sample.dotted { border-top-style: dotted; }
.chart-box { min-height: 480px; width: 100%; }
.channel-matrix-block {
  margin: 12px 0 0;
  padding: 10px 12px;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
.matrix-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}
.matrix-desc {
  margin: 0 0 8px;
  font-size: 11px;
  color: #6b7280;
  line-height: 1.45;
}
.matrix-scroll {
  overflow-x: auto;
}
.channel-matrix {
  border-collapse: collapse;
  font-size: 12px;
  min-width: 100%;
}
.channel-matrix th,
.channel-matrix td {
  border: 1px solid #e5e7eb;
  padding: 6px 10px;
  text-align: center;
  white-space: nowrap;
}
.matrix-corner,
.matrix-row-head {
  text-align: left;
  background: #f3f4f6;
  font-weight: 600;
  color: #374151;
  position: sticky;
  left: 0;
  z-index: 1;
}
.matrix-col-head {
  background: #eff6ff;
  color: #1e40af;
  font-weight: 600;
  max-width: 140px;
  white-space: normal;
}
.matrix-cell {
  color: #9ca3af;
  min-width: 48px;
}
.matrix-cell.occupied {
  color: #059669;
  font-weight: 700;
  font-size: 14px;
}
.hint { font-size: 11px; color: #6b7280; margin: 4px 0 0; line-height: 1.45; }
.load-hint, .load-error { font-size: 12px; }
.load-error { color: #b91c1c; }
.viz-empty {
  padding: 20px;
  text-align: center;
  color: #9ca3af;
  background: #f9fafb;
  border-radius: 8px;
}
.viz-empty.inline { padding: 12px; margin: 0; }
</style>
