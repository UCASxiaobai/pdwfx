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
        <table v-if="channelMatrix.rows.length" class="channel-matrix">
          <thead>
            <tr>
              <th class="matrix-corner">目标 \\ 波道</th>
              <th
                v-for="col in channelMatrix.columns"
                :key="col.id"
                class="matrix-col-head"
              >
                {{ col.label }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in channelMatrix.rows" :key="row.key">
              <th class="matrix-row-head">{{ row.label }}</th>
              <td
                v-for="(checked, ci) in row.cells"
                :key="ci"
                class="matrix-cell"
                :class="{ occupied: checked }"
              >
                {{ checked ? "✓" : "" }}
              </td>
            </tr>
          </tbody>
        </table>
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

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";
import { formatFreq, formatSceneLabel, targetTypeLabel } from "../scene/sceneFilters.js";
import {
  buildHopTargetChannelMatrix,
  buildTargetChannelMatrixFromRows
} from "../scene/hopChannelMatrix.js";

const props = defineProps({
  rows: { type: Array, default: () => [] },
  hoppingTrackViews: { type: Array, default: () => [] },
  sceneGrouped: { type: Boolean, default: true }
});

const chartTarget = ref(null);
const chartComm = ref(null);
let instTarget = null;
let instComm = null;

function channelLabel(row) {
  return row.commLinkChannelLabel || row.commLinkChannel || "";
}

function splitChannels(text) {
  if (!text) return [];
  return text.split(/[、,；;]/).map((s) => s.trim()).filter(Boolean);
}

function targetDisplayLabel(targetId, targetType, targetTypeLabelText, freqMhz) {
  const type = targetTypeLabelText || targetTypeLabel(targetType);
  const base = type && type !== "—" ? `${targetId}-${type}` : targetId;
  const f = formatFreq(freqMhz);
  return f && f !== "—" ? `${base} ${f}` : base;
}

function applyRowspan(list, keyFn, showField, spanField) {
  let i = 0;
  while (i < list.length) {
    const key = keyFn(list[i]);
    let j = i + 1;
    while (j < list.length && keyFn(list[j]) === key) j++;
    const span = j - i;
    list[i][showField] = true;
    list[i][spanField] = span;
    for (let k = i + 1; k < j; k++) {
      list[k][showField] = false;
      list[k][spanField] = 1;
    }
    i = j;
  }
}

const targetToChannelsRows = computed(() => {
  const seen = new Set();
  const list = [];
  for (const r of props.rows || []) {
    if (!r.targetId) continue;
    const chText = r.targetChannelsUsed || channelLabel(r);
    const channels = splitChannels(chText);
    const chList = channels.length ? channels : (channelLabel(r) ? [channelLabel(r)] : ["未研判"]);
    const targetLabel = targetDisplayLabel(r.targetId, r.targetType, r.targetTypeLabel, r.networkFreqMhz);
    for (const ch of chList) {
      const dedupeKey = props.sceneGrouped
        ? `${r.sceneRank}|${r.targetId}|${formatFreq(r.networkFreqMhz)}|${ch}`
        : `${targetLabel}|${ch}`;
      if (seen.has(dedupeKey)) continue;
      seen.add(dedupeKey);
      list.push({
        sceneRank: r.sceneRank,
        sceneType: r.sceneType,
        sceneLabel: formatSceneLabel(r.sceneRank, r.sceneType, r),
        targetLabel,
        channel: ch
      });
    }
  }
  list.sort(
    (a, b) =>
      (props.sceneGrouped ? a.sceneRank - b.sceneRank : 0)
      || a.channel.localeCompare(b.channel, "zh-CN")
      || a.targetLabel.localeCompare(b.targetLabel, "zh-CN")
  );
  if (props.sceneGrouped) {
    applyRowspan(list, (r) => r.sceneRank, "showScene", "spanScene");
    applyRowspan(list, (r) => `${r.sceneRank}|${r.channel}`, "showChannel", "spanChannel");
  } else {
    applyRowspan(list, (r) => r.channel, "showChannel", "spanChannel");
  }
  return list;
});

const channelToTargetsRows = computed(() => {
  const map = new Map();
  for (const r of props.rows || []) {
    if (!r.targetId) continue;
    const ch = channelLabel(r) || "未研判";
    const targetLabel = targetDisplayLabel(r.targetId, r.targetType, r.targetTypeLabel, r.networkFreqMhz);
    const key = props.sceneGrouped ? `${r.sceneRank}|${ch}` : ch;
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
  }
  const list = [...map.values()]
    .map((x) => ({
      sceneRank: x.sceneRank,
      sceneLabel: formatSceneLabel(x.sceneRank, x.sceneType, x.sceneMeta),
      channel: x.channel,
      targets: [...x.targets].sort((a, b) => a.localeCompare(b, "zh-CN")).join("、")
    }))
    .sort(
      (a, b) =>
        (props.sceneGrouped ? a.sceneRank - b.sceneRank : 0)
        || a.channel.localeCompare(b.channel, "zh-CN")
    );
  if (props.sceneGrouped) {
    applyRowspan(list, (r) => r.sceneRank, "showScene", "spanScene");
    applyRowspan(list, (r) => `${r.sceneRank}|${r.channel}`, "showChannel", "spanChannel");
  } else {
    applyRowspan(list, (r) => r.channel, "showChannel", "spanChannel");
  }
  return list;
});

const channelMatrix = computed(() => {
  const list = props.hoppingTrackViews || [];
  const hop = list.find((v) => v?.unified) || list[0];
  if (hop?.tracks?.length) {
    return buildHopTargetChannelMatrix(hop, props.rows);
  }
  return buildTargetChannelMatrixFromRows(props.rows);
});

function disposeAll() {
  instTarget?.dispose();
  instComm?.dispose();
  instTarget = instComm = null;
}

function render() {
  disposeAll();
  if (!props.rows?.length) return;

  const byType = {};
  const byComm = {};

  for (const r of props.rows) {
    const tt = r.targetType || "UNKNOWN";
    byType[tt] = (byType[tt] || 0) + 1;
    const cl = channelLabel(r) || "未研判";
    byComm[cl] = (byComm[cl] || 0) + 1;
  }

  if (chartTarget.value) {
    instTarget = echarts.init(chartTarget.value);
    instTarget.setOption({
      title: { text: "目标类型分布", left: "center", textStyle: { fontSize: 14 } },
      tooltip: { trigger: "item" },
      series: [
        {
          type: "pie",
          radius: "55%",
          data: Object.entries(byType).map(([k, v]) => ({
            name: targetTypeLabel(k),
            value: v
          }))
        }
      ]
    });
  }

  if (chartComm.value) {
    instComm = echarts.init(chartComm.value);
    const entries = Object.entries(byComm).sort((a, b) => b[1] - a[1]).slice(0, 12);
    instComm.setOption({
      title: { text: "波道分布（Top12）", left: "center", textStyle: { fontSize: 14 } },
      tooltip: { trigger: "axis" },
      grid: { left: 48, right: 16, bottom: 72, top: 48 },
      xAxis: {
        type: "category",
        data: entries.map((e) => e[0]),
        axisLabel: { rotate: 35, fontSize: 10 }
      },
      yAxis: { type: "value", name: "目标数" },
      series: [{ type: "bar", data: entries.map((e) => e[1]), itemStyle: { color: "#2563eb" } }]
    });
  }
}

function resizeCharts() {
  instTarget?.resize();
  instComm?.resize();
}

function getChartImages() {
  const out = [];
  for (const inst of [instTarget, instComm]) {
    if (inst) {
      out.push(inst.getDataURL({ type: "png", pixelRatio: 2, backgroundColor: "#fff" }));
    } else {
      out.push(null);
    }
  }
  out.push(null);
  return out;
}

defineExpose({ getChartImages });

watch(() => props.rows, () => render());

onMounted(() => {
  render();
  window.addEventListener("resize", resizeCharts);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", resizeCharts);
  disposeAll();
});
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
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}
.chart-desc {
  margin: 8px 4px 0;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.45;
}
.channel-cross {
  grid-column: 1 / -1;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 12px 14px;
  background: #fafafa;
}
.channel-cross h4 {
  margin: 0 0 4px;
  font-size: 14px;
}
.section-desc {
  margin: 0 0 12px;
  font-size: 12px;
  color: #6b7280;
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
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 8px;
}
.cross-panel h5 {
  margin: 0 0 8px;
  font-size: 13px;
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
  border: 1px solid #e5e7eb;
  padding: 5px 8px;
  text-align: left;
  vertical-align: middle;
}
.merge-table th {
  background: #f9fafb;
  font-weight: 600;
}
.merge-cell {
  background: #fafafa;
  font-weight: 500;
  white-space: nowrap;
}
.empty {
  color: #9ca3af;
  font-size: 12px;
  margin: 0;
}
.matrix-scroll { overflow-x: auto; }
.channel-matrix {
  border-collapse: collapse;
  font-size: 12px;
  min-width: 100%;
  background: #fff;
}
.channel-matrix th,
.channel-matrix td {
  border: 1px solid #e5e7eb;
  padding: 6px 10px;
  text-align: center;
  white-space: nowrap;
}
.matrix-corner,
.matrix-row-head {
  text-align: left;
  background: #f3f4f6;
  font-weight: 600;
  color: #374151;
  position: sticky;
  left: 0;
  z-index: 1;
}
.matrix-col-head {
  background: #eff6ff;
  color: #1e40af;
  font-weight: 600;
}
.matrix-cell { color: #9ca3af; min-width: 48px; }
.matrix-cell.occupied {
  color: #059669;
  font-weight: 700;
  font-size: 14px;
}
</style>
