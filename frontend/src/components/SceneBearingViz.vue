<!-- vue2-done -->
<template>
  <div class="bearing-viz">
    <div class="viz-head">
      <h4>场景方位轨迹</h4>
      <span class="viz-meta">{{ sceneTabs.length }} 个场景 · 检测 {{ summary.detections }} · 轨迹 {{ summary.tracks }}</span>
    </div>
    <div v-if="!sceneTabs.length" class="viz-empty">当前筛选条件下无场景轨迹可显示</div>
    <template v-else>
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
          <strong>{{ current ? current.title : panelTitleFallback }}</strong>
          <span v-if="currentFreqLabel" class="freq-badge">{{ currentFreqLabel }}</span>
          <span>{{ panelMeta }}</span>
        </div>
        <div v-if="!current" class="viz-empty inline">该场景暂无预筛轨迹数据</div>
        <div v-else ref="chartEl" class="chart-box" />
        <p v-if="current && current.dateLabel" class="date-foot">{{ current.dateLabel }}</p>
        <p v-if="current && current.note" class="scene-note">{{ current.note }}</p>
        <p class="hint">{{ chartHint }}</p>
      </div>
    </template>
  </div>
</template>

<script>
import * as echarts from "echarts";
import { formatPeriodSecMsUs } from "@/scene/sceneFormat.js";
import { formatSceneFreq, formatSceneLabel } from "@/scene/sceneFilters.js";

