<!-- vue2-done -->
<template>
  <div class="bearing-viz">
    <div class="viz-head">
      <h4>信号分析方位轨迹 · 分频视图</h4>
    </div>

    <div v-if="!sceneTabs.length" class="viz-empty">当前筛选条件下无可绘制的分析轨迹</div>

    <template v-else>
      <p class="viz-meta">{{ metaLine }}</p>

      <div class="scene-tabs">
        <el-button
          v-for="tab in sceneTabs"
          :key="tab.rank"
          size="mini"
          :type="tab.rank === activeRank ? 'primary' : 'default'"
          @click="selectTab(tab.rank)"
        >
          {{ formatSceneLabel(tab.rank, tab.sceneType, tab) }}
        </el-button>
      </div>

      <div class="panel">
        <div class="panel-title">
          <strong>{{ currentView ? currentView.title : "—" }}</strong>
          <span v-if="currentView && currentView.freqLabel" class="freq-badge">{{ currentView.freqLabel }}</span>
        </div>

        <div v-if="currentView && currentView.freqGroups && currentView.freqGroups.length > 1" class="freq-summary">
          <el-button
            size="mini"
            :type="!selectedFreqKey ? 'primary' : 'default'"
            @click="selectedFreqKey = ''"
          >
            全部 {{ currentView.freqGroups.length }} 频点（合图）
          </el-button>
          <el-button
            v-for="g in currentView.freqGroups"
            :key="g.freqKey"
            size="mini"
            :type="selectedFreqKey === g.freqKey ? 'primary' : 'default'"
            @click="selectedFreqKey = g.freqKey"
          >
            <strong>{{ g.freqKey }} MHz</strong>
            <span class="chip-meta">{{ g.targetCount }} 目标</span>
          </el-button>
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
        <p v-if="!loading && currentView && !(currentView.targets && currentView.targets.length)" class="viz-empty inline">
          该场景下暂无目标方位数据
        </p>
        <p v-if="currentView && currentView.timeRange" class="time-foot">{{ currentView.timeRange }}</p>
        <p class="hint">
          默认同一场景内全部频点绘制在一张时间-方位图（颜色区分目标、线型区分频率）；
          可点选单一频点放大查看。轮询场景按目标分色展示各轮 burst 点位或槽位轨迹。
        </p>
      </div>
    </template>
  </div>
</template>

<script>
import * as echarts from "echarts";
import {
  analysisViewCacheKey,
  forwardItemsSignature
} from "@/scene/analysisViewCache.js";
import {
  buildCombinedSceneChartOption,
  buildFacetChartOption,
  combinedChartHeight,
  facetChartHeight,
  findForwardItemForRank,
  getOrLoadSceneAnalysisView,
  applyReportTargetTypes,
  reportRowsSignature
} from "@/scene/analysisBearing.js";
import { formatSceneLabel } from "@/scene/sceneFilters.js";

