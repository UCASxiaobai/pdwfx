<!-- vue2-done -->
<template>
  <div class="cross-freq-viz">
    <div class="viz-head">
      <h4>跨频方位关联 · 时间-方位图</h4>
      <span class="viz-meta">分析后推断 · 时间重合场景合并显示</span>
    </div>

    <div class="view-mode">
      <span class="mode-label">显示模式</span>
      <el-button size="mini" :type="viewMode === 'fused' ? 'primary' : 'default'" @click="viewMode = 'fused'">
        合成轨迹
      </el-button>
      <el-button size="mini" :type="viewMode === 'tracks' ? 'primary' : 'default'" @click="viewMode = 'tracks'">
        分轨视图
      </el-button>
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
          ref="chartBox"
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
            <el-table
              :data="panelTargetChannelMatrix(view).rows"
              size="mini"
              border
              class="channel-matrix"
            >
              <el-table-column prop="label" label="目标 \ 频率" min-width="120" fixed />
              <el-table-column
                v-for="(col, ci) in panelTargetChannelMatrix(view).columns"
                :key="col.id"
                :label="col.label"
                min-width="72"
                align="center"
              >
                <template slot-scope="scope">
                  <span :class="{ occupied: scope.row.cells[ci] }">{{ scope.row.cells[ci] ? "✓" : "" }}</span>
                </template>
              </el-table-column>
            </el-table>
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

<script>
import * as echarts from "echarts";
import {
  allowedRanksSignature,
  forwardItemsSignature
} from "@/scene/analysisViewCache.js";
import { loadAllSceneAnalysisViews } from "@/scene/analysisBearing.js";
import { DEFAULT_MATCH_OPTIONS } from "@/scene/bearingMatch.js";
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
} from "@/scene/crossFreqMatchView.js";

