<!-- vue2-done -->
<template>
  <div v-if="panels.length" class="awacs-cn-viz">
    <div class="viz-head">
      <h4>预警机指挥网</h4>
      <span class="viz-meta">
        {{ panels.length }} 个占用频点 · 同频同时段建轨（指挥网二次）
      </span>
    </div>
    <div class="seed-tabs">
      <el-button
        v-for="p in panels"
        :key="panelKey(p)"
        size="mini"
        :type="panelKey(p) === activePanelKey ? 'primary' : 'default'"
        @click="activePanelKey = panelKey(p)"
      >
        {{ p.label || panelKey(p) }}
      </el-button>
    </div>
    <div v-if="!activePanel" class="viz-empty">请选择预警机</div>
    <template v-else>
      <div class="panel">
        <div class="panel-title">
          <strong>{{ activePanel.label }}</strong>
          <span v-if="activePanel.freqMhz" class="freq-badge">{{ formatFreq(activePanel.freqMhz) }} MHz</span>
          <span>{{ trackCount }} 条轨迹 · {{ rowCount }} 个目标</span>
        </div>
        <div v-if="!trackCount" class="viz-empty inline">该预警机占用窗内暂无建轨结果</div>
        <div v-else ref="chartEl" class="chart-box" />
        <p class="hint">
          仅显示与所选预警机在相同时间、相同频率占用窗内的建轨与类型；其他场景/换频模块不受影响。
        </p>
      </div>
      <div v-if="tableRows.length" class="table-wrap">
        <h5>同频同时段目标</h5>
        <el-table :data="tableRows" size="mini" border stripe class="mini-table">
          <el-table-column prop="targetId" label="目标" min-width="80">
            <template slot-scope="scope">{{ scope.row.targetId || "—" }}</template>
          </el-table-column>
          <el-table-column label="类型" min-width="80">
            <template slot-scope="scope">{{ scope.row.targetTypeLabel || targetTypeLabel(scope.row.targetType) }}</template>
          </el-table-column>
          <el-table-column label="频率" min-width="80" class-name="num">
            <template slot-scope="scope">{{ formatFreq(scope.row.networkFreqMhz) }}</template>
          </el-table-column>
          <el-table-column label="波道" min-width="80">
            <template slot-scope="scope">{{ scope.row.commLinkChannelLabel || scope.row.commLinkChannel || "—" }}</template>
          </el-table-column>
          <el-table-column prop="role" label="角色" min-width="60">
            <template slot-scope="scope">{{ scope.row.role || "—" }}</template>
          </el-table-column>
          <el-table-column label="侦获次数" min-width="80" class-name="num">
            <template slot-scope="scope">{{ scope.row.detectCount != null ? scope.row.detectCount : "—" }}</template>
          </el-table-column>
        </el-table>
      </div>
    </template>
  </div>
</template>

<script>
import * as echarts from "echarts";
import { applyStreamTargetTypeLabels, formatFreq, targetTypeLabel } from "@/scene/sceneFilters.js";

