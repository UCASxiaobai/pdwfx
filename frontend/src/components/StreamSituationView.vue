<template>
  <div class="stream-page">
    <header class="head">
      <div>
        <h2>流式态势（独立模块）</h2>
        <p class="sub">
          TCP 收包 → 落盘切批 → 场景筛选/建轨 → 逐场景信号分析 → 标注。
          <a href="#/">返回主流程</a>
        </p>
      </div>
      <button type="button" class="refresh" :disabled="loading" @click="reload">
        {{ loading ? "刷新中…" : "刷新" }}
      </button>
    </header>

    <p v-if="error" class="error">{{ error }}</p>

    <section class="card">
      <h3>运行状态</h3>
      <div v-if="status" class="grid">
        <div>TCP：{{ status.tcpRunning ? `监听 ${status.tcpPort}` : "未启动" }}</div>
        <div>流水线：{{ status.pipeline || "—" }}</div>
        <div>收包：{{ status.packetsIn }} 包 / {{ formatBytes(status.bytesIn) }}</div>
        <div>解析记录：{{ status.parse?.records ?? 0 }}（丢弃非B108 {{ status.parse?.skippedOtherType ?? 0 }} · DOA失败 {{ status.parse?.skippedDoa ?? 0 }}）</div>
        <div>
          已写行：{{ status.writtenRows }}
          <span v-if="status.dedupEnabled !== false"> · 去重跳过 {{ status.skippedDupRows ?? 0 }}</span>
          · 已封批：{{ status.sealedBatches }}
        </div>
        <div>分析队列：{{ status.queueSize }} / {{ status.maxQueueFiles }}{{ status.queuePaused ? "（暂停收包）" : "" }}</div>
        <div>正在分析：{{ status.analyzingFile || "—" }}{{ status.analyzingPhase ? ` · ${status.analyzingPhase}` : "" }}</div>
        <div>落盘目录：{{ status.batchDir }}</div>
        <div>分析服务：{{ status.analyzeBaseUrl }}</div>
        <div>
          场景窗：{{ status.scene?.fullSpanWindow !== false ? "全段（=本批实际跨度）" : ("滑动 " + (status.scene?.windowSeconds ?? "—") + "s") }}
          · TOP 连续 {{ status.scene?.topKTrackScenes ?? "—" }}
          · TOP 轮询 {{ status.scene?.topKPollingScenes ?? "—" }}
          · 网络详情 {{ status.preloadAll !== false ? "preloadAll" : "摘要" }}
        </div>
      </div>
      <p v-else class="hint">无法连接流式模块（默认 http://localhost:19080）。请先启动 stream 进程。</p>
    </section>

    <section class="card">
      <h3>最近批结果</h3>
      <table v-if="batches.length" class="tbl">
        <thead>
          <tr>
            <th>批 ID</th>
            <th>状态</th>
            <th>场景</th>
            <th>连续/轮询</th>
            <th>网络</th>
            <th>标签</th>
            <th>analysisId</th>
            <th>完成时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="b in batches"
            :key="b.streamBatchId"
            :class="{ on: selected?.streamBatchId === b.streamBatchId }"
            @click="selectBatch(b)"
          >
            <td>{{ b.streamBatchId }}</td>
            <td>{{ b.status }}</td>
            <td>{{ b.sceneCount ?? 0 }}</td>
            <td>{{ b.trackSceneCount ?? 0 }} / {{ b.pollingSceneCount ?? 0 }}</td>
            <td>{{ b.networkCount }}</td>
            <td>{{ b.trackCount }}</td>
            <td>
              <a
                v-if="b.analysisId && b.networkCount"
                :href="`#/network?analysisId=${encodeURIComponent(b.analysisId)}&networkId=1`"
                @click.stop
              >{{ b.analysisId.slice(0, 8) }}…</a>
              <span v-else>{{ b.analysisId ? b.analysisId.slice(0, 8) + "…" : "—" }}</span>
            </td>
            <td>{{ formatTime(b.finishedAt) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="hint">暂无已分析批次</p>
    </section>

    <template v-if="selected">
      <section class="card">
        <h3>批详情 · {{ selected.streamBatchId }}</h3>
        <p v-if="selected.error" class="error">{{ selected.error }}</p>
        <div class="grid">
          <div>场景目录：{{ selected.sceneOutputDir || "—" }}</div>
          <div>标注文件：{{ selected.annotationPath || "—" }}</div>
          <div>
            <a
              v-if="selected.streamBatchId"
              :href="`${STREAM_BASE}/api/stream/batches/${encodeURIComponent(selected.streamBatchId)}/annotation`"
              target="_blank"
              rel="noopener"
            >打开标注 JSON</a>
          </div>
        </div>
      </section>

      <section class="card">
        <h3>场景摘要</h3>
        <table v-if="selected.scenes?.length" class="tbl no-pointer">
          <thead>
            <tr>
              <th>Rank</th>
              <th>类型</th>
              <th>频率 MHz</th>
              <th>轨迹数</th>
              <th>网络</th>
              <th>时间窗</th>
              <th>analysisId</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in selected.scenes" :key="s.rank">
              <td>#{{ s.rank }}</td>
              <td>{{ sceneTypeLabel(s.sceneType) }}{{ s.skipped ? "（跳过）" : "" }}</td>
              <td>{{ formatSceneFreq(s) || "—" }}</td>
              <td>{{ s.trackCount ?? "—" }}</td>
              <td>{{ s.networkCount ?? 0 }}</td>
              <td>{{ formatWindow(s.windowStartMs, s.windowEndMs) }}</td>
              <td>
                <a
                  v-if="s.analysisId"
                  :href="`#/network?analysisId=${encodeURIComponent(s.analysisId)}&networkId=1`"
                >{{ s.analysisId.slice(0, 8) }}…</a>
                <span v-else>—</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="hint">该批尚无场景（筛选中或未筛出）</p>
      </section>

      <section class="card">
        <h3>本批全局可视化</h3>
        <ImportDataScatterViz
          v-if="importScatter || vizLoading"
          :scatter="importScatter"
          :loading="vizLoading && !importScatter"
          :error="importScatterError"
        />
        <p v-else class="hint">
          {{ selected.sceneOutputDir ? "暂无全量散点（需新批次重新分析后生成 importScatter）" : "等待场景筛选完成…" }}
        </p>
      </section>

      <section class="card">
        <h3>场景方位轨迹（开发校验）</h3>
        <p v-if="vizLoading" class="hint">加载轨迹数据…</p>
        <p v-else-if="vizError" class="error">{{ vizError }}</p>
        <SceneBearingViz
          v-else-if="sceneTabs.length"
          :scene-tabs="sceneTabs"
          :trajectory-views="alignedViews"
          :active-rank="activeBearingRank"
          :summary="bearingSummary"
          @update:activeRank="activeBearingRank = $event"
        />
        <p v-else class="hint">
          {{ selected.sceneOutputDir ? "无可用轨迹可视化（请确认 backend 已启动且 outputDir 存在）" : "等待场景筛选完成…" }}
        </p>
      </section>

      <section class="card">
        <h3>目标类型与波道占用</h3>
        <div v-if="occupancySummary.length" class="grid occ-grid">
          <div v-for="item in occupancySummary" :key="item.key">{{ item.text }}</div>
        </div>
        <SceneResultsCharts
          v-if="chartRows.length"
          :rows="chartRows"
        />
        <p v-else class="hint">该批尚无目标类型/波道研判结果（需 preloadAll 后重新分析）</p>
      </section>

      <section class="card">
        <h3>目标标签 · {{ selected.streamBatchId }}</h3>
        <table v-if="selected.labels?.length" class="tbl no-pointer">
          <thead>
            <tr>
              <th>场景</th>
              <th>批号</th>
              <th>目标类型</th>
              <th>角色</th>
              <th>占空比</th>
              <th>流量%</th>
              <th>波道</th>
              <th>占用波道</th>
              <th>频点 MHz</th>
              <th>点数</th>
              <th>研判</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="l in selected.labels" :key="l.batchId">
              <td>#{{ l.sceneRank }} {{ sceneTypeLabel(l.sceneType) }}{{ sceneFreqSuffix(l.sceneRank) }}</td>
              <td>{{ l.batchId }}</td>
              <td>{{ l.targetTypeLabel || l.targetType || "—" }}</td>
              <td>{{ l.role || "—" }}</td>
              <td>{{ pct(l.dutyCycle) }}</td>
              <td>{{ num(l.trafficSharePct) }}</td>
              <td>{{ l.channelLabel || l.channel || "—" }}</td>
              <td>{{ l.targetChannelsUsed || l.channelLabel || l.channel || "—" }}</td>
              <td>{{ num(l.freqMhz, 3) }}</td>
              <td>{{ l.detectCount }}</td>
              <td>
                <a
                  v-if="l.analysisId"
                  :href="`#/network?analysisId=${encodeURIComponent(l.analysisId)}&networkId=${l.networkId || 1}`"
                >打开</a>
                <span v-else>—</span>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else class="hint">该批无轨迹标签</p>
      </section>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import SceneBearingViz from "./SceneBearingViz.vue";
import ImportDataScatterViz from "./ImportDataScatterViz.vue";
import SceneResultsCharts from "./SceneResultsCharts.vue";
import { fetchVisualizationData } from "../scene/sceneApi.js";
import {
  alignTrajectoryViewsToSceneTabs,
  applyStreamTargetTypeLabelsToViews,
  buildDisplaySceneTabs,
  formatSceneFreq,
  sceneTypeLabel,
  targetTypeLabel
} from "../scene/sceneFilters.js";

const STREAM_BASE = (typeof window !== "undefined" && window.__STREAM_API_BASE__) || "http://localhost:19080";

const loading = ref(false);
const error = ref("");
const status = ref(null);
const batches = ref([]);
const selected = ref(null);

const vizLoading = ref(false);
const vizError = ref("");
const visualizationPayload = ref(null);
const activeBearingRank = ref(null);
let vizAbort = null;
let timer = null;

const sceneTabs = computed(() =>
  buildDisplaySceneTabs(selected.value?.scenes || [])
);

const alignedViews = computed(() =>
  applyStreamTargetTypeLabelsToViews(
    alignTrajectoryViewsToSceneTabs(
      visualizationPayload.value?.trajectoryViews || [],
      sceneTabs.value
    ),
    selected.value?.labels || []
  )
);

const importScatter = computed(() => visualizationPayload.value?.importScatter || null);
const importScatterError = computed(() => (vizError.value && !importScatter.value ? vizError.value : ""));

const chartRows = computed(() => {
  const rows = selected.value?.reportRows;
  if (Array.isArray(rows) && rows.length) return rows;
  return (selected.value?.labels || [])
    .filter((l) => l.targetId)
    .map((l) => ({
      sceneRank: l.sceneRank,
      sceneType: l.sceneType,
      analysisId: l.analysisId,
      networkId: l.networkId,
      networkFreqMhz: l.freqMhz,
      targetId: l.targetId,
      targetType: l.targetType,
      targetTypeLabel: l.targetTypeLabel,
      role: l.role,
      confidence: l.confidence,
      avgDutyCycle: l.dutyCycle,
      emissionSharePct: l.trafficSharePct,
      commLinkChannel: l.channel,
      commLinkChannelLabel: l.channelLabel,
      targetChannelsUsed: l.targetChannelsUsed || l.channelLabel || l.channel,
      detectCount: l.detectCount
    }));
});

const occupancySummary = computed(() => {
  const occ = selected.value?.occupancy;
  const items = [];
  const types = occ?.targetTypes || [];
  if (types.length) {
    items.push({
      key: "types",
      text: "目标类型：" + types.map((t) => `${t.label || targetTypeLabel(t.type) || t.type}×${t.targetCount}`).join(" · ")
    });
  }
  const channels = (occ?.channels || []).filter((c) => c.channel && c.channel !== "_");
  if (channels.length) {
    items.push({
      key: "ch",
      text: "波道占用：" + channels.map((c) => `${c.label || c.channel}×${c.targetCount}`).join(" · ")
    });
  }
  return items;
});

const bearingSummary = computed(() => ({
  detections:
    selected.value?.totalDetections
    || visualizationPayload.value?.totalDetections
    || 0,
  tracks:
    selected.value?.confirmedTracks
    || visualizationPayload.value?.confirmedTracks
    || 0
}));

watch(sceneTabs, (tabs) => {
  if (!tabs.length) {
    activeBearingRank.value = null;
    return;
  }
  const active = Number(activeBearingRank.value);
  if (!tabs.some((t) => Number(t.rank) === active)) {
    activeBearingRank.value = tabs[0].rank;
  }
}, { immediate: true });

async function selectBatch(b) {
  selected.value = b;
  await loadVisualization(b);
}

async function loadVisualization(batch) {
  vizAbort?.abort();
  visualizationPayload.value = null;
  vizError.value = "";
  const outputDir = batch?.sceneOutputDir;
  if (!outputDir || !batch?.scenes?.length) {
    return;
  }
  vizLoading.value = true;
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

async function reload() {
  loading.value = true;
  error.value = "";
  try {
    const [sRes, bRes] = await Promise.all([
      fetch(`${STREAM_BASE}/api/stream/status`),
      fetch(`${STREAM_BASE}/api/stream/batches`)
    ]);
    if (!sRes.ok) throw new Error(`status HTTP ${sRes.status}`);
    if (!bRes.ok) throw new Error(`batches HTTP ${bRes.status}`);
    status.value = await sRes.json();
    batches.value = await bRes.json();
    if (selected.value) {
      const next =
        batches.value.find((x) => x.streamBatchId === selected.value.streamBatchId) || selected.value;
      selected.value = next;
      const dirChanged = next.sceneOutputDir && next.sceneOutputDir !== visualizationPayload.value?._dir;
      if (dirChanged || (next.scenes?.length && !visualizationPayload.value)) {
        await loadVisualization(next);
        if (visualizationPayload.value) {
          visualizationPayload.value._dir = next.sceneOutputDir;
        }
      }
    }
  } catch (e) {
    status.value = null;
    error.value = e?.message || String(e);
  } finally {
    loading.value = false;
  }
}

function formatBytes(n) {
  const v = Number(n) || 0;
  if (v < 1024) return `${v} B`;
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`;
  return `${(v / 1024 / 1024).toFixed(2)} MB`;
}

function formatTime(iso) {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleString("zh-CN", { hour12: false });
  } catch {
    return String(iso);
  }
}

function formatWindow(startMs, endMs) {
  if (startMs == null && endMs == null) return "—";
  const a = startMs != null ? formatTime(startMs) : "?";
  const b = endMs != null ? formatTime(endMs) : "?";
  return `${a} ~ ${b}`;
}

function sceneFreqSuffix(rank) {
  const s = (selected.value?.scenes || []).find((x) => Number(x.rank) === Number(rank));
  const f = formatSceneFreq(s);
  return f ? ` · ${f}` : "";
}

function num(v, digits = 1) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(digits) : "—";
}

function pct(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return "—";
  return `${(n <= 1 ? n * 100 : n).toFixed(1)}%`;
}

onMounted(() => {
  reload();
  timer = setInterval(reload, 5000);
});

onUnmounted(() => {
  if (timer) clearInterval(timer);
  vizAbort?.abort();
});
</script>

<style scoped>
.stream-page { font-family: Arial, sans-serif; padding: 16px; max-width: 1280px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.sub { color: #6b7280; font-size: 13px; margin: 4px 0 0; }
.sub a { margin-left: 8px; }
.refresh { padding: 6px 12px; }
.card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px 14px; margin-top: 14px; }
.card h3 { margin: 0 0 10px; font-size: 15px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px 16px; font-size: 13px; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th, .tbl td { border-bottom: 1px solid #e5e7eb; padding: 6px 8px; text-align: left; }
.tbl tr { cursor: pointer; }
.tbl.no-pointer tr { cursor: default; }
.tbl tr.on { background: #eff6ff; }
.hint { color: #6b7280; font-size: 13px; }
.error { color: #b91c1c; font-size: 13px; }
.occ-grid { margin-bottom: 10px; }
</style>
