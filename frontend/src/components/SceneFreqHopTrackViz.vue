<!-- vue2-done -->
<template>
  <div class="hop-viz">
    <div class="viz-head">
      <h4>换频研判轨迹</h4>
      <span v-if="view" class="viz-meta">
        {{ plottedCount }} / {{ view.trackCount != null ? view.trackCount : 0 }} 个目标 · 跨频
        {{ view.hopTrackCount != null ? view.hopTrackCount : 0 }}
        <template v-if="view.noiseSkippedCount">
          · 已隐藏噪声 {{ view.noiseSkippedCount }}
        </template>
        · {{ freqLegendCount }} 个频点着色
        <template v-if="omittedFreqUsed">
          · 含概览未标绘频点
        </template>
      </span>
    </div>
    <div v-if="view" class="type-filters">
      <el-button
        v-for="opt in typeFilters"
        :key="opt.value || 'all'"
        size="mini"
        :type="typeFilter === opt.value ? 'primary' : 'default'"
        @click="typeFilter = opt.value"
      >
        {{ opt.label }}
      </el-button>
    </div>
    <div v-if="!view" class="viz-empty">暂无换频研判数据（需重新分析本批）</div>
    <div v-else class="panel">
      <div class="panel-title">
        <strong>{{ view.title || "换频研判" }}</strong>
        <span>{{ panelMeta }}</span>
      </div>
      <div
        ref="chartEl"
        class="chart-box"
        :style="{ height: IMPORT_SCATTER_CHART_HEIGHT + 'px' }"
      />
      <p v-if="typeFilter && !plottedCount" class="hint">当前类型筛选无轨迹</p>
      <p v-if="view.note" class="scene-note">{{ view.note }}</p>
      <p class="hint">
        与全量数据概览同尺度：图高 {{ IMPORT_SCATTER_CHART_HEIGHT }}px，纵轴 0–360° / 每格
        {{ AZIMUTH_Y_AXIS.interval }}°，同频同色（Top{{ MAX_IMPORT_SCATTER_FREQS }}）；概览未标绘频点为灰色。颜色变化即换频。噪声链不显示。
        目标-波道表中「换频xxx(未入场景)」表示该频点仅由换频衔接并入轨迹，未进入 TOP-K 场景信号分析。
      </p>
      <div
        v-if="showChannelMatrix && channelMatrix.columns.length && channelMatrix.rows.length"
        class="channel-matrix-block"
      >
        <h6 class="matrix-title">目标-波道表</h6>
        <p class="matrix-desc">
          横轴为 D01–D06。已分析频点直接标注；仅换频衔接、未入 TOP-K 场景的频点标为「换频…(未入场景)」。
        </p>
        <div class="matrix-scroll">
          <el-table :data="channelMatrix.rows" size="mini" border class="channel-matrix">
            <el-table-column prop="label" label="目标 \ 波道" min-width="120" fixed />
            <el-table-column
              v-for="(col, ci) in channelMatrix.columns"
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
    </div>
  </div>
</template>

<script>
import * as echarts from "echarts";
import {
  AZIMUTH_Y_AXIS,
  IMPORT_SCATTER_CHART_HEIGHT,
  IMPORT_SCATTER_GRID,
  MAX_IMPORT_SCATTER_FREQS,
  OMITTED_FREQ_COLOR,
  buildFreqColorMapByPointRank,
  buildFreqColorMapFromScatter,
  roundFreq3,
  scatterTimeRange,
  timeAxisPad
} from "@/scene/importScatterChart.js";
import { buildHopTargetChannelMatrix, formatHopTrackFreqLabel, HOP_TYPE_FILTERS, hopTrackMatchesType } from "@/scene/hopChannelMatrix.js";

