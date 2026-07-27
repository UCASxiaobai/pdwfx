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

    <div class="chart-block chart-block-wide">
      <div ref="chartSceneNet" class="chart-box chart-box-wide" />
      <p class="chart-desc">
        仅统计<strong>时间维度场景</strong>（整段时窗内异频数据合并分析，非按单频点拆分）：每个场景内识别出的通信网络数量，用于对比多频共存时的电磁环境复杂度。同频窄带场景恒为 1 网，不在此图中展示。
      </p>
      <p v-if="!timeMergedSceneCount" class="chart-empty-hint">当前报表中无满足条件的异频时间合并场景。</p>
    </div>

    <section class="channel-cross">
      <h4>目标 ↔ 波道关联</h4>
      <p class="section-desc">
        按场景汇总：目标列标明类型（如 T1-飞机）；波道列合并同场景下的相同波道条目。
      </p>
      <div class="cross-grid">
        <div class="cross-panel">
          <h5>各目标占用波道</h5>
          <div class="list-scroll">
            <table v-if="targetToChannelsRows.length" class="merge-table">
              <thead>
                <tr><th>场景</th><th>目标</th><th>波道</th></tr>
              </thead>
              <tbody>
                <tr v-for="(row, i) in targetToChannelsRows" :key="'t' + i">
                  <td
                    v-if="row.showScene"
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
                <tr><th>场景</th><th>波道</th><th>目标</th></tr>
              </thead>
              <tbody>
                <tr v-for="(row, i) in channelToTargetsRows" :key="'c' + i">
                  <td
                    v-if="row.showScene"
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
import { formatSceneLabel, targetTypeLabel } from "../scene/sceneFilters.js";

const props = defineProps({
  rows: { type: Array, default: () => [] }
});

/** 场景频宽超过此值视为异频时间合并场景（非单频点分析） */
const MIN_MULTI_FREQ_SPAN_MHZ = 0.5;

const chartTarget = ref(null);
const chartComm = ref(null);
const chartSceneNet = ref(null);
let instTarget = null;
let instComm = null;
let instSceneNet = null;

function channelLabel(row) {
  return row.commLinkChannelLabel || row.commLinkChannel || "";
}

function splitChannels(text) {
  if (!text) return [];
  return text.split(/[、,]/).map((s) => s.trim()).filter(Boolean);
}

function targetDisplayLabel(targetId, targetType) {
  const type = targetTypeLabel(targetType);
  return type && type !== "—" ? `${targetId}-${type}` : targetId;
}

/** 整段时窗异频合并分析的场景（排除单频窄带，避免网络数恒为 1） */
function isTimeMergedMultiFreqScene(rows, sceneRank) {
  const sceneRows = (rows || []).filter((r) => r.sceneRank === sceneRank);
  if (!sceneRows.length) return false;
  const r0 = sceneRows[0];
  const fmin = Number(r0.sceneFreqMinMhz);
  const fmax = Number(r0.sceneFreqMaxMhz);
  const span = Number.isFinite(fmin) && Number.isFinite(fmax) ? fmax - fmin : 0;
  if (span >= MIN_MULTI_FREQ_SPAN_MHZ) return true;
  const netFreqs = new Set(
    sceneRows.map((r) => Number(r.networkFreqMhz)).filter(Number.isFinite)
  );
  return netFreqs.size > 1;
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
    const chList = channels.length ? channels : (channelLabel(r) ? [channelLabel(r)] : []);
    if (!chList.length) continue;
    const targetLabel = targetDisplayLabel(r.targetId, r.targetType);
    for (const ch of chList) {
      const dedupeKey = `${r.sceneRank}|${r.targetId}|${ch}`;
      if (seen.has(dedupeKey)) continue;
      seen.add(dedupeKey);
      list.push({
        sceneRank: r.sceneRank,
        sceneType: r.sceneType,
        sceneLabel: formatSceneLabel(r.sceneRank, r.sceneType),
        targetLabel,
        channel: ch
      });
    }
  }
  list.sort(
    (a, b) =>
      a.sceneRank - b.sceneRank
      || a.channel.localeCompare(b.channel, "zh-CN")
      || a.targetLabel.localeCompare(b.targetLabel, "zh-CN")
  );
  applyRowspan(list, (r) => r.sceneRank, "showScene", "spanScene");
  applyRowspan(list, (r) => `${r.sceneRank}|${r.channel}`, "showChannel", "spanChannel");
  return list;
});

