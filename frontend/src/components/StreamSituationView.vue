<template>
  <div class="stream-page">
    <header class="head">
      <div>
        <h2>流式态势（独立模块）</h2>
        <p class="sub">
          TCP 收包 → 落盘切批 → 场景筛选/建轨 → 逐场景信号分析 → 预警机指挥网二次 → 标注。
          <a href="#/">返回主流程</a>
        </p>
      </div>
      <div class="head-actions">
        <button
          type="button"
          class="restart"
          :disabled="restarting || loading"
          @click="restartStream"
        >
          {{ restarting ? "重启中…" : "重启接收" }}
        </button>
        <button type="button" class="refresh" :disabled="loading || restarting" @click="reload">
          {{ loading ? "刷新中…" : "刷新" }}
        </button>
      </div>
    </header>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="restartMsg" class="ok">{{ restartMsg }}</p>

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
          · 封批 {{ status.sealMode || "—" }}
          · 姿态 {{ status.attitudeState || "—" }}
          <span v-if="status.droppedManeuverRows"> · 高横滚丢点 {{ status.droppedManeuverRows }}</span>
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
        <template v-if="selected">
          <ImportDataScatterViz
            v-if="importScatter || vizLoading || selected.sceneOutputDir"
            title="全量数据概览 · 频率筛选标绘"
            :scatter="importScatter"
            :loading="vizLoading && !importScatter"
            :error="importScatterError || (!vizLoading && !importScatter ? scatterHint : '')"
            embedded
          />
          <p v-else class="hint">{{ scatterHint || "等待场景筛选完成…" }}</p>
        </template>
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
        <h3>换频研判轨迹</h3>
        <p v-if="vizLoading" class="hint">加载换频研判数据…</p>
        <p v-else-if="vizError && !hoppingViews.length" class="error">{{ vizError }}</p>
        <SceneFreqHopTrackViz
          v-else-if="hoppingViews.length"
          :hopping-track-views="hoppingViews"
          :import-scatter="importScatter"
          :report-rows="chartRows"
          :show-channel-matrix="false"
        />
        <p v-else class="hint">
          {{ selected.sceneOutputDir ? "暂无换频研判数据（需新批次重新分析）" : "等待场景筛选完成…" }}
        </p>
      </section>

      <section class="card">
        <h3>预警机指挥网</h3>
        <p v-if="commandNetSkipHint" class="hint">{{ commandNetSkipHint }}</p>
        <SceneAwacsCommandNetViz v-else :command-net-pass="selected.commandNetPass" />
      </section>

      <section class="card">
        <h3>目标类型与目标-波道表</h3>
        <div v-if="occupancySummary.length" class="grid occ-grid">
          <div v-for="item in occupancySummary" :key="item.key">{{ item.text }}</div>
        </div>
        <SceneResultsCharts
          v-if="chartRows.length || hoppingViews.length"
          :rows="chartRows"
          :hopping-track-views="hoppingViews"
          :scene-grouped="false"
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
import SceneFreqHopTrackViz from "./SceneFreqHopTrackViz.vue";
import SceneAwacsCommandNetViz from "./SceneAwacsCommandNetViz.vue";
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
import { formatHopTrackFreqLabel } from "../scene/hopChannelMatrix.js";

const STREAM_BASE = (typeof window !== "undefined" && window.__STREAM_API_BASE__) || "http://localhost:19080";

const loading = ref(false);
const restarting = ref(false);
const error = ref("");
const restartMsg = ref("");
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
  buildDisplaySceneTabs(
    (selected.value?.scenes || []).filter((s) => s.sceneType !== "COMMAND_NET")
  )
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
const scatterHint = computed(() => {
  if (!selected.value) return "";
  if (vizLoading.value) return "";
  if (importScatter.value) return "";
  if (vizError.value) return vizError.value;
  if (selected.value.sceneOutputDir) {
    return "暂无全量散点（需新批次重新分析后生成 importScatter）";
  }
  return "等待场景筛选完成…";
});
const hoppingViews = computed(() =>
  applyStreamTargetTypeLabelsToViews(
    visualizationPayload.value?.hoppingTrackViews || [],
    selected.value?.labels || []
  )
);

