<!-- vue2-done -->
<template>
  <section class="import-scatter" :class="{ embedded: embedded }">
    <div class="head">
      <h3>{{ title }}</h3>
      <span v-if="meta" class="meta">{{ meta }}</span>
    </div>
    <p class="hint">
      本次导入 CSV 的检测点（经频段筛选后）：横轴时间、纵轴方位。点击频点标签即可只标绘该频率；按住 Ctrl 可多选叠加。默认显示数据量最多的
      {{ MAX_IMPORT_SCATTER_FREQS }} 个频点。
    </p>
    <p v-if="loading" class="status">正在加载并标绘散点…</p>
    <p v-else-if="error" class="status error">{{ error }}</p>
    <p v-else-if="!hasSourceData" class="status">暂无散点数据。</p>

    <div v-if="hasSourceData && !loading" class="freq-plugin">
      <div class="plugin-head">
        <strong>频率筛选</strong>
        <span class="plugin-meta">
          已标绘 {{ plottedFreqKeys.length }} / 共 {{ allFreqOptions.length }} 个频点
        </span>
        <el-button type="text" size="mini" @click="pluginOpen = !pluginOpen">
          {{ pluginOpen ? "收起" : "展开" }}
        </el-button>
      </div>
      <div v-show="pluginOpen" class="plugin-body">
        <div class="plugin-row">
          <label>
            下限 MHz
            <input v-model.number="filterMin" type="number" step="0.001" />
          </label>
          <label>
            上限 MHz
            <input v-model.number="filterMax" type="number" step="0.001" />
          </label>
          <label class="grow">
            搜索
            <input v-model.trim="filterQuery" type="text" placeholder="如 306.925" />
          </label>
        </div>
        <div class="plugin-actions">
          <el-button size="mini" @click="selectDefaultTop">默认 Top{{ MAX_IMPORT_SCATTER_FREQS }}</el-button>
          <el-button size="mini" @click="selectVisible">全选可见并标绘</el-button>
          <el-button size="mini" @click="clearSelection">清空</el-button>
          <el-button type="primary" size="mini" @click="applyAndRender">标绘到时间-方位图</el-button>
        </div>
        <div class="freq-chips">
          <el-button
            v-for="opt in visibleFreqOptions"
            :key="opt.key"
            size="mini"
            :type="isPlotted(opt.key) ? 'primary' : 'default'"
            :class="{ top: opt.defaultPlot }"
            :title="opt.label + ' · ' + opt.totalPoints + ' 点（单击只看该频；Ctrl+单击多选）'"
            @click="onFreqChipClick($event, opt.key)"
          >
            <i class="swatch" :style="{ background: opt.color }" />
            {{ opt.label }}
            <span class="cnt">{{ opt.totalPoints }}</span>
          </el-button>
          <p v-if="!visibleFreqOptions.length" class="empty-filter">当前筛选条件下无频点</p>
        </div>
      </div>
    </div>

    <p v-if="hasSourceData && !loading && !hasPlottedData" class="status">请点击频点标签进行标绘。</p>
    <div
      v-show="hasPlottedData && !loading"
      ref="chartEl"
      class="chart-box"
      :style="{ height: IMPORT_SCATTER_CHART_HEIGHT + 'px' }"
    />
  </section>
</template>

<script>
import * as echarts from "echarts";
import {
  AZIMUTH_Y_AXIS,
  IMPORT_FREQ_COLORS,
  IMPORT_SCATTER_CHART_HEIGHT,
  IMPORT_SCATTER_GRID,
  MAX_IMPORT_SCATTER_FREQS,
  roundFreq3,
  timeAxisPad
} from "@/scene/importScatterChart.js";

