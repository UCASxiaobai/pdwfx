<!-- vue2-done -->
<template>
  <div class="results-panel">
    <p v-if="loading" class="loading-banner">正在按场景汇总表格数据，可先使用下方「信号分析」浏览网络…</p>

    <ThemePanel title="结果筛选" :label-index="3">
      <div class="filter-grid">
        <label>
          场景
          <el-select v-model="localFilters.sceneRank" placeholder="全部" clearable size="small">
            <el-option label="全部" value="" />
            <el-option
              v-for="opt in sceneRankOptions"
              :key="opt.rank"
              :label="formatSceneLabel(opt.rank, opt.sceneType, opt)"
              :value="String(opt.rank)"
            />
          </el-select>
        </label>
        <label>
          目标类型
          <el-select v-model="localFilters.targetType" size="small">
            <el-option
              v-for="o in targetTypeOptions"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </label>
        <label>
          波道
          <el-select v-model="localFilters.commLink" placeholder="全部" clearable size="small">
            <el-option label="全部" value="" />
            <el-option v-for="c in commLinkOptions" :key="c" :label="c" :value="c" />
          </el-select>
        </label>
        <label>
          频率下限 MHz
          <el-input v-model="localFilters.freqMin" type="number" size="small" />
        </label>
        <label>
          频率上限 MHz
          <el-input v-model="localFilters.freqMax" type="number" size="small" />
        </label>
        <label>
          时间起
          <el-input v-model="localFilters.timeStart" type="datetime-local" size="small" />
        </label>
        <label>
          时间止
          <el-input v-model="localFilters.timeEnd" type="datetime-local" size="small" />
        </label>
      </div>
      <p class="stats-line">
        <template v-if="hasReportRows">
          筛选后 {{ filteredRows.length }} 条明细 · {{ stats.sceneCount }} 个场景
        </template>
        <template v-else>
          场景筛选 {{ (sceneResult && sceneResult.scenes && sceneResult.scenes.length) || 0 }} 个 · 当前显示 {{ bearingAllowedRanks.size }} 个轨迹图
        </template>
      </p>

      <p v-if="vizLoading" class="viz-hint">正在加载场景方位轨迹数据…</p>
      <SceneBearingViz
        v-else-if="visualizationPayload"
        :active-rank="activeBearingSceneRank"
        @update:active-rank="activeBearingSceneRank = $event"
        :scene-tabs="unifiedSceneTabs"
        :trajectory-views="alignedTrajectoryViews"
        :summary="bearingSummary"
      />
      <p v-else-if="sceneResult && sceneResult.scenes && sceneResult.scenes.length && vizError" class="viz-hint">{{ vizError }}</p>

      <SceneAnalysisBearingViz
        v-if="showAnalysisBearing"
        :active-rank="activeBearingSceneRank"
        @update:active-rank="activeBearingSceneRank = $event"
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
    </ThemePanel>

    <ThemePanel v-if="hasReportRows && (!loading || chartRows.length)" title="统计分析" :label-index="5">
      <SceneResultsCharts
        ref="chartsRef"
        :rows="chartRows"
      />
    </ThemePanel>

    <ThemePanel v-if="hasReportRows" title="明细表格" :label-index="4">
      <div class="table-head">
        <el-button type="primary" size="small" :disabled="exporting" @click="onExport">
          {{ exporting ? "正在生成…" : "导出 Word 报告" }}
        </el-button>
      </div>
      <div class="table-scroll report-table-wrap">
        <table class="cet36-table report-table">
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
              <td class="num">{{ row.detectCount != null ? row.detectCount : "—" }}</td>
              <td>{{ row.role || "—" }}</td>
              <td class="num">{{ formatConfidence(row.confidence) }}</td>
              <td class="num">{{ formatShare(row.emissionSharePct) }}</td>
              <td>
                <el-button type="text" size="mini" @click.stop="emitSelectRow(row)">图表</el-button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="tableRows.length > pageSize" class="pager">
        <el-button size="mini" :disabled="page <= 1" @click="page--">上一页</el-button>
        <span>{{ page }} / {{ totalPages }}</span>
        <el-button size="mini" :disabled="page >= totalPages" @click="page++">下一页</el-button>
      </div>
    </ThemePanel>
  </div>
</template>