export default {
  name: "SceneAnalysisBearingViz",
  props: {
    sceneTabs: { type: Array, default: function () { return []; } },
    activeRank: { type: Number, default: null },
    forwardItems: { type: Array, default: function () { return []; } },
    sceneResult: { type: Object, default: null },
    reportRows: { type: Array, default: function () { return []; } }
  },
  data: function () {
    return {
      selectedFreqKey: "",
      loading: false,
      loadError: "",
      tabViewCache: new Map(),
      freqLineLegend: [],
      loadSeq: 0
    };
  },
  computed: {
    currentTab: function () {
      var want = Number(this.activeRank);
      if (!Number.isFinite(want)) return null;
      return this.sceneTabs.find(function (t) { return Number(t.rank) === want; }) || null;
    },
    currentView: function () {
      var tab = this.currentTab;
      if (!tab) return null;
      return this.tabViewCache.get(this.tabCacheKey(tab)) || null;
    },
    chartView: function () {
      var v = this.currentView;
      if (!v) return null;
      if (v.bearingChart) {
        return Object.assign({}, v, v.bearingChart, { pollingLaneMode: v.pollingLaneMode });
      }
      return v;
    },
    displayGroups: function () {
      var v = this.chartView;
      if (!v || !v.freqGroups || !v.freqGroups.length) return [];
      if (!this.selectedFreqKey) return v.freqGroups;
      var g = v.freqGroups.find(function (x) { return x.freqKey === this.selectedFreqKey; }.bind(this));
      return g ? [g] : v.freqGroups;
    },
    metaLine: function () {
      var v = this.currentView;
      if (!v) return this.sceneTabs.length + " 个场景（与上方场景方位轨迹 Tab 一一对应）";
      var line = this.sceneTabs.length + " 个场景 · " + v.freqCount + " 个通信网频点 · " + v.targetCount + " 个分析目标";
      if (v.clusteringMethod === "POSITION_MATCH") {
        line += " · 编批：位置匹配（异频合批）";
      }
      return line;
    },
    useCombinedChart: function () {
      var v = this.chartView;
      if (!v || !v.freqGroups || !v.freqGroups.length) return false;
      return !this.selectedFreqKey;
    },
    chartHeightPx: function () {
      var v = this.chartView;
      if (!v || !v.targets || !v.targets.length) return 480;
      if (this.useCombinedChart) {
        return combinedChartHeight(v.ySpan != null ? v.ySpan : v.targets);
      }
      var n = this.displayGroups.length;
      return n <= 1
        ? combinedChartHeight(v.ySpan != null ? v.ySpan : ((this.displayGroups[0] && this.displayGroups[0].targets) || v.targets))
        : facetChartHeight(n);
    },
    chartScrollMaxPx: function () {
      var vh = typeof window !== "undefined" ? window.innerHeight : 800;
      var cap = this.useCombinedChart ? 0.88 : 0.72;
      return Math.min(this.chartHeightPx, Math.round(vh * cap));
    },
    sceneByRank: function () {
      var m = new Map();
      var scenes = (this.sceneResult && this.sceneResult.scenes) || [];
      scenes.forEach(function (s) { m.set(s.rank, s); });
      return m;
    },
    watchSignature: function () {
      return [
        forwardItemsSignature(this.forwardItems),
        this.activeRank,
        reportRowsSignature(this.reportRows),
        this.sceneTabs.map(function (t) { return t.rank; }).join(",")
      ].join("|");
    }
  },
  watch: {
    watchSignature: function () {
      this.selectedFreqKey = "";
      this.tabViewCache = new Map();
      this.loadCurrentView();
    },
    selectedFreqKey: function () {
      var self = this;
      this.$nextTick(function () {
        self.renderChart();
      });
    }
  },
  mounted: function () {
    this.loadCurrentView();
    window.addEventListener("resize", this.onResize);
  },
  beforeDestroy: function () {
    if (this.abortCtrl) this.abortCtrl.abort();
    window.removeEventListener("resize", this.onResize);
    this.disposeChart();
  },
  methods: {
    formatSceneLabel: formatSceneLabel,
    selectTab: function (rank) {
      this.$emit("update:active-rank", rank);
    },
    tabCacheKey: function (tab) {
      var forwardItem = findForwardItemForRank(this.forwardItems, tab.rank);
      return forwardItem ? analysisViewCacheKey(forwardItem) : "rank:" + tab.rank;
    },
    lineSampleClass: function (lineType) {
      if (lineType === "dashed") return "dashed";
      if (lineType === "dotted") return "dotted";
      return "solid";
    },
    loadCurrentView: function () {
      var self = this;
      if (this.abortCtrl) this.abortCtrl.abort();
      var tab = this.currentTab;
      if (!tab) {
        this.disposeChart();
        return;
      }
      if (this.tabViewCache.has(this.tabCacheKey(tab))) {
        this.renderChart();
        return;
      }
      var forwardItem = findForwardItemForRank(this.forwardItems, tab.rank);
      if (!forwardItem) {
        this.loadError = "未找到该场景的分析会话";
        return;
      }
      var seq = ++this.loadSeq;
      this.loading = true;
      this.loadError = "";
      this.abortCtrl = new AbortController();
      getOrLoadSceneAnalysisView(
        forwardItem,
        this.sceneByRank.get(tab.rank),
        this.abortCtrl.signal
      ).then(function (baseView) {
        var view = applyReportTargetTypes(baseView, self.reportRows, tab.rank);
        if (seq !== self.loadSeq) return;
        var next = new Map(self.tabViewCache);
        next.set(self.tabCacheKey(tab), view);
        self.tabViewCache = next;
        self.loading = false;
        return self.renderChart();
      }).catch(function (e) {
        if (e && e.name === "AbortError") {
          if (seq === self.loadSeq) self.loading = false;
          return;
        }
        if (seq !== self.loadSeq) return;
        self.loadError = "加载失败：" + ((e && e.message) || e);
        self.loading = false;
      });
    },
    renderChart: function () {
      var self = this;
      var base = this.currentView;
      var v = this.chartView;
      var groups = this.displayGroups;
      if (!base || !base.targets || !base.targets.length || !v || !v.targets || !v.targets.length || !groups.length) {
        this.freqLineLegend = [];
        this.disposeChart();
        return Promise.resolve();
      }
      return new Promise(function (r) { requestAnimationFrame(r); }).then(function () {
        var el = self.$refs.chartEl;
        if (!el) return;
        if (!self.chartInst) self.chartInst = echarts.init(el);
        if (self.useCombinedChart) {
          var built = buildCombinedSceneChartOption(v, v.freqGroups);
          self.freqLineLegend = built.freqLineLegend || [];
          self.chartInst.setOption(Object.assign({}, built.option, { graphic: [] }), true);
        } else {
          self.freqLineLegend = [];
          self.chartInst.setOption(Object.assign({}, buildFacetChartOption(v, groups), { graphic: [] }), true);
        }
        self.chartInst.resize();
      });
    },
    disposeChart: function () {
      if (this.chartInst) {
        this.chartInst.dispose();
        this.chartInst = null;
      }
    },
    onResize: function () {
      if (this.chartInst) this.chartInst.resize();
    }
  }
};
</script>