export default {
  name: "ImportDataScatterViz",
  props: {
    scatter: { type: Object, default: null },
    loading: { type: Boolean, default: false },
    error: { type: String, default: "" },
    title: { type: String, default: "全量数据概览" },
    embedded: { type: Boolean, default: false }
  },
  data: function () {
    return {
      pluginOpen: true,
      filterMin: null,
      filterMax: null,
      filterQuery: "",
      selectedFreqKeys: [],
      plottedFreqKeys: [],
      IMPORT_SCATTER_CHART_HEIGHT: IMPORT_SCATTER_CHART_HEIGHT,
      MAX_IMPORT_SCATTER_FREQS: MAX_IMPORT_SCATTER_FREQS
    };
  },
  computed: {
    sourceScatter: function () {
      return this.ensureSeriesColors(this.scatter);
    },
    allFreqOptions: function () {
      var s = this.sourceScatter;
      var fromSeries = (s && s.series ? s.series : []).map(function (ser, i) {
        var f = roundFreq3(ser.freqMhz);
        return {
          key: this.freqKey(ser.freqMhz),
          freqMhz: f,
          label: ser.label || (f != null ? f + " MHz" : "—"),
          color: ser.color || IMPORT_FREQ_COLORS[i % IMPORT_FREQ_COLORS.length],
          totalPoints: ser.totalPoints || (ser.points ? ser.points.length : 0) || 0,
          defaultPlot: ser.defaultPlot === true || i < MAX_IMPORT_SCATTER_FREQS,
          series: ser
        };
      }.bind(this)).filter(function (o) { return o.key; });
      if (fromSeries.length) return fromSeries;

      return (s && s.freqCatalog ? s.freqCatalog : []).map(function (c, i) {
        var f = roundFreq3(c.freqMhz);
        return {
          key: this.freqKey(c.freqMhz),
          freqMhz: f,
          label: f != null ? Number(f).toFixed(3) + " MHz" : "—",
          color: IMPORT_FREQ_COLORS[i % IMPORT_FREQ_COLORS.length],
          totalPoints: c.totalPoints || 0,
          defaultPlot: c.defaultPlot === true || i < MAX_IMPORT_SCATTER_FREQS,
          series: null
        };
      }.bind(this)).filter(function (o) { return o.key; });
    },
    visibleFreqOptions: function () {
      var q = String(this.filterQuery || "").trim();
      var min = Number(this.filterMin);
      var max = Number(this.filterMax);
      var hasMin = Number.isFinite(min);
      var hasMax = Number.isFinite(max);
      return this.allFreqOptions.filter(function (o) {
        if (hasMin && o.freqMhz != null && o.freqMhz < min) return false;
        if (hasMax && o.freqMhz != null && o.freqMhz > max) return false;
        if (q && String(o.label).indexOf(q) < 0 && String(o.freqMhz).indexOf(q) < 0) return false;
        return true;
      });
    },
    hasSourceData: function () {
      return (this.sourceScatter && this.sourceScatter.series ? this.sourceScatter.series : [])
        .some(function (s) { return (s.points || []).length; });
    },
    plottedSeries: function () {
      var want = {};
      this.plottedFreqKeys.forEach(function (k) { want[k] = true; });
      if (!this.plottedFreqKeys.length) return [];
      return this.allFreqOptions
        .filter(function (o) { return want[o.key] && o.series && (o.series.points || []).length; })
        .map(function (o) { return o.series; });
    },
    hasPlottedData: function () {
      return this.plottedSeries.length > 0;
    },
    meta: function () {
      var s = this.sourceScatter;
      if (!s) return "";
      var total = s.totalPoints != null ? s.totalPoints : 0;
      var shown = this.plottedSeries.reduce(function (n, ser) {
        return n + (ser.displayedPoints != null ? ser.displayedPoints : (ser.points ? ser.points.length : 0) || 0);
      }, 0);
      var totalFreq = s.totalFreqCount != null ? s.totalFreqCount : this.allFreqOptions.length;
      var selected = this.plottedFreqKeys.length;
      var sampled = total > 0 && shown < total ? " · 抽样约 " + shown + " 点" : "";
      return "标绘 " + selected + " / 共 " + totalFreq + " 频点 · 源数据 " + total + " 点" + sampled;
    }
  },
  watch: {
    scatter: { handler: "onScatterChange", deep: true },
    loading: "onScatterChange"
  },
  mounted: function () {
    if (!this.loading) {
      this.resetSelectionFromScatter();
      this.render();
    }
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
    freqKey: function (mhz) {
      var f = roundFreq3(mhz);
      return f == null ? "" : String(f);
    },
    ensureSeriesColors: function (raw) {
      if (!raw || !raw.series || !raw.series.length) return raw;
      var series = raw.series.map(function (s, i) {
        return Object.assign({}, s, {
          color: s.color || IMPORT_FREQ_COLORS[i % IMPORT_FREQ_COLORS.length]
        });
      });
      return Object.assign({}, raw, { series: series });
    },
    isPlotted: function (key) {
      return this.plottedFreqKeys.indexOf(key) >= 0;
    },
    onFreqChipClick: function (event, key) {
      var multi = !!(event && (event.ctrlKey || event.metaKey));
      if (multi) {
        var i = this.selectedFreqKeys.indexOf(key);
        if (i >= 0) {
          this.selectedFreqKeys = this.selectedFreqKeys.filter(function (k) { return k !== key; });
        } else {
          this.selectedFreqKeys = this.selectedFreqKeys.concat([key]);
        }
      } else {
        this.selectedFreqKeys = [key];
      }
      this.applyAndRender();
    },
    selectDefaultTop: function () {
      this.selectedFreqKeys = this.allFreqOptions
        .filter(function (o) { return o.defaultPlot; })
        .slice(0, MAX_IMPORT_SCATTER_FREQS)
        .map(function (o) { return o.key; });
      if (!this.selectedFreqKeys.length) {
        this.selectedFreqKeys = this.allFreqOptions
          .slice(0, MAX_IMPORT_SCATTER_FREQS)
          .map(function (o) { return o.key; });
      }
      this.applyAndRender();
    },
    selectVisible: function () {
      this.selectedFreqKeys = this.visibleFreqOptions.map(function (o) { return o.key; });
      this.applyAndRender();
    },
    clearSelection: function () {
      this.selectedFreqKeys = [];
      this.applyAndRender();
    },
    applyAndRender: function () {
      this.plottedFreqKeys = this.selectedFreqKeys.slice();
      this.render();
    },
    resetSelectionFromScatter: function () {
      var opts = this.allFreqOptions;
      if (!opts.length) {
        this.selectedFreqKeys = [];
        this.plottedFreqKeys = [];
        return;
      }
      var defaults = opts.filter(function (o) { return o.defaultPlot; }).map(function (o) { return o.key; });
      var keys = defaults.length
        ? defaults.slice(0, MAX_IMPORT_SCATTER_FREQS)
        : opts.slice(0, MAX_IMPORT_SCATTER_FREQS).map(function (o) { return o.key; });
      this.selectedFreqKeys = keys;
      this.plottedFreqKeys = keys.slice();

      var freqs = opts.map(function (o) { return o.freqMhz; }).filter(function (n) { return n != null; });
      if (freqs.length) {
        this.filterMin = Math.min.apply(null, freqs);
        this.filterMax = Math.max.apply(null, freqs);
      } else {
        this.filterMin = null;
        this.filterMax = null;
      }
      this.filterQuery = "";
    },
    buildSeriesAndRange: function () {
      var xMin = Number.POSITIVE_INFINITY;
      var xMax = Number.NEGATIVE_INFINITY;
      var series = this.plottedSeries.map(function (s) {
        var data = (s.points || []).map(function (p) {
          var x = Number(p[0]);
          var y = Number(p[1]);
          if (!Number.isFinite(x) || !Number.isFinite(y)) return null;
          xMin = Math.min(xMin, x);
          xMax = Math.max(xMax, x);
          return [x, y];
        }).filter(Boolean);
        return {
          name: s.label || s.freqMhz + " MHz",
          type: "scatter",
          symbolSize: 5,
          itemStyle: { color: s.color || "#51e9ff", opacity: 0.8 },
          emphasis: { focus: "series" },
          data: data
        };
      }).filter(function (s) { return s.data.length > 0; });
      return { series: series, xMin: xMin, xMax: xMax };
    },
    render: function () {
      var self = this;
      return this.$nextTick().then(function () {
        var el = self.$refs.chartEl;
        if (!el || !self.hasPlottedData) {
          if (self.chartInst) {
            self.chartInst.dispose();
            self.chartInst = null;
          }
          return;
        }
        if (!self.chartInst) {
          self.chartInst = echarts.init(el);
        }
        var built = self.buildSeriesAndRange();
        if (!built.series.length) {
          self.chartInst.clear();
          return;
        }
        var pad = timeAxisPad(built.xMin, built.xMax);
        self.chartInst.setOption({
          tooltip: {
            trigger: "item",
            formatter: function (params) {
              var t = new Date(params.value[0]);
              var time = Number.isNaN(t.getTime())
                ? params.value[0]
                : t.toLocaleString("zh-CN", { hour12: false });
              return params.seriesName + "<br/>时间: " + time + "<br/>方位: " + Number(params.value[1]).toFixed(1) + "°";
            }
          },
          legend: {
            type: "scroll",
            top: 4,
            left: "center",
            textStyle: { fontSize: 11 }
          },
          grid: Object.assign({}, IMPORT_SCATTER_GRID),
          xAxis: {
            type: "time",
            name: "时间",
            nameLocation: "middle",
            nameGap: 38,
            min: built.xMin - pad,
            max: built.xMax + pad,
            axisLabel: { fontSize: 10, hideOverlap: true }
          },
          yAxis: {
            type: "value",
            name: "方位 (°)",
            min: AZIMUTH_Y_AXIS.min,
            max: AZIMUTH_Y_AXIS.max,
            interval: AZIMUTH_Y_AXIS.interval,
            nameTextStyle: { fontSize: 11 },
            axisLabel: { fontSize: 11 },
            splitLine: { lineStyle: { color: "rgba(45, 108, 131, 0.35)", type: "dashed" } }
          },
          dataZoom: [
            { type: "inside", xAxisIndex: 0 },
            { type: "slider", xAxisIndex: 0, height: 20, bottom: 10 }
          ],
          series: built.series
        }, true);
        self.chartInst.resize();
      });
    },
    onScatterChange: function () {
      if (this.loading) return;
      this.resetSelectionFromScatter();
      this.render();
    },
    onResize: function () {
      if (this.chartInst) this.chartInst.resize();
    }
  }
};
</script>