<script>
import ThemePanel from "./shell/ThemePanel.vue";
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
import { fetchVisualizationData } from "../api/pdwfx.js";
import { exportAnalysisDocx } from "../scene/exportDocx.js";
import { debounce, sampleRowsForCharts } from "../scene/reportUtils.js";
import { applyMergeRowspans, buildFormattedTable } from "../scene/reportTableFormat.js";

export default {
  name: "SceneResultsPanel",
  components: {
    ThemePanel,
    SceneBearingViz,
    SceneAnalysisBearingViz,
    SceneCrossFreqMatchViz,
    SceneResultsCharts,
    SceneFreqHopTrackViz,
    SceneAwacsCommandNetViz
  },
  props: {
    report: { type: Object, default: () => ({ rows: [], buildTimeMs: 0 }) },
    sceneResult: { type: Object, default: null },
    forwardItems: { type: Array, default: () => [] },
    commandNetPass: { type: Object, default: null },
    loading: { type: Boolean, default: false },
    activeRowKey: { type: String, default: "" }
  },
  data() {
    return {
      exporting: false,
      visualizationPayload: null,
      vizLoading: false,
      vizError: "",
      vizAbort: null,
      page: 1,
      pageSize: 50,
      activeBearingSceneRank: null,
      chartRows: [],
      localFilters: {
        sceneRank: "",
        targetType: "",
        commLink: "",
        freqMin: "",
        freqMax: "",
        timeStart: "",
        timeEnd: ""
      },
      targetTypeOptions: TARGET_TYPE_OPTIONS,
      formatSceneLabel,
      formatFreq,
      targetTypeLabel
    };
  },
  computed: {
    allRows() {
      return (this.report && this.report.rows) || [];
    },
    hasReportRows() {
      return this.allRows.length > 0;
    },
    showAnalysisBearing() {
      return (
        this.hasReportRows
        && !(this.report && this.report.partial)
        && !this.loading
        && this.forwardItems.length > 0
      );
    },
    filteredRows() {
      return filterReportRows(this.allRows, this.localFilters);
    },
    analyzedRanks() {
      const set = new Set();
      for (const item of this.forwardItems || []) {
        const r = Number(item.rank);
        if (Number.isFinite(r)) set.add(r);
      }
      return set;
    },
    bearingAllowedRanks() {
      const scenes = filterScenes(
        (this.sceneResult && this.sceneResult.scenes) || [],
        this.localFilters,
        null
      );
      let ranks = new Set(scenes.map((s) => Number(s.rank)).filter(Number.isFinite));
      if (this.analyzedRanks.size) {
        ranks = new Set([...ranks].filter((r) => this.analyzedRanks.has(r)));
      }
      const hasSignalFilter = this.localFilters.targetType || this.localFilters.commLink;
      if (hasSignalFilter && this.allRows.length) {
        const fromReport = new Set(
          this.filteredRows.map((r) => Number(r.sceneRank)).filter(Number.isFinite)
        );
        ranks = new Set([...ranks].filter((r) => fromReport.has(r)));
      }
      return ranks;
    },
    unifiedSceneTabs() {
      return buildDisplaySceneTabs(
        (this.sceneResult && this.sceneResult.scenes) || [],
        {
          allowedRanks: this.bearingAllowedRanks,
          requireAnalyzedRanks: this.analyzedRanks.size ? this.analyzedRanks : null
        }
      );
    },
    alignedTrajectoryViews() {
      const views = alignTrajectoryViewsToSceneTabs(
        (this.visualizationPayload && this.visualizationPayload.trajectoryViews) || [],
        this.unifiedSceneTabs
      );
      const labels = (this.allRows || []).map((r) => ({
        sceneRank: r.sceneRank,
        targetType: r.targetType,
        targetTypeLabel: r.targetTypeLabel || targetTypeLabel(r.targetType),
        freqMhz: r.networkFreqMhz,
        meanAzimuthDeg: r.meanAzimuthDeg != null ? r.meanAzimuthDeg : null,
        channel: r.commLinkChannel,
        channelLabel: r.commLinkChannelLabel,
        targetChannelsUsed: r.targetChannelsUsed
      }));
      return applyStreamTargetTypeLabelsToViews(views, labels);
    },
    hoppingViews() {
      return applyStreamTargetTypeLabelsToViews(
        (this.visualizationPayload && this.visualizationPayload.hoppingTrackViews) || [],
        (this.allRows || []).map((r) => ({
          sceneRank: r.sceneRank,
          targetType: r.targetType,
          targetTypeLabel: r.targetTypeLabel || targetTypeLabel(r.targetType),
          freqMhz: r.networkFreqMhz,
          meanAzimuthDeg: r.meanAzimuthDeg != null ? r.meanAzimuthDeg : null,
          channel: r.commLinkChannel,
          channelLabel: r.commLinkChannelLabel,
          targetChannelsUsed: r.targetChannelsUsed
        }))
      );
    },
    bearingSummary() {
      return {
        detections:
          (this.sceneResult && this.sceneResult.totalDetections)
          || (this.visualizationPayload && this.visualizationPayload.totalDetections)
          || 0,
        tracks:
          (this.sceneResult && this.sceneResult.confirmedTracks)
          || (this.visualizationPayload && this.visualizationPayload.confirmedTracks)
          || 0
      };
    },
    tableRows() {
      return buildFormattedTable(this.filteredRows, this.sceneResult);
    },
    stats() {
      return aggregateStats(this.filteredRows);
    },
    sceneRankOptions() {
      const byRank = new Map();
      for (const s of (this.sceneResult && this.sceneResult.scenes) || []) {
        const rank = Number(s.rank);
        if (Number.isFinite(rank)) {
          byRank.set(rank, {
            rank,
            sceneType: s.sceneType,
            freqCenterMhz: s.freqCenterMhz != null ? s.freqCenterMhz : null,
            freqMinMhz: s.freqMinMhz != null ? s.freqMinMhz : null,
            freqMaxMhz: s.freqMaxMhz != null ? s.freqMaxMhz : null
          });
        }
      }
      for (const r of this.allRows) {
        const rank = Number(r.sceneRank);
        if (!Number.isFinite(rank)) continue;
        if (!byRank.has(rank)) {
          byRank.set(rank, {
            rank,
            sceneType: r.sceneType,
            freqCenterMhz: r.sceneFreqCenterMhz != null ? r.sceneFreqCenterMhz : (r.networkFreqMhz != null ? r.networkFreqMhz : null),
            freqMinMhz: r.sceneFreqMinMhz != null ? r.sceneFreqMinMhz : null,
            freqMaxMhz: r.sceneFreqMaxMhz != null ? r.sceneFreqMaxMhz : null
          });
        }
      }
      const entries = this.analyzedRanks.size
        ? [...byRank.values()].filter((s) => this.analyzedRanks.has(s.rank))
        : [...byRank.values()];
      return sortScenesForDisplay(entries);
    },
    commLinkOptions() {
      return uniqueCommLinks(this.allRows);
    },
    totalPages() {
      return Math.max(1, Math.ceil(this.tableRows.length / this.pageSize));
    },
    pagedRows() {
      const start = (this.page - 1) * this.pageSize;
      const slice = this.tableRows.slice(start, start + this.pageSize);
      return this.reapplyRowspansInPage(slice);
    }
  },
  watch: {
    unifiedSceneTabs: {
      immediate: true,
      handler(tabs) {
        if (!tabs.length) {
          this.activeBearingSceneRank = null;
          return;
        }
        const active = Number(this.activeBearingSceneRank);
        if (!tabs.some((t) => Number(t.rank) === active)) {
          this.activeBearingSceneRank = tabs[0].rank;
        }
      }
    },
    "sceneResult.visualization": "loadVisualization",
    "sceneResult.outputDir": "loadVisualization",
    filteredRows: {
      immediate: true,
      handler() {
        this.page = 1;
        this.refreshChartRows();
      }
    }
  },
  mounted() {
    this.loadVisualization();
  },
  beforeDestroy() {
    if (this.vizAbort) this.vizAbort.abort();
  },
  created() {
    this.refreshChartRows = debounce(() => {
      this.chartRows = sampleRowsForCharts(this.filteredRows);
    }, 300);
  },
  methods: {
    async loadVisualization() {
      if (this.vizAbort) this.vizAbort.abort();
      const inline = this.sceneResult && this.sceneResult.visualization;
      if (inline) {
        this.visualizationPayload = inline;
        this.vizError = "";
        return;
      }
      const outputDir = this.sceneResult && this.sceneResult.outputDir;
      if (!outputDir || !(this.sceneResult && this.sceneResult.scenes && this.sceneResult.scenes.length)) {
        this.visualizationPayload = null;
        return;
      }
      this.vizLoading = true;
      this.vizError = "";
      this.vizAbort = new AbortController();
      try {
        this.visualizationPayload = await fetchVisualizationData(outputDir, this.vizAbort.signal);
      } catch (e) {
        if (e && e.name === "AbortError") return;
        this.visualizationPayload = null;
        this.vizError = `轨迹数据加载失败：${(e && e.message) || e}`;
      } finally {
        this.vizLoading = false;
      }
    },
    reapplyRowspansInPage(pageRows) {
      if (!pageRows.length) return [];
      return applyMergeRowspans(pageRows.map((r) => ({ ...r })));
    },
    rowKey(row, idx) {
      return `${row.sceneRank}-${row.analysisId}-${row.networkId}-${row.targetId || ""}-${idx}`;
    },
    formatConfidence(v) {
      const n = Number(v);
      return Number.isFinite(n) ? n.toFixed(2) : "—";
    },
    formatShare(v) {
      const n = Number(v);
      return Number.isFinite(n) ? n.toFixed(1) : "—";
    },
    formatLocate(row) {
      const lon = row.targetLocateLon;
      const lat = row.targetLocateLat;
      if (lon == null || lat == null) return "—";
      const a = Number(lon);
      const b = Number(lat);
      if (!Number.isFinite(a) || !Number.isFinite(b)) return "—";
      return `${a.toFixed(4)}, ${b.toFixed(4)}`;
    },
    formatDetectTime(iso) {
      if (!iso) return "—";
      const d = new Date(iso);
      if (Number.isNaN(d.getTime())) return String(iso);
      const pad = (n) => String(n).padStart(2, "0");
      return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    },
    isActiveRow(row) {
      const key = `${row.sceneRank}-${row.analysisId}-${row.networkId}-${row.targetId || ""}`;
      return this.activeRowKey === key;
    },
    emitSelectRow(row) {
      this.$emit("select-row", { ...row });
    },
    async onExport() {
      this.exporting = true;
      try {
        await new Promise((r) => requestAnimationFrame(r));
        const chartsRef = this.$refs.chartsRef;
        const chartImages = (chartsRef && chartsRef.getChartImages && chartsRef.getChartImages()) || [];
        await exportAnalysisDocx({
          sceneResult: this.sceneResult,
          report: this.report,
          filteredRows: this.filteredRows,
          formattedRows: this.tableRows,
          stats: this.stats,
          chartImages
        });
      } catch (e) {
        this.$message.error(`导出失败: ${(e && e.message) || e}`);
      } finally {
        this.exporting = false;
      }
    }
  }
};
</script>

