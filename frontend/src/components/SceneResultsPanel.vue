<template>
  <div class="results-panel">
    <p v-if="loading" class="loading-banner">正在按场景汇总表格数据，可先使用下方「信号分析」浏览网络…</p>

    <section class="filters">
      <h3>结果筛选</h3>
      <div class="filter-grid">
        <label>
          场景
          <select v-model="localFilters.sceneRank">
            <option value="">全部</option>
            <option
              v-for="opt in sceneRankOptions"
              :key="opt.rank"
              :value="String(opt.rank)"
            >
              {{ formatSceneLabel(opt.rank, opt.sceneType, opt) }}
            </option>
          </select>
        </label>
        <label>
          目标类型
          <select v-model="localFilters.targetType">
            <option v-for="o in targetTypeOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
          </select>
        </label>
        <label>
          波道
          <select v-model="localFilters.commLink">
            <option value="">全部</option>
            <option v-for="c in commLinkOptions" :key="c" :value="c">{{ c }}</option>
          </select>
        </label>
        <label>
          频率下限 MHz
          <input v-model="localFilters.freqMin" type="number" step="0.1" />
        </label>
        <label>
          频率上限 MHz
          <input v-model="localFilters.freqMax" type="number" step="0.1" />
        </label>
        <label>
          时间起
          <input v-model="localFilters.timeStart" type="datetime-local" />
        </label>
        <label>
          时间止
          <input v-model="localFilters.timeEnd" type="datetime-local" />
        </label>
      </div>
      <p class="stats-line">
        <template v-if="hasReportRows">
          筛选后 {{ filteredRows.length }} 条明细 · {{ stats.sceneCount }} 个场景
        </template>
        <template v-else>
          场景筛选 {{ sceneResult?.scenes?.length || 0 }} 个 · 当前显示 {{ bearingAllowedRanks.size }} 个轨迹图
        </template>
      </p>

      <p v-if="vizLoading" class="viz-hint">正在加载场景方位轨迹数据…</p>
      <SceneBearingViz
        v-else-if="visualizationPayload"
        v-model:active-rank="activeBearingSceneRank"
        :scene-tabs="unifiedSceneTabs"
        :trajectory-views="alignedTrajectoryViews"
        :summary="bearingSummary"
      />
      <p v-else-if="sceneResult?.scenes?.length && vizError" class="viz-hint">{{ vizError }}</p>

      <SceneAnalysisBearingViz
        v-if="showAnalysisBearing"
        v-model:active-rank="activeBearingSceneRank"
        :scene-tabs="unifiedSceneTabs"
        :forward-items="forwardItems"
        :scene-result="sceneResult"
        :report-rows="allRows"
      />

      <SceneCrossFreqMatchViz
        v-if="showAnalysisBearing"
        :forward-items="forwardItems"
        :scene-result="sceneResult"
        :allowed-ranks="bearingAllowedRanks"
      />

      <SceneFreqHopTrackViz
        v-if="hoppingViews.length"
        :hopping-track-views="hoppingViews"
        :import-scatter="visualizationPayload && visualizationPayload.importScatter"
        :report-rows="allRows"
      />

      <SceneAwacsCommandNetViz :command-net-pass="commandNetPass" />
    </section>

    <SceneResultsCharts
      v-if="hasReportRows && (!loading || chartRows.length)"
      ref="chartsRef"
      :rows="chartRows"
    />

    <section v-if="hasReportRows" class="table-section">
      <div class="table-head">
        <h3>明细表格</h3>
        <button type="button" :disabled="exporting" @click="onExport">
          {{ exporting ? "正在生成…" : "导出 Word 报告" }}
        </button>
      </div>
      <div class="table-scroll report-table-wrap">
        <table class="report-table">
          <thead>
            <tr>
              <th>场景</th>
              <th>起止时间</th>
              <th>网络</th>
              <th>网络频率</th>
              <th>波道</th>
              <th>目标</th>
              <th>目标类型</th>
              <th>定位 (经,纬)</th>
              <th>定位方式</th>
              <th>侦获起</th>
              <th>侦获止</th>
              <th>侦获次数</th>
              <th>角色</th>
              <th>置信度</th>
              <th>流量%</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(row, idx) in pagedRows"
              :key="rowKey(row, idx)"
              :class="{ active: isActiveRow(row) }"
              @click="emitSelectRow(row)"
            >
              <td v-if="row.showScene" :rowspan="row.spanScene" class="merge-cell">
                {{ row.sceneLabel }}
              </td>
              <td v-if="row.showTime" :rowspan="row.spanTime" class="merge-cell time-cell">
                <div class="time-stack">
                  <div>{{ row.timeStartLabel }}</div>
                  <div class="time-sep">~</div>
                  <div>{{ row.timeEndLabel }}</div>
                </div>
              </td>
              <td v-if="row.showNetwork" :rowspan="row.spanNetwork" class="merge-cell">
                {{ row.networkId }}
              </td>
              <td v-if="row.showNetworkFreq" :rowspan="row.spanNetworkFreq" class="merge-cell num">
                {{ formatFreq(row.networkFreqMhz) }}
              </td>
              <td v-if="row.showChannel" :rowspan="row.spanChannel" class="merge-cell">
                {{ row.commLinkChannelLabel || row.commLinkChannel || "—" }}
              </td>
              <td>{{ row.targetId || "—" }}</td>
              <td>{{ targetTypeLabel(row.targetType) }}</td>
              <td class="num">{{ formatLocate(row) }}</td>
              <td>{{ row.locateMethodLabel || row.locateMethod || "—" }}</td>
              <td class="time-cell-sm">{{ formatDetectTime(row.detectStartTime) }}</td>
              <td class="time-cell-sm">{{ formatDetectTime(row.detectEndTime) }}</td>
              <td class="num">{{ row.detectCount ?? "—" }}</td>
              <td>{{ row.role || "—" }}</td>
              <td class="num">{{ formatConfidence(row.confidence) }}</td>
              <td class="num">{{ formatShare(row.emissionSharePct) }}</td>
              <td>
                <button type="button" class="link-btn" @click.stop="emitSelectRow(row)">图表</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="pager" v-if="tableRows.length > pageSize">
        <button type="button" :disabled="page <= 1" @click="page--">上一页</button>
        <span>{{ page }} / {{ totalPages }}</span>
        <button type="button" :disabled="page >= totalPages" @click="page++">下一页</button>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, reactive, ref, shallowRef, watch } from "vue";
