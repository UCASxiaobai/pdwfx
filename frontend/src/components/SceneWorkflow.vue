<template>
  <div class="scene-workflow">
    <header class="workflow-header">
      <h2>通信侦获信号分析系统</h2>
      <p class="subtitle">
        原始 CSV → 场景筛选 → 场景数据作为信号分析输入 → 综合报表与网络研判
      </p>
      <ol class="steps">
        <li :class="{ done: pickedFiles.length }">上传数据</li>
        <li :class="{ done: sceneResult }">场景筛选</li>
        <li :class="{ done: forwardResult }">信号分析</li>
        <li :class="{ done: report?.rows?.length }">结果查看</li>
      </ol>
    </header>

    <section class="step-card">
      <h3>① 数据上传</h3>
      <div class="upload-row">
        <label class="upload-btn">
          上传文件
          <input type="file" accept=".csv" multiple hidden @change="onPickFiles" />
        </label>
        <button type="button" class="text-btn" :disabled="!pickedFiles.length" @click="clearAll">
          清空
        </button>
      </div>
      <ul v-if="pickedFiles.length" class="file-list">
        <li v-for="(f, i) in pickedFiles" :key="i">{{ f.name }}</li>
      </ul>
      <p v-else class="hint">支持一次选择多个 CSV：PDW 侦获数据 + 可选外源定位（如雷情导入 Lq1139，含 detectTime/longitude/latitude）</p>
      <label class="check-label scatter-opt">
        <input v-model="params.enableImportScatter" type="checkbox" />
        生成全量数据概览散点（需预筛全量数据，较慢）
      </label>
      <button
        v-if="pickedFiles.length && params.enableImportScatter"
        type="button"
        class="secondary-btn scatter-btn"
        :disabled="importVizLoading || pipelineRunning"
        @click="runSceneScreeningForViz"
      >
        {{ importVizLoading ? "生成中…" : "生成全量概览" }}
      </button>
    </section>

    <ImportDataScatterViz
      v-if="params.enableImportScatter && (importScatter || importVizLoading || importVizError)"
      :scatter="importScatter"
      :loading="importVizLoading"
      :error="importVizError"
    />

    <section class="step-card">
      <h3>② 场景筛选参数</h3>
      <div class="param-grid">
        <label>频段下限 MHz <input v-model.number="params.freqMin" type="number" step="0.1" /></label>
        <label>频段上限 MHz <input v-model.number="params.freqMax" type="number" step="0.1" /></label>
        <label>评分时间窗 (秒) <input v-model.number="params.windowSeconds" type="number" min="1" /></label>
        <label>窗滑动步进 (秒) <input v-model.number="params.windowStepSeconds" type="number" min="1" /></label>
        <label>场景最少轨迹数 <input v-model.number="params.minTracksInScene" type="number" min="1" /></label>
        <label>TOP-K 连续轨迹 <input v-model.number="params.topKTrackScenes" type="number" min="1" /></label>
        <label>TOP-K 轮询场景 <input v-model.number="params.topKPollingScenes" type="number" min="1" /></label>
        <label>输出目录 <input v-model.trim="params.outputDir" type="text" /></label>
      </div>
    </section>

    <section class="step-card">
      <h3>③ 信号分析参数</h3>
      <div class="param-grid signal-params">
        <label>
          频率容差 (MHz)
          <input v-model.number="params.freqTolerance" type="number" step="0.01" min="0" />
          <span class="field-hint">建轨、场景筛选同频分组、信号分析分网共用</span>
        </label>
        <label class="check-label">
          <input v-model="params.preloadAll" type="checkbox" />
          汇总时加载全部网络目标详情（数据量大时耗时长，易超时）
        </label>
        <p class="param-hint">
          场景较多时建议取消勾选，先快速看汇总表；点击「图表」再按需加载单网详情。
        </p>
      </div>
    </section>

    <section class="step-card">
      <h3>测向–定位关联</h3>
      <SceneDfMatchPanel :upload-files="pickedFiles" @match-result="dfMatchResult = $event" />
    </section>

    <section class="step-card actions">
      <button
        type="button"
        class="primary"
        :disabled="!pickedFiles.length || pipelineRunning"
        @click="runFullPipeline"
      >
        {{ pipelineRunning ? "处理中…" : "开始分析（场景筛选 → 信号分析）" }}
      </button>
      <span v-if="statusMsg" class="status">{{ statusMsg }}</span>
      <span v-if="errorMsg" class="error">{{ errorMsg }}</span>
    </section>

    <section v-if="sceneResult?.scenes?.length" class="step-card">
      <h3>优质场景（将导出 CSV 并送入信号分析）</h3>
      <p class="meta">
        检测 {{ sceneResult.totalDetections }} 条 · 轨迹 {{ sceneResult.confirmedTracks }} 条 ·
        {{ sceneResult.scenes.length }} 个场景
      </p>
      <div class="scene-chips">
        <label
          v-for="s in sceneResult.scenes"
          :key="s.rank"
          class="chip"
          :class="{ on: selectedRanks.includes(s.rank) }"
        >
          <input v-model="selectedRanks" type="checkbox" :value="s.rank" />
          #{{ s.rank }} {{ sceneTypeLabel(s.sceneType) }} · {{ formatFreq(s.freqCenterMhz) }} MHz
        </label>
      </div>
      <label class="select-all">
        <input type="checkbox" :checked="allScenesSelected" @change="toggleAllScenes" />
        全选参与信号分析
      </label>
      <button
        v-if="forwardResult"
        type="button"
        class="secondary-btn"
        :disabled="!canReForward || pipelineRunning"
        @click="runForwardOnly"
      >
        仅用当前所选场景重新做信号分析
      </button>
    </section>

    <SceneResultsPanel
      v-if="sceneResult"
      :report="report || { rows: [], buildTimeMs: 0 }"
      :scene-result="sceneResult"
      :forward-items="forwardItems"
      :loading="reportLoading"
      :active-row-key="activeRowKey"
      @select-row="onTableSelectRow"
    />

    <SignalNetworkExplorer
      v-if="forwardItems.length"
      ref="explorerRef"
      :scene-items="forwardItems"
      :external-target-fixes="externalTargetFixes"
      :external-fix-meta="externalFixMeta"
      :df-match-result="dfMatchResult"
      :initial-scene-rank="initialExplorerScene"
      :initial-network-id="initialExplorerNetwork"
    />
  </div>