// #region agent log
watch(
  [() => selected.value?.streamBatchId, hoppingViews, () => selected.value?.scenes, () => selected.value?.labels, () => selected.value?.reportRows],
  () => {
    const batchId = selected.value?.streamBatchId || "";
    if (!batchId || !String(batchId).includes("1787121803459")) return;
    const scenes = (selected.value?.scenes || []).map((s) => ({
      rank: s.rank,
      freq: s.freqCenterMhz,
      type: s.sceneType
    }));
    const sceneFreqs = scenes.map((s) => Number(s.freq)).filter((n) => Number.isFinite(n));
    const near460 = sceneFreqs.filter((f) => Math.abs(f - 460.625) < 0.5);
    const hasExact460 = sceneFreqs.some((f) => Math.abs(f - 460.625) <= 0.01);
    const has246 = sceneFreqs.some((f) => Math.abs(f - 246.075) <= 0.01);
    const tracks = [];
    for (const v of hoppingViews.value || []) {
      for (const t of v.tracks || []) {
        const freqs = [];
        for (const p of t.points || []) {
          const f = Number(p.freqMhz);
          if (Number.isFinite(f) && freqs.indexOf(f) < 0) freqs.push(f);
        }
        const hops = (t.hops || []).map((h) => ({
          from: h.fromFreqMhz,
          to: h.toFreqMhz,
          fromTrackId: h.fromTrackId,
          toTrackId: h.toTrackId
        }));
        const hit460 = freqs.some((f) => Math.abs(f - 460.625) <= 0.01);
        const hit246 = freqs.some((f) => Math.abs(f - 246.075) <= 0.01);
        if (hit460 || hit246 || /目标3/.test(String(t.label || ""))) {
          tracks.push({
            label: t.label,
            targetType: t.targetType,
            targetTypeLabel: t.targetTypeLabel,
            seedFreq: t.seedFreqMhz,
            linkedTrackIds: t.linkedTrackIds,
            freqs,
            hops,
            hasFreqHop: t.hasFreqHop
          });
        }
      }
    }
    const labels = (selected.value?.labels || [])
      .filter((l) => Math.abs(Number(l.freqMhz) - 246.075) <= 0.01 || Math.abs(Number(l.freqMhz) - 460.625) <= 0.01 || /AWACS|预警/.test(String(l.targetTypeLabel || l.targetType || "")))
      .map((l) => ({
        targetId: l.targetId,
        type: l.targetType,
        typeLabel: l.targetTypeLabel,
        freq: l.freqMhz,
        sceneRank: l.sceneRank
      }));
    const reportFreqs = (selected.value?.reportRows || []).map((r) => r.networkFreqMhz);
    fetch("http://127.0.0.1:7901/ingest/e16fb981-fe8c-4a2f-8b90-e593d79414a3", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Debug-Session-Id": "0cb39e" },
      body: JSON.stringify({
        sessionId: "0cb39e",
        runId: "post-fix",
        hypothesisId: "A,D-fix",
        location: "StreamSituationView.vue:hop-vs-scene",
        message: "batch hop freqs vs scene/analysis freqs",
        data: {
          batchId,
          sceneCount: scenes.length,
          hasExactScene460: hasExact460,
          hasScene246: has246,
          scenesNear460: near460,
          sceneFreqSample: sceneFreqs.slice(0, 30),
          hopTracksOfInterest: tracks,
          hopLabelsAfterFix: (tracks || []).map((t) =>
            formatHopTrackFreqLabel(
              t.label || "目标",
              { points: (t.freqs || []).map((f) => ({ freqMhz: f })) },
              selected.value?.reportRows || selected.value?.labels || []
            )
          ),
          analysisLabelsNear: labels,
          reportHas460: reportFreqs.some((f) => Math.abs(Number(f) - 460.625) <= 0.01),
          reportHas246: reportFreqs.some((f) => Math.abs(Number(f) - 246.075) <= 0.01)
        },
        timestamp: Date.now()
      })
    }).catch(() => {});
  },
  { deep: true }
);
// #endregion

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