import SceneBearingViz from "./SceneBearingViz.vue";
import SceneAnalysisBearingViz from "./SceneAnalysisBearingViz.vue";
import SceneCrossFreqMatchViz from "./SceneCrossFreqMatchViz.vue";
import SceneResultsCharts from "./SceneResultsCharts.vue";
import SceneFreqHopTrackViz from "./SceneFreqHopTrackViz.vue";
import SceneAwacsCommandNetViz from "./SceneAwacsCommandNetViz.vue";
import {
  TARGET_TYPE_OPTIONS,
  aggregateStats,
  alignTrajectoryViewsToSceneTabs,
  applyStreamTargetTypeLabelsToViews,
  filterReportRows,
  filterScenes,
  buildDisplaySceneTabs,
  formatFreq,
  formatSceneLabel,
  sortScenesForDisplay,
  targetTypeLabel,
  uniqueCommLinks
} from "../scene/sceneFilters.js";
import { fetchVisualizationData } from "../scene/sceneApi.js";
import { exportAnalysisDocx } from "../scene/exportDocx.js";
import { debounce, sampleRowsForCharts } from "../scene/reportUtils.js";
import { applyMergeRowspans, buildFormattedTable } from "../scene/reportTableFormat.js";

const props = defineProps({
  report: { type: Object, default: () => ({ rows: [], buildTimeMs: 0 }) },
  sceneResult: { type: Object, default: null },
  forwardItems: { type: Array, default: () => [] },
  commandNetPass: { type: Object, default: null },
  loading: { type: Boolean, default: false },
  activeRowKey: { type: String, default: "" }
});

