<!-- vue2-done -->
<template>
  <div class="charts-wrap">
    <div class="chart-block">
      <div ref="chartTarget" class="chart-box" />
      <p class="chart-desc">
        按目标类型统计编批结果中的目标条数，用于快速了解场景中地面站、预警机、飞机等平台的构成比例。
      </p>
    </div>
    <div class="chart-block">
      <div ref="chartComm" class="chart-box" />
      <p class="chart-desc">
        统计各通信波道（D01–D06 等）关联的目标数量，帮助识别当前场景中哪些波道活动最密集、需优先关注。
      </p>
    </div>

    <section class="channel-cross">
      <h4>目标-波道表</h4>
      <p class="section-desc">
        <template v-if="sceneGrouped">
          按场景汇总：目标列标明类型与频率（如 T1-飞机 306.925）；波道列合并同场景下的相同波道条目。
        </template>
        <template v-else>
          横轴为 D01–D06，纵轴为本批全部目标（标注频率，不按场景拆分）；占用该波道则打勾。
        </template>
      </p>
      <div v-if="!sceneGrouped" class="matrix-scroll">
        <el-table v-if="channelMatrix.rows.length" :data="channelMatrix.rows" size="mini" border class="channel-matrix">
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
        <p v-else class="empty">暂无数据</p>
      </div>
      <div v-else class="cross-grid">
        <div class="cross-panel">
          <h5>各目标占用波道</h5>
          <div class="list-scroll">
            <table v-if="targetToChannelsRows.length" class="merge-table">
              <thead>
                <tr>
                  <th v-if="sceneGrouped">场景</th>
                  <th>目标</th>
                  <th>波道</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, i) in targetToChannelsRows" :key="'t' + i">
                  <td
                    v-if="sceneGrouped && row.showScene"
                    :rowspan="row.spanScene"
                    class="merge-cell"
                  >
                    {{ row.sceneLabel }}
                  </td>
                  <td>{{ row.targetLabel }}</td>
                  <td
                    v-if="row.showChannel"
                    :rowspan="row.spanChannel"
                    class="merge-cell"
                  >
                    {{ row.channel }}
                  </td>
                </tr>
              </tbody>
            </table>
            <p v-else class="empty">暂无数据</p>
          </div>
        </div>
        <div class="cross-panel">
          <h5>各波道关联目标</h5>
          <div class="list-scroll">
            <table v-if="channelToTargetsRows.length" class="merge-table">
              <thead>
                <tr>
                  <th v-if="sceneGrouped">场景</th>
                  <th>波道</th>
                  <th>目标</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, i) in channelToTargetsRows" :key="'c' + i">
                  <td
                    v-if="sceneGrouped && row.showScene"
                    :rowspan="row.spanScene"
                    class="merge-cell"
                  >
                    {{ row.sceneLabel }}
                  </td>
                  <td
                    v-if="row.showChannel"
                    :rowspan="row.spanChannel"
                    class="merge-cell"
                  >
                    {{ row.channel }}
                  </td>
                  <td>{{ row.targets }}</td>
                </tr>
              </tbody>
            </table>
            <p v-else class="empty">暂无数据</p>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script>
import { initCet36Chart, applyCet36Theme, CET36_CHART_COLORS } from "@/scene/echartsTheme.js";
import { formatFreq, formatSceneLabel, targetTypeLabel } from "@/scene/sceneFilters.js";
import {
  buildHopTargetChannelMatrix,
  buildTargetChannelMatrixFromRows
} from "@/scene/hopChannelMatrix.js";

