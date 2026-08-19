<template>
  <div class="hop-viz">
    <div class="viz-head">
      <h4>换频研判轨迹</h4>
      <span v-if="view" class="viz-meta">
        {{ plottedCount }} / {{ view.trackCount ?? 0 }} 个目标 · 跨频
        {{ view.hopTrackCount ?? 0 }}
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
      <button
        v-for="opt in typeFilters"
        :key="opt.value || 'all'"
        type="button"
        class="type-btn"
        :class="{ active: typeFilter === opt.value }"
        @click="typeFilter = opt.value"
      >
        {{ opt.label }}
      </button>
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
          <table class="channel-matrix">
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
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
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
} from "../scene/importScatterChart.js";
import { buildHopTargetChannelMatrix, formatHopTrackFreqLabel, HOP_TYPE_FILTERS, hopTrackMatchesType } from "../scene/hopChannelMatrix.js";

const props = defineProps({
  hoppingTrackViews: { type: Array, default: () => [] },
  importScatter: { type: Object, default: null },
  reportRows: { type: Array, default: () => [] },
  showChannelMatrix: { type: Boolean, default: true }
});

const chartEl = ref(null);
let chartInst = null;
const omittedFreqUsed = ref(false);
const typeFilter = ref("");
const typeFilters = HOP_TYPE_FILTERS;

const view = computed(() => {
  const list = props.hoppingTrackViews || [];
  if (!list.length) return null;
  const unified = list.find((v) => v?.unified) || list[0];
  if (!unified?.tracks?.length) return null;
  return unified;
});

const plottedTracks = computed(() => {
  const tracks = (view.value?.tracks || []).filter((t) => !t.noiseCandidate);
  return tracks.filter((t) => hopTrackMatchesType(t, typeFilter.value));
});

const plottedCount = computed(() => plottedTracks.value.length);

const freqColorMap = computed(() => resolveFreqColorMap(view.value, props.importScatter));

const freqLegendCount = computed(() => freqColorMap.value.size);

const panelMeta = computed(() => {
  const v = view.value;
  if (!v) return "";
  const n = plottedCount.value;
  const total = v.trackCount ?? 0;
  const countText = typeFilter.value && n !== total ? `${n}/${total}` : `${total}`;
  return `${v.timeRange || ""} · ${countText} 条轨迹`;
});

const channelMatrix = computed(() => {
  if (!view.value) {
    return buildHopTargetChannelMatrix(null, props.reportRows);
  }
  return buildHopTargetChannelMatrix(
    { ...view.value, tracks: plottedTracks.value },
    props.reportRows
  );
});

watch(
  () => [props.hoppingTrackViews, props.importScatter],
  async (curr, prev) => {
    const hopSame = curr?.[0] === prev?.[0];
    const scatterSame = curr?.[1] === prev?.[1];
    if (prev && hopSame && scatterSame) return;
    await nextTick();
    renderChart(false);
  },
  { deep: true }
);

watch(typeFilter, async () => {
  await nextTick();
  renderChart(true);
});

function resolveFreqColorMap(v, scatter) {
  const fromScatter = buildFreqColorMapFromScatter(scatter);
  if (fromScatter.size) return fromScatter;
  return buildFreqColorMapByPointRank(collectFreqCounts(v));
}

function collectFreqCounts(v) {
  const counts = new Map();
  for (const t of v?.tracks || []) {
    if (t.noiseCandidate) continue;
    for (const p of t.points || []) {
      const f = roundFreq3(p.freqMhz);
      if (f == null) continue;
      counts.set(f, (counts.get(f) || 0) + 1);
    }
  }
  return [...counts.entries()].map(([freqMhz, count]) => ({ freqMhz, count }));
}

function colorForFreq(colorByFreq, freq) {
  if (freq == null) return OMITTED_FREQ_COLOR;
  if (colorByFreq.has(freq)) return colorByFreq.get(freq);
  return OMITTED_FREQ_COLOR;
}

function formatFreqLabel(f) {
  if (f == null) return "未知频点";
  return `${Number(f).toFixed(3)} MHz`;
}