<style scoped>
.bearing-viz { margin-top: 14px; padding-top: 14px; border-top: 1px solid var(--theme-border); }
.viz-head h4 { margin: 0 0 6px; font-size: 14px; color: var(--theme-text-primary); }
.viz-meta { font-size: 12px; color: var(--theme-text-muted); margin: 0 0 8px; }
.scene-tabs { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.panel {
  background: var(--theme-bg-panel);
  border: 1px solid var(--theme-border);
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
.panel-title strong { font-size: 13px; color: var(--theme-text-primary); }
.freq-badge { color: var(--theme-text-accent); font-weight: 600; }
.freq-summary { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.chip-meta { color: var(--theme-text-muted); font-size: 11px; margin-left: 4px; }
.encoding-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  margin-bottom: 8px;
  font-size: 11px;
}
.encoding-label { color: var(--theme-text-muted); font-weight: 600; }
.line-legend-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid var(--theme-border);
  border-radius: 12px;
  background: var(--theme-bg-deep);
  font-size: 11px;
  color: var(--theme-text-secondary);
}
.line-sample { width: 22px; border-top: 2px solid var(--theme-text-secondary); }
.line-sample.dashed { border-top-style: dashed; }
.line-sample.dotted { border-top-style: dotted; }
.chart-scroll {
  overflow-y: auto;
  overflow-x: hidden;
  border: 1px solid var(--theme-border);
  border-radius: 6px;
  background: var(--theme-bg-deep);
}
.chart-box { width: 100%; min-height: 320px; }
.time-foot { text-align: right; font-size: 11px; color: var(--theme-text-muted); }
.hint { font-size: 11px; color: var(--theme-text-muted); margin: 8px 0 0; }
.load-hint, .load-error { font-size: 12px; }
.load-error { color: #ee6666; }
.viz-empty {
  padding: 24px;
  text-align: center;
  color: var(--theme-text-muted);
  background: var(--theme-bg-deep);
  border-radius: 8px;
}
.viz-empty.inline { padding: 16px; }
</style>