export default {
  name: "SceneResultsCharts",
  props: {
    rows: { type: Array, default: function () { return []; } },
    hoppingTrackViews: { type: Array, default: function () { return []; } },
    sceneGrouped: { type: Boolean, default: true }
  },
  computed: {
    targetToChannelsRows: function () {
      var seen = new Set();
      var list = [];
      var self = this;
      (this.rows || []).forEach(function (r) {
        if (!r.targetId) return;
        var chText = r.targetChannelsUsed || self.channelLabel(r);
        var channels = self.splitChannels(chText);
        var chList = channels.length ? channels : (self.channelLabel(r) ? [self.channelLabel(r)] : ["未研判"]);
        var targetLabel = self.targetDisplayLabel(r.targetId, r.targetType, r.targetTypeLabel, r.networkFreqMhz);
        chList.forEach(function (ch) {
          var dedupeKey = self.sceneGrouped
            ? r.sceneRank + "|" + r.targetId + "|" + formatFreq(r.networkFreqMhz) + "|" + ch
            : targetLabel + "|" + ch;
          if (seen.has(dedupeKey)) return;
          seen.add(dedupeKey);
          list.push({
            sceneRank: r.sceneRank,
            sceneType: r.sceneType,
            sceneLabel: formatSceneLabel(r.sceneRank, r.sceneType, r),
            targetLabel: targetLabel,
            channel: ch
          });
        });
      });
      list.sort(function (a, b) {
        return (self.sceneGrouped ? a.sceneRank - b.sceneRank : 0)
          || a.channel.localeCompare(b.channel, "zh-CN")
          || a.targetLabel.localeCompare(b.targetLabel, "zh-CN");
      });
      if (this.sceneGrouped) {
        this.applyRowspan(list, function (r) { return r.sceneRank; }, "showScene", "spanScene");
        this.applyRowspan(list, function (r) { return r.sceneRank + "|" + r.channel; }, "showChannel", "spanChannel");
      } else {
        this.applyRowspan(list, function (r) { return r.channel; }, "showChannel", "spanChannel");
      }
      return list;
    },
    channelToTargetsRows: function () {
      var map = new Map();
      var self = this;
      (this.rows || []).forEach(function (r) {
        if (!r.targetId) return;
        var ch = self.channelLabel(r) || "未研判";
        var targetLabel = self.targetDisplayLabel(r.targetId, r.targetType, r.targetTypeLabel, r.networkFreqMhz);
        var key = self.sceneGrouped ? r.sceneRank + "|" + ch : ch;
        if (!map.has(key)) {
          map.set(key, {
            sceneRank: r.sceneRank,
            sceneType: r.sceneType,
            sceneMeta: r,
            channel: ch,
            targets: new Set()
          });
        }
        map.get(key).targets.add(targetLabel);
      });
      var list = Array.from(map.values()).map(function (x) {
        return {
          sceneRank: x.sceneRank,
          sceneLabel: formatSceneLabel(x.sceneRank, x.sceneType, x.sceneMeta),
          channel: x.channel,
          targets: Array.from(x.targets).sort(function (a, b) { return a.localeCompare(b, "zh-CN"); }).join("、")
        };
      }).sort(function (a, b) {
        return (self.sceneGrouped ? a.sceneRank - b.sceneRank : 0)
          || a.channel.localeCompare(b.channel, "zh-CN");
      });
      if (this.sceneGrouped) {
        this.applyRowspan(list, function (r) { return r.sceneRank; }, "showScene", "spanScene");
        this.applyRowspan(list, function (r) { return r.sceneRank + "|" + r.channel; }, "showChannel", "spanChannel");
      } else {
        this.applyRowspan(list, function (r) { return r.channel; }, "showChannel", "spanChannel");
      }
      return list;
    },
    channelMatrix: function () {
      var list = this.hoppingTrackViews || [];
      var hop = list.find(function (v) { return v && v.unified; }) || list[0];
      if (hop && hop.tracks && hop.tracks.length) {
        return buildHopTargetChannelMatrix(hop, this.rows);
      }
      return buildTargetChannelMatrixFromRows(this.rows);
    }
  },
  watch: {
    rows: {
      handler: function () {
        this.scheduleRender();
      },
      deep: true
    }
  },
  mounted: function () {
    this.scheduleRender();
    window.addEventListener("resize", this.resizeCharts);
  },
  beforeDestroy: function () {
    window.removeEventListener("resize", this.resizeCharts);
    this.disposeAll();
  },
  methods: {
    channelLabel: function (row) {
      return row.commLinkChannelLabel || row.commLinkChannel || "";
    },
    splitChannels: function (text) {
      if (!text) return [];
      return text.split(/[、,；;]/).map(function (s) { return s.trim(); }).filter(Boolean);
    },
    targetDisplayLabel: function (targetId, targetType, targetTypeLabelText, freqMhz) {
      var type = targetTypeLabelText || targetTypeLabel(targetType);
      var base = type && type !== "—" ? targetId + "-" + type : targetId;
      var f = formatFreq(freqMhz);
      return f && f !== "—" ? base + " " + f : base;
    },
    applyRowspan: function (list, keyFn, showField, spanField) {
      var i = 0;
      while (i < list.length) {
        var key = keyFn(list[i]);
        var j = i + 1;
        while (j < list.length && keyFn(list[j]) === key) j++;
        var span = j - i;
        list[i][showField] = true;
        list[i][spanField] = span;
        for (var k = i + 1; k < j; k++) {
          list[k][showField] = false;
          list[k][spanField] = 1;
        }
        i = j;
      }
    },
    scheduleRender: function () {
      var self = this;
      this.$nextTick(function () {
        self.render();
      });
    },
    disposeAll: function () {
      if (this.instTarget) {
        this.instTarget.dispose();
        this.instTarget = null;
      }
      if (this.instComm) {
        this.instComm.dispose();
        this.instComm = null;
      }
    },
    render: function () {
      this.disposeAll();
      if (!this.rows || !this.rows.length) return;

      var byType = {};
      var byComm = {};
      var self = this;

      this.rows.forEach(function (r) {
        var tt = r.targetType || "UNKNOWN";
        byType[tt] = (byType[tt] || 0) + 1;
        var cl = self.channelLabel(r) || "未研判";
        byComm[cl] = (byComm[cl] || 0) + 1;
      });

      if (this.$refs.chartTarget) {
        this.instTarget = initCet36Chart(this.$refs.chartTarget);
        this.instTarget.setOption(applyCet36Theme({
          color: CET36_CHART_COLORS,
          title: { text: "目标类型分布", left: "center", textStyle: { fontSize: 14 } },
          tooltip: { trigger: "item" },
          series: [{
            type: "pie",
            radius: "55%",
            data: Object.entries(byType).map(function (entry) {
              return { name: targetTypeLabel(entry[0]), value: entry[1] };
            })
          }]
        }));
      }

      if (this.$refs.chartComm) {
        this.instComm = initCet36Chart(this.$refs.chartComm);
        var entries = Object.entries(byComm).sort(function (a, b) { return b[1] - a[1]; }).slice(0, 12);
        this.instComm.setOption(applyCet36Theme({
          color: CET36_CHART_COLORS,
          title: { text: "波道分布（Top12）", left: "center", textStyle: { fontSize: 14 } },
          tooltip: { trigger: "axis" },
          grid: { left: 48, right: 16, bottom: 72, top: 48 },
          xAxis: {
            type: "category",
            data: entries.map(function (e) { return e[0]; }),
            axisLabel: { rotate: 35, fontSize: 10 }
          },
          yAxis: { type: "value", name: "目标数" },
          series: [{ type: "bar", data: entries.map(function (e) { return e[1]; }) }]
        }));
      }
    },
    resizeCharts: function () {
      if (this.instTarget) this.instTarget.resize();
      if (this.instComm) this.instComm.resize();
    },
    getChartImages: function () {
      var out = [];
      var insts = [this.instTarget, this.instComm];
      for (var i = 0; i < insts.length; i++) {
        if (insts[i]) {
          out.push(insts[i].getDataURL({ type: "png", pixelRatio: 2, backgroundColor: "#17182c" }));
        } else {
          out.push(null);
        }
      }
      out.push(null);
      return out;
    }
  }
};
</script>

