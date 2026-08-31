<!-- vue2-done -->
<template>
  <AppShell mode="ff-scatter">
    <div class="pdwfx-page ff-tool">
      <header class="head">
        <div>
          <h2>PrcFf 原始频率标绘</h2>
          <p class="sub">
            本地读取 PDW CSV（pl / xhfw / zcsj），按频率点选后画时间-方位散点。
            不做驻留窗、频段上下限、抽样。缺列或无法解析的行会跳过。
          </p>
        </div>
      </header>

      <section class="cet36-panel">
        <div class="cet36-panel__body">
          <label class="upload-btn">
            选择 CSV
            <input type="file" accept=".csv" hidden @change="onPickFile" />
          </label>
          <span v-if="fileName" class="file-name">{{ fileName }}</span>
          <p class="cet36-hint">可直接选工程目录中的 PrcFf1139.csv。大文件解析会在浏览器内完成，请稍候。</p>
          <p v-if="status" class="cet36-status">{{ status }}</p>
          <p v-if="error" class="cet36-error">{{ error }}</p>
        </div>
      </section>

      <section v-if="bands.length" class="cet36-panel">
        <div class="cet36-panel__body">
          <div class="plugin-head">
            <strong class="section-title">频率筛选</strong>
            <span class="plugin-meta">
              已标绘 {{ plottedKeys.length }} / 共 {{ bands.length }} 个频点 · 源数据 {{ parsedRows }} 行
              <template v-if="skipped"> · 无法解析 {{ skipped }}</template>
            </span>
          </div>
          <div class="plugin-row">
            <label>
              搜索
              <input v-model.trim="query" type="text" placeholder="如 451.175" class="search-input" />
            </label>
          </div>
          <div class="freq-chips">
            <button
              v-for="b in visibleBands"
              :key="b.key"
              type="button"
              class="freq-chip"
              :class="{ on: isPlotted(b.key) }"
              :title="b.freqMhz + ' MHz · ' + b.points.length + ' 点（单击只看该频；Ctrl+单击多选）'"
              @click="onChipClick($event, b.key)"
            >
              <i class="swatch" :style="{ background: colorOf(b.key) }" />
              {{ b.freqMhz.toFixed(3) }} MHz
              <span class="cnt">{{ b.points.length }}</span>
            </button>
            <p v-if="!visibleBands.length" class="empty">当前搜索无频点</p>
          </div>
        </div>
      </section>

      <section v-if="plottedSeries.length" class="cet36-panel">
        <header class="cet36-panel__title">
          <h3 class="cet36-panel__title-text">时间-方位</h3>
        </header>
        <div class="cet36-panel__body">
          <p class="meta">{{ plotMeta }}</p>
          <div ref="chartEl" class="chart" />
        </div>
      </section>
      <p v-else-if="bands.length" class="cet36-hint pad">点击频点标签进行标绘。</p>
    </div>
  </AppShell>
</template>

<script>
import AppShell from "./shell/AppShell.vue";
import * as echarts from "echarts";
import {
  AZIMUTH_Y_AXIS,
  IMPORT_FREQ_COLORS,
  IMPORT_SCATTER_GRID,
  timeAxisPad
} from "@/scene/importScatterChart.js";
import { parsePrcFfTimeAzimuth } from "@/scene/prcFfRawScatter.js";