</template>

<script setup>
import { computed, nextTick, reactive, ref } from "vue";
import SceneResultsPanel from "./SceneResultsPanel.vue";
import SignalNetworkExplorer from "./SignalNetworkExplorer.vue";
import ImportDataScatterViz from "./ImportDataScatterViz.vue";
import SceneDfMatchPanel from "./SceneDfMatchPanel.vue";
import { analyzeScenesUpload, fetchVisualizationData, processScenesPipeline } from "../scene/sceneApi.js";
import { clearAnalysisViewCache } from "../scene/analysisViewCache.js";
import { formatFreq, sceneTypeLabel } from "../scene/sceneFilters.js";

const pickedFiles = ref([]);
const params = reactive({
  freqMin: 200,
  freqMax: 1000,
  windowSeconds: 120,
  windowStepSeconds: 30,
  minTracksInScene: 1,
  topKTrackScenes: 50,
  topKPollingScenes: 50,
  outputDir: "./output",
  freqTolerance: 0.01,
  preloadAll: true,
  enableImportScatter: false
});

const pipelineRunning = ref(false);
const statusMsg = ref("");
const errorMsg = ref("");
const sceneResult = ref(null);
const forwardResult = ref(null);
const report = ref(null);
const reportLoading = ref(false);
const forwardItems = ref([]);
const selectedRanks = ref([]);
const activeRowKey = ref("");
const explorerRef = ref(null);
const initialExplorerScene = ref(null);
const initialExplorerNetwork = ref(null);
const importScatter = ref(null);
const importVizLoading = ref(false);
const importVizError = ref("");

let abortController = null;
let importAbort = null;

const allScenesSelected = computed(() => {
  const n = sceneResult.value?.scenes?.length || 0;
  return n > 0 && selectedRanks.value.length === n;
});

const canReForward = computed(
  () =>
    sceneResult.value?.sourceCsv &&
    sceneResult.value?.outputDir &&
    selectedRanks.value.length > 0
);

const externalTargetFixes = computed(() => sceneResult.value?.externalTargetFixes || []);
const dfMatchResult = ref(null);

const externalFixMeta = computed(() => {
  const total = sceneResult.value?.externalTargetFixTotalCount || 0;
  const shown = externalTargetFixes.value.length;
  const files = sceneResult.value?.externalSourceFiles || [];
  if (!total) return "";
  const fileNote = files.length ? ` · ${files.join(", ")}` : "";
  const sampleNote = shown < total ? `（地图抽样 ${shown}/${total}）` : "";
  return `外源定位 ${total} 点${sampleNote}${fileNote}`;
});

function onPickFiles(e) {
  const list = [...(e.target.files || [])].filter((f) =>
    f.name.toLowerCase().endsWith(".csv")
  );
  if (!list.length) {
    errorMsg.value = "未找到 CSV 文件";
    return;
  }
  pickedFiles.value = list;
  errorMsg.value = "";
  e.target.value = "";
}