function targetBaseName(t) {
  const base = t.label || `目标${t.trackId}`;
  const type = t.targetTypeLabel || "";
  let withType = base;
  if (type && type !== "—" && !String(base).includes(type)) {
    withType = `${base}-${type}`;
  }
  return formatHopTrackFreqLabel(withType, t, props.reportRows);
}

async function renderChart(resetInteraction) {
  if (!chartEl.value || !view.value) {
    chartInst?.dispose();
    chartInst = null;
    return;
  }
  if (!chartInst) {
    chartInst = echarts.init(chartEl.value);
  }
  let legendSelected = null;
  let zoom = null;
  if (!resetInteraction) {
    try {
      const cur = chartInst.getOption?.();
      legendSelected = cur?.legend?.[0]?.selected || null;
      zoom = (cur?.dataZoom || []).map((z) => ({ start: z.start, end: z.end }));
    } catch (_) { /* ignore */ }
  }
  const opt = buildOption(view.value, freqColorMap.value, plottedTracks.value);
  // 保留用户图例勾选与 dataZoom，避免无数据变化时的误触重绘清状态
  if (legendSelected) {
    opt.legend = { ...(opt.legend || {}), selected: legendSelected };
  }
  if (zoom?.length) {
    opt.dataZoom = (opt.dataZoom || []).map((z, i) => ({
      ...z,
      start: zoom[i]?.start ?? z.start,
      end: zoom[i]?.end ?? z.end
    }));
  }
  chartInst.setOption(opt, true);
  await nextTick();
  chartInst.resize();
}

function normalizeBearing(deg) {
  let d = Number(deg);
  if (!Number.isFinite(d)) return d;
  d %= 360;
  if (d < 0) d += 360;
  return d;
}

function resolveXRange(v) {
  const fromScatter = scatterTimeRange(props.importScatter);
  if (fromScatter) return fromScatter;
  const xMin = Number(v.xMin);
  const xMax = Number(v.xMax);
  if (Number.isFinite(xMin) && Number.isFinite(xMax)) {
    const pad = timeAxisPad(xMin, xMax);
    return { xMin: xMin - pad, xMax: xMax + pad };
  }
  return { xMin: undefined, xMax: undefined };
}

function buildOption(v, colorByFreq, tracks) {
  const series = [];
  const legendNames = [];
  let usedOmitted = false;

  const pickColor = (freq) => {
    const c = colorForFreq(colorByFreq, freq);
    if (freq != null && !colorByFreq.has(freq)) usedOmitted = true;
    return c;
  };

  for (const t of tracks || []) {
    const base = targetBaseName(t);
    const normalizedPts = (t.points || []).map((p) => ({
      ...p,
      y: normalizeBearing(p.y)
    }));
    const segments = splitByFrequency(normalizedPts);
    let prevLast = null;
    for (let i = 0; i < segments.length; i++) {
      const seg = segments[i];
      const color = pickColor(seg.freq);
      const omittedTag =
        seg.freq != null && !colorByFreq.has(seg.freq) ? "（概览未标绘）" : "";
      const name = `${base} · ${formatFreqLabel(seg.freq)}${omittedTag}`;
      if (!legendNames.includes(name)) legendNames.push(name);

      if (prevLast && seg.points.length) {
        series.push({
          name: `${base}·衔接`,
          type: "line",
          showSymbol: false,
          lineStyle: { width: 1, color: "#9ca3af", type: "dashed", opacity: 0.7 },
          data: [prevLast, seg.points[0]],
          tooltip: { show: false },
          legendHoverLink: false
        });
      }

      series.push({
        name,
        type: "line",
        showSymbol: true,
        symbolSize: 5,
        lineStyle: { width: 2, color },
        itemStyle: { color },
        data: seg.points
      });
      prevLast = seg.points[seg.points.length - 1];
    }
    if (!segments.length) {
      const color = t.color || OMITTED_FREQ_COLOR;
      const name = base;
      if (!legendNames.includes(name)) legendNames.push(name);
      const data = normalizedPts.map((p) => [p.x, p.y]);
      series.push({
        name,
        type: "line",
        showSymbol: true,
        symbolSize: 5,
        lineStyle: { width: 2, color },
        itemStyle: { color },
        data
      });
    }
  }

  omittedFreqUsed.value = usedOmitted;
  const { xMin, xMax } = resolveXRange(v);

  return {
    tooltip: {
      trigger: "axis",
      formatter(params) {
        if (!params?.length) return "";
        const visible = params.filter((p) => !String(p.seriesName || "").endsWith("·衔接"));
        if (!visible.length) return "";
        const t = formatClock(visible[0].value[0]);
        const lines = visible.map(
          (p) => `${p.marker}${p.seriesName}: ${Number(p.value[1]).toFixed(1)}°`
        );
        return `${t}<br/>${lines.join("<br/>")}`;
      }
    },
    legend: {
      top: 4,
      left: "center",
      type: "scroll",
      data: legendNames,
      textStyle: { fontSize: 11 }
    },
    grid: { ...IMPORT_SCATTER_GRID },
    xAxis: {
      type: "time",
      name: "时间",
      nameLocation: "middle",
      nameGap: 38,
      min: xMin,
      max: xMax,
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
      splitLine: { lineStyle: { color: "#e5e7eb", type: "dashed" } }
    },
    dataZoom: [
      { type: "inside", xAxisIndex: 0 },
      { type: "slider", xAxisIndex: 0, height: 20, bottom: 10 }
    ],
    series
  };
}

