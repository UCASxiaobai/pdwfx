<!-- vue2-done -->
<template>
  <div class="scene-workflow">
    <ThemePanel title="数据上传" :label-index="1">
      <p class="subtitle">
        原始 CSV → 场景筛选（默认全段窗，与流式态势相同） → 场景数据作为信号分析输入 → 综合报表与网络研判
      </p>
      <ol class="cet36-steps">
        <li :class="{ done: pickedFiles.length }">上传数据</li>
        <li :class="{ done: sceneResult }">场景筛选</li>
        <li :class="{ done: forwardResult }">信号分析</li>
        <li :class="{ done: report && report.rows && report.rows.length }">结果查看</li>
      </ol>
      <div class="upload-row">
        <el-button type="primary" @click.native="openFilePicker">上传文件</el-button>
        <input
          ref="fileInput"
          class="upload-input"
          type="file"
          accept=".csv"
          multiple
          @change="onPickFiles"
        />
        <el-button type="text" :disabled="!pickedFiles.length" @click="clearAll">
          清空
        </el-button>
      </div>
      <ul v-if="pickedFiles.length" class="file-list">
        <li v-for="(f, i) in pickedFiles" :key="i">{{ f.name }}</li>
      </ul>
      <p v-else class="cet36-hint">
        支持一次选择多个 CSV：PDW 侦获数据 + 可选外源定位（如雷情导入 Lq1139，含 detectTime/longitude/latitude）
      </p>
      <label class="check-label scatter-opt">
        <input v-model="params.enableImportScatter" type="checkbox" />
        生成全量数据概览散点（需预筛全量数据，较慢）
      </label>
      <el-button
        v-if="pickedFiles.length && params.enableImportScatter"
        :disabled="importVizLoading || pipelineRunning"
        @click="runSceneScreeningForViz"
      >
        {{ importVizLoading ? "生成中…" : "生成全量概览" }}
      </el-button>
    </ThemePanel>

    <ImportDataScatterViz
      v-if="params.enableImportScatter && (importScatter || importVizLoading || importVizError)"
      :scatter="importScatter"
      :loading="importVizLoading"
      :error="importVizError"
    />

    <ThemePanel title="场景筛选参数" :label-index="2">
      <label class="check-label">
        <input v-model="params.fullSpanWindow" type="checkbox" />
        全段时间窗（与流式态势一致：各频段按整段跨度评一次，不做滑动切分）
      </label>
      <p class="param-hint">
        关闭后才使用下方评分时间窗/步进。全段窗可避免短窗把同一批目标拆碎，结果与流式分析对齐。
      </p>
      <div class="param-grid">
        <label>频段下限 MHz <input v-model.number="params.freqMin" type="number" step="0.1" /></label>
        <label>频段上限 MHz <input v-model.number="params.freqMax" type="number" step="0.1" /></label>
        <label>
          评分时间窗 (秒)
          <input
            v-model.number="params.windowSeconds"
            type="number"
            min="1"
            :disabled="params.fullSpanWindow"
          />
        </label>
        <label>
          窗滑动步进 (秒)
          <input
            v-model.number="params.windowStepSeconds"
            type="number"
            min="1"
            :disabled="params.fullSpanWindow"
          />
        </label>
        <label>场景最少轨迹数 <input v-model.number="params.minTracksInScene" type="number" min="1" /></label>
        <label>TOP-K 连续轨迹 <input v-model.number="params.topKTrackScenes" type="number" min="1" /></label>
        <label>TOP-K 轮询场景 <input v-model.number="params.topKPollingScenes" type="number" min="1" /></label>
        <label>输出目录 <input v-model.trim="params.outputDir" type="text" /></label>
      </div>
    </ThemePanel>

    <ThemePanel title="信号分析参数" :label-index="3">
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
      <div class="df-match-section">
        <h4 class="section-subtitle">测向–定位关联</h4>
        <SceneDfMatchPanel
          ref="dfMatchPanel"
          :upload-files="pickedFiles"
          :output-dir="(sceneResult && sceneResult.outputDir) || params.outputDir || ''"
          @match-result="dfMatchResult = $event"
        />
      </div>
      <div class="actions">
        <el-button
          type="primary"
          :disabled="!pickedFiles.length || pipelineRunning"
          @click="runFullPipeline"
        >
          {{ pipelineRunning ? "处理中…" : "开始分析（场景筛选 → 信号分析）" }}
        </el-button>
        <span v-if="statusMsg" class="cet36-status">{{ statusMsg }}</span>
        <span v-if="errorMsg" class="cet36-error">{{ errorMsg }}</span>
      </div>
    </ThemePanel>

    <ThemePanel v-if="sceneResult" title="结果查看" :label-index="4">
      <template v-if="sceneResult.scenes && sceneResult.scenes.length">
        <p class="meta">
          检测 {{ sceneResult.totalDetections }} 条 · 轨迹 {{ sceneResult.confirmedTracks }} 条 ·
          {{ sceneResult.scenes.length }} 个场景
        </p>
        <div class="scene-chips">
          <label
            v-for="s in displayScenes"
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
        <el-button
          v-if="forwardResult"
          :disabled="!canReForward || pipelineRunning"
          @click="runForwardOnly"
        >
          仅用当前所选场景重新做信号分析
        </el-button>
      </template>

      <SceneResultsPanel
        v-if="sceneResult"
        :report="report || { rows: [], buildTimeMs: 0 }"
        :scene-result="sceneResult"
        :forward-items="forwardItems"
        :command-net-pass="commandNetPass"
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
    </ThemePanel>
  </div>