async function loadImportScatter(result) {
  importVizError.value = "";
  const inline = result?.visualization?.importScatter
    ? result.visualization.importScatter
    : null;
  if (inline) {
    importScatter.value = inline;
    return;
  }
  const outputDir = result?.outputDir;
  if (!outputDir) {
    importScatter.value = null;
    return;
  }
  importAbort?.abort();
  importAbort = new AbortController();
  try {
    const data = await fetchVisualizationData(outputDir, importAbort.signal);
    importScatter.value = data?.importScatter || null;
    if (!importScatter.value) {
      importVizError.value = "散点数据未生成，请确认已完成场景预筛。";
    }
  } catch (err) {
    if (err?.name !== "AbortError") {
      importVizError.value = err?.message || "加载散点失败";
      importScatter.value = null;
    }
  }
}

async function runSceneScreeningForViz() {
  if (!pickedFiles.value.length) return;
  importAbort?.abort();
  importAbort = new AbortController();
  clearAnalysisViewCache();
  forwardResult.value = null;
  forwardItems.value = [];
  report.value = null;
  activeRowKey.value = "";
  importVizLoading.value = true;
  importVizError.value = "";
  importScatter.value = null;
  statusMsg.value = "正在预筛场景并标绘全量散点…";
  try {
    const result = await analyzeScenesUpload(
      pickedFiles.value,
      params,
      importAbort.signal
    );
    sceneResult.value = result;
    selectedRanks.value = (result.scenes || []).map((s) => s.rank);
    await loadImportScatter(result);
    statusMsg.value = result.scenes?.length
      ? `已标绘全量散点 · 筛出 ${result.scenes.length} 个优质场景`
      : "已完成预筛，未找到优质场景";
  } catch (err) {
    if (err?.name !== "AbortError") {
      importVizError.value = err?.message || "预筛失败";
      errorMsg.value = importVizError.value;
    }
    statusMsg.value = "";
  } finally {
    importVizLoading.value = false;
  }
}

function clearAll() {
  importAbort?.abort();
  clearAnalysisViewCache();
  pickedFiles.value = [];
  sceneResult.value = null;
  forwardResult.value = null;
  report.value = null;
  forwardItems.value = [];
  selectedRanks.value = [];
  activeRowKey.value = "";
  importScatter.value = null;
  importVizError.value = "";
  importVizLoading.value = false;
  statusMsg.value = "";
  errorMsg.value = "";
}

function toggleAllScenes(e) {
  const scenes = sceneResult.value?.scenes || [];
  selectedRanks.value = e.target.checked ? scenes.map((s) => s.rank) : [];
}

async function runFullPipeline() {
  if (!pickedFiles.value.length) return;
  clearAnalysisViewCache();
  pipelineRunning.value = true;
  errorMsg.value = "";
  statusMsg.value = "场景筛选中…";
  abortController = new AbortController();
  const signal = abortController.signal;
  const timer = setTimeout(() => abortController?.abort(), 90 * 60 * 1000);
  try {
    sceneResult.value = await analyzeScenesUpload(pickedFiles.value, params, signal);
    selectedRanks.value = (sceneResult.value.scenes || []).map((s) => s.rank);
    if (params.enableImportScatter) {
      await loadImportScatter(sceneResult.value);
    }
    if (!selectedRanks.value.length) {
      errorMsg.value = "未筛选出优质场景，请调整参数";
      return;
    }
    await runForwardAndReport(signal);
    statusMsg.value = `完成：${report.value?.rows?.length || 0} 条目标明细`;
  } catch (e) {
    errorMsg.value =
      e?.name === "AbortError" ? "分析超时（30 分钟）" : e?.message || "分析失败";
  } finally {
    clearTimeout(timer);
    pipelineRunning.value = false;
  }
}

async function runForwardOnly() {
  if (!canReForward.value) return;
  clearAnalysisViewCache();
  pipelineRunning.value = true;
  errorMsg.value = "";
  abortController = new AbortController();
  try {
    statusMsg.value = "信号分析中…";
    await runForwardAndReport(abortController.signal);
    pickInitialExplorerFocus();
    statusMsg.value = "信号分析与报告已更新";
  } catch (e) {
    errorMsg.value = e?.message || "失败";
  } finally {
    pipelineRunning.value = false;
  }
}