export default {
  name: "SceneFreqHopTrackViz",
  props: {
    hoppingTrackViews: { type: Array, default: function () { return []; } },
    importScatter: { type: Object, default: null },
    reportRows: { type: Array, default: function () { return []; } },
    showChannelMatrix: { type: Boolean, default: true }
  },
  data: function () {
    return {
      omittedFreqUsed: false,
      typeFilter: "",
      typeFilters: HOP_TYPE_FILTERS,
      IMPORT_SCATTER_CHART_HEIGHT: IMPORT_SCATTER_CHART_HEIGHT,
      AZIMUTH_Y_AXIS: AZIMUTH_Y_AXIS,
      MAX_IMPORT_SCATTER_FREQS: MAX_IMPORT_SCATTER_FREQS
    };
  },
  computed: {
    view: function () {
      var list = this.hoppingTrackViews || [];
      if (!list.length) return null;
      var unified = list.find(function (v) { return v && v.unified; }) || list[0];
      if (!unified || !unified.tracks || !unified.tracks.length) return null;
      return unified;
    },
    plottedTracks: function () {
      var self = this;
      var tracks = ((this.view && this.view.tracks) || []).filter(function (t) { return !t.noiseCandidate; });
      return tracks.filter(function (t) { return hopTrackMatchesType(t, self.typeFilter); });
    },
    plottedCount: function () {
      return this.plottedTracks.length;
    },
    freqColorMap: function () {
      return this.resolveFreqColorMap(this.view, this.importScatter);
    },
    freqLegendCount: function () {
      return this.freqColorMap.size;
    },
    panelMeta: function () {
      var v = this.view;
      if (!v) return "";
      var n = this.plottedCount;
      var total = v.trackCount != null ? v.trackCount : 0;
      var countText = this.typeFilter && n !== total ? n + "/" + total : String(total);
      return (v.timeRange || "") + " · " + countText + " 条轨迹";
    },
    channelMatrix: function () {
      if (!this.view) {
        return buildHopTargetChannelMatrix(null, this.reportRows);
      }
      return buildHopTargetChannelMatrix(
        Object.assign({}, this.view, { tracks: this.plottedTracks }),
        this.reportRows
      );
    }
  },
  watch: {
    hoppingTrackViews: { handler: "onDataChange", deep: true },
    importScatter: "onDataChange",
    typeFilter: "onTypeFilterChange"
  },
  mounted: function () {
    var self = this;
    this.$nextTick(function () {
      self.renderChart(false);
    });
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
    onDataChange: function () {
      var self = this;
      this.$nextTick(function () {
        self.renderChart(false);
      });
    },
    onTypeFilterChange: function () {
      var self = this;
      this.$nextTick(function () {
        self.renderChart(true);
      });
    },
    resolveFreqColorMap: function (v, scatter) {
      var fromScatter = buildFreqColorMapFromScatter(scatter);
      if (fromScatter.size) return fromScatter;
      return buildFreqColorMapByPointRank(this.collectFreqCounts(v));
    },
    collectFreqCounts: function (v) {
      var counts = new Map();
      (v && v.tracks ? v.tracks : []).forEach(function (t) {
        if (t.noiseCandidate) return;
        (t.points || []).forEach(function (p) {
          var f = roundFreq3(p.freqMhz);
          if (f == null) return;
          counts.set(f, (counts.get(f) || 0) + 1);
        });
      });
      return Array.from(counts.entries()).map(function (entry) {
        return { freqMhz: entry[0], count: entry[1] };
      });
    },
    colorForFreq: function (colorByFreq, freq) {
      if (freq == null) return OMITTED_FREQ_COLOR;
      if (colorByFreq.has(freq)) return colorByFreq.get(freq);
      return OMITTED_FREQ_COLOR;
    },
    formatFreqLabel: function (f) {
      if (f == null) return "未知频点";
      return Number(f).toFixed(3) + " MHz";
    },
    targetBaseName: function (t) {
      var base = t.label || "目标" + t.trackId;
      var type = t.targetTypeLabel || "";
      var withType = base;
      if (type && type !== "—" && String(base).indexOf(type) < 0) {
        withType = base + "-" + type;
      }
      return formatHopTrackFreqLabel(withType, t, this.reportRows);
    },
    renderChart: function (resetInteraction) {
      var self = this;
      var el = this.$refs.chartEl;
      if (!el || !this.view) {
        if (this.chartInst) {
          this.chartInst.dispose();
          this.chartInst = null;
        }
        return Promise.resolve();
      }
      if (!this.chartInst) {
        this.chartInst = echarts.init(el);
      }
      var legendSelected = null;
      var zoom = null;
      if (!resetInteraction && this.chartInst.getOption) {
        try {
          var cur = this.chartInst.getOption();
          legendSelected = (cur.legend && cur.legend[0] && cur.legend[0].selected) || null;
          zoom = (cur.dataZoom || []).map(function (z) { return { start: z.start, end: z.end }; });
        } catch (_) { /* ignore */ }
      }
      var opt = this.buildOption(this.view, this.freqColorMap, this.plottedTracks);
      if (legendSelected) {
        opt.legend = Object.assign({}, opt.legend || {}, { selected: legendSelected });
      }
      if (zoom && zoom.length) {
        opt.dataZoom = (opt.dataZoom || []).map(function (z, i) {
          return Object.assign({}, z, {
            start: (zoom[i] && zoom[i].start != null) ? zoom[i].start : z.start,
            end: (zoom[i] && zoom[i].end != null) ? zoom[i].end : z.end
          });
        });
      }
      this.chartInst.setOption(opt, true);
      return this.$nextTick().then(function () {
        self.chartInst.resize();
      });
    },
    normalizeBearing: function (deg) {
      var d = Number(deg);
      if (!Number.isFinite(d)) return d;
      d %= 360;
      if (d < 0) d += 360;
      return d;
    },
    resolveXRange: function (v) {
      var fromScatter = scatterTimeRange(this.importScatter);
      if (fromScatter) return fromScatter;
      var xMin = Number(v.xMin);
      var xMax = Number(v.xMax);
      if (Number.isFinite(xMin) && Number.isFinite(xMax)) {
        var pad = timeAxisPad(xMin, xMax);
        return { xMin: xMin - pad, xMax: xMax + pad };
      }
      return { xMin: undefined, xMax: undefined };
    },
    buildOption: function (v, colorByFreq, tracks) {
      var self = this;
      var series = [];
      var legendNames = [];
      var usedOmitted = false;

      var pickColor = function (freq) {
        var c = self.colorForFreq(colorByFreq, freq);
        if (freq != null && !colorByFreq.has(freq)) usedOmitted = true;
        return c;
      };

      (tracks || []).forEach(function (t) {
        var base = self.targetBaseName(t);
        var normalizedPts = (t.points || []).map(function (p) {
          return Object.assign({}, p, { y: self.normalizeBearing(p.y) });
        });
        var segments = self.splitByFrequency(normalizedPts);
        var prevLast = null;
        for (var i = 0; i < segments.length; i++) {
          var seg = segments[i];
          var color = pickColor(seg.freq);
          var omittedTag = seg.freq != null && !colorByFreq.has(seg.freq) ? "（概览未标绘）" : "";
          var name = base + " · " + self.formatFreqLabel(seg.freq) + omittedTag;
          if (legendNames.indexOf(name) < 0) legendNames.push(name);

          if (prevLast && seg.points.length) {
            series.push({
              name: base + "·衔接",
              type: "line",
              showSymbol: false,
              lineStyle: { width: 1, color: "#9ca3af", type: "dashed", opacity: 0.7 },
              data: [prevLast, seg.points[0]],
              tooltip: { show: false },
              legendHoverLink: false
            });
          }

          series.push({
            name: name,
            type: "line",
            showSymbol: true,
            symbolSize: 5,
            lineStyle: { width: 2, color: color },
            itemStyle: { color: color },
            data: seg.points
          });
          prevLast = seg.points[seg.points.length - 1];
        }
        if (!segments.length) {
          var fallbackColor = t.color || OMITTED_FREQ_COLOR;
          var fallbackName = base;
          if (legendNames.indexOf(fallbackName) < 0) legendNames.push(fallbackName);
          series.push({
            name: fallbackName,
            type: "line",
            showSymbol: true,
            symbolSize: 5,
            lineStyle: { width: 2, color: fallbackColor },
            itemStyle: { color: fallbackColor },
            data: normalizedPts.map(function (p) { return [p.x, p.y]; })
          });
        }
      });

      this.omittedFreqUsed = usedOmitted;
      var range = this.resolveXRange(v);

      return {
        tooltip: {
          trigger: "axis",
          formatter: function (params) {
            if (!params || !params.length) return "";
            var visible = params.filter(function (p) {
              return !String(p.seriesName || "").endsWith("·衔接");
            });
            if (!visible.length) return "";
            var t = self.formatClock(visible[0].value[0]);
            var lines = visible.map(function (p) {
              return p.marker + p.seriesName + ": " + Number(p.value[1]).toFixed(1) + "°";
            });
            return t + "<br/>" + lines.join("<br/>");
          }
        },
        legend: {
          top: 4,
          left: "center",
          type: "scroll",
          data: legendNames,
          textStyle: { fontSize: 11 }
        },
        grid: Object.assign({}, IMPORT_SCATTER_GRID),
        xAxis: {
          type: "time",
          name: "时间",
          nameLocation: "middle",
          nameGap: 38,
          min: range.xMin,
          max: range.xMax,
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
        series: series
      };
    },
    splitByFrequency: function (points) {
      var self = this;
      var segs = [];
      var cur = null;
      (points || []).forEach(function (p) {
        var f = roundFreq3(p.freqMhz);
        var xy = [p.x, self.normalizeBearing(p.y)];
        if (f == null) {
          if (!cur) {
            cur = { freq: null, points: [] };
            segs.push(cur);
          }
          cur.points.push(xy);
          return;
        }
        if (!cur || cur.freq !== f) {
          cur = { freq: f, points: [] };
          segs.push(cur);
        }
        cur.points.push(xy);
      });
      return segs.filter(function (s) { return s.points.length; });
    },
    formatClock: function (ms) {
      return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
    },
    onResize: function () {
      if (this.chartInst) this.chartInst.resize();
    }
  }
};
</script>

<style scoped>
.hop-viz { margin-top: 0; }
.viz-head {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}
.viz-head h4 { margin: 0; font-size: 14px; color: var(--theme-text-primary); }
.viz-meta { font-size: 12px; color: var(--theme-text-muted); }
.type-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 0 10px;
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
  gap: 10px;
  align-items: baseline;
  font-size: 13px;
  margin-bottom: 6px;
  color: var(--theme-text-primary);
}
.panel-title span { color: var(--theme-text-muted); font-size: 12px; }
.chart-box {
  width: 100%;
  background: var(--theme-bg-panel);
  border-radius: 6px;
}
.viz-empty { color: var(--theme-text-muted); font-size: 13px; padding: 12px 0; }
.scene-note { font-size: 12px; color: var(--theme-text-secondary); margin: 8px 0 0; }
.hint { font-size: 12px; color: var(--theme-text-muted); margin: 6px 0 0; }
.channel-matrix-block {
  margin: 12px 0 0;
  padding: 10px 12px;
  background: var(--theme-bg-panel);
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
</style>
