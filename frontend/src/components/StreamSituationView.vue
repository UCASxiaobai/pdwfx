<!-- vue2-done -->
<template>
  <AppShell mode="stream">
    <div class="pdwfx-page stream-page">
      <header class="head">
        <div>
          <h2>流式态势（独立模块）</h2>
          <p class="sub">
            TCP 收包 → 落盘切批 → 场景筛选/建轨 → 逐场景信号分析 → 预警机指挥网二次 → 标注。
          </p>
        </div>
        <div class="head-actions">
          <el-button
            type="warning"
            size="small"
            :disabled="restarting || loading"
            @click="restartStream"
          >
            {{ restarting ? "重启中…" : "重启接收" }}
          </el-button>
          <el-button
            size="small"
            :disabled="loading || restarting"
            @click="reload"
          >
            {{ loading ? "刷新中…" : "刷新" }}
          </el-button>
        </div>
      </header>

      <p v-if="error" class="cet36-error">{{ error }}</p>
      <p v-if="restartMsg" class="cet36-status ok">{{ restartMsg }}</p>

      <section class="cet36-panel">
        <header class="cet36-panel__title">
          <h3 class="cet36-panel__title-text">运行状态</h3>
        </header>
        <div class="cet36-panel__body">
          <div v-if="status" class="grid">
            <div>TCP：{{ status.tcpRunning ? "监听 " + status.tcpPort : "未启动" }}</div>
            <div>流水线：{{ status.pipeline || "—" }}</div>
            <div>收包：{{ status.packetsIn }} 包 / {{ formatBytes(status.bytesIn) }}</div>
            <div>
              解析记录：{{ parseRecords }}（丢弃非B108 {{ parseSkippedOther }} · DOA失败 {{ parseSkippedDoa }}）
            </div>
            <div>
              已写行：{{ status.writtenRows }}
              <span v-if="status.dedupEnabled !== false"> · 去重跳过 {{ status.skippedDupRows || 0 }}</span>
              · 已封批：{{ status.sealedBatches }}
            </div>
            <div>
              分析队列：{{ status.queueSize }} / {{ status.maxQueueFiles }}{{ status.queuePaused ? "（暂停收包）" : "" }}
            </div>
            <div>
              正在分析：{{ status.analyzingFile || "—" }}{{ status.analyzingPhase ? " · " + status.analyzingPhase : "" }}
            </div>
            <div>落盘目录：{{ status.batchDir }}</div>
            <div>分析服务：{{ status.analyzeBaseUrl }}</div>
            <div>
              场景窗：{{ sceneWindowLabel }}
              · TOP 连续 {{ sceneTopKTrack }}
              · TOP 轮询 {{ sceneTopKPolling }}
              · 网络详情 {{ status.preloadAll !== false ? "preloadAll" : "摘要" }}
              · 封批 {{ status.sealMode || "—" }}
              · 姿态 {{ status.attitudeState || "—" }}
              <span v-if="status.droppedManeuverRows"> · 高横滚丢点 {{ status.droppedManeuverRows }}</span>
            </div>
          </div>
          <p v-else class="cet36-hint">无法连接流式模块（默认 /stream-api）。请先启动 stream 进程。</p>
        </div>
      </section>

      <section class="cet36-panel">
        <header class="cet36-panel__title">
          <h3 class="cet36-panel__title-text">最近批结果</h3>
        </header>
        <div class="cet36-panel__body">
          <table v-if="batches.length" class="cet36-table tbl">
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
                :class="{ on: selected && selected.streamBatchId === b.streamBatchId }"
                @click="selectBatch(b)"
              >
                <td>{{ b.streamBatchId }}</td>
                <td>{{ b.status }}</td>
                <td>{{ b.sceneCount != null ? b.sceneCount : 0 }}</td>
                <td>{{ b.trackSceneCount != null ? b.trackSceneCount : 0 }} / {{ b.pollingSceneCount != null ? b.pollingSceneCount : 0 }}</td>
                <td>{{ b.networkCount }}</td>
                <td>{{ b.trackCount }}</td>
                <td>
                  <a
                    v-if="b.analysisId && b.networkCount"
                    :href="'#/network?analysisId=' + encodeURIComponent(b.analysisId) + '&networkId=1'"
                    @click.stop
                  >{{ b.analysisId.slice(0, 8) }}…</a>
                  <span v-else>{{ b.analysisId ? b.analysisId.slice(0, 8) + "…" : "—" }}</span>
                </td>
                <td>{{ formatTime(b.finishedAt) }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="cet36-hint">暂无已分析批次</p>
        </div>
      </section>

      <template v-if="selected">
        <section class="cet36-panel">
          <header class="cet36-panel__title">
            <h3 class="cet36-panel__title-text">批详情 · {{ selected.streamBatchId }}</h3>
          </header>
          <div class="cet36-panel__body">
            <p v-if="selected.error" class="cet36-error">{{ selected.error }}</p>
            <div class="grid">
              <div>场景目录：{{ selected.sceneOutputDir || "—" }}</div>
              <div>标注文件：{{ selected.annotationPath || "—" }}</div>
              <div>
                <a
                  v-if="selected.streamBatchId"
                  :href="annotationUrl"
                  target="_blank"
                  rel="noopener"
                  class="link"
                >打开标注 JSON</a>
              </div>
            </div>
          </div>
        </section>

        <section class="cet36-panel">
          <header class="cet36-panel__title">
            <h3 class="cet36-panel__title-text">场景摘要</h3>
          </header>
          <div class="cet36-panel__body">
            <table v-if="selectedScenes.length" class="cet36-table tbl no-pointer">
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
                <tr v-for="s in selectedScenes" :key="s.rank">
                  <td>#{{ s.rank }}</td>
                  <td>{{ sceneTypeLabel(s.sceneType) }}{{ s.skipped ? "（跳过）" : "" }}</td>
                  <td>{{ formatSceneFreq(s) || "—" }}</td>
                  <td>{{ s.trackCount != null ? s.trackCount : "—" }}</td>
                  <td>{{ s.networkCount != null ? s.networkCount : 0 }}</td>
                  <td>{{ formatWindow(s.windowStartMs, s.windowEndMs) }}</td>
                  <td>
                    <a
                      v-if="s.analysisId"
                      :href="'#/network?analysisId=' + encodeURIComponent(s.analysisId) + '&networkId=1'"
                      class="link"
                    >{{ s.analysisId.slice(0, 8) }}…</a>
                    <span v-else>—</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-else class="cet36-hint">该批尚无场景（筛选中或未筛出）</p>
          </div>
        </section>

        <section class="cet36-panel">
          <div class="cet36-panel__body">
            <ImportDataScatterViz
              v-if="importScatter || vizLoading || selected.sceneOutputDir"
              title="全量数据概览 · 频率筛选标绘"
              :scatter="importScatter"
              :loading="vizLoading && !importScatter"
              :error="importScatterError || (!vizLoading && !importScatter ? scatterHint : '')"
              embedded
            />
            <p v-else class="cet36-hint">{{ scatterHint || "等待场景筛选完成…" }}</p>
          </div>
        </section>

        <section class="cet36-panel">
          <header class="cet36-panel__title">
            <h3 class="cet36-panel__title-text">场景方位轨迹（开发校验）</h3>
          </header>
          <div class="cet36-panel__body">
            <p v-if="vizLoading" class="cet36-hint">加载轨迹数据…</p>
            <p v-else-if="vizError" class="cet36-error">{{ vizError }}</p>
            <SceneBearingViz
              v-else-if="sceneTabs.length"
              :scene-tabs="sceneTabs"
              :trajectory-views="alignedViews"
              :active-rank="activeBearingRank"
              :summary="bearingSummary"
              @update:activeRank="activeBearingRank = $event"
            />
            <p v-else class="cet36-hint">
              {{ selected.sceneOutputDir ? "无可用轨迹可视化（请确认 backend 已启动且 outputDir 存在）" : "等待场景筛选完成…" }}
            </p>
          </div>
        </section>

        <section class="cet36-panel">
          <header class="cet36-panel__title">
            <h3 class="cet36-panel__title-text">换频研判轨迹</h3>
          </header>
          <div class="cet36-panel__body">
            <p v-if="vizLoading" class="cet36-hint">加载换频研判数据…</p>
            <p v-else-if="vizError && !hoppingViews.length" class="cet36-error">{{ vizError }}</p>
            <SceneFreqHopTrackViz
              v-else-if="hoppingViews.length"
              :hopping-track-views="hoppingViews"
              :import-scatter="importScatter"
              :report-rows="chartRows"
              :show-channel-matrix="false"
            />
            <p v-else class="cet36-hint">
              {{ selected.sceneOutputDir ? "暂无换频研判数据（需新批次重新分析）" : "等待场景筛选完成…" }}
            </p>
          </div>
        </section>

        <section class="cet36-panel">
          <header class="cet36-panel__title">
            <h3 class="cet36-panel__title-text">预警机指挥网</h3>
          </header>
          <div class="cet36-panel__body">
            <p v-if="commandNetSkipHint" class="cet36-hint">{{ commandNetSkipHint }}</p>
            <SceneAwacsCommandNetViz v-else :command-net-pass="selected.commandNetPass" />
          </div>
        </section>

        <section class="cet36-panel">
          <header class="cet36-panel__title">
            <h3 class="cet36-panel__title-text">目标类型与目标-波道表</h3>
          </header>
          <div class="cet36-panel__body">
            <div v-if="occupancySummary.length" class="grid occ-grid">
              <div v-for="item in occupancySummary" :key="item.key">{{ item.text }}</div>
            </div>
            <SceneResultsCharts
              v-if="chartRows.length || hoppingViews.length"
              :rows="chartRows"
              :hopping-track-views="hoppingViews"
              :scene-grouped="false"
            />
            <p v-else class="cet36-hint">该批尚无目标类型/波道研判结果（需 preloadAll 后重新分析）</p>
          </div>
        </section>

        <section class="cet36-panel">
          <header class="cet36-panel__title">
            <h3 class="cet36-panel__title-text">目标标签 · {{ selected.streamBatchId }}</h3>
          </header>
          <div class="cet36-panel__body">
            <table v-if="selectedLabels.length" class="cet36-table tbl no-pointer">
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
                <tr v-for="l in selectedLabels" :key="l.batchId">
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
                      :href="'#/network?analysisId=' + encodeURIComponent(l.analysisId) + '&networkId=' + (l.networkId || 1)"
                      class="link"
                    >打开</a>
                    <span v-else>—</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-else class="cet36-hint">该批无轨迹标签</p>
          </div>
        </section>
      </template>
    </div>
  </AppShell>
</template>

<script>
import AppShell from "./shell/AppShell.vue";
import SceneBearingViz from "./SceneBearingViz.vue";
import SceneFreqHopTrackViz from "./SceneFreqHopTrackViz.vue";
import SceneAwacsCommandNetViz from "./SceneAwacsCommandNetViz.vue";
import ImportDataScatterViz from "./ImportDataScatterViz.vue";
import SceneResultsCharts from "./SceneResultsCharts.vue";
import { streamApiUrl } from "@/api/streamBase.js";
import { fetchVisualizationData } from "@/scene/sceneApi.js";
import {
  alignTrajectoryViewsToSceneTabs,
  applyStreamTargetTypeLabelsToViews,
  buildDisplaySceneTabs,
  formatSceneFreq,
  sceneTypeLabel,
  targetTypeLabel
} from "@/scene/sceneFilters.js";

export default {
  name: "StreamSituationView",
  components: {
    AppShell,
    SceneBearingViz,
    SceneFreqHopTrackViz,
    SceneAwacsCommandNetViz,
    ImportDataScatterViz,
    SceneResultsCharts
  },
  data() {
    return {
      loading: false,
      restarting: false,
      error: "",
      restartMsg: "",
      status: null,
      batches: [],
      selected: null,
      vizLoading: false,
      vizError: "",
      visualizationPayload: null,
      activeBearingRank: null
    };
  },
  computed: {
    parseRecords() {
      return (this.status && this.status.parse && this.status.parse.records) || 0;
    },
    parseSkippedOther() {
      return (this.status && this.status.parse && this.status.parse.skippedOtherType) || 0;
    },
    parseSkippedDoa() {
      return (this.status && this.status.parse && this.status.parse.skippedDoa) || 0;
    },
    sceneWindowLabel() {
      if (!this.status || !this.status.scene) return "—";
      if (this.status.scene.fullSpanWindow !== false) return "全段（=本批实际跨度）";
      return "滑动 " + (this.status.scene.windowSeconds != null ? this.status.scene.windowSeconds : "—") + "s";
    },
    sceneTopKTrack() {
      return (this.status && this.status.scene && this.status.scene.topKTrackScenes) || "—";
    },
    sceneTopKPolling() {
      return (this.status && this.status.scene && this.status.scene.topKPollingScenes) || "—";
    },
    selectedScenes() {
      return (this.selected && this.selected.scenes) || [];
    },
    selectedLabels() {
      return (this.selected && this.selected.labels) || [];
    },
    annotationUrl() {
      if (!this.selected || !this.selected.streamBatchId) return "";
      return streamApiUrl(
        "/api/stream/batches/" + encodeURIComponent(this.selected.streamBatchId) + "/annotation"
      );
    },
    sceneTabs() {
      const scenes = (this.selected && this.selected.scenes) || [];
      return buildDisplaySceneTabs(scenes.filter(function (s) { return s.sceneType !== "COMMAND_NET"; }));
    },
    alignedViews() {
      const views = (this.visualizationPayload && this.visualizationPayload.trajectoryViews) || [];
      const labels = (this.selected && this.selected.labels) || [];
      return applyStreamTargetTypeLabelsToViews(
        alignTrajectoryViewsToSceneTabs(views, this.sceneTabs),
        labels
      );
    },
    importScatter() {
      return (this.visualizationPayload && this.visualizationPayload.importScatter) || null;
    },
    importScatterError() {
      return this.vizError && !this.importScatter ? this.vizError : "";
    },
    scatterHint() {
      if (!this.selected) return "";
      if (this.vizLoading) return "";
      if (this.importScatter) return "";
      if (this.vizError) return this.vizError;
      if (this.selected.sceneOutputDir) {
        return "暂无全量散点（需新批次重新分析后生成 importScatter）";
      }
      return "等待场景筛选完成…";
    },
    hoppingViews() {
      const views = (this.visualizationPayload && this.visualizationPayload.hoppingTrackViews) || [];
      const labels = (this.selected && this.selected.labels) || [];
      return applyStreamTargetTypeLabelsToViews(views, labels);
    },
    chartRows() {
      const rows = this.selected && this.selected.reportRows;
      if (Array.isArray(rows) && rows.length) return rows;
      return ((this.selected && this.selected.labels) || [])
        .filter(function (l) { return l.targetId; })
        .map(function (l) {
          return {
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
          };
        });
    },
    commandNetSkipHint() {
      const pass = this.selected && this.selected.commandNetPass;
      const batchStatus = this.selected && this.selected.status;
      if (!pass || (typeof pass === "object" && !Object.keys(pass).length)) {
        if (batchStatus === "COMMAND_NET") return "指挥网二次分析中…";
        if (batchStatus === "PROCESS_SCENE" || batchStatus === "SCENE_FILTER") {
          return "等待一次分析完成后自动进行指挥网二次…";
        }
        return "该批尚无指挥网二次结果（需新批次重新分析）";
      }
      if (pass.skipped) {
        return "指挥网二次跳过：" + (pass.skipReason || "未知原因");
      }
      if (!((pass.awacsPanels || []).length)) {
        return "指挥网二次已完成，但没有可展示的预警机占用窗";
      }
      return "";
    },
    occupancySummary() {
      const occ = this.selected && this.selected.occupancy;
      const items = [];
      const types = (occ && occ.targetTypes) || [];
      if (types.length) {
        items.push({
          key: "types",
          text: "目标类型：" + types.map(function (t) {
            return (t.label || targetTypeLabel(t.type) || t.type) + "×" + t.targetCount;
          }).join(" · ")
        });
      }
      const channels = ((occ && occ.channels) || []).filter(function (c) {
        return c.channel && c.channel !== "_";
      });
      if (channels.length) {
        items.push({
          key: "ch",
          text: "波道占用：" + channels.map(function (c) {
            return (c.label || c.channel) + "×" + c.targetCount;
          }).join(" · ")
        });
      }
      return items;
    },
    bearingSummary() {
      return {
        detections:
          (this.selected && this.selected.totalDetections)
          || (this.visualizationPayload && this.visualizationPayload.totalDetections)
          || 0,
        tracks:
          (this.selected && this.selected.confirmedTracks)
          || (this.visualizationPayload && this.visualizationPayload.confirmedTracks)
          || 0
      };
    }
  },
  watch: {
    sceneTabs: {
      handler: function (tabs) {
        if (!tabs.length) {
          this.activeBearingRank = null;
          return;
        }
        const active = Number(this.activeBearingRank);
        if (!tabs.some(function (t) { return Number(t.rank) === active; })) {
          this.activeBearingRank = tabs[0].rank;
        }
      },
      immediate: true
    }
  },
  mounted: function () {
    this.reload();
    this.timer = setInterval(this.reload, 5000);
  },
  beforeDestroy: function () {
    if (this.timer) clearInterval(this.timer);
    if (this.vizAbort) this.vizAbort.abort();
  },
  methods: {
    sceneTypeLabel: sceneTypeLabel,
    formatSceneFreq: formatSceneFreq,
    async selectBatch(b) {
      this.selected = b;
      await this.loadVisualization(b);
    },
    async restartStream() {
      try {
        await this.$confirm(
          "将清除最近批结果与运行计数，丢弃未分析队列和当前开批，然后继续接收新数据。是否继续？",
          "重启接收",
          { type: "warning", confirmButtonText: "确定", cancelButtonText: "取消" }
        );
      } catch (e) {
        return;
      }
      this.restarting = true;
      this.restartMsg = "";
      this.error = "";
      try {
        const res = await fetch(streamApiUrl("/api/stream/restart"), { method: "POST" });
        const text = await res.text();
        var body = null;
        try {
          body = text ? JSON.parse(text) : null;
        } catch (parseErr) {
          body = null;
        }
        if (!res.ok) {
          throw new Error((body && body.error) || text || "HTTP " + res.status);
        }
        this.selected = null;
        this.batches = [];
        this.visualizationPayload = null;
        this.vizError = "";
        const q = (body && body.discardedQueueCount) || 0;
        const open = body && body.discardedOpen ? " · 丢弃开批 " + body.discardedOpen : "";
        this.restartMsg =
          ((body && body.message) || "已重启") +
          (q ? " · 丢弃队列 " + q + " 个" : "") +
          open;
        await this.reload();
      } catch (e) {
        this.error = "重启失败：" + ((e && e.message) || e);
      } finally {
        this.restarting = false;
      }
    },
    batchDisplayFingerprint: function (b) {
      if (!b) return "";
      const scenes = b.scenes || [];
      const labels = b.labels || [];
      return [
        b.streamBatchId,
        b.sceneOutputDir || "",
        b.completedAt || b.finishedAt || "",
        scenes.length,
        scenes.map(function (s) { return s.rank + ":" + (s.sceneType || ""); }).join(","),
        labels.length,
        (b.reportRows && b.reportRows.length) || 0,
        b.totalDetections || 0,
        b.confirmedTracks || 0,
        b.commandNetPass && b.commandNetPass.skipped ? "1" : "0",
        (b.commandNetPass && b.commandNetPass.awacsPanels && b.commandNetPass.awacsPanels.length) || 0,
        (b.commandNetPass && b.commandNetPass.skipReason) || ""
      ].join("|");
    },
    async loadVisualization(batch) {
      if (this.vizAbort) this.vizAbort.abort();
      this.vizError = "";
      const outputDir = batch && batch.sceneOutputDir;
      const batchId = batch && batch.streamBatchId;
      if (!outputDir || !(batch && batch.scenes && batch.scenes.length)) {
        this.visualizationPayload = null;
        return;
      }
      this.vizLoading = true;
      this.vizAbort = new AbortController();
      try {
        var payload = null;
        if (batchId) {
          try {
            const res = await fetch(
              streamApiUrl("/api/stream/batches/" + encodeURIComponent(batchId) + "/visualization-data"),
              { signal: this.vizAbort.signal }
            );
            if (res.ok) {
              payload = await res.json();
            }
          } catch (e) {
            if (e && e.name === "AbortError") throw e;
          }
        }
        if (!payload) {
          payload = await fetchVisualizationData(outputDir, this.vizAbort.signal);
        }
        if (payload) payload._dir = outputDir;
        this.visualizationPayload = payload;
      } catch (e) {
        if (e && e.name === "AbortError") return;
        this.visualizationPayload = null;
        this.vizError = "轨迹数据加载失败：" + ((e && e.message) || e);
      } finally {
        this.vizLoading = false;
      }
    },
    async reload() {
      this.loading = true;
      this.error = "";
      try {
        const statusUrl = streamApiUrl("/api/stream/status");
        const batchesUrl = streamApiUrl("/api/stream/batches");
        const results = await Promise.all([
          fetch(statusUrl),
          fetch(batchesUrl)
        ]);
        const sRes = results[0];
        const bRes = results[1];
        // #region agent log
        fetch("http://127.0.0.1:7901/ingest/e16fb981-fe8c-4a2f-8b90-e593d79414a3",{method:"POST",headers:{"Content-Type":"application/json","X-Debug-Session-Id":"b9c0b8"},body:JSON.stringify({sessionId:"b9c0b8",runId:"post-fix",hypothesisId:"P",location:"StreamSituationView.vue:reload",message:"stream fetch result",data:{statusUrl:statusUrl,batchesUrl:batchesUrl,statusHttp:sRes.status,batchesHttp:bRes.status,streamBase:typeof window!=="undefined"?window.__STREAM_API_BASE__:""},timestamp:Date.now()})}).catch(function(){});
        fetch("/__agent_log",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({sessionId:"b9c0b8",runId:"post-fix",hypothesisId:"P",location:"StreamSituationView.vue:reload",message:"stream fetch result",data:{statusUrl:statusUrl,batchesUrl:batchesUrl,statusHttp:sRes.status,batchesHttp:bRes.status,streamBase:typeof window!=="undefined"?window.__STREAM_API_BASE__:""},timestamp:Date.now()})}).catch(function(){});
        // #endregion
        if (!sRes.ok) throw new Error("status HTTP " + sRes.status);
        if (!bRes.ok) throw new Error("batches HTTP " + bRes.status);
        this.status = await sRes.json();
        this.batches = await bRes.json();
        if (this.selected) {
          const self = this;
          const next =
            this.batches.find(function (x) {
              return x.streamBatchId === self.selected.streamBatchId;
            }) || this.selected;
          const prevFp = this.batchDisplayFingerprint(this.selected);
          const nextFp = this.batchDisplayFingerprint(next);
          const selectedUnchanged = prevFp === nextFp;
          const dirChanged =
            next.sceneOutputDir &&
            next.sceneOutputDir !== (this.visualizationPayload && this.visualizationPayload._dir);
          const needViz = Boolean(dirChanged || (next.scenes && next.scenes.length && !this.visualizationPayload));
          if (!selectedUnchanged) {
            this.selected = next;
          }
          if (needViz) {
            await this.loadVisualization(next);
          }
        }
      } catch (e) {
        this.status = null;
        this.error = (e && e.message) || String(e);
      } finally {
        this.loading = false;
      }
    },
    formatBytes: function (n) {
      const v = Number(n) || 0;
      if (v < 1024) return v + " B";
      if (v < 1024 * 1024) return (v / 1024).toFixed(1) + " KB";
      return (v / 1024 / 1024).toFixed(2) + " MB";
    },
    formatTime: function (iso) {
      if (!iso) return "—";
      try {
        return new Date(iso).toLocaleString("zh-CN", { hour12: false });
      } catch (e) {
        return String(iso);
      }
    },
    formatWindow: function (startMs, endMs) {
      if (startMs == null && endMs == null) return "—";
      const a = startMs != null ? this.formatTime(startMs) : "?";
      const b = endMs != null ? this.formatTime(endMs) : "?";
      return a + " ~ " + b;
    },
    sceneFreqSuffix: function (rank) {
      const scenes = (this.selected && this.selected.scenes) || [];
      const s = scenes.find(function (x) { return Number(x.rank) === Number(rank); });
      const f = formatSceneFreq(s);
      return f ? " · " + f : "";
    },
    num: function (v, digits) {
      const d = digits == null ? 1 : digits;
      const n = Number(v);
      return Number.isFinite(n) ? n.toFixed(d) : "—";
    },
    pct: function (v) {
      const n = Number(v);
      if (!Number.isFinite(n)) return "—";
      return (n <= 1 ? n * 100 : n).toFixed(1) + "%";
    }
  }
};
</script>

<style scoped>
.stream-page {
  max-width: 1280px;
  margin: 0 auto;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
}
.head h2 {
  margin: 0 0 4px;
  font-size: 18px;
  color: var(--theme-text-accent);
}
.head-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.sub {
  color: var(--theme-text-secondary);
  font-size: 13px;
  margin: 0;
  line-height: 1.5;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 8px 16px;
  font-size: 13px;
  color: var(--theme-text-secondary);
}
.tbl tr {
  cursor: pointer;
}
.tbl.no-pointer tr {
  cursor: default;
}
.tbl tr.on td {
  background: var(--theme-bg-table-current);
}
.link {
  color: var(--theme-text-link);
}
.ok {
  color: var(--theme-accent-bright);
}
.occ-grid {
  margin-bottom: 10px;
}
</style>