<style scoped>
.import-scatter {
  margin-bottom: 14px;
  border: 1px solid var(--theme-border);
  border-radius: 8px;
  padding: 12px 14px;
  background: var(--theme-bg-panel);
}
.import-scatter.embedded {
  margin-bottom: 0;
  border: none;
  border-radius: 0;
  padding: 0;
  background: transparent;
}
.head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
}
.head h3 {
  margin: 0;
  font-size: 1rem;
  color: var(--theme-text-primary);
}
.meta {
  font-size: 12px;
  color: var(--theme-text-muted);
}
.hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--theme-text-muted);
  line-height: 1.45;
}
.status {
  font-size: 13px;
  color: var(--theme-text-accent);
  margin: 0 0 8px;
}
.status.error {
  color: #ee6666;
}
.chart-box {
  width: 100%;
}
.freq-plugin {
  margin: 0 0 12px;
  border: 1px solid var(--theme-border);
  border-radius: 6px;
  background: var(--theme-bg-deep);
  padding: 8px 10px;
}
.plugin-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.plugin-head strong {
  font-size: 13px;
  color: var(--theme-text-primary);
}
.plugin-meta {
  font-size: 12px;
  color: var(--theme-text-muted);
  flex: 1;
}
.plugin-body {
  margin-top: 8px;
}
.plugin-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 8px;
}
.plugin-row label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--theme-text-secondary);
}
.plugin-row label.grow {
  flex: 1;
  min-width: 140px;
}
.plugin-row input {
  border: 1px solid var(--theme-border-input);
  border-radius: 4px;
  padding: 4px 8px;
  font-size: 13px;
  min-width: 100px;
  background: var(--theme-bg-input);
  color: var(--theme-text-primary);
}
.plugin-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.freq-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 160px;
  overflow: auto;
  padding: 2px 0;
}
.freq-chips .top {
  border-style: dashed;
}
.swatch {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  margin-right: 4px;
}
.cnt {
  color: var(--theme-text-muted);
  font-size: 11px;
  margin-left: 4px;
}
.empty-filter {
  margin: 4px 0;
  font-size: 12px;
  color: var(--theme-text-muted);
}
</style>