const emit = defineEmits(["select-row"]);

const chartsRef = ref(null);
const exporting = ref(false);
const visualizationPayload = ref(null);
const vizLoading = ref(false);
const vizError = ref("");
let vizAbort = null;
const page = ref(1);
const pageSize = 50;
const activeBearingSceneRank = ref(null);

const localFilters = reactive({
  sceneRank: "",
  targetType: "",
  commLink: "",
  freqMin: "",
  freqMax: "",
  timeStart: "",
  timeEnd: ""
});

const allRows = computed(() => props.report?.rows || []);
const hasReportRows = computed(() => allRows.value.length > 0);
const showAnalysisBearing = computed(
  () =>
    hasReportRows.value
    && !props.report?.partial
    && !props.loading
    && props.forwardItems.length > 0
);

const filteredRows = computed(() => filterReportRows(allRows.value, localFilters));

const analyzedRanks = computed(() => {
  const set = new Set();
  for (const item of props.forwardItems || []) {
    const r = Number(item.rank);
    if (Number.isFinite(r)) set.add(r);
  }
  return set;
});

const bearingAllowedRanks = computed(() => {
  const scenes = filterScenes(props.sceneResult?.scenes || [], localFilters, null);
  let ranks = new Set(scenes.map((s) => Number(s.rank)).filter(Number.isFinite));
  // 信号分析完成后：上下图与明细表只展示本次分析过的预筛选序号
  if (analyzedRanks.value.size) {
    ranks = new Set([...ranks].filter((r) => analyzedRanks.value.has(r)));
  }
  const hasSignalFilter = localFilters.targetType || localFilters.commLink;
  if (hasSignalFilter && allRows.value.length) {
    const fromReport = new Set(
      filteredRows.value.map((r) => Number(r.sceneRank)).filter(Number.isFinite)
    );
    ranks = new Set([...ranks].filter((r) => fromReport.has(r)));
  }
  return ranks;
});

const unifiedSceneTabs = computed(() =>
  buildDisplaySceneTabs(props.sceneResult?.scenes || [], {
    allowedRanks: bearingAllowedRanks.value,
    requireAnalyzedRanks: analyzedRanks.value.size ? analyzedRanks.value : null
  })
);

/** 预筛轨迹图与 Tab 按同一预筛选序号对齐；有明细时叠目标类型 */
const alignedTrajectoryViews = computed(() => {
  const views = alignTrajectoryViewsToSceneTabs(
    visualizationPayload.value?.trajectoryViews || [],
    unifiedSceneTabs.value
  );
  const labels = (allRows.value || []).map((r) => ({
    sceneRank: r.sceneRank,
    targetType: r.targetType,
    targetTypeLabel: r.targetTypeLabel || targetTypeLabel(r.targetType),
    freqMhz: r.networkFreqMhz,
    meanAzimuthDeg: r.meanAzimuthDeg ?? null,
    channel: r.commLinkChannel,
    channelLabel: r.commLinkChannelLabel,
    targetChannelsUsed: r.targetChannelsUsed
  }));
  return applyStreamTargetTypeLabelsToViews(views, labels);
});

const hoppingViews = computed(() =>
  applyStreamTargetTypeLabelsToViews(
    visualizationPayload.value?.hoppingTrackViews || [],
    (allRows.value || []).map((r) => ({
      sceneRank: r.sceneRank,
      targetType: r.targetType,
      targetTypeLabel: r.targetTypeLabel || targetTypeLabel(r.targetType),
      freqMhz: r.networkFreqMhz,
      meanAzimuthDeg: r.meanAzimuthDeg ?? null,
      channel: r.commLinkChannel,
      channelLabel: r.commLinkChannelLabel,
      targetChannelsUsed: r.targetChannelsUsed
    }))
  )
);