const channelToTargetsRows = computed(() => {
  const map = new Map();
  for (const r of props.rows || []) {
    if (!r.targetId) continue;
    const ch = channelLabel(r) || "未研判";
    const targetLabel = targetDisplayLabel(r.targetId, r.targetType);
    const key = `${r.sceneRank}|${ch}`;
    if (!map.has(key)) {
      map.set(key, {
        sceneRank: r.sceneRank,
        sceneType: r.sceneType,
        channel: ch,
        targets: new Set()
      });
    }
    map.get(key).targets.add(targetLabel);
  }
  const list = [...map.values()]
    .map((x) => ({
      sceneRank: x.sceneRank,
      sceneLabel: formatSceneLabel(x.sceneRank, x.sceneType),
      channel: x.channel,
      targets: [...x.targets].sort((a, b) => a.localeCompare(b, "zh-CN")).join("、")
    }))
    .sort(
      (a, b) =>
        a.sceneRank - b.sceneRank
        || a.channel.localeCompare(b.channel, "zh-CN")
    );
  applyRowspan(list, (r) => r.sceneRank, "showScene", "spanScene");
  applyRowspan(list, (r) => `${r.sceneRank}|${r.channel}`, "showChannel", "spanChannel");
  return list;
});

const timeMergedSceneCount = computed(() => {
  const ranks = new Set((props.rows || []).map((r) => r.sceneRank));
  let n = 0;
  for (const rank of ranks) {
    if (isTimeMergedMultiFreqScene(props.rows, rank)) n++;
  }
  return n;
});

function disposeAll() {
  instTarget?.dispose();
  instComm?.dispose();
  instSceneNet?.dispose();
  instTarget = instComm = instSceneNet = null;
}

function render() {
  disposeAll();
  if (!props.rows?.length) return;

  const byType = {};
  const byComm = {};
  const netsPerTimeScene = {};
  const sceneTypes = {};

  for (const r of props.rows) {
    const tt = r.targetType || "UNKNOWN";
    byType[tt] = (byType[tt] || 0) + 1;
    const cl = channelLabel(r) || "未研判";
    byComm[cl] = (byComm[cl] || 0) + 1;
    sceneTypes[r.sceneRank] = r.sceneType;
    if (!isTimeMergedMultiFreqScene(props.rows, r.sceneRank)) continue;
    const key = r.sceneRank;
    const netKey = `${r.analysisId}-${r.networkId}`;
    if (!netsPerTimeScene[key]) netsPerTimeScene[key] = new Set();
    netsPerTimeScene[key].add(netKey);
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

  if (chartSceneNet.value) {
    instSceneNet = echarts.init(chartSceneNet.value);
    const scenes = Object.keys(netsPerTimeScene)
      .map(Number)
      .sort((a, b) => a - b);
    const labels = scenes.map((s) => formatSceneLabel(s, sceneTypes[s]));
    instSceneNet.setOption({
      title: {
        text: "各场景网络数（时间合并·异频场景）",
        left: "center",
        textStyle: { fontSize: 14 }
      },
      tooltip: {
        trigger: "axis",
        formatter(params) {
          const p = params?.[0];
          if (!p) return "";
          return `${p.name}<br/>网络数: ${p.value}`;
        }
      },
      grid: { left: 56, right: 24, bottom: scenes.length > 8 ? 72 : 48, top: 52 },
      xAxis: {
        type: "category",
        data: labels,
        axisLabel: { rotate: scenes.length > 6 ? 28 : 0, fontSize: 11 }
      },
      yAxis: { type: "value", name: "网络数", minInterval: 1 },
      series: [
        {
          type: "bar",
          barMaxWidth: 48,
          data: scenes.map((s) => netsPerTimeScene[s].size),
          itemStyle: { color: "#7c3aed" }
        }
      ]
    });
  }
}

function resizeCharts() {
  instTarget?.resize();
  instComm?.resize();
  instSceneNet?.resize();
}

function getChartImages() {
  const out = [];
  for (const inst of [instTarget, instComm, instSceneNet]) {
    if (inst) {
      out.push(inst.getDataURL({ type: "png", pixelRatio: 2, backgroundColor: "#fff" }));
    } else {
      out.push(null);
    }
  }
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
.chart-block-wide {
  grid-column: 1 / -1;
}
.chart-box {
  height: 280px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}
.chart-box-wide {
  height: 300px;
  width: 100%;
}
.chart-desc {
  margin: 8px 4px 0;
  font-size: 12px;
  color: #6b7280;
  line-height: 1.45;
}
.chart-desc strong {
  color: #374151;
  font-weight: 600;
}
.chart-empty-hint {
  margin: 6px 4px 0;
  font-size: 12px;
  color: #b45309;
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
  max-height: 280px;
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
</style>