/** 按连续同频切段，便于分段着色 */
function splitByFrequency(points) {
  const segs = [];
  let cur = null;
  for (const p of points || []) {
    const f = roundFreq3(p.freqMhz);
    const xy = [p.x, normalizeBearing(p.y)];
    if (f == null) {
      if (!cur) {
        cur = { freq: null, points: [] };
        segs.push(cur);
      }
      cur.points.push(xy);
      continue;
    }
    if (!cur || cur.freq !== f) {
      cur = { freq: f, points: [] };
      segs.push(cur);
    }
    cur.points.push(xy);
  }
  return segs.filter((s) => s.points.length);
}

function formatClock(ms) {
  return new Date(ms).toLocaleTimeString("zh-CN", { hour12: false });
}

function onResize() {
  chartInst?.resize();
}

onMounted(async () => {
  await nextTick();
  renderChart(false);
  window.addEventListener("resize", onResize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
  chartInst?.dispose();
  chartInst = null;
});
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
.viz-head h4 { margin: 0; font-size: 14px; }
.viz-meta { font-size: 12px; color: #6b7280; }
.type-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 0 10px;
}
.type-btn {
  border: 1px solid #d1d5db;
  background: #fff;
  color: #374151;
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
}
.type-btn:hover { border-color: #93c5fd; color: #1d4ed8; }
.type-btn.active {
  background: #1d4ed8;
  border-color: #1d4ed8;
  color: #fff;
}
.panel {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fafafa;
}
.panel-title {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: baseline;
  font-size: 13px;
  margin-bottom: 6px;
}
.panel-title span { color: #6b7280; font-size: 12px; }
.chart-box {
  width: 100%;
  background: #fff;
  border-radius: 6px;
}
.viz-empty { color: #6b7280; font-size: 13px; padding: 12px 0; }
.scene-note { font-size: 12px; color: #4b5563; margin: 8px 0 0; }
.hint { font-size: 12px; color: #9ca3af; margin: 6px 0 0; }
.channel-matrix-block {
  margin: 12px 0 0;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}
.matrix-title {
  margin: 0 0 4px;
  font-size: 13px;
  font-weight: 600;
  color: #111827;
}
.matrix-desc {
  margin: 0 0 8px;
  font-size: 11px;
  color: #6b7280;
  line-height: 1.45;
}
.matrix-scroll { overflow-x: auto; }
.channel-matrix {
  border-collapse: collapse;
  font-size: 12px;
  min-width: 100%;
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