export default {
  name: "SceneAwacsCommandNetViz",
  props: {
    commandNetPass: { type: Object, default: null }
  },
  data: function () {
    return {
      activePanelKey: ""
    };
  },
  computed: {
    panels: function () {
      var pass = this.commandNetPass;
      if (!pass || pass.skipped) return [];
      return pass.awacsPanels || [];
    },
    activePanel: function () {
      var list = this.panels;
      if (!list.length) return null;
      var self = this;
      return list.find(function (p) { return self.panelKey(p) === self.activePanelKey; }) || list[0];
    },
    labeledView: function () {
      var panel = this.activePanel;
      if (!panel || !panel.trajectoryView) return null;
      var labels = (panel.reportRows || []).map(function (r) {
        return {
          sceneRank: r.sceneRank,
          targetType: r.targetType,
          targetTypeLabel: r.targetTypeLabel || targetTypeLabel(r.targetType),
          freqMhz: r.networkFreqMhz,
          meanAzimuthDeg: r.meanAzimuthDeg != null ? r.meanAzimuthDeg : null,
          channel: r.commLinkChannel,
          channelLabel: r.commLinkChannelLabel,
          targetChannelsUsed: r.targetChannelsUsed
        };
      });
      return applyStreamTargetTypeLabels(Object.assign({}, panel.trajectoryView, { unified: true }), labels);
    },
    trackCount: function () {
      return ((this.labeledView && this.labeledView.tracks) || []).length;
    },
    rowCount: function () {
      return ((this.activePanel && this.activePanel.reportRows) || []).length;
    },
    tableRows: function () {
      return (this.activePanel && this.activePanel.reportRows) || [];
    }
  },
  watch: {
    panels: {
      immediate: true,
      handler: function (list) {
        if (!list.length) {
          this.activePanelKey = "";
          return;
        }
        var self = this;
        if (!list.some(function (p) { return self.panelKey(p) === self.activePanelKey; })) {
          this.activePanelKey = this.panelKey(list[0]);
        }
      }
    },
    activePanelKey: "scheduleRender",
    labeledView: "scheduleRender"
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
    formatFreq: formatFreq,
    targetTypeLabel: targetTypeLabel,
    panelKey: function (p) {
      if (!p) return "";
      if (p.panelId) return p.panelId;
      return (p.seedTargetId || "") + "@" + formatFreq(p.freqMhz);
    },
    scheduleRender: function () {
      var self = this;
      this.$nextTick(function () {
        self.renderChart();
      });
    },
    buildOption: function (v) {
      var self = this;
      var tracks = v && v.tracks ? v.tracks : [];
      var series = tracks.map(function (t) {
        return {
          name: self.legendName(t),
          type: "line",
          showSymbol: true,
          symbolSize: 5,
          lineStyle: { width: 1.5, color: t.color },
          itemStyle: { color: t.color },
          data: (t.points || []).map(function (p) { return [p.x, p.y]; })
        };
      });
      return {
        tooltip: {
          trigger: "item",
          formatter: function (p) {
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
    legendName: function (t) {
      var id = t.trackId != null ? " (#" + t.trackId + ")" : "";
      var base = t.label || "目标";
      var type = t.targetTypeLabel;
      if (type && type !== "—" && String(base).indexOf(type) < 0) {
        return base + "-" + type + id;
      }
      return base + id;
    },
    formatClock: function (ms) {
      return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
    },
    renderChart: function () {
      var v = this.labeledView;
      var el = this.$refs.chartEl;
      if (!el || !v || !(v.tracks || []).length) {
        if (this.chartInst) this.chartInst.clear();
        return;
      }
      if (!this.chartInst) {
        this.chartInst = echarts.init(el);
      }
      this.chartInst.setOption(this.buildOption(v), true);
    },
    onResize: function () {
      if (this.chartInst) this.chartInst.resize();
    }
  }
};
</script>

<style scoped>
.awacs-cn-viz {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid var(--theme-border);
}
.viz-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.viz-head h4 {
  margin: 0;
  font-size: 14px;
  color: var(--theme-text-primary);
}
.viz-meta {
  font-size: 12px;
  color: var(--theme-text-muted);
}
.seed-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.panel {
  border: 1px solid var(--theme-border);
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--theme-bg-deep);
}
.panel-title {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: baseline;
  font-size: 12px;
  color: var(--theme-text-secondary);
  margin-bottom: 6px;
}
.panel-title strong {
  color: var(--theme-text-primary);
  font-size: 13px;
}
.freq-badge {
  background: var(--theme-bg-panel-alt);
  color: var(--theme-text-accent);
  padding: 1px 6px;
  border-radius: 4px;
}
.chart-box {
  width: 100%;
  height: 320px;
  background: var(--theme-bg-panel);
  border-radius: 6px;
}
.viz-empty {
  font-size: 13px;
  color: var(--theme-text-muted);
  padding: 12px 0;
}
.viz-empty.inline {
  padding: 24px 0;
  text-align: center;
}
.hint {
  margin: 8px 0 0;
  font-size: 11px;
  color: var(--theme-text-muted);
}
.table-wrap {
  margin-top: 10px;
}
.table-wrap h5 {
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--theme-text-primary);
}
.mini-table {
  width: 100%;
}
</style>