const commandNetSkipHint = computed(() => {
  const pass = selected.value?.commandNetPass;
  const status = selected.value?.status;
  if (!pass || (typeof pass === "object" && !Object.keys(pass).length)) {
    if (status === "COMMAND_NET") return "指挥网二次分析中…";
    if (status === "PROCESS_SCENE" || status === "SCENE_FILTER") {
      return "等待一次分析完成后自动进行指挥网二次…";
    }
    return "该批尚无指挥网二次结果（需新批次重新分析）";
  }
  if (pass.skipped) {
    return `指挥网二次跳过：${pass.skipReason || "未知原因"}`;
  }
  if (!((pass.awacsPanels || []).length)) {
    return "指挥网二次已完成，但没有可展示的预警机占用窗";
  }
  return "";
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

async function restartStream() {
  const ok = window.confirm(
    "将清除最近批结果与运行计数，丢弃未分析队列和当前开批，然后继续接收新数据。是否继续？"
  );
  if (!ok) return;
  restarting.value = true;
  restartMsg.value = "";
  error.value = "";
  try {
    const res = await fetch(`${STREAM_BASE}/api/stream/restart`, { method: "POST" });
    const text = await res.text();
    let body = null;
    try {
      body = text ? JSON.parse(text) : null;
    } catch {
      body = null;
    }
    if (!res.ok) {
      throw new Error((body && body.error) || text || `HTTP ${res.status}`);
    }
    selected.value = null;
    batches.value = [];
    visualizationPayload.value = null;
    vizError.value = "";
    const q = body?.discardedQueueCount ?? 0;
    const open = body?.discardedOpen ? ` · 丢弃开批 ${body.discardedOpen}` : "";
    restartMsg.value =
      (body?.message || "已重启") +
      (q ? ` · 丢弃队列 ${q} 个` : "") +
      open;
    await reload();
  } catch (e) {
    error.value = `重启失败：${e?.message || e}`;
  } finally {
    restarting.value = false;
  }
}

function batchDisplayFingerprint(b) {
  if (!b) return "";
  const scenes = b.scenes || [];
  const labels = b.labels || [];
  return [
    b.streamBatchId,
    b.sceneOutputDir || "",
    b.completedAt || b.finishedAt || "",
    scenes.length,
    scenes.map((s) => `${s.rank}:${s.sceneType || ""}`).join(","),
    labels.length,
    b.reportRows?.length || 0,
    b.totalDetections || 0,
    b.confirmedTracks || 0,
    b.commandNetPass?.skipped ? "1" : "0",
    (b.commandNetPass?.awacsPanels || []).length,
    b.commandNetPass?.skipReason || ""
  ].join("|");
}

async function loadVisualization(batch) {
  vizAbort?.abort();
  vizError.value = "";
  const outputDir = batch?.sceneOutputDir;
  const batchId = batch?.streamBatchId;
  if (!outputDir || !batch?.scenes?.length) {
    visualizationPayload.value = null;
    return;
  }
  vizLoading.value = true;
  vizAbort = new AbortController();
  try {
    let payload = null;
    // 优先从 stream 本机读 visualization-data.json（含频率筛选所需 importScatter）
    if (batchId) {
      try {
        const res = await fetch(
          `${STREAM_BASE}/api/stream/batches/${encodeURIComponent(batchId)}/visualization-data`,
          { signal: vizAbort.signal }
        );
        if (res.ok) {
          payload = await res.json();
        }
      } catch (e) {
        if (e?.name === "AbortError") throw e;
      }
    }
    if (!payload) {
      payload = await fetchVisualizationData(outputDir, vizAbort.signal);
    }
    if (payload) payload._dir = outputDir;
    visualizationPayload.value = payload;
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
      const prevFp = batchDisplayFingerprint(selected.value);
      const nextFp = batchDisplayFingerprint(next);
      const selectedUnchanged = prevFp === nextFp;
      const dirChanged = next.sceneOutputDir && next.sceneOutputDir !== visualizationPayload.value?._dir;
      const needViz = Boolean(dirChanged || (next.scenes?.length && !visualizationPayload.value));
      if (!selectedUnchanged) {
        selected.value = next;
      }
      // 内容未变时不换 selected，避免 hoppingViews 新引用触发图表重置；仍补拉缺失 viz
      if (needViz) {
        await loadVisualization(next);
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
.head-actions { display: flex; gap: 8px; flex-shrink: 0; }
.sub { color: #6b7280; font-size: 13px; margin: 4px 0 0; }
.sub a { margin-left: 8px; }
.refresh, .restart { padding: 6px 12px; cursor: pointer; }
.restart {
  background: #fff7ed;
  border: 1px solid #fdba74;
  color: #c2410c;
  border-radius: 4px;
}
.restart:disabled, .refresh:disabled { opacity: 0.6; cursor: not-allowed; }
.refresh { border: 1px solid #d1d5db; border-radius: 4px; background: #fff; }
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
.ok { color: #047857; font-size: 13px; }
.occ-grid { margin-bottom: 10px; }
</style>