<style scoped>
.charts-wrap {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 16px;
}
.chart-block {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.chart-box {
  height: 280px;
  border: 1px solid var(--theme-border);
  border-radius: 8px;
  background: var(--theme-bg-panel);
}
.chart-desc {
  margin: 8px 4px 0;
  font-size: 12px;
  color: var(--theme-text-muted);
  line-height: 1.45;
}
.channel-cross {
  grid-column: 1 / -1;
  border: 1px solid var(--theme-border);
  border-radius: 8px;
  padding: 12px 14px;
  background: var(--theme-bg-deep);
}
.channel-cross h4 {
  margin: 0 0 4px;
  font-size: 14px;
  color: var(--theme-text-primary);
}
.section-desc {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--theme-text-muted);
}
.cross-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
@media (max-width: 900px) {
  .charts-wrap {
    grid-template-columns: 1fr;
  }
  .cross-grid {
    grid-template-columns: 1fr;
  }
}
.cross-panel {
  background: var(--theme-bg-panel);
  border: 1px solid var(--theme-border);
  border-radius: 6px;
  padding: 8px;
}
.cross-panel h5 {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--theme-text-primary);
}
.list-scroll {
  max-height: 360px;
  overflow: auto;
}
.merge-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.merge-table th,
.merge-table td {
  border: 1px solid var(--theme-border);
  padding: 5px 8px;
  text-align: left;
  vertical-align: middle;
  color: var(--theme-text-table);
}
.merge-table th {
  background: var(--theme-bg-table-header);
  font-weight: 600;
  color: var(--theme-text-table-header);
}
.merge-cell {
  background: var(--theme-bg-panel-alt);
  font-weight: 500;
  white-space: nowrap;
  color: var(--theme-text-secondary);
}
.empty {
  color: var(--theme-text-muted);
  font-size: 12px;
  margin: 0;
}
.matrix-scroll { overflow-x: auto; }
.occupied {
  color: #91cc75;
  font-weight: 700;
  font-size: 14px;
}
</style>