watch(
  unifiedSceneTabs,
  (tabs) => {
    if (!tabs.length) {
      activeBearingSceneRank.value = null;
      return;
    }
    const active = Number(activeBearingSceneRank.value);
    if (!tabs.some((t) => Number(t.rank) === active)) {
      activeBearingSceneRank.value = tabs[0].rank;
    }
  },
  { immediate: true }
);

const bearingSummary = computed(() => ({
  detections:
    props.sceneResult?.totalDetections
    || visualizationPayload.value?.totalDetections
    || 0,
  tracks:
    props.sceneResult?.confirmedTracks
    || visualizationPayload.value?.confirmedTracks
    || 0
}));

async function loadVisualization() {
  vizAbort?.abort();
  const inline = props.sceneResult?.visualization;
  if (inline) {
    visualizationPayload.value = inline;
    vizError.value = "";
    return;
  }
  const outputDir = props.sceneResult?.outputDir;
  if (!outputDir || !props.sceneResult?.scenes?.length) {
    visualizationPayload.value = null;
    return;
  }
  vizLoading.value = true;
  vizError.value = "";
  vizAbort = new AbortController();
  try {
    visualizationPayload.value = await fetchVisualizationData(outputDir, vizAbort.signal);
  } catch (e) {
    if (e?.name === "AbortError") return;
    visualizationPayload.value = null;
    vizError.value = `轨迹数据加载失败：${e?.message || e}`;
  } finally {
    vizLoading.value = false;
  }
}

watch(
  () => [props.sceneResult?.visualization, props.sceneResult?.outputDir],
  () => {
    loadVisualization();
  },
  { immediate: true }
);

onBeforeUnmount(() => {
  vizAbort?.abort();
});

const tableRows = computed(() =>
  buildFormattedTable(filteredRows.value, props.sceneResult)
);

const chartRows = shallowRef([]);

const refreshChartRows = debounce(() => {
  chartRows.value = sampleRowsForCharts(filteredRows.value);
}, 300);

const stats = computed(() => aggregateStats(filteredRows.value));

const sceneRankOptions = computed(() => {
  const byRank = new Map();
  for (const s of props.sceneResult?.scenes || []) {
    const rank = Number(s.rank);
    if (Number.isFinite(rank)) {
      byRank.set(rank, {
        rank,
        sceneType: s.sceneType,
        freqCenterMhz: s.freqCenterMhz ?? null,
        freqMinMhz: s.freqMinMhz ?? null,
        freqMaxMhz: s.freqMaxMhz ?? null
      });
    }
  }
  for (const r of allRows.value) {
    const rank = Number(r.sceneRank);
    if (!Number.isFinite(rank)) continue;
    if (!byRank.has(rank)) {
      byRank.set(rank, {
        rank,
        sceneType: r.sceneType,
        freqCenterMhz: r.sceneFreqCenterMhz ?? r.networkFreqMhz ?? null,
        freqMinMhz: r.sceneFreqMinMhz ?? null,
        freqMaxMhz: r.sceneFreqMaxMhz ?? null
      });
    }
  }
  // 筛选下拉只保留本次分析过的预筛选序号，与图/表一致
  const entries = analyzedRanks.value.size
    ? [...byRank.values()].filter((s) => analyzedRanks.value.has(s.rank))
    : [...byRank.values()];
  return sortScenesForDisplay(entries);
});

const commLinkOptions = computed(() => uniqueCommLinks(allRows.value));
const targetTypeOptions = TARGET_TYPE_OPTIONS;

const totalPages = computed(() =>
  Math.max(1, Math.ceil(tableRows.value.length / pageSize))
);