</template>

<script>
import ThemePanel from "@/components/shell/ThemePanel.vue";
import SceneResultsPanel from "./SceneResultsPanel.vue";
import SignalNetworkExplorer from "./SignalNetworkExplorer.vue";
import ImportDataScatterViz from "./ImportDataScatterViz.vue";
import SceneDfMatchPanel from "./SceneDfMatchPanel.vue";
import {
  analyzeScenesUpload,
  fetchVisualizationData,
  processScenesPipeline
} from "@/api/pdwfx";
import { clearAnalysisViewCache } from "../scene/analysisViewCache.js";
import { formatFreq, sceneTypeLabel, sortScenesForDisplay } from "../scene/sceneFilters.js";

export default {
  name: "SceneWorkflow",
  components: {
    ThemePanel,
    SceneResultsPanel,
    SignalNetworkExplorer,
    ImportDataScatterViz,
    SceneDfMatchPanel
  },
  data() {
    return {
      pickedFiles: [],
      params: {
        freqMin: 200,
        freqMax: 1000,
        fullSpanWindow: true,
        windowSeconds: 120,
        windowStepSeconds: 30,
        minTracksInScene: 1,
        topKTrackScenes: 50,
        topKPollingScenes: 50,
        outputDir: "./output",
        freqTolerance: 0.01,
        preloadAll: true,
        enableImportScatter: false
      },
      pipelineRunning: false,
      statusMsg: "",
      errorMsg: "",
      sceneResult: null,
      forwardResult: null,
      report: null,
      reportLoading: false,
      forwardItems: [],
      commandNetPass: null,
      selectedRanks: [],
      activeRowKey: "",
      initialExplorerScene: null,
      initialExplorerNetwork: null,
      importScatter: null,
      importVizLoading: false,
      importVizError: "",
      dfMatchResult: null,
      abortController: null,
      importAbort: null
    };
  },
  computed: {
    allScenesSelected() {
      const n = this.displayScenes.length;
      return n > 0 && this.selectedRanks.length === n;
    },
    displayScenes() {
      return sortScenesForDisplay(
        (this.sceneResult && this.sceneResult.scenes) || []
      );
    },
    canReForward() {
      return (
        this.sceneResult &&
        this.sceneResult.sourceCsv &&
        this.sceneResult.outputDir &&
        this.selectedRanks.length > 0
      );
    },
    externalTargetFixes() {
      return (this.sceneResult && this.sceneResult.externalTargetFixes) || [];
    },
    externalFixMeta() {
      const total =
        (this.sceneResult && this.sceneResult.externalTargetFixTotalCount) || 0;
      const shown = this.externalTargetFixes.length;
      const files =
        (this.sceneResult && this.sceneResult.externalSourceFiles) || [];
      if (!total) return "";
      const fileNote = files.length ? " · " + files.join(", ") : "";
      const sampleNote = shown < total ? "（地图抽样 " + shown + "/" + total + "）" : "";
      return "外源定位 " + total + " 点" + sampleNote + fileNote;
    }
  },
  watch: {},
  methods: {
    formatFreq,
    sceneTypeLabel,
    openFilePicker() {
      const input = this.$refs.fileInput;
      if (input) {
        input.click();
      }
    },
    onPickFiles(e) {
      const list = [...(e.target.files || [])].filter(function(f) {
        return f.name.toLowerCase().endsWith(".csv");
      });
      if (!list.length) {
        this.errorMsg = "未找到 CSV 文件";
        return;
      }
      this.pickedFiles = list;
      this.errorMsg = "";
      e.target.value = "";
    },
    async loadImportScatter(result) {
      this.importVizError = "";
      const inline =
        result && result.visualization && result.visualization.importScatter
          ? result.visualization.importScatter
          : null;
      if (inline) {
        this.importScatter = inline;
        return;
      }
      const outputDir = result && result.outputDir;
      if (!outputDir) {
        this.importScatter = null;
        return;
      }
      if (this.importAbort) {
        this.importAbort.abort();
      }
      this.importAbort = new AbortController();
      try {
        const data = await fetchVisualizationData(outputDir, this.importAbort.signal);
        this.importScatter = (data && data.importScatter) || null;
        if (!this.importScatter) {
          this.importVizError = "散点数据未生成，请确认已完成场景预筛。";
        }
      } catch (err) {
        if (!err || err.name !== "AbortError") {
          this.importVizError = (err && err.message) || "加载散点失败";
          this.importScatter = null;
        }
      }
    },
    async runSceneScreeningForViz() {
      if (!this.pickedFiles.length) return;
      if (this.importAbort) {
        this.importAbort.abort();
      }
      this.importAbort = new AbortController();
      clearAnalysisViewCache();
      this.forwardResult = null;
      this.forwardItems = [];
      this.commandNetPass = null;
      this.report = null;
      this.activeRowKey = "";
      this.importVizLoading = true;
      this.importVizError = "";
      this.importScatter = null;
      this.statusMsg = "正在预筛场景并标绘全量散点…";
      try {
        const result = await analyzeScenesUpload(
          this.pickedFiles,
          this.params,
          this.importAbort.signal
        );
        this.sceneResult = result;
        this.selectedRanks = sortScenesForDisplay(result.scenes || []).map(function(s) {
          return s.rank;
        });
        await this.loadImportScatter(result);
        this.statusMsg =
          result.scenes && result.scenes.length
            ? "已标绘全量散点 · 筛出 " + result.scenes.length + " 个优质场景"
            : "已完成预筛，未找到优质场景";
      } catch (err) {
        if (!err || err.name !== "AbortError") {
          this.importVizError = (err && err.message) || "预筛失败";
          this.errorMsg = this.importVizError;
        }
        this.statusMsg = "";
      } finally {
        this.importVizLoading = false;
      }
    },
    clearAll() {
      if (this.importAbort) {
        this.importAbort.abort();
      }
      clearAnalysisViewCache();
      this.pickedFiles = [];
      this.sceneResult = null;
      this.forwardResult = null;
      this.report = null;
      this.forwardItems = [];
      this.commandNetPass = null;
      this.selectedRanks = [];
      this.activeRowKey = "";
      this.importScatter = null;
      this.importVizError = "";
      this.importVizLoading = false;
      this.statusMsg = "";
      this.errorMsg = "";
    },
    toggleAllScenes(e) {
      const scenes = (this.sceneResult && this.sceneResult.scenes) || [];
      this.selectedRanks = e.target.checked
        ? scenes.map(function(s) {
            return s.rank;
          })
        : [];
    },
    async runFullPipeline() {
      if (!this.pickedFiles.length) return;
      clearAnalysisViewCache();
      this.pipelineRunning = true;
      this.errorMsg = "";
      this.statusMsg = "场景筛选中…";
      this.abortController = new AbortController();
      const signal = this.abortController.signal;
      const self = this;
      const timer = setTimeout(function() {
        if (self.abortController) {
          self.abortController.abort();
        }
      }, 90 * 60 * 1000);
      try {
        this.sceneResult = await analyzeScenesUpload(
          this.pickedFiles,
          this.params,
          signal
        );
        this.selectedRanks = (this.sceneResult.scenes || []).map(function(s) {
          return s.rank;
        });
        if (this.params.enableImportScatter) {
          await this.loadImportScatter(this.sceneResult);
        }
        if (!this.selectedRanks.length) {
          this.errorMsg = "未筛选出优质场景，请调整参数";
          return;
        }
        await this.runForwardAndReport(signal);
        await this.autoRunDfMatch();
        this.statusMsg =
          "完成：" + ((this.report && this.report.rows && this.report.rows.length) || 0) + " 条目标明细";
      } catch (e) {
        this.errorMsg =
          e && e.name === "AbortError" ? "分析超时（30 分钟）" : (e && e.message) || "分析失败";
      } finally {
        clearTimeout(timer);
        this.pipelineRunning = false;
      }
    },
    async runForwardOnly() {
      if (!this.canReForward) return;
      clearAnalysisViewCache();
      this.pipelineRunning = true;
      this.errorMsg = "";
      this.abortController = new AbortController();
      try {
        this.statusMsg = "信号分析中…";
        await this.runForwardAndReport(this.abortController.signal);
        this.pickInitialExplorerFocus();
        this.statusMsg = "信号分析与报告已更新";
      } catch (e) {
        this.errorMsg = (e && e.message) || "失败";
      } finally {
        this.pipelineRunning = false;
      }
    },
    async autoRunDfMatch() {
      await this.$nextTick();
      const panel = this.$refs.dfMatchPanel;
      if (!panel || !panel.runIfReady) return;
      this.statusMsg = "测向–定位匹配中…";
      try {
        await panel.runIfReady();
      } catch (e) {
        if (!e || e.name !== "AbortError") {
          this.errorMsg = (e && e.message) || "测向匹配失败";
        }
      }
    },
    async runForwardAndReport(signal) {
      this.reportLoading = true;
      this.report = { rows: [], buildTimeMs: 0, partial: true };
      this.forwardItems = [];
      this.commandNetPass = null;
      this.activeRowKey = "";
      await this.$nextTick();

      try {
        const self = this;
        const summary = await processScenesPipeline(
          {
            sourceCsvPath: this.sceneResult.sourceCsv,
            outputDir: this.sceneResult.outputDir,
            sceneRanks: [...this.selectedRanks].map(Number).sort(function(a, b) {
              return a - b;
            }),
            freqTolerance: this.params.freqTolerance,
            preloadAll: this.params.preloadAll
          },
          {
            signal: signal,
            onProgress: function(cur, total, rank) {
              self.statusMsg = "优质场景 " + cur + "/" + total + "（#" + rank + "）分析中…";
            },
            onCommandNet: function() {
              self.statusMsg = "预警机指挥网二次分析中…";
            },
            onPartialRows: function(rows) {
              self.report = {
                rows: [...rows],
                buildTimeMs: (self.report && self.report.buildTimeMs) || 0,
                partial: true
              };
            }
          }
        );
        this.forwardItems = sortScenesForDisplay(summary.forwardItems || []);
        this.commandNetPass = summary.commandNetPass || null;
        this.forwardResult = {
          sourceCsv: this.sceneResult.sourceCsv,
          outputDir: this.sceneResult.outputDir,
          scenes: summary.forwardItems.length
        };
        this.report = {
          rows: summary.rows,
          buildTimeMs: summary.buildTimeMs,
          networkDetailCount: summary.networkDetailCount,
          partial: false
        };
        this.pickInitialExplorerFocus();
      } finally {
        this.reportLoading = false;
      }
    },
    pickInitialExplorerFocus() {
      const first = this.forwardItems.find(function(x) {
        return x.session && x.session.networks && x.session.networks.length;
      });
      if (!first) return;
      const net = first.session.networks[0];
      this.initialExplorerScene = first.rank;
      this.initialExplorerNetwork = net && net.networkId;
      const self = this;
      setTimeout(function() {
        const explorer = self.$refs.explorerRef;
        if (explorer && explorer.focusNetwork) {
          explorer.focusNetwork(first.rank, net && net.networkId);
        }
      }, 100);
    },
    onTableSelectRow(r) {
      this.activeRowKey =
        r.sceneRank + "-" + r.analysisId + "-" + r.networkId + "-" + (r.targetId || "");
      const explorer = this.$refs.explorerRef;
      if (explorer && explorer.focusNetwork) {
        explorer.focusNetwork(r.sceneRank, r.networkId, r.targetId);
      }
    }
  },
  mounted() {},
  beforeDestroy() {
    if (this.abortController) {
      this.abortController.abort();
    }
    if (this.importAbort) {
      this.importAbort.abort();
    }
  }
};
</script>