<style scoped>
.results-panel {
  margin-top: 16px;
}
.loading-banner {
  padding: 10px 12px;
  background: rgba(0, 105, 135, 0.25);
  border: 1px solid var(--theme-border);
  border-radius: 2px;
  color: var(--theme-text-accent);
  font-size: 13px;
  margin-bottom: 12px;
}
.filter-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}
.filter-grid label {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  color: var(--theme-text-label);
  gap: 4px;
}
.stats-line {
  font-size: 13px;
  color: var(--theme-text-secondary);
  margin: 10px 0 0;
}
.viz-hint {
  font-size: 12px;
  color: var(--theme-text-muted);
  margin-top: 12px;
}
.table-head {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 8px;
}
.report-table-wrap {
  overflow: auto;
  max-height: 520px;
  border: 1px solid var(--theme-border);
  border-radius: 2px;
}
.report-table {
  width: 100%;
  font-size: 12px;
  table-layout: auto;
}
.report-table th,
.report-table td {
  text-align: center;
  vertical-align: middle;
}
.merge-cell {
  font-weight: 500;
}
.time-cell {
  line-height: 1.35;
}
.time-stack .time-sep {
  line-height: 1.2;
}
.num {
  text-align: right;
}
.time-cell-sm {
  font-size: 11px;
  white-space: nowrap;
}
.report-table tbody tr {
  cursor: pointer;
}
.report-table tbody tr.active td {
  background: var(--theme-bg-table-current) !important;
}
.pager {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-top: 8px;
  font-size: 13px;
  color: var(--theme-text-secondary);
}
</style>