const pagedRows = computed(() => {
  const start = (page.value - 1) * pageSize;
  const slice = tableRows.value.slice(start, start + pageSize);
  return reapplyRowspansInPage(slice);
});

watch(filteredRows, () => {
  page.value = 1;
  refreshChartRows();
}, { immediate: true });

function reapplyRowspansInPage(pageRows) {
  if (!pageRows.length) return [];
  return applyMergeRowspans(pageRows.map((r) => ({ ...r })));
}

function rowKey(row, idx) {
  return `${row.sceneRank}-${row.analysisId}-${row.networkId}-${row.targetId || ""}-${idx}`;
}

function formatConfidence(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(2) : "—";
}

function formatShare(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(1) : "—";
}

function formatLocate(row) {
  const lon = row.targetLocateLon;
  const lat = row.targetLocateLat;
  if (lon == null || lat == null) return "—";
  const a = Number(lon);
  const b = Number(lat);
  if (!Number.isFinite(a) || !Number.isFinite(b)) return "—";
  return `${a.toFixed(4)}, ${b.toFixed(4)}`;
}

function formatDetectTime(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return String(iso);
  const pad = (n) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function isActiveRow(row) {
  const key = `${row.sceneRank}-${row.analysisId}-${row.networkId}-${row.targetId || ""}`;
  return props.activeRowKey === key;
}

function emitSelectRow(row) {
  emit("select-row", { ...row });
}

async function onExport() {
  exporting.value = true;
  try {
    await new Promise((r) => requestAnimationFrame(r));
    const chartImages = chartsRef.value?.getChartImages?.() || [];
    await exportAnalysisDocx({
      sceneResult: props.sceneResult,
      report: props.report,
      filteredRows: filteredRows.value,
      formattedRows: tableRows.value,
      stats: stats.value,
      chartImages
    });
  } catch (e) {
    alert(`导出失败: ${e?.message || e}`);
  } finally {
    exporting.value = false;
  }
}
</script>

<style scoped>
.results-panel { margin-top: 16px; }
.loading-banner {
  padding: 10px 12px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  color: #1d4ed8;
  font-size: 13px;
  margin-bottom: 12px;
}
.filters { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px; margin-bottom: 12px; }
.filter-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 10px; }
.filter-grid label { display: flex; flex-direction: column; font-size: 12px; color: #374151; gap: 4px; }
.filter-grid input, .filter-grid select { padding: 6px 8px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
.stats-line { font-size: 13px; color: #4b5563; margin: 10px 0 0; }
.viz-hint { font-size: 12px; color: #6b7280; margin-top: 12px; }
.table-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.table-head button { padding: 8px 14px; background: #1d4ed8; color: #fff; border: none; border-radius: 6px; cursor: pointer; }
.table-head button:disabled { opacity: 0.6; cursor: wait; }
.report-table-wrap { overflow: auto; max-height: 520px; border: 1px solid #9ca3af; border-radius: 4px; }
.report-table { width: 100%; border-collapse: collapse; font-size: 12px; table-layout: auto; }
.report-table th, .report-table td {
  border: 1px solid #9ca3af;
  padding: 6px 8px;
  text-align: center;
  vertical-align: middle;
}
.report-table th { background: #f3f4f6; position: sticky; top: 0; z-index: 1; font-weight: 600; }
.merge-cell { background: #fafafa; font-weight: 500; }
.time-cell { line-height: 1.35; }
.time-stack .time-sep { line-height: 1.2; }
.num { text-align: right; }
.time-cell-sm { font-size: 11px; white-space: nowrap; }
.report-table tbody tr { cursor: pointer; }
.report-table tbody tr:hover { background: #f9fafb; }
.report-table tbody tr.active { background: #eff6ff; }
.link-btn { background: none; border: none; color: #2563eb; cursor: pointer; padding: 0; }
.pager { display: flex; gap: 12px; align-items: center; margin-top: 8px; font-size: 13px; }
</style>