export default {
  name: "PrcFfFreqScatterTool",
  components: { AppShell },
  data: function () {
    return {
      fileName: "",
      status: "",
      error: "",
      bands: [],
      parsedRows: 0,
      skipped: 0,
      query: "",
      plottedKeys: []
    };
  },
  computed: {
    visibleBands: function () {
      const q = String(this.query || "").trim();
      if (!q) return this.bands;
      const self = this;
      return this.bands.filter(function (b) {
        return String(b.freqMhz).indexOf(q) >= 0 || b.key.indexOf(q) >= 0;
      });
    },
    plottedSeries: function () {
      const want = {};
      this.plottedKeys.forEach(function (k) { want[k] = true; });
      return this.bands.filter(function (b) { return want[b.key]; });
    },
    plotMeta: function () {
      const n = this.plottedSeries.reduce(function (s, b) { return s + b.points.length; }, 0);
      return "图上 " + n + " 点（该频全部点，未抽样）";
    }
  },
  watch: {
    plottedKeys: {
      handler: function () {
        this.render();
      },
      deep: true
    }
  },
  mounted: function () {
    this.colorMap = new Map();
    this.chartInst = null;
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
    colorOf: function (key) {
      if (!this.colorMap.has(key)) {
        this.colorMap.set(key, IMPORT_FREQ_COLORS[this.colorMap.size % IMPORT_FREQ_COLORS.length]);
      }
      return this.colorMap.get(key);
    },
    isPlotted: function (key) {
      return this.plottedKeys.indexOf(key) >= 0;
    },
    onChipClick: function (event, key) {
      const multi = !!(event && (event.ctrlKey || event.metaKey));
      if (multi) {
        const i = this.plottedKeys.indexOf(key);
        this.plottedKeys =
          i >= 0 ? this.plottedKeys.filter(function (k) { return k !== key; }) : this.plottedKeys.concat([key]);
      } else {
        this.plottedKeys = [key];
      }
    },
    onPickFile: async function (e) {
      const file = e.target.files && e.target.files[0];
      e.target.value = "";
      if (!file) return;
      this.fileName = file.name;
      this.error = "";
      this.status = "正在读取并解析…";
      this.bands = [];
      this.plottedKeys = [];
      this.parsedRows = 0;
      this.skipped = 0;
      this.colorMap.clear();
      try {
        const text = await file.text();
        this.status = "正在按频率归组…";
        await this.$nextTick();
        await new Promise(function (resolve) { setTimeout(resolve, 0); });
        const parsed = parsePrcFfTimeAzimuth(text);
        this.parsedRows = parsed.rowCount;
        this.skipped = parsed.skipped;
        this.bands = parsed.bands;
        const self = this;
        parsed.bands.forEach(function (b) { self.colorOf(b.key); });
        this.status = "已解析 " + parsed.rowCount + " 行，" + parsed.bands.length + " 个频点";
      } catch (err) {
        this.error = (err && err.message) || String(err);
        this.status = "";
      }
    },
    render: async function () {
      await this.$nextTick();
      const chartEl = this.$refs.chartEl;
      if (!chartEl || !this.plottedSeries.length) {
        if (this.chartInst) {
          this.chartInst.dispose();
          this.chartInst = null;
        }
        return;
      }
      if (!this.chartInst) {
        this.chartInst = echarts.init(chartEl);
      }
      var xMin = Number.POSITIVE_INFINITY;
      var xMax = Number.NEGATIVE_INFINITY;
      const self = this;
      const series = this.plottedSeries.map(function (b) {
        const data = b.points;
        for (var i = 0; i < data.length; i++) {
          const x = data[i][0];
          if (x < xMin) xMin = x;
          if (x > xMax) xMax = x;
        }
        return {
          name: b.freqMhz.toFixed(3) + " MHz (" + b.points.length + ")",
          type: "scatter",
          symbolSize: 5,
          large: true,
          largeThreshold: 2000,
          itemStyle: { color: self.colorOf(b.key), opacity: 0.75 },
          data: data
        };
      });
      if (!Number.isFinite(xMin) || !Number.isFinite(xMax)) {
        this.chartInst.clear();
        return;
      }
      const pad = timeAxisPad(xMin, xMax);
      this.chartInst.setOption(
        {
          backgroundColor: "transparent",
          tooltip: {
            trigger: "item",
            formatter: function (params) {
              const t = new Date(params.value[0]);
              const time = Number.isNaN(t.getTime())
                ? params.value[0]
                : t.toLocaleString("zh-CN", { hour12: false });
              return params.seriesName + "<br/>时间: " + time + "<br/>方位: " + Number(params.value[1]).toFixed(1) + "°";
            }
          },
          legend: {
            type: "scroll",
            top: 4,
            left: "center",
            textStyle: { fontSize: 11, color: "#b3d2d5" }
          },
          grid: Object.assign({}, IMPORT_SCATTER_GRID),
          xAxis: {
            type: "time",
            name: "时间",
            nameLocation: "middle",
            nameGap: 38,
            nameTextStyle: { color: "#b3d2d5" },
            min: xMin - pad,
            max: xMax + pad,
            axisLabel: { fontSize: 10, hideOverlap: true, color: "#b3d2d5" },
            axisLine: { lineStyle: { color: "#2d6c83" } }
          },
          yAxis: {
            type: "value",
            name: "方位 (°)",
            nameTextStyle: { color: "#b3d2d5" },
            min: AZIMUTH_Y_AXIS.min,
            max: AZIMUTH_Y_AXIS.max,
            interval: AZIMUTH_Y_AXIS.interval,
            axisLabel: { fontSize: 11, color: "#b3d2d5" },
            axisLine: { lineStyle: { color: "#2d6c83" } },
            splitLine: { lineStyle: { color: "#2d6c83", type: "dashed", opacity: 0.4 } }
          },
          dataZoom: [
            { type: "inside", xAxisIndex: 0 },
            { type: "slider", xAxisIndex: 0, height: 20, bottom: 10 }
          ],
          series: series
        },
        true
      );
      this.chartInst.resize();
    },
    onResize: function () {
      if (this.chartInst) this.chartInst.resize();
    }
  }
};
</script>