<style scoped>
.scene-workflow {
  font-family: Arial, sans-serif;
  max-width: 100%;
}
.subtitle {
  color: var(--theme-text-secondary);
  font-size: 13px;
  margin: 0 0 10px;
}
.upload-row {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.upload-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
  opacity: 0;
}
.file-list {
  margin: 8px 0 0;
  padding-left: 20px;
  font-size: 12px;
  max-height: 100px;
  overflow-y: auto;
  color: var(--theme-text-secondary);
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
  color: var(--theme-text-secondary);
  gap: 4px;
}
.param-grid input[type="number"],
.param-grid input[type="text"] {
  padding: 6px 8px;
  border: 1px solid var(--theme-border);
  border-radius: 6px;
  background: var(--theme-bg-primary);
  color: var(--theme-text-primary);
}
.param-grid input:disabled {
  background: var(--theme-bg-secondary);
  color: var(--theme-text-muted);
}
.signal-params {
  grid-template-columns: 1fr 1fr;
}
.check-label {
  display: flex;
  flex-direction: row !important;
  align-items: center;
  gap: 8px !important;
  font-size: 13px;
  color: var(--theme-text-primary);
  margin: 0 0 8px;
}
.scatter-opt {
  margin-top: 10px;
}
.param-hint,
.field-hint {
  font-size: 11px;
  color: var(--theme-text-muted);
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
.df-match-section {
  margin-top: 12px;
}
.section-subtitle {
  margin: 0 0 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--theme-text-accent);
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
}
.meta {
  font-size: 12px;
  color: var(--theme-text-muted);
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
  border: 1px solid var(--theme-border);
  border-radius: 20px;
  font-size: 12px;
  cursor: pointer;
  color: var(--theme-text-secondary);
}
.chip.on {
  border-color: var(--theme-accent-bright);
  background: var(--theme-bg-panel);
  color: var(--theme-text-accent);
}
.select-all {
  display: block;
  margin-top: 10px;
  font-size: 13px;
  color: var(--theme-text-primary);
}
</style>