export default {
  name: "SceneBearingViz",
  props: {
    sceneTabs: { type: Array, default: function () { return []; } },
    trajectoryViews: { type: Array, default: function () { return []; } },
    activeRank: { type: Number, default: null },
    summary: {
      type: Object,
      default: function () { return { detections: 0, tracks: 0 }; }
    }
  },
  computed: {
    viewByRank: function () {
      var map = new Map();
      var self = this;
      (this.trajectoryViews || []).forEach(function (v) {
        if (v == null || v.sceneRank == null) return;
        var rank = Number(v.sceneRank);
        if (!Number.isFinite(rank)) return;
        var tab = self.sceneTabs.find(function (t) { return Number(t.rank) === rank; });
        map.set(
          rank,
          tab
            ? Object.assign({}, v, {
              sceneRank: rank,
              sceneType: tab.sceneType,
              freqCenterMhz: v.freqCenterMhz != null ? v.freqCenterMhz : tab.freqCenterMhz,
              freqMinMhz: v.freqMinMhz != null ? v.freqMinMhz : tab.freqMinMhz,
              freqMaxMhz: v.freqMaxMhz != null ? v.freqMaxMhz : tab.freqMaxMhz
            })
            : Object.assign({}, v, { sceneRank: rank })
        );
      });
      return map;
    },
    current: function () {
      var want = Number(this.activeRank);
      if (!Number.isFinite(want)) return null;
      return this.viewByRank.get(want) || null;
    },
    currentTab: function () {
      var want = Number(this.activeRank);
      if (!Number.isFinite(want)) return null;
      return this.sceneTabs.find(function (t) { return Number(t.rank) === want; }) || null;
    },
    currentFreqLabel: function () {
      var fromView = formatSceneFreq(this.current);
      if (fromView) return fromView;
      return formatSceneFreq(this.currentTab);
    },
    panelTitleFallback: function () {
      var tab = this.currentTab;
      if (!tab) return "—";
      var freq = formatSceneFreq(tab);
      return freq ? "场景 #" + tab.rank + " · " + freq : "场景 #" + tab.rank;
    },
    panelMeta: function () {
      var v = this.current;
      if (!v) return this.currentTab ? "预筛轨迹数据缺失" : "";
      if (v.viewMode === "polling") {
        var total = v.scatterTotal != null ? v.scatterTotal : v.displayedTracks;
        var down = v.scatterDownsampled ? "（抽样显示 " + v.displayedTracks + "/" + total + "）" : "";
        var targetNote = v.pollingParticipantsOnly
          ? " · " + (v.pollingTargetCount != null ? v.pollingTargetCount : "?") + " 个轮询目标"
          : "";
        var windowNote =
          v.pollingParticipantsOnly && v.windowPointTotal > total
            ? "（窗内 " + v.windowPointTotal + " 点已过滤）"
            : "";
        return v.timeRange + " · 轮询点 " + total + down + targetNote + windowNote + " · 轮次 " + v.trackCount;
      }
      return v.timeRange + " · 显示 " + v.displayedTracks + " / " + v.trackCount + " 条轨迹";
    },
    chartHint: function () {
      var v = this.current;
      if (!v) return "与下方「信号分析方位轨迹」共用场景 Tab；此处为预筛散点/轨迹。";
      if (v.viewMode === "polling") {
        return v.pollingParticipantsOnly
          ? "仅显示飞机轮询目标的 burst 点位（按目标分色）；预警机/地面站连续轨如有则画询问机衬线，不计入轮询目标。"
          : "散点为场景窗内原始检测方位。";
      }
      return "按时间帧聚合目标方位（xhfw）；有信号分析结果时图例显示「目标N-类型」。";
    }
  },
  watch: {
    activeRank: "renderChart",
    trajectoryViews: { handler: "renderChart", deep: true },
    sceneTabs: { handler: "renderChart", deep: true }
  },
  mounted: function () {
    this.renderChart();
    window.addEventListener("resize", this.onResize);
  },
  beforeDestroy: function () {
    window.removeEventListener("resize", this.onResize);
    if (this.chartInst) {
      this.chartInst.dispose();
      this.chartInst = null;
    }
  },
  methods: {
    formatSceneLabel: formatSceneLabel,
    selectTab: function (rank) {
      this.$emit("update:active-rank", rank);
    },
    renderChart: function () {
      var el = this.$refs.chartEl;
      if (!el || !this.current) {
        if (this.chartInst) {
          this.chartInst.dispose();
          this.chartInst = null;
        }
        return;
      }
      if (!this.chartInst) {
        this.chartInst = echarts.init(el);
      }
      var v = this.current;
      var option = v.viewMode === "polling" ? this.buildPollingOption(v) : this.buildTrackOption(v);
      this.chartInst.setOption(option, true);
    },
    buildTrackOption: function (v) {
      var self = this;
      var series = (v.tracks || []).map(function (t) {
        return {
          name: self.trackLegendName(t),
          type: "line",
          showSymbol: true,
          symbolSize: 6,
          lineStyle: { width: 1.5, color: t.color },
          itemStyle: { color: t.color },
          data: (t.points || []).map(function (p) { return [p.x, p.y]; })
        };
      });
      return {
        tooltip: {
          trigger: "axis",
          formatter: function (params) {
            var p = params && params[0];
            if (!p) return "";
            return self.formatClock(p.value[0]) + "<br/>" + p.seriesName + ": " + Number(p.value[1]).toFixed(1) + "°";
          }
        },
        legend: { top: 4, right: 8, type: "scroll" },
        grid: { left: 56, right: 16, top: 40, bottom: 48 },
        xAxis: {
          type: "value",
          min: v.xMin,
          max: v.xMax,
          name: "时间",
          axisLabel: { formatter: function (val) { return self.formatClock(val); } }
        },
        yAxis: {
          type: "value",
          min: v.yMin,
          max: v.yMax,
          name: "方位 (°)"
        },
        series: series
      };
    },
    buildPollingOption: function (v) {
      var self = this;
      var ann = v.chartAnnotations || {};
      var graphics = [];
      var periodLabel = ann.periodLabel || formatPeriodSecMsUs(ann.periodSec);
      if (periodLabel && periodLabel !== "—") {
        graphics.push({
          type: "text",
          left: "center",
          bottom: 56,
          style: { text: "轮询周期 " + periodLabel, fill: "#51e9ff", fontSize: 12 }
        });
      }
      var targets = v.pollingTargets && v.pollingTargets.length ? v.pollingTargets : null;
      var series = targets
        ? targets.map(function (t) {
          return {
            name: self.trackLegendName(t),
            type: "scatter",
            symbolSize: 6,
            itemStyle: { color: t.color },
            data: (t.points || []).map(function (p) { return [p.x, p.y]; })
          };
        })
        : [{
          name: "检测点",
          type: "scatter",
          symbolSize: 5,
          itemStyle: { color: "rgba(81, 233, 255, 0.65)" },
          data: (v.scatterPoints || []).map(function (p) { return [p.x, p.y]; })
        }];
      if (v.interrogatorOverlay && (v.interrogatorOverlay.points || []).length) {
        var ov = v.interrogatorOverlay;
        series.push({
          name: self.trackLegendName(Object.assign({}, ov, { label: ov.label || "询问机（连续）" })),
          type: "scatter",
          symbolSize: 5,
          itemStyle: { color: ov.color || "#7B2D8E" },
          data: ov.points.map(function (p) { return [p.x, p.y]; })
        });
      }
      return {
        tooltip: {
          trigger: "item",
          formatter: function (p) {
            return self.formatClock(p.value[0]) + "<br/>" + p.seriesName + ": " + Number(p.value[1]).toFixed(1) + "°";
          }
        },
        legend: { top: 4, right: 8, type: "scroll" },
        grid: { left: 56, right: 16, top: 40, bottom: ann.periodSec ? 72 : 48 },
        xAxis: {
          type: "value",
          min: v.xMin,
          max: v.xMax,
          name: "时间",
          axisLabel: { formatter: function (val) { return self.formatClock(val); } }
        },
        yAxis: {
          type: "value",
          min: v.yMin,
          max: v.yMax,
          name: "方位 (°)"
        },
        graphic: graphics,
        series: series
      };
    },
    formatClock: function (ms) {
      return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
    },
    trackLegendName: function (t) {
      var id = t.trackId != null ? " (#" + t.trackId + ")" : "";
      var base = t.label || "目标";
      var type = t.targetTypeLabel;
      if (type && type !== "—" && String(base).indexOf(type) < 0) {
        return base + "-" + type + id;
      }
      return base + id;
    },
    onResize: function () {
      if (this.chartInst) this.chartInst.resize();
    }
  }
};
</script>

<style scoped>
.bearing-viz { margin-top: 0; }
.viz-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.viz-head h4 { margin: 0; font-size: 14px; color: var(--theme-text-primary); }
.viz-meta { font-size: 12px; color: var(--theme-text-muted); }
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
  color: var(--theme-text-secondary);
}
.panel-title strong { font-size: 13px; color: var(--theme-text-primary); }
.freq-badge {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--theme-bg-panel-alt);
  color: var(--theme-text-accent);
  font-size: 12px;
  font-weight: 600;
}
.chart-box { width: 100%; height: 360px; }
.date-foot { text-align: right; font-size: 11px; color: var(--theme-text-muted); margin: 4px 0 0; }
.scene-note { font-size: 11px; color: var(--theme-text-secondary); margin: 4px 0 0; }
.hint { font-size: 11px; color: var(--theme-text-muted); margin: 8px 0 0; }
.viz-empty {
  padding: 24px;
  text-align: center;
  color: var(--theme-text-muted);
  background: var(--theme-bg-deep);
  border-radius: 8px;
}
.viz-empty.inline { padding: 16px; margin-bottom: 8px; }
</style>