<style scoped>
.ff-tool {
  max-width: 1280px;
  margin: 0 auto;
}
.head h2 {
  margin: 0 0 6px;
  font-size: 18px;
  color: var(--theme-text-accent);
}
.sub {
  margin: 0;
  font-size: 13px;
  color: var(--theme-text-secondary);
  line-height: 1.5;
}
.upload-btn {
  display: inline-block;
  background: var(--theme-button-primary-bg);
  color: var(--theme-button-primary-text);
  border: 1px solid var(--theme-button-primary-border);
  border-radius: 2px;
  padding: 6px 14px;
  font-size: 13px;
  cursor: pointer;
}
.upload-btn:hover {
  background: var(--theme-button-primary-hover-bg);
  color: var(--theme-button-primary-hover-text);
}
.file-name {
  margin-left: 10px;
  font-size: 13px;
  color: var(--theme-text-secondary);
}
.hint.pad {
  padding: 0 14px;
}
.section-title {
  color: var(--theme-text-accent);
}
.plugin-head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
  margin-bottom: 8px;
}
.plugin-meta {
  font-size: 12px;
  color: var(--theme-text-muted);
}
.plugin-row label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--theme-text-label);
  margin-bottom: 8px;
}
.search-input {
  border: 1px solid var(--theme-border-input);
  border-radius: 2px;
  padding: 4px 8px;
  font-size: 13px;
  min-width: 160px;
  background: var(--theme-bg-input);
  color: var(--theme-text-primary);
}
.freq-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 220px;
  overflow: auto;
}
.freq-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--theme-border);
  background: var(--theme-bg-panel-alt);
  border-radius: 999px;
  padding: 3px 10px 3px 6px;
  font-size: 12px;
  color: var(--theme-text-secondary);
  cursor: pointer;
}
.freq-chip.on {
  border-color: var(--theme-border-focus);
  background: var(--theme-bg-table-current);
  color: var(--theme-text-accent);
  font-weight: 600;
}
.swatch {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.cnt {
  color: var(--theme-text-muted);
  font-size: 11px;
}
.empty {
  margin: 4px 0;
  font-size: 12px;
  color: var(--theme-text-muted);
}
.meta {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--theme-text-muted);
}
.chart {
  width: 100%;
  height: 760px;
}
</style>