async function runForwardAndReport(signal) {
  reportLoading.value = true;
  report.value = { rows: [], buildTimeMs: 0, partial: true };
  forwardItems.value = [];
  activeRowKey.value = "";
  await nextTick();

  try {
    const summary = await processScenesPipeline(
      {
        sourceCsvPath: sceneResult.value.sourceCsv,
        outputDir: sceneResult.value.outputDir,
        sceneRanks: [...selectedRanks.value],
        freqTolerance: params.freqTolerance,
        preloadAll: params.preloadAll
      },
      {
        signal,
        onProgress: (cur, total, rank) => {
          statusMsg.value = `优质场景 ${cur}/${total}（#${rank}）分析中…`;
        },
        onPartialRows: (rows) => {
          report.value = {
            rows: [...rows],
            buildTimeMs: report.value?.buildTimeMs || 0,
            partial: true
          };
        }
      }
    );
    forwardItems.value = summary.forwardItems;
    forwardResult.value = {
      sourceCsv: sceneResult.value.sourceCsv,
      outputDir: sceneResult.value.outputDir,
      scenes: summary.forwardItems.length
    };
    report.value = {
      rows: summary.rows,
      buildTimeMs: summary.buildTimeMs,
      networkDetailCount: summary.networkDetailCount,
      partial: false
    };
    pickInitialExplorerFocus();
  } finally {
    reportLoading.value = false;
  }
}

function pickInitialExplorerFocus() {
  const first = forwardItems.value.find((x) => x.session?.networks?.length);
  if (!first) return;
  const net = first.session.networks[0];
  initialExplorerScene.value = first.rank;
  initialExplorerNetwork.value = net?.networkId;
  setTimeout(() => {
    explorerRef.value?.focusNetwork?.(first.rank, net?.networkId);
  }, 100);
}

function onTableSelectRow(r) {
  activeRowKey.value = `${r.sceneRank}-${r.analysisId}-${r.networkId}-${r.targetId || ""}`;
  explorerRef.value?.focusNetwork?.(r.sceneRank, r.networkId, r.targetId);
}
</script>

<style scoped>
.scene-workflow {
  font-family: Arial, sans-serif;
  max-width: 100%;
}
.workflow-header h2 {
  margin: 0 0 4px;
  font-size: 1.35rem;
}
.subtitle {
  color: #6b7280;
  font-size: 13px;
  margin: 0 0 10px;
}
.steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  margin: 0 0 16px;
  padding: 0;
  list-style: none;
  font-size: 13px;
  color: #9ca3af;
}
.steps li.done {
  color: #1d4ed8;
  font-weight: 600;
}
.step-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 14px;
  background: #fff;
}
.step-card h3 {
  margin: 0 0 10px;
  font-size: 1rem;
}
.upload-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.upload-btn {
  display: inline-block;
  padding: 8px 14px;
  background: #2563eb;
  color: #fff;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.upload-btn.secondary {
  background: #6b7280;
}
.upload-btn input {
  display: none;
}
.text-btn {
  background: none;
  border: none;
  color: #6b7280;
  cursor: pointer;
}
.file-list {
  margin: 8px 0 0;
  padding-left: 20px;
  font-size: 12px;
  max-height: 100px;
  overflow-y: auto;
}
.hint {
  font-size: 12px;
  color: #9ca3af;
  margin: 0;
}
.param-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 10px;
}
.param-grid label {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: #4b5563;
  gap: 4px;
}
.param-grid input[type="number"],
.param-grid input[type="text"] {
  padding: 6px 8px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
}
.signal-params {
  grid-template-columns: 1fr 1fr;
}
.check-label {
  flex-direction: row !important;
  align-items: center;
  gap: 8px !important;
}
.scatter-opt {
  margin-top: 10px;
}
.scatter-btn {
  margin-top: 8px;
}
.param-hint,
.field-hint {
  font-size: 11px;
  color: #6b7280;
  font-weight: normal;
}
.param-hint {
  grid-column: 1 / -1;
  margin: 0;
}
.field-hint {
  display: block;
  margin-top: 2px;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.primary {
  padding: 10px 20px;
  background: #1d4ed8;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}
.primary:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.secondary-btn {
  margin-top: 8px;
  padding: 8px 14px;
  background: #f3f4f6;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  cursor: pointer;
}
.status {
  color: #2563eb;
  font-weight: 600;
  font-size: 13px;
}
.error {
  color: #b91c1c;
  font-weight: 600;
  font-size: 13px;
}
.meta {
  font-size: 12px;
  color: #6b7280;
  margin: 0 0 8px;
}
.scene-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 20px;
  font-size: 12px;
  cursor: pointer;
}
.chip.on {
  border-color: #7c3aed;
  background: #f5f3ff;
}
.select-all {
  display: block;
  margin-top: 10px;
  font-size: 13px;
}
</style>