export default {
  name: "SceneCrossFreqMatchViz",
  props: {
    forwardItems: { type: Array, default: function () { return []; } },
    sceneResult: { type: Object, default: null },
    allowedRanks: { type: Object, default: null },
    freqTolerance: { type: Number, default: 0.01 }
  },
  data: function () {
    return {
      viewMode: "fused",
      loading: false,
      loadProgress: "",
      loadError: "",
      panelItems: [],
      sceneViewsCache: [],
      matchOpts: Object.assign({}, DEFAULT_MATCH_OPTIONS),
      loadSeq: 0
    };
  },
  computed: {
    watchSignature: function () {
      return forwardItemsSignature(this.forwardItems) + "|" + allowedRanksSignature(this.allowedRanks);
    }
  },
  watch: {
    watchSignature: "loadSceneData",
    viewMode: "renderCharts",
    matchOpts: {
      deep: true,
      handler: function () {
        var self = this;
        clearTimeout(this.paramTimer);
        this.paramTimer = setTimeout(function () {
          self.applyMatchOnly();
        }, 200);
      }
    }
  },
  mounted: function () {
    this.loadSceneData();
    window.addEventListener("resize", this.onResize);
  },
  beforeDestroy: function () {
    clearTimeout(this.paramTimer);
    if (this.abortCtrl) this.abortCtrl.abort();
    window.removeEventListener("resize", this.onResize);
    this.disposeCharts();
  },
  methods: {
    panelFreqColorLegend: panelFreqColorLegend,
    panelFreqLineLegend: panelFreqLineLegend,
    panelFusedTargets: panelFusedTargets,
    panelHighlightedFreqLegend: panelHighlightedFreqLegend,
    panelMatchClusters: panelMatchClusters,
    panelTargetChannelMatrix: function (view) {
      return (view && view.targetChannelMatrix) || { columns: [], rows: [] };
    },
    sceneByRankMap: function () {
      var m = new Map();
      var scenes = (this.sceneResult && this.sceneResult.scenes) || [];
      scenes.forEach(function (s) { m.set(s.rank, s); });
      return m;
    },
    matchOptsKey: function () {
      var o = this.matchOpts;
      return JSON.stringify({
        gateDeg: o.gateDeg,
        timeToleranceMs: o.timeToleranceMs,
        minMatchPoints: o.minMatchPoints,
        minOverlapMs: o.minOverlapMs,
        showOthers: o.showOthers,
        topColoredTracks: o.topColoredTracks,
        matrixTopFreqs: o.matrixTopFreqs,
        requireMotionConsistent: o.requireMotionConsistent,
        requireSameTargetType: o.requireSameTargetType
      });
    },
    panelKey: function (view, idx) {
      return (view.memberRanks || []).join("-") + "-" + idx + "-" + this.viewMode + "-" + this.matchOptsKey();
    },
    chartHasData: function (view) {
      if (view.empty) return false;
      if (this.viewMode === "fused") return (view.fusedTargets || []).length > 0;
      return (view.targets || []).length > 0;
    },
    chartHeightFor: function (view) {
      return crossFreqChartHeight(view);
    },
    lineSampleClass: function (lineType) {
      if (lineType === "dashed") return "dashed";
      if (lineType === "dotted") return "dotted";
      return "solid";
    },
    applyMatchOnly: function () {
      if (!this.sceneViewsCache.length) return;
      this.panelItems = buildCrossFreqPanelsFromViews(this.sceneViewsCache, Object.assign({}, this.matchOpts));
      this.renderCharts();
    },
    loadSceneData: function () {
      var self = this;
      if (this.abortCtrl) this.abortCtrl.abort();
      var items = this.forwardItems || [];
      if (!items.length) {
        this.sceneViewsCache = [];
        this.panelItems = [];
        this.disposeCharts();
        this.loading = false;
        return;
      }
      var seq = ++this.loadSeq;
      this.loading = true;
      this.loadProgress = "";
      this.loadError = "";
      this.abortCtrl = new AbortController();
      loadAllSceneAnalysisViews(
        items,
        this.sceneByRankMap(),
        this.abortCtrl.signal,
        this.freqTolerance,
        this.allowedRanks,
        function (cur, total, rank) {
          self.loadProgress = "（场景 #" + rank + "，" + cur + "/" + total + "）";
        }
      ).then(function (views) {
        if (seq !== self.loadSeq) return;
        self.sceneViewsCache = views;
        self.panelItems = buildCrossFreqPanelsFromViews(views, Object.assign({}, self.matchOpts));
        self.loading = false;
        self.loadProgress = "";
        return self.renderCharts();
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
    renderCharts: function () {
      var self = this;
      return this.$nextTick().then(function () {
        self.disposeCharts();
        var refs = self.$refs.chartBox;
        if (!refs) return;
        var els = Array.isArray(refs) ? refs : [refs];
        self.panelItems.forEach(function (view, idx) {
          var el = els[idx];
          if (!el || !self.chartHasData(view)) return;
          var inst = echarts.init(el);
          if (!self.chartInsts) self.chartInsts = [];
          self.chartInsts[idx] = inst;
          var option = self.viewMode === "fused"
            ? buildFusedChartOption(view)
            : buildMatchChartOption(view);
          inst.setOption(option, true);
        });
      });
    },
    disposeCharts: function () {
      if (this.chartInsts) {
        this.chartInsts.forEach(function (inst) {
          if (inst) inst.dispose();
        });
      }
      this.chartInsts = [];
    },
    onResize: function () {
      if (this.chartInsts) {
        this.chartInsts.forEach(function (inst) {
          if (inst) inst.resize();
        });
      }
    }
  }
};
</script>

<style scoped>
.cross-freq-viz {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid var(--theme-border);
}
.viz-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.viz-head h4 { margin: 0; font-size: 14px; color: var(--theme-text-primary); }
.viz-meta { font-size: 11px; color: var(--theme-text-muted); }
.view-mode {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
}
.mode-label { color: var(--theme-text-muted); font-weight: 600; }
.summary-line { font-size: 12px; color: var(--theme-text-secondary); margin: 0 0 10px; }
.match-params {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--theme-text-secondary);
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
  border: 1px solid var(--theme-border-input);
  border-radius: 4px;
  background: var(--theme-bg-input);
  color: var(--theme-text-primary);
}
.check { flex-direction: row !important; align-items: center; gap: 6px !important; }
.chart-panel {
  border: 1px solid var(--theme-border);
  border-radius: 8px;
  padding: 10px 12px 12px;
  margin-bottom: 14px;
  background: var(--theme-bg-panel);
}
.panel-title { margin: 0 0 4px; font-size: 14px; font-weight: 600; color: var(--theme-text-primary); }
.panel-sub { margin: 0 0 4px; font-size: 12px; color: var(--theme-text-secondary); }
.time-badge {
  display: inline-block;
  margin: 0 0 8px;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--theme-text-accent);
  background: var(--theme-bg-panel-alt);
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
.encoding-label { color: var(--theme-text-muted); font-weight: 600; }
.match-chip, .freq-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border: 1px solid var(--theme-border);
  border-radius: 12px;
  background: var(--theme-bg-deep);
  color: var(--theme-text-secondary);
}
.swatch { width: 10px; height: 10px; border-radius: 2px; }
.line-sample { width: 22px; border-top: 2px solid var(--theme-text-secondary); }
.line-sample.dashed { border-top-style: dashed; }
.line-sample.dotted { border-top-style: dotted; }
.chart-box { min-height: 480px; width: 100%; }
.channel-matrix-block {
  margin: 12px 0 0;
  padding: 10px 12px;
  background: var(--theme-bg-deep);
  border: 1px solid var(--theme-border);
  border-radius: 8px;
}
.matrix-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: var(--theme-text-primary);
}
.matrix-desc {
  margin: 0 0 8px;
  font-size: 11px;
  color: var(--theme-text-muted);
  line-height: 1.45;
}
.matrix-scroll { overflow-x: auto; }
.occupied {
  color: #91cc75;
  font-weight: 700;
  font-size: 14px;
}
.hint { font-size: 11px; color: var(--theme-text-muted); margin: 4px 0 0; line-height: 1.45; }
.load-hint, .load-error { font-size: 12px; }
.load-error { color: #ee6666; }
.viz-empty {
  padding: 20px;
  text-align: center;
  color: var(--theme-text-muted);
  background: var(--theme-bg-deep);
  border-radius: 8px;
}
.viz-empty.inline { padding: 12px; margin: 0; }
</style>
