<template>

  <div v-if="network" class="workbench">

    <section class="map-section">
      <div class="map-header">
        <h4>态势地图</h4>
        <div class="map-legend">
          <span class="legend-platform">● 我方（侦察平台）</span>
          <span class="legend-target">◆ 分析目标定位</span>
          <span class="legend-external-neutral">▲ 外源定位</span>
          <span class="legend-external-matched">▲ 交叉定位（已关联测向批）</span>
          <span class="legend-ray">— 测向射线</span>
          <span v-if="externalFixMeta" class="legend-meta">{{ externalFixMeta }}</span>
        </div>
      </div>

      <div class="map-replay-controls">
        <label class="replay-field">
          标绘模式
          <select v-model="mapDisplayMode" @change="onMapModeChange">
            <option value="static">全量标绘</option>
            <option value="replay">时间回放</option>
          </select>
        </label>
        <template v-if="mapDisplayMode === 'replay'">
          <label class="replay-field">
            保留时长 (秒)
            <input
              v-model.number="featureTtlSec"
              type="number"
              min="0"
              step="1"
              title="每条标绘在地图上保留的秒数，0 表示回放过程中不自动清除"
              @change="onReplayParamsChange"
            />
          </label>
          <label class="replay-field">
            回放倍速
            <input
              v-model.number="replaySpeed"
              type="number"
              min="0.1"
              step="0.5"
              title="相对真实时间的推进倍率"
            />
          </label>
          <button type="button" class="replay-btn" :disabled="!mapTimeline.length" @click="resetReplay">重置</button>
          <span v-if="!mapTimeline.length" class="replay-hint">当前筛选下无可回放事件</span>
          <span v-else class="replay-hint">事件 {{ mapTimeline.length }} 条 · 保留 {{ featureTtlSec > 0 ? featureTtlSec + "s" : "不限" }}</span>
        </template>
      </div>

      <div v-if="selectedTargetDetail" class="target-detail-panel">
        <strong>{{ selectedTargetDetail.targetId }}</strong>
        <span>类型: {{ targetTypeText(selectedTargetDetail.targetType) }}</span>
        <span>角色: {{ selectedTargetDetail.role || "—" }}</span>
        <span>置信度: {{ formatNum(selectedTargetDetail.confidence) }}</span>
        <span>流量占比: {{ formatNum(selectedTargetDetail.emissionSharePct) }}%</span>
        <span v-if="selectedTargetDetail.locateLon != null">
          定位: {{ selectedTargetDetail.locateLon?.toFixed(4) }}, {{ selectedTargetDetail.locateLat?.toFixed(4) }}
          ({{ locateMethodText(selectedTargetDetail.locateMethod) }})
        </span>
        <span>侦获: {{ formatDetectMs(selectedTargetDetail.detectStartMs) }} ~ {{ formatDetectMs(selectedTargetDetail.detectEndMs) }}</span>
        <span>侦获次数: {{ selectedTargetDetail.detectCount ?? "—" }}</span>
        <span>收敛: {{ selectedTargetDetail.convergence || "—" }}</span>
        <button type="button" class="clear-btn" @click="selectTarget(null, null, true)">清除筛选</button>
      </div>

      <div class="map-panel map-panel-full">
        <div ref="mapRef" class="map"></div>
      </div>

      <div v-if="mapInspect" class="map-inspect-panel">
        <div class="map-inspect-head">
          <strong>{{ mapInspectTitle }}</strong>
          <button type="button" class="clear-btn" @click="clearMapInspect">关闭</button>
        </div>
        <template v-if="mapInspect.kind === 'fix'">
          <p class="map-inspect-line">目标名称：{{ mapInspect.fix.targetName || "—" }}</p>
          <p class="map-inspect-line">
            经纬度：{{ formatCoord(mapInspect.fix.longitude) }}, {{ formatCoord(mapInspect.fix.latitude) }}
          </p>
          <p class="map-inspect-line">侦测时间：{{ formatDetectMs(mapInspect.fix.detectTimeMs) }}</p>
          <label v-if="mapInspect.linkedBatchIds.length" class="inspect-check">
            <input v-model="fixShowLinkedBearings" type="checkbox" @change="onFixLinkedToggle" />
            查看与该定位点关联的测向数据
          </label>
          <p v-else class="map-inspect-hint">
            未找到关联测向批；请先在「测向–定位关联」中执行匹配，且该目标已被锁定。
          </p>
          <p v-if="mapInspect.linkedBatchIds.length" class="map-inspect-meta">
            关联批号：{{ mapInspect.linkedBatchIds.join("、") }}
          </p>
          <p v-if="fixShowLinkedBearings && mapDisplayMode === 'static'" class="map-inspect-hint">
            地图已标绘上述批号的测向线（来自匹配结果）。
          </p>
        </template>
        <template v-else-if="mapInspect.kind === 'ray'">
          <p class="map-inspect-line">测向批号：{{ mapInspect.batchId || "—" }}</p>
          <p class="map-inspect-line">分析目标：{{ mapInspect.targetId || "—" }}</p>
          <p class="map-inspect-line">方位：{{ formatNum(mapInspect.azimuth) }}°</p>
          <p class="map-inspect-line">侦测时间：{{ formatDetectMs(mapInspect.timeMs) }}</p>
          <p v-if="mapDisplayMode === 'static'" class="map-inspect-hint">
            全量标绘：地图已过滤为仅显示该批测向线。
          </p>
        </template>
      </div>

      <div v-if="mapDisplayMode === 'replay'" class="map-replay-transport">
        <button
          type="button"
          class="replay-play-btn"
          :disabled="!mapTimeline.length"
          :title="replayPlaying ? '暂停' : '按时间播放'"
          @click="toggleReplay"
        >
          {{ replayPlaying ? "⏸" : "▶" }}
        </button>
        <div class="replay-track-wrap">
          <div class="replay-track-labels">
            <span>{{ formatDetectMs(replayMinMs) }}</span>
            <span class="replay-cursor-time">{{ formatDetectMs(replayCurrentMs) }}</span>
            <span>{{ formatDetectMs(replayMaxMs) }}</span>
          </div>
          <input
            v-model.number="replayCurrentMs"
            class="replay-range"
            type="range"
            :min="replayMinMs"
            :max="replayMaxMs || replayMinMs + 1"
            :step="replaySliderStep"
            :disabled="!mapTimeline.length"
            @input="onReplayScrub"
          />
        </div>
      </div>

      <div v-if="mapInspectTableRows.length" class="map-detail-table-wrap">
        <h5 class="map-detail-title">
          {{ mapInspectTableTitle }}
          <span v-if="mapInspectBatchBounds.startMs != null" class="map-detail-bounds">
            起始侦获 {{ formatDetectMs(mapInspectBatchBounds.startMs) }}
            · 消失 {{ formatDetectMs(mapInspectBatchBounds.endMs) }}
          </span>
        </h5>
        <div class="map-detail-scroll">
          <table class="map-detail-table">
            <thead>
              <tr>
                <th>测向批号</th>
                <th>侦测时间</th>
                <th>方位(°)</th>
                <th>频率(MHz)</th>
                <th>平台经度</th>
                <th>平台纬度</th>
                <th>幅度</th>
                <th>驻留(ms)</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(row, idx) in mapInspectTableRows"
                :key="idx"
                :class="{ 'row-start': row.isStart, 'row-end': row.isEnd }"
              >
                <td>{{ row.batchId || "—" }}</td>
                <td>
                  {{ formatDetectMs(row.timeMs) }}
                  <span v-if="row.isStart" class="time-tag start">起始</span>
                  <span v-if="row.isEnd" class="time-tag end">消失</span>
                </td>
                <td>{{ formatNum(row.bearing) }}</td>
                <td>{{ formatBatchFreq(row.freqHz) }}</td>
                <td>{{ formatCoord(row.rxLon) }}</td>
                <td>{{ formatCoord(row.rxLat) }}</td>
                <td>{{ formatNum(row.signalLevel) }}</td>
                <td>{{ formatNum(row.signalDwellMs) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <section class="stats-panel">

      <h4>目标统计（幅度 + 通信行为）</h4>
      <p class="stats-hint">驻留 = CSV nSignalTime（10µs/单位）；侦测时间 = zcsj；占空比 = Σ驻留/观测窗</p>

      <table>

        <thead>

          <tr>

            <th>目标</th>

            <th>角色</th>

            <th>平均幅度</th>

            <th>最小</th>

            <th>最大</th>

            <th>主频 (MHz)</th>

            <th>点数</th>

            <th>主周期(ms)</th>

            <th>平均驻留(ms)</th>

            <th>占空比%</th>

            <th>burst数</th>

          </tr>

        </thead>

        <tbody>

          <tr

            v-for="row in amplitudeStats"

            :key="row.targetId"

            :class="{ 'row-active': selectedTargetId === row.targetId }"

            @click="selectTarget(row.targetId)"

          >

            <td>

              <span class="color-dot" :style="{ background: row.color }"></span>

              {{ row.targetId }}

            </td>

            <td>{{ row.role }}</td>

            <td>{{ row.avgLevel.toFixed(1) }}</td>

            <td>{{ row.minLevel.toFixed(1) }}</td>

            <td>{{ row.maxLevel.toFixed(1) }}</td>

            <td>{{ row.primaryFreq }}</td>

            <td>{{ row.count }}</td>

            <td>{{ row.periodMs }}</td>

            <td>{{ row.burstDwellMs }}</td>

            <td>{{ row.dutyCycle }}</td>

            <td>{{ row.burstCount }}</td>

          </tr>

        </tbody>

      </table>
      <p class="chart-caption">快速对比各目标幅度、主频、驻留与占空比，用于初判主从关系与活跃程度。</p>

    </section>

    <section v-if="selectedPoint" class="point-panel">
      <strong>当前选中点</strong>
      <span>目标: {{ selectedPoint.targetId || "-" }}</span>
      <span>时间: {{ formatTime(selectedPoint.time) }}</span>
      <span>{{ selectedPoint.axis }}: {{ selectedPoint.value.toFixed(2) }}</span>
      <span v-if="selectedPoint.chart">来源: {{ selectedPoint.chart }}</span>
    </section>

    <div class="grid">

      <div class="chart-card">
        <div ref="azRef" class="panel"></div>
        <p class="chart-caption">观察方位随时间的变化，识别平行航迹、交叉与收敛趋势。</p>
      </div>

      <div class="chart-card">
        <div ref="sigRef" class="panel"></div>
        <p class="chart-caption">对比各目标发射强度起伏，判断活动时段与相对强弱。</p>
      </div>

      <div class="chart-card">
        <div ref="rawAzRef" class="panel"></div>
        <p class="chart-caption">展示原始测向散点，核查采样密度与异常跳变。</p>
      </div>

      <div class="chart-card">
        <div ref="rawSigRef" class="panel"></div>
        <p class="chart-caption">展示原始幅度散点，辅助确认突发与截获完整性。</p>
      </div>

      <div class="chart-card">
        <div ref="freqRef" class="panel"></div>
        <p class="chart-caption">判断频率是否稳定、是否存在跳频或多频共存。</p>
      </div>

      <div class="chart-card">
        <div ref="topoRef" class="panel"></div>
        <p class="chart-caption">概览本网目标构成与主从角色分布，辅助理解组网关系。</p>
      </div>

    </div>

    <section class="rhythm-section">
      <h4>通信节奏可视化 (TOA/PRI)</h4>
      <div class="rhythm-grid">
        <div class="chart-card">
          <div ref="toaRef" class="panel"></div>
          <p class="chart-caption">分析脉冲间隔规律，辅助识别通信 rhythm 与重复周期。</p>
        </div>
        <div class="chart-card">
          <div ref="burstFreqRef" class="panel"></div>
          <p class="chart-caption">查看每次突发内的频率变化，发现分集或协同发射特征。</p>
        </div>
        <div class="chart-card">
          <div ref="priRef" class="panel"></div>
          <p class="chart-caption">统计 PRI 分布，区分定频链路与多 PRI 体制。</p>
        </div>
        <div class="chart-card">
          <div ref="jitterRef" class="panel"></div>
          <p class="chart-caption">评估 PRI 抖动程度，判断时钟稳定与调制类型。</p>
        </div>
        <div class="chart-card">
          <div ref="periodErrRef" class="panel"></div>
          <p class="chart-caption">检验周期预测误差，识别失步或通信模式切换。</p>
        </div>
        <div class="chart-card">
          <div ref="dutyRef" class="panel"></div>
          <p class="chart-caption">跟踪占空比随时间变化，反映链路负载与活跃性。</p>
        </div>
      </div>
    </section>

  </div>

</template>



<script setup>
/**
 * 网络详情可视化工作台。
 *
 * 图表数据来自 props.network（GET /api/signals/analysis/{id}/networks/{networkId}）：
 * - targets[].azimuthSeries / signalSeries — 时间-方位/幅度折线（按目标分色）
 * - targets[].rawAzimuthSeries / rawSignalSeries — 目标级散点，和上方折线同色对齐
 *
 * 交互：
 * - selectTarget() — 点击折线/图例/统计表行，同步高亮方位+幅度图
 * - buildLineOption() — 选中目标加粗，其余变淡；connectNulls 同目标不断线
 */

import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";

import * as echarts from "echarts";

import OlMap from "ol/Map";

import View from "ol/View";

import VectorLayer from "ol/layer/Vector";

import VectorSource from "ol/source/Vector";

import Feature from "ol/Feature";

import LineString from "ol/geom/LineString";

import Point from "ol/geom/Point";

import { Circle, Fill, RegularShape, Stroke, Style } from "ol/style";

import { fromLonLat } from "ol/proj";

import {
  buildMapTimeline,
  eventKey,
  timelineRange,
  visibleEventKeysAt
} from "../scene/mapReplay.js";
import { createBasemapLayer } from "../scene/mapBasemap.js";
import {
  AZIMUTH_RAY_KM,
  EXTERNAL_FIX_NEUTRAL_COLOR,
  buildMatchedDeviceColors,
  resolveBatchRayColor,
  resolveExternalFixColor
} from "../scene/dfMatchMapColors.js";
import {
  batchIdsForDeviceName,
  batchTimeBounds,
  downsampleInspectRows,
  enrichTableRows,
  formatFreqMhzFromHz,
  rowsForBatchId,
  rowsForBatchIds
} from "../scene/mapInspect.js";



const props = defineProps({

  network: { type: Object, default: null },

  highlightTargetId: { type: String, default: null },

  externalTargetFixes: { type: Array, default: () => [] },

  externalFixMeta: { type: String, default: "" },

  /** 测向–定位匹配结果；用于外源定位点着色（已关联批=测向同色，未关联=灰） */
  dfMatchResult: { type: Object, default: null }

});







const azRef = ref(null);

const sigRef = ref(null);

const rawAzRef = ref(null);

const rawSigRef = ref(null);

const freqRef = ref(null);

const topoRef = ref(null);

const toaRef = ref(null);

const burstFreqRef = ref(null);

const priRef = ref(null);

const jitterRef = ref(null);

const periodErrRef = ref(null);

const dutyRef = ref(null);

const mapRef = ref(null);

const mapDisplayMode = ref("static");
/** 每条标绘保留秒数，0 = 回放时不自动删除 */
const featureTtlSec = ref(10);
const replaySpeed = ref(10);
const replayPlaying = ref(false);
const replayCurrentMs = ref(0);
const replayMinMs = ref(0);
const replayMaxMs = ref(0);
const mapTimeline = ref([]);

let replayTimer = null;
const REPLAY_TICK_MS = 80;
/** @type {Map<string, import('ol/Feature').default>} */
let replayFeatureCache = new Map();
let mapExtentFitKey = "";



let azChart;

let sigChart;

let rawAzChart;

let rawSigChart;

let freqChart;

let topoChart;

let toaChart;

let burstFreqChart;

let priChart;

let jitterChart;

let periodErrChart;

let dutyChart;

let map;

let vectorSource;

let alive = false;

let drawToken = 0;



const colors = ["#ef4444", "#2563eb", "#16a34a", "#f59e0b", "#7c3aed", "#0f766e", "#db2777", "#0891b2", "#ca8a04", "#4f46e5", "#059669", "#dc2626"];

const lineCharts = [];

const MAX_SCATTER_POINTS = 2000;

const MAX_MAP_FEATURES = 800;

const matchedDeviceColors = computed(() =>
  buildMatchedDeviceColors(props.dfMatchResult?.batchToDevice, colors)
);

function azimuthEndLonLat(lon, lat, azimuthDeg, distanceKm) {
  const R = 6371;
  const brng = (azimuthDeg * Math.PI) / 180;
  const lat1 = (lat * Math.PI) / 180;
  const lon1 = (lon * Math.PI) / 180;
  const d = distanceKm / R;
  const lat2 = Math.asin(
    Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brng)
  );
  const lon2 = lon1 + Math.atan2(
    Math.sin(brng) * Math.sin(d) * Math.cos(lat1),
    Math.cos(d) - Math.sin(lat1) * Math.sin(lat2)
  );
  return [(lon2 * 180) / Math.PI, (lat2 * 180) / Math.PI];
}

const platformStyle = new Style({
  image: new Circle({
    radius: 7,
    fill: new Fill({ color: "#dc2626" }),
    stroke: new Stroke({ color: "#fff", width: 2 })
  }),
  zIndex: 20
});

const platformPathStyle = new Style({
  stroke: new Stroke({ color: "#dc2626", width: 2, lineDash: [6, 4] }),
  zIndex: 10
});

function asArray(v) {
  return Array.isArray(v) ? v : [];
}

const selectedTargetId = ref(null);

const selectedPoint = ref(null);

/** 地图点击检视：定位点 / 测向线 */
const mapInspect = ref(null);
const fixShowLinkedBearings = ref(false);

const staticRayBatchFilter = computed(() => {
  if (!mapInspect.value || mapDisplayMode.value !== "static") return null;
  if (mapInspect.value.kind === "ray" && mapInspect.value.batchId) {
    return new Set([mapInspect.value.batchId]);
  }
  if (mapInspect.value.kind === "fix" && fixShowLinkedBearings.value) {
    const ids = mapInspect.value.linkedBatchIds || [];
    return ids.length ? new Set(ids) : null;
  }
  return null;
});

const mapInspectTitle = computed(() => {
  if (!mapInspect.value) return "";
  return mapInspect.value.kind === "fix" ? "外源定位点" : "测向数据";
});

const mapInspectTableTitle = computed(() => {
  if (!mapInspect.value) return "";
  if (mapInspect.value.kind === "fix") return "关联测向数据明细";
  return `测向批 ${mapInspect.value.batchId || ""} 明细`;
});

const mapInspectTableRows = computed(() => {
  if (!mapInspect.value) return [];
  let rows = [];
  if (mapInspect.value.kind === "ray" && mapInspect.value.batchId) {
    rows = rowsForBatchId(mapInspect.value.batchId, props.dfMatchResult, props.network);
  } else if (mapInspect.value.kind === "fix" && fixShowLinkedBearings.value) {
    rows = rowsForBatchIds(mapInspect.value.linkedBatchIds, props.dfMatchResult, props.network);
  }
  return enrichTableRows(rows);
});

const mapInspectBatchBounds = computed(() => batchTimeBounds(mapInspectTableRows.value));

const selectedTargetDetail = computed(() => {
  if (!selectedTargetId.value || !props.network?.targets) return null;
  return props.network.targets.find((t) => t.targetId === selectedTargetId.value) || null;
});

/** 进度条步进：长时段数据约 3000 档，便于精细拖动 */
const replaySliderStep = computed(() => {
  const span = replayMaxMs.value - replayMinMs.value;
  if (span <= 0) return 1;
  return Math.max(1, Math.floor(span / 3000));
});

function targetTypeText(type) {
  const map = { GROUND: "地面站", AWACS: "预警机", AIR: "飞机" };
  return map[type] || type || "—";
}

function locateMethodText(m) {
  const map = { BEARING: "方位推算", CSV: "CSV定位", MIXED: "融合定位", NONE: "无定位" };
  return map[m] || m || "—";
}

function formatNum(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(2) : "—";
}

function formatDetectMs(ms) {
  if (ms == null) return "—";
  const d = new Date(Number(ms));
  if (Number.isNaN(d.getTime())) return "—";
  const pad = (x) => String(x).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function formatCoord(v) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(6) : "—";
}

function formatBatchFreq(freqHz) {
  return formatFreqMhzFromHz(freqHz);
}

function clearMapInspect() {
  mapInspect.value = null;
  fixShowLinkedBearings.value = false;
  mapExtentFitKey = "";
  drawMap();
}

function onFixLinkedToggle() {
  mapExtentFitKey = "";
  drawMap();
}

function selectExternalFixOnMap(fix) {
  if (!fix) return;
  const linkedBatchIds = batchIdsForDeviceName(fix.targetName, props.dfMatchResult?.batchToDevice);
  mapInspect.value = {
    kind: "fix",
    fix: {
      targetName: fix.targetName || fix.targetId || "外源",
      longitude: Number(fix.longitude),
      latitude: Number(fix.latitude),
      detectTimeMs: Number(fix.detectTimeMs)
    },
    linkedBatchIds
  };
  fixShowLinkedBearings.value = false;
  mapExtentFitKey = "";
  drawMap();
}

function selectRayOnMap(meta) {
  if (!meta?.batchId) return;
  mapInspect.value = {
    kind: "ray",
    batchId: meta.batchId,
    targetId: meta.targetId || "",
    azimuth: meta.azimuth,
    timeMs: meta.timeMs
  };
  fixShowLinkedBearings.value = false;
  mapExtentFitKey = "";
  drawMap();
}

function handleMapClick(evt) {
  const hits = [];
  map.forEachFeatureAtPixel(evt.pixel, (feature) => {
    hits.push(feature);
    return false;
  });
  const fixFeature = hits.find((f) => f.get("featureType") === "externalFix");
  if (fixFeature) {
    selectExternalFixOnMap({
      targetName: fixFeature.get("targetName"),
      targetId: fixFeature.get("targetId"),
      longitude: fixFeature.get("fixLon"),
      latitude: fixFeature.get("fixLat"),
      detectTimeMs: fixFeature.get("detectTimeMs")
    });
    return;
  }
  const rayFeature = hits.find((f) => f.get("featureType") === "ray");
  if (rayFeature?.get("batchId")) {
    selectRayOnMap({
      batchId: rayFeature.get("batchId"),
      targetId: rayFeature.get("targetId"),
      azimuth: rayFeature.get("azimuth"),
      timeMs: rayFeature.get("rayTimeMs")
    });
    return;
  }
  const targetFeature = hits.find((f) => f.get("targetId"));
  if (targetFeature) {
    selectTarget(targetFeature.get("targetId"), null, true);
  }
}



function rangeMinMax(values) {

  if (!values.length) return { min: 0, max: 0 };

  let min = values[0];

  let max = values[0];

  for (let i = 1; i < values.length; i++) {

    if (values[i] < min) min = values[i];

    if (values[i] > max) max = values[i];

  }

  return { min, max };

}



function downsamplePairs(pairs, maxPoints) {

  if (pairs.length <= maxPoints) return pairs;

  const step = Math.ceil(pairs.length / maxPoints);

  const out = [];

  for (let i = 0; i < pairs.length; i += step) out.push(pairs[i]);

  return out;

}



const amplitudeStats = computed(() => {
  const targets = asArray(props.network?.targets);
  return targets.map((t, idx) => {
    const levels = asArray(t.signalSeries).map((p) => Number(p.v)).filter(Number.isFinite);
    const freqs = asArray(t.freqSeries).map((p) => Number(p.v)).filter(Number.isFinite);

    const avgLevel = levels.length ? levels.reduce((a, b) => a + b, 0) / levels.length : 0;

    const { min: minLevel, max: maxLevel } = rangeMinMax(levels);

    return {

      targetId: t.targetId,

      role: t.role,

      color: colors[idx % colors.length],

      avgLevel,

      minLevel,

      maxLevel,

      primaryFreq: calcPrimaryFreq(freqs),

      count: levels.length,

      periodMs: t.periodMs != null ? Number(t.periodMs).toFixed(0) : "-",

      burstDwellMs: Number.isFinite(Number(t.burstDurationMeanMs)) ? Number(t.burstDurationMeanMs).toFixed(1) : "-",

      dutyCycle: Number.isFinite(Number(t.avgDutyCycle)) ? Number(t.avgDutyCycle).toFixed(1) : "-",

      burstCount: t.burstCount != null ? t.burstCount : "-"

    };

  });

});



function calcPrimaryFreq(freqs) {
  if (!Array.isArray(freqs) || freqs.length === 0) return "-";
  const freqCounts = new Map();
  for (const f of freqs) {
    if (!Number.isFinite(f)) continue;
    const key = f.toFixed(3);
    freqCounts.set(key, (freqCounts.get(key) || 0) + 1);
  }
  if (freqCounts.size === 0) return "-";
  let best = freqs[0].toFixed(3);
  let max = 0;
  freqCounts.forEach((count, key) => {
    if (count > max) {
      max = count;
      best = key;
    }
  });
  return best;
}



function toEpochMs(raw) {

  const t = Number(raw);

  if (!Number.isFinite(t) || t <= 0) return 0;

  // 13位毫秒时间戳 (约 2001~2286)

  if (t >= 1e12 && t < 1e13) return t;

  // 14位及以上紧凑格式 yyyyMMddHHmmss

  if (t >= 1e13) {

    const s = String(Math.trunc(t));

    if (s.length >= 14 && s.startsWith("20")) {

      const ms = new Date(

        Number(s.slice(0, 4)),

        Number(s.slice(4, 6)) - 1,

        Number(s.slice(6, 8)),

        Number(s.slice(8, 10)),

        Number(s.slice(10, 12)),

        Number(s.slice(12, 14))

      ).getTime();

      return Number.isFinite(ms) ? ms : 0;

    }

    if (t < 1e15) return t;

  }

  if (t >= 1e9 && t < 1e12) return t * 1000;

  return 0;

}



function formatTime(raw) {

  const ms = toEpochMs(raw);

  const dt = new Date(ms);

  if (Number.isNaN(dt.getTime())) return String(raw);

  const p = (n) => String(n).padStart(2, "0");

  return `${p(dt.getHours())}:${p(dt.getMinutes())}:${p(dt.getSeconds())}`;

}



function seriesName(t) {

  return `${t.targetId} (${t.role})`;

}

const CHART_TITLE_TOP = 6;
const CHART_LEGEND_TOP = 34;
const CHART_GRID_TOP = 78;
const CHART_GRID_BOTTOM = 60;

function friendlyYName(yName) {
  const map = {
    AZIMUTH: "方位(°)",
    SIGNAL_LEVEL: "幅度",
    FREQ: "频率(MHz)"
  };
  return map[yName] || yName;
}

function buildScrollLegend(legendNames) {
  return {
    type: "scroll",
    orient: "horizontal",
    top: CHART_LEGEND_TOP,
    left: 68,
    right: 12,
    height: 22,
    itemGap: 16,
    itemWidth: 12,
    itemHeight: 8,
    pageButtonItemGap: 8,
    pageIconSize: 10,
    pageTextStyle: { fontSize: 10 },
    textStyle: { fontSize: 11, lineHeight: 14 },
    data: legendNames,
    selectedMode: true
  };
}

function buildValueYAxis(yName) {
  return {
    type: "value",
    name: friendlyYName(yName),
    scale: true,
    nameLocation: "middle",
    nameGap: 44,
    nameTextStyle: { fontSize: 11, color: "#6b7280", padding: [0, 0, 0, 0] }
  };
}

function chartTitle(text) {
  return {
    text,
    left: 10,
    top: CHART_TITLE_TOP,
    textStyle: { fontSize: 13, fontWeight: 600 }
  };
}

function chartGrid(extra = {}) {
  return {
    left: 56,
    right: 20,
    top: CHART_GRID_TOP,
    bottom: CHART_GRID_BOTTOM,
    containLabel: false,
    ...extra
  };
}



function mapSeriesData(field) {
  const breakAz = field === "azimuthSeries";
  return asArray(props.network?.targets)
    .map((t) => {
      const pts = asArray(t[field])
        .map((p) => [toEpochMs(p.t), Number(p.v)])
        .filter((p) => Number.isFinite(p[0]) && p[0] > 0 && Number.isFinite(p[1]))
        .sort((a, b) => a[0] - b[0]);
      if (!breakAz || pts.length < 2) return pts;
      const out = [];
      for (let i = 0; i < pts.length; i++) {
        if (i > 0 && Math.abs(pts[i][1] - pts[i - 1][1]) > 15) {
          out.push([pts[i][0], null]);
        }
        out.push(pts[i]);
      }
      return out;
    });
}

function targetSeriesData(t, field) {
  const fallbackField = field === "rawAzimuthSeries"
    ? "azimuthSeries"
    : (field === "rawSignalSeries" ? "signalSeries" : field);
  const source = asArray(t?.[field]).length ? t?.[field] : t?.[fallbackField];
  return asArray(source)
    .map((p) => [toEpochMs(p.t), Number(p.v)])
    .filter((p) => Number.isFinite(p[0]) && p[0] > 0 && Number.isFinite(p[1]))
    .sort((a, b) => a[0] - b[0]);
}



function targetIdFromSeriesName(name) {

  return name ? String(name).split(" ")[0] : null;

}



function selectTarget(targetId, point = null, force = false) {
  mapExtentFitKey = "";
  if (mapDisplayMode.value === "replay") {
    stopReplayLoop();
  }
  if (!targetId) {
    selectedTargetId.value = null;
    selectedPoint.value = null;
    drawCharts();
    drawMap();
    if (mapDisplayMode.value === "replay") {
      replayCurrentMs.value = replayMinMs.value;
      syncReplayMap();
    }
    return;
  }
  const tid = String(targetId);
  if (force) {
    selectedTargetId.value = tid;
  } else {
    selectedTargetId.value = selectedTargetId.value === tid ? null : tid;
  }
  selectedPoint.value = null;
  drawCharts();
  drawMap();
  if (mapDisplayMode.value === "replay") {
    replayCurrentMs.value = replayMinMs.value;
    syncReplayMap();
  }
}

function setSelectedPoint(targetId, axis, value, time, chart) {
  // 点击点进入“单点高亮”：不再对整条曲线做淡化/加粗
  selectedTargetId.value = null;
  selectedPoint.value = {
    targetId: targetId ? String(targetId) : null,
    axis,
    value: Number(value),
    time,
    chart
  };
  drawCharts();
  drawMap();
}

function nearestSelectedPointIndex(targetId, data) {
  if (!selectedPoint.value || !targetId || selectedPoint.value.targetId !== targetId) return -1;
  if (!Array.isArray(data) || data.length === 0) return -1;
  const t = Number(selectedPoint.value.time);
  let bestIdx = -1;
  let bestGap = Number.POSITIVE_INFINITY;
  for (let i = 0; i < data.length; i++) {
    const x = Array.isArray(data[i]) ? Number(data[i][0]) : Number(data[i]?.value?.[0]);
    if (!Number.isFinite(x)) continue;
    const gap = Math.abs(x - t);
    if (gap < bestGap) {
      bestGap = gap;
      bestIdx = i;
    }
  }
  return bestIdx;
}



function buildLineOption(title, yName, field, avgLine) {
  const targets = asArray(props.network?.targets);

  const targetToShow = selectedTargetId.value || selectedPoint.value?.targetId;
  const hasSelection = !!targetToShow;

  const legendNames = targets.map(seriesName);

  const allData = mapSeriesData(field);



  const series = targets.map((t, idx) => {

    const isSelected = selectedTargetId.value === t.targetId;

    const color = colors[idx % colors.length];

    const data = allData[idx] || [];
    // 单点高亮：跨图只按“同一时间点(time)”匹配；y 值不同也要高亮同一采样时刻
    const hasPointOnThisSeries = !!selectedPoint.value
      && selectedPoint.value.targetId === t.targetId;
    const nearestIdx = hasPointOnThisSeries ? nearestSelectedPointIndex(t.targetId, data) : -1;

    const dataWithPointMark = hasPointOnThisSeries
      ? data.map(([x, y], i) => {
        if (i === nearestIdx) {
          return {
            value: [x, y],
            symbol: "diamond",
            symbolSize: 12,
            itemStyle: { color, borderColor: "#111827", borderWidth: 2 }
          };
        }
        return [x, y];
      })
      : data;

    return {

      id: t.targetId,

      name: seriesName(t),

      type: "line",

      showSymbol: hasPointOnThisSeries || isSelected || data.length <= 80,
 
      data: dataWithPointMark,

      connectNulls: true,

      lineStyle: {

        width: isSelected ? 4 : (t.role === "MASTER" ? 3 : 1.8),

        color,

        opacity: hasSelection && !isSelected ? 0.35 : 1

      },

      itemStyle: { color, opacity: hasSelection && !isSelected ? 0.35 : 1 },

      z: idx,

      emphasis: { focus: "none", disabled: true },

      markLine: avgLine && isSelected ? {

        silent: true,

        symbol: "none",

        lineStyle: { color, type: "dashed" },

        data: [{ yAxis: avgLine(t[field] || []) }]

      } : undefined

    };

  });



  return {

    animation: false,

    title: chartTitle(title),

    tooltip: {

      trigger: "item",

      formatter: (p) => {

        const [t, v] = p.value || [];

        return `${p.seriesName}<br/>time: ${formatTime(t)}<br/>${friendlyYName(yName)}: ${Number(v).toFixed(2)}`;

      }

    },

    legend: buildScrollLegend(legendNames),

    grid: chartGrid(),

    xAxis: { type: "time", axisLabel: { formatter: (v) => formatTime(v) } },

    yAxis: buildValueYAxis(yName),

    dataZoom: [{ type: "inside" }, { type: "slider", height: 18, bottom: 6 }],

    series

  };

}



function buildScatterOption(title, yName, field) {
  const targets = asArray(props.network?.targets);
  const hasSelection = !!selectedTargetId.value;
  const legendNames = targets.map(seriesName);
  const series = targets.map((t, idx) => {
    const isSelected = selectedTargetId.value === t.targetId;
    const color = colors[idx % colors.length];
    const data = downsamplePairs(targetSeriesData(t, field), MAX_SCATTER_POINTS);
    // 单点高亮：跨图只按同一 time 匹配
    const hasPointOnThisSeries = !!selectedPoint.value
      && selectedPoint.value.targetId === t.targetId;
    const nearestIdx = hasPointOnThisSeries ? nearestSelectedPointIndex(t.targetId, data) : -1;
    const dataWithPointMark = hasPointOnThisSeries
      ? data.map(([x, y], i) => {
        if (i === nearestIdx) {
          return {
            value: [x, y],
            symbol: "diamond",
            symbolSize: 14,
            itemStyle: { color, borderColor: "#111827", borderWidth: 2 }
          };
        }
        return [x, y];
      })
      : data;
    return {
      id: t.targetId,
      name: seriesName(t),
      type: "scatter",
      large: false,
      largeThreshold: 400,
      itemStyle: {
        color,
        opacity: hasSelection && !isSelected ? 0.18 : 0.68,
        borderColor: isSelected ? "#111827" : color,
        borderWidth: isSelected ? 1 : 0
      },
      data: dataWithPointMark
    };
  });

  return {
    animation: false,
    title: chartTitle(`${title}（按目标着色）`),
    tooltip: {
      trigger: "item",
      formatter: (p) => {
        const [t, v] = p.value || [];
        return `${p.seriesName}<br/>time: ${formatTime(t)}<br/>${friendlyYName(yName)}: ${Number(v).toFixed(2)}`;
      }
    },
    legend: buildScrollLegend(legendNames),
    grid: chartGrid(),
    xAxis: { type: "time", axisLabel: { formatter: (v) => formatTime(v) } },
    yAxis: buildValueYAxis(yName),
    dataZoom: [{ type: "inside" }, { type: "slider", height: 18, bottom: 6 }],
    series
  };
}



function avg(series) {

  if (!series.length) return 0;

  return series.reduce((s, p) => s + Number(p.v || 0), 0) / series.length;

}



function setChartOption(chart, option) {
  if (!chart) return;
  chart.dispatchAction({ type: "downplay" });
  chart.dispatchAction({ type: "unselect" });
  chart.clear();
  chart.setOption(option, { notMerge: true });
  if (option.legend?.data?.length) {
    const selected = {};
    option.legend.data.forEach((name) => { selected[name] = true; });
    chart.setOption({ legend: { selected } });
  }
}



function drawCharts() {

  if (!alive || !props.network || !azChart) return;

  setChartOption(azChart, buildLineOption("时间-方位图", "AZIMUTH", "azimuthSeries"));

  setChartOption(sigChart, buildLineOption("时间-信号强度图", "SIGNAL_LEVEL", "signalSeries", avg));

  setChartOption(rawAzChart, buildScatterOption("原始散点-时间方位", "AZIMUTH", "rawAzimuthSeries"));

  setChartOption(rawSigChart, buildScatterOption("原始散点-时间幅度", "SIGNAL_LEVEL", "rawSignalSeries"));

  setChartOption(freqChart, buildLineOption("时间-频率图", "FREQ", "freqSeries"));



  const targets = asArray(props.network?.targets);

  const nodes = targets.map((t, i) => ({

    id: t.targetId,

    name: seriesName(t),

    x: 140 + (i % 6) * 120,

    y: Math.floor(i / 6) * 100 + (t.role === "MASTER" ? 60 : 160),

    symbolSize: 28,

    itemStyle: { color: colors[i % colors.length] }

  }));

  topoChart.setOption({

    animation: false,

    title: { text: "网络拓扑图", left: 10, top: 8, textStyle: { fontSize: 13 } },

    series: [{ type: "graph", layout: "none", roam: true, data: nodes, links: [], label: { show: true, fontSize: 10 } }]

  }, true);

  drawRhythmCharts();

}



function mapRhythmSeriesData(field) {
  const targetToShow = selectedTargetId.value || selectedPoint.value?.targetId;
  const hasSelection = !!targetToShow;
  return asArray(props.network?.targets)
    .filter((t) => !hasSelection || t.targetId === targetToShow)
    .map((t) => {
      const pts = asArray(t[field])
        .map((p) => [toEpochMs(p.t), Number(p.v)])
        .filter((p) => Number.isFinite(p[0]) && p[0] > 0 && Number.isFinite(p[1]))
        .sort((a, b) => a[0] - b[0]);
      return { target: t, pts };
    });
}



function buildRhythmLineOption(title, yName, field) {
  const rows = mapRhythmSeriesData(field);
  const legendNames = rows.map((r) => seriesName(r.target));
  const hasSelection = !!selectedTargetId.value || !!selectedPoint.value?.targetId;
  const series = rows.map((r, idx) => {
    const color = colors[idx % colors.length];
    const isSelected = selectedTargetId.value === r.target.targetId;
    return {
      id: r.target.targetId,
      name: seriesName(r.target),
      type: "line",
      showSymbol: r.pts.length <= 120,
      data: r.pts,
      connectNulls: true,
      lineStyle: {
        width: isSelected ? 3 : 1.8,
        color,
        opacity: hasSelection && !isSelected ? 0.35 : 1
      },
      itemStyle: { color }
    };
  });
  return {
    animation: false,
    title: chartTitle(title),
    tooltip: {
      trigger: "item",
      formatter: (p) => {
        const [t, v] = p.value || [];
        return `${p.seriesName}<br/>TOA: ${formatTime(t)}<br/>${yName}: ${Number(v).toFixed(3)}`;
      }
    },
    legend: buildScrollLegend(legendNames),
    grid: chartGrid(),
    xAxis: { type: "time", axisLabel: { formatter: (v) => formatTime(v) } },
    yAxis: {
      type: "value",
      name: yName,
      scale: true,
      nameLocation: "middle",
      nameGap: 44,
      nameTextStyle: { fontSize: 11, color: "#6b7280" }
    },
    dataZoom: [{ type: "inside" }, { type: "slider", height: 18, bottom: 6 }],
    series
  };
}



function buildPriHistOption() {
  const targetToShow = selectedTargetId.value || selectedPoint.value?.targetId;
  const hasSelection = !!targetToShow;
  const rows = asArray(props.network?.targets)
    .filter((t) => !hasSelection || t.targetId === targetToShow)
    .map((t) => ({
      target: t,
      pts: asArray(t.priHistogram)
        .map((p) => [Number(p.t), Number(p.v)])
        .filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]))
        .sort((a, b) => a[0] - b[0])
    }));
  const legendNames = rows.map((r) => seriesName(r.target));
  const series = rows.map((r, idx) => ({
    id: r.target.targetId,
    name: seriesName(r.target),
    type: "bar",
    barGap: "8%",
    data: r.pts.map(([t, v]) => [t, v]),
    itemStyle: { color: colors[idx % colors.length], opacity: 0.85 }
  }));
  return {
    animation: false,
    title: chartTitle("PRI 分布图"),
    tooltip: { trigger: "item", formatter: (p) => `PRI ${p.value[0]} ms<br/>计数 ${p.value[1]}` },
    legend: buildScrollLegend(legendNames),
    grid: chartGrid(),
    xAxis: {
      type: "value",
      name: "PRI (ms)",
      scale: true,
      nameLocation: "middle",
      nameGap: 28,
      nameTextStyle: { fontSize: 11, color: "#6b7280" }
    },
    yAxis: {
      type: "value",
      name: "计数",
      nameLocation: "middle",
      nameGap: 36,
      nameTextStyle: { fontSize: 11, color: "#6b7280" }
    },
    dataZoom: [{ type: "inside" }, { type: "slider", height: 18, bottom: 6 }],
    series
  };
}



function drawRhythmCharts() {
  if (!alive || !props.network || !toaChart) return;
  setChartOption(toaChart, buildRhythmLineOption("TOA 间隔图", "间隔(s)", "toaIntervalSeries"));
  setChartOption(burstFreqChart, buildRhythmLineOption("时间-频率突发图", "频率(MHz)", "burstTimelineSeries"));
  setChartOption(priChart, buildPriHistOption());
  setChartOption(jitterChart, buildRhythmLineOption("Jitter 曲线", "抖动(%)", "jitterSeries"));
  setChartOption(periodErrChart, buildRhythmLineOption("周期时间轴/预测误差", "误差(ms)", "periodErrorSeries"));
  setChartOption(dutyChart, buildRhythmLineOption("占空比趋势图", "占空比(%)", "dutyCycleTrend"));
}



function targetLocateStyle(color, active) {
  return new Style({
    image: new Circle({
      radius: active ? 10 : 7,
      fill: new Fill({ color }),
      stroke: new Stroke({ color: active ? "#111827" : "#fff", width: active ? 3 : 2 })
    }),
    zIndex: active ? 30 : 25
  });
}

function externalFixStyle(color, matched, selected = false) {
  return new Style({
    image: new RegularShape({
      points: 3,
      radius: selected ? 10 : matched ? 9 : 8,
      rotation: 0,
      fill: new Fill({ color }),
      stroke: new Stroke({
        color: selected ? "#111827" : matched ? "#111827" : "#fff",
        width: selected ? 3 : matched ? 2 : 1.5
      })
    }),
    zIndex: selected ? 24 : matched ? 22 : 20
  });
}

function isSelectedExternalFix(fix) {
  const inspect = mapInspect.value;
  if (!inspect || inspect.kind !== "fix" || !inspect.fix) return false;
  const name = (fix?.targetName || fix?.targetId || "").trim();
  const inspectName = (inspect.fix.targetName || "").trim();
  if (name && inspectName && name !== inspectName) return false;
  const lon = Number(fix?.longitude);
  const lat = Number(fix?.latitude);
  const tMs = Number(fix?.detectTimeMs);
  return (
    Math.abs(lon - Number(inspect.fix.longitude)) < 1e-5
    && Math.abs(lat - Number(inspect.fix.latitude)) < 1e-5
    && Math.abs(tMs - Number(inspect.fix.detectTimeMs)) < 1
  );
}

function colorForBatch(batchId) {
  return resolveBatchRayColor(
    batchId,
    props.dfMatchResult?.batchToDevice,
    matchedDeviceColors.value,
    colors
  );
}

/** 从测向–定位匹配结果绘制检视用测向线 */
function addInspectBearingRays(rows, featureCountRef) {
  const sampled = downsampleInspectRows(rows);
  const platformKeys = new Set();

  for (const row of sampled) {
    if (featureCountRef.count >= MAX_MAP_FEATURES) break;
    const platLon = Number(row.rxLon);
    const platLat = Number(row.rxLat);
    const az = Number(row.bearing);
    if (!Number.isFinite(platLon) || !Number.isFinite(platLat) || !Number.isFinite(az)) continue;

    const batchId = row.batchId || "";
    const color = colorForBatch(batchId);
    const key = `${platLon.toFixed(5)},${platLat.toFixed(5)}`;
    if (!platformKeys.has(key)) {
      platformKeys.add(key);
      const marker = new Feature({ geometry: new Point(fromLonLat([platLon, platLat])) });
      marker.setStyle(platformStyle);
      vectorSource.addFeature(marker);
      featureCountRef.count += 1;
    }

    const end = azimuthEndLonLat(platLon, platLat, az, AZIMUTH_RAY_KM);
    const line = new Feature({
      geometry: new LineString([fromLonLat([platLon, platLat]), fromLonLat(end)])
    });
    line.set("featureType", "ray");
    line.set("batchId", batchId);
    line.set("azimuth", az);
    line.set("rayTimeMs", Number(row.timeMs));
    line.setStyle(new Style({
      stroke: new Stroke({ color, width: 2.8, opacity: 1 }),
      zIndex: 8
    }));
    vectorSource.addFeature(line);
    featureCountRef.count += 1;
  }
}

function externalFixDisplayColor(fix) {
  return resolveExternalFixColor(fix, matchedDeviceColors.value);
}

function isMatchedExternalFix(fix) {
  const name = (fix?.targetName || "").trim();
  return Boolean(name && matchedDeviceColors.value.has(name));
}

function mapTargetFilter() {
  return selectedTargetId.value || selectedPoint.value?.targetId || null;
}

function rebuildMapTimeline() {
  const filter = mapTargetFilter();
  const events = buildMapTimeline(
    props.network,
    props.externalTargetFixes,
    toEpochMs,
    {
      targetIdFilter: filter,
      matchedDeviceColors: matchedDeviceColors.value
    }
  );
  mapTimeline.value = events;
  const range = timelineRange(events);
  replayMinMs.value = range.minMs;
  replayMaxMs.value = range.maxMs;
  if (replayCurrentMs.value < range.minMs || replayCurrentMs.value > range.maxMs) {
    replayCurrentMs.value = range.minMs;
  }
}

function clearReplayFeatures() {
  if (!vectorSource) return;
  replayFeatureCache.forEach((f) => vectorSource.removeFeature(f));
  replayFeatureCache.clear();
}

function computePointFitExtent(source) {
  if (!source) return null;
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  let found = false;

  for (const feature of source.getFeatures()) {
    if (feature.get("featureType") === "ray") continue;
    const geom = feature.getGeometry();
    if (!geom) continue;
    const ext = geom.getExtent();
    if (!ext.every(Number.isFinite)) continue;
    minX = Math.min(minX, ext[0]);
    minY = Math.min(minY, ext[1]);
    maxX = Math.max(maxX, ext[2]);
    maxY = Math.max(maxY, ext[3]);
    found = true;
  }

  return found ? [minX, minY, maxX, maxY] : null;
}

function fitMapExtentIfNeeded() {
  if (!map || !vectorSource) return;
  map.updateSize();
  const key = `${props.network?.networkId || ""}:${mapDisplayMode.value}:${mapTargetFilter() || "all"}`;
  if (mapExtentFitKey === key) return;
  const extent = computePointFitExtent(vectorSource);
  if (extent && extent.every(Number.isFinite)) {
    map.getView().fit(extent, { padding: [40, 40, 40, 40], maxZoom: 12, duration: 200 });
    mapExtentFitKey = key;
  }
}

function createReplayFeature(ev, index) {
  const targetToShow = mapTargetFilter();
  const hasSelection = !!targetToShow;

  if (ev.type === "ray") {
    const color = colors[ev.colorIdx % colors.length];
    const end = azimuthEndLonLat(ev.platLon, ev.platLat, ev.azimuth, AZIMUTH_RAY_KM);
    const line = new Feature({
      geometry: new LineString([fromLonLat([ev.platLon, ev.platLat]), fromLonLat(end)])
    });
    line.set("targetId", ev.targetId);
    line.set("featureType", "ray");
    line.set("batchId", ev.batchId || "");
    line.set("azimuth", ev.azimuth);
    line.set("rayTimeMs", ev.timeMs);
    line.setStyle(new Style({
      stroke: new Stroke({ color, width: hasSelection ? 2 : 1.2 }),
      zIndex: 5
    }));
    return line;
  }

  if (ev.type === "platform") {
    const marker = new Feature({ geometry: new Point(fromLonLat([ev.platLon, ev.platLat])) });
    marker.setStyle(platformStyle);
    marker.set("featureType", "platform");
    return marker;
  }

  if (ev.type === "targetLocate") {
    const color = colors[ev.colorIdx % colors.length];
    const marker = new Feature({ geometry: new Point(fromLonLat([ev.lon, ev.lat])) });
    marker.set("targetId", ev.targetId);
    marker.set("featureType", "target");
    marker.setStyle(targetLocateStyle(color, targetToShow === ev.targetId));
    return marker;
  }

  if (ev.type === "externalFix") {
    const marker = new Feature({ geometry: new Point(fromLonLat([ev.lon, ev.lat])) });
    marker.set("featureType", "externalFix");
    marker.set("targetName", ev.label);
    marker.set("targetId", ev.targetId || "");
    marker.set("affiliation", ev.affiliation);
    marker.set("fixLon", ev.lon);
    marker.set("fixLat", ev.lat);
    marker.set("detectTimeMs", ev.detectTimeMs || ev.timeMs);
    const matched = Boolean(ev.matchColor);
    const color = ev.matchColor || EXTERNAL_FIX_NEUTRAL_COLOR;
    marker.setStyle(externalFixStyle(color, matched));
    return marker;
  }

  return null;
}

function collectVisibleReplayKeys(currentMs) {
  const events = mapTimeline.value;
  const ttl = featureTtlSec.value;
  const keys = visibleEventKeysAt(events, currentMs, ttl);
  const platformKeys = new Set();
  for (let i = 0; i < events.length; i++) {
    const ev = events[i];
    if (ev.type !== "ray") continue;
    const k = eventKey(ev, i);
    if (!keys.has(k)) continue;
    platformKeys.add(`plat:${ev.platLon.toFixed(5)},${ev.platLat.toFixed(5)}`);
  }
  platformKeys.forEach((k) => keys.add(k));
  return keys;
}

function syncReplayMap() {
  if (!alive || !vectorSource) return;
  const events = mapTimeline.value;
  if (!events.length) {
    vectorSource.clear();
    replayFeatureCache.clear();
    return;
  }
  const needed = collectVisibleReplayKeys(replayCurrentMs.value);

  replayFeatureCache.forEach((feature, key) => {
    if (!needed.has(key)) {
      vectorSource.removeFeature(feature);
      replayFeatureCache.delete(key);
    }
  });

  const platformAdded = new Set();
  for (let i = 0; i < events.length; i++) {
    const ev = events[i];
    const key = eventKey(ev, i);
    if (!needed.has(key)) continue;
    if (replayFeatureCache.has(key)) continue;

    if (ev.type === "ray") {
      const platKey = `plat:${ev.platLon.toFixed(5)},${ev.platLat.toFixed(5)}`;
      if (!platformAdded.has(platKey) && !replayFeatureCache.has(platKey)) {
        const platEv = { type: "platform", platLon: ev.platLon, platLat: ev.platLat };
        const platFeature = createReplayFeature(platEv, i);
        if (platFeature) {
          vectorSource.addFeature(platFeature);
          replayFeatureCache.set(platKey, platFeature);
          platformAdded.add(platKey);
        }
      }
    }

    const feature = createReplayFeature(ev, i);
    if (feature) {
      vectorSource.addFeature(feature);
      replayFeatureCache.set(key, feature);
    }
    if (replayFeatureCache.size >= MAX_MAP_FEATURES) break;
  }

  fitMapExtentIfNeeded();
}

function drawMapStatic() {
  if (!alive || !vectorSource || !props.network) return;
  vectorSource.clear();
  replayFeatureCache.clear();

  const targetToShow = mapTargetFilter();
  const hasSelection = !!targetToShow;
  const targets = asArray(props.network?.targets);
  const platformKeys = new Set();
  const platformCoords = [];

  const batchFilter = staticRayBatchFilter.value;
  const onlyBatchRays = Boolean(batchFilter);
  const featureCountRef = { count: 0 };

  let drewMatchInspectRays = false;
  if (batchFilter?.size && props.dfMatchResult?.measurementsByBatch) {
    const inspectRows = rowsForBatchIds([...batchFilter], props.dfMatchResult, props.network);
    if (inspectRows.length) {
      addInspectBearingRays(inspectRows, featureCountRef);
      drewMatchInspectRays = true;
    }
  }

  targets.forEach((t, idx) => {
    if (drewMatchInspectRays) return;
    if (hasSelection && targetToShow !== t.targetId) return;
    const color = colors[idx % colors.length];

    const lon = Number(t.locateLon);
    const lat = Number(t.locateLat);
    if (Number.isFinite(lon) && Number.isFinite(lat) && !onlyBatchRays) {
      const marker = new Feature({
        geometry: new Point(fromLonLat([lon, lat]))
      });
      marker.set("targetId", t.targetId);
      marker.set("featureType", "target");
      marker.setStyle(targetLocateStyle(color, targetToShow === t.targetId));
      vectorSource.addFeature(marker);
      featureCountRef.count += 1;
    }

    for (const p of asArray(t.trackPoints)) {
      if (featureCountRef.count >= MAX_MAP_FEATURES) return;
      if (p.platformLon == null || p.platformLat == null) continue;

      const platLon = Number(p.platformLon);
      const platLat = Number(p.platformLat);
      if (!Number.isFinite(platLon) || !Number.isFinite(platLat)) continue;

      const batchId = p.batchId || "";
      if (batchFilter && !batchFilter.has(batchId)) continue;

      const key = `${platLon.toFixed(5)},${platLat.toFixed(5)}`;
      if (!platformKeys.has(key)) {
        platformKeys.add(key);
        platformCoords.push([platLon, platLat]);
        const marker = new Feature({ geometry: new Point(fromLonLat([platLon, platLat])) });
        marker.setStyle(platformStyle);
        vectorSource.addFeature(marker);
      }

      const az = Number(p.azimuth);
      if (!Number.isFinite(az)) continue;
      const end = azimuthEndLonLat(platLon, platLat, az, AZIMUTH_RAY_KM);
      const line = new Feature({
        geometry: new LineString([fromLonLat([platLon, platLat]), fromLonLat(end)])
      });
      line.set("featureType", "ray");
      line.set("targetId", t.targetId);
      line.set("batchId", batchId);
      line.set("azimuth", az);
      line.set("rayTimeMs", Number(p.t));
      const highlight = onlyBatchRays && batchFilter.has(batchId);
      line.setStyle(new Style({
        stroke: new Stroke({
          color,
          width: highlight ? 2.8 : hasSelection ? 2 : 1.2,
          opacity: highlight ? 1 : 0.85
        }),
        zIndex: highlight ? 8 : 5
      }));
      vectorSource.addFeature(line);
      featureCountRef.count += 1;
    }
  });

  if (platformCoords.length >= 2 && !onlyBatchRays) {
    const sorted = [...platformCoords].sort((a, b) => a[0] - b[0] || a[1] - b[1]);
    const path = new Feature({
      geometry: new LineString(sorted.map((c) => fromLonLat(c)))
    });
    path.setStyle(platformPathStyle);
    vectorSource.addFeature(path);
  }

  for (const fix of asArray(props.externalTargetFixes)) {
    if (featureCountRef.count >= MAX_MAP_FEATURES) break;
    const fixLon = Number(fix.longitude);
    const fixLat = Number(fix.latitude);
    if (!Number.isFinite(fixLon) || !Number.isFinite(fixLat)) continue;
    const marker = new Feature({ geometry: new Point(fromLonLat([fixLon, fixLat])) });
    marker.set("featureType", "externalFix");
    marker.set("targetName", fix.targetName || fix.targetId || "外源");
    marker.set("targetId", fix.targetId || "");
    marker.set("affiliation", fix.affiliation || "");
    marker.set("fixLon", fixLon);
    marker.set("fixLat", fixLat);
    marker.set("detectTimeMs", Number(fix.detectTimeMs));
    const matched = isMatchedExternalFix(fix);
    const selected = isSelectedExternalFix(fix);
    marker.setStyle(externalFixStyle(externalFixDisplayColor(fix), matched, selected));
    vectorSource.addFeature(marker);
    featureCountRef.count += 1;
  }

  fitMapExtentIfNeeded();
}

function drawMap() {
  if (!alive || !vectorSource || !props.network) return;
  if (mapDisplayMode.value === "replay") {
    rebuildMapTimeline();
    syncReplayMap();
  } else {
    stopReplayLoop();
    drawMapStatic();
  }
}

function stopReplayLoop() {
  replayPlaying.value = false;
  if (replayTimer != null) {
    clearInterval(replayTimer);
    replayTimer = null;
  }
}

function replayTick() {
  if (!replayPlaying.value || mapDisplayMode.value !== "replay") return;
  const max = replayMaxMs.value;
  let next = replayCurrentMs.value + REPLAY_TICK_MS * replaySpeed.value;
  if (next >= max) {
    next = max;
    stopReplayLoop();
  }
  replayCurrentMs.value = next;
  syncReplayMap();
}

function startReplayLoop() {
  stopReplayLoop();
  if (!mapTimeline.value.length) return;
  replayPlaying.value = true;
  replayTimer = setInterval(replayTick, REPLAY_TICK_MS);
}

function toggleReplay() {
  if (mapDisplayMode.value !== "replay") return;
  if (replayPlaying.value) {
    stopReplayLoop();
  } else {
    if (replayCurrentMs.value >= replayMaxMs.value) {
      replayCurrentMs.value = replayMinMs.value;
      syncReplayMap();
    }
    startReplayLoop();
  }
}

function resetReplay() {
  stopReplayLoop();
  replayCurrentMs.value = replayMinMs.value;
  mapExtentFitKey = "";
  syncReplayMap();
}

function onMapModeChange() {
  mapExtentFitKey = "";
  if (mapDisplayMode.value === "replay") {
    rebuildMapTimeline();
    replayCurrentMs.value = replayMinMs.value;
    vectorSource?.clear();
    clearReplayFeatures();
    syncReplayMap();
  } else {
    stopReplayLoop();
    drawMapStatic();
  }
}

function onReplayParamsChange() {
  if (mapDisplayMode.value === "replay") {
    syncReplayMap();
  }
}

function onReplayScrub() {
  if (replayPlaying.value) stopReplayLoop();
  syncReplayMap();
}



function bindChartEvents(chart, axis, chartName) {
  chart.off("click");
  chart.off("legendselectchanged");
  chart.on("click", (params) => {
    if (params.componentType !== "series") return;
    const targetId = params.seriesId ?? targetIdFromSeriesName(params.seriesName);
    const [time, value] = params.value || [];
    if (targetId && Number.isFinite(Number(time)) && Number.isFinite(Number(value))) {
      setSelectedPoint(targetId, axis, Number(value), Number(time), chartName);
    } else if (targetId) {
      selectTarget(targetId);
    }
  });
  chart.on("legendselectchanged", (params) => {
    const selected = {};
    Object.keys(params.selected || {}).forEach((name) => { selected[name] = true; });
    chart.setOption({ legend: { selected } });
    const targetId = targetIdFromSeriesName(params.name);
    if (targetId) selectTarget(targetId);
  });
}



async function scheduleDraw() {

  const token = ++drawToken;

  await nextTick();

  requestAnimationFrame(() => {

    if (!alive || token !== drawToken) return;

    try {

      map?.updateSize();

      drawCharts();

      drawMap();

      [azChart, sigChart, rawAzChart, rawSigChart, freqChart, topoChart, toaChart, burstFreqChart, priChart, jitterChart, periodErrChart, dutyChart].forEach((c) => c?.resize());

    } catch (e) {

      console.error("chart draw failed", e);

    }

  });

}



onMounted(() => {

  alive = true;

  azChart = echarts.init(azRef.value);

  sigChart = echarts.init(sigRef.value);

  rawAzChart = echarts.init(rawAzRef.value);

  rawSigChart = echarts.init(rawSigRef.value);

  freqChart = echarts.init(freqRef.value);

  topoChart = echarts.init(topoRef.value);

  toaChart = echarts.init(toaRef.value);

  burstFreqChart = echarts.init(burstFreqRef.value);

  priChart = echarts.init(priRef.value);

  jitterChart = echarts.init(jitterRef.value);

  periodErrChart = echarts.init(periodErrRef.value);

  dutyChart = echarts.init(dutyRef.value);

  [
    [azChart, "AZIMUTH", "时间-方位图"],
    [sigChart, "SIGNAL_LEVEL", "时间-信号强度图"],
    [rawAzChart, "AZIMUTH", "原始散点-时间方位"],
    [rawSigChart, "SIGNAL_LEVEL", "原始散点-时间幅度"],
    [freqChart, "FREQ", "时间-频率图"]
  ].forEach(([chart, axis, name]) => {
    lineCharts.push(chart);
    bindChartEvents(chart, axis, name);
  });

  vectorSource = new VectorSource();

  map = new OlMap({

    target: mapRef.value,

    layers: [createBasemapLayer(), new VectorLayer({ source: vectorSource })],

    view: new View({ center: fromLonLat([110, 34]), zoom: 4 })

  });

  map.on("click", handleMapClick);

  map.on("pointermove", (evt) => {
    if (!mapRef.value) return;
    const hit = map.hasFeatureAtPixel(evt.pixel, { hitTolerance: 6 });
    mapRef.value.style.cursor = hit ? "pointer" : "";
  });

  scheduleDraw();

});



watch(() => props.network?.networkId, () => {
  selectedPoint.value = null;
  clearMapInspect();
  stopReplayLoop();
  mapExtentFitKey = "";
  if (props.highlightTargetId && props.network?.targets?.some((t) => t.targetId === props.highlightTargetId)) {
    selectedTargetId.value = props.highlightTargetId;
  } else {
    selectedTargetId.value = null;
  }
  scheduleDraw();
});

watch(
  () => [props.network?.networkId, props.highlightTargetId],
  ([, id]) => {
    if (id && props.network?.targets?.some((t) => t.targetId === id)) {
      nextTick(() => selectTarget(id, null, true));
    }
  }
);

watch(
  () => [props.externalTargetFixes, props.dfMatchResult],
  () => {
    mapExtentFitKey = "";
    scheduleDraw();
  },
  { deep: true }
);

defineExpose({ selectTarget });



onBeforeUnmount(() => {

  alive = false;

  stopReplayLoop();
  replayFeatureCache.clear();

  drawToken++;

  [azChart, sigChart, rawAzChart, rawSigChart, freqChart, topoChart, toaChart, burstFreqChart, priChart, jitterChart, periodErrChart, dutyChart].forEach((c) => c?.dispose());

  azChart = sigChart = rawAzChart = rawSigChart = freqChart = topoChart = null;

  toaChart = burstFreqChart = priChart = jitterChart = periodErrChart = dutyChart = null;

  lineCharts.length = 0;

  if (map) {

    map.setTarget(undefined);

    map = null;

  }

  vectorSource = null;

});

</script>



<style scoped>

.workbench { margin-top: 10px; }

.map-section {
  margin-bottom: 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 10px;
  background: #fff;
}

.map-header {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.map-header h4 { margin: 0; font-size: 14px; }

.map-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  font-size: 12px;
  background: #f9fafb;
  padding: 4px 8px;
  border-radius: 4px;
  border: 1px solid #e5e7eb;
}

.legend-platform { color: #dc2626; font-weight: 600; }
.legend-target { color: #2563eb; font-weight: 600; }
.legend-external-neutral { color: #9ca3af; font-weight: 600; }
.legend-external-matched { color: #2563eb; font-weight: 600; }
.legend-meta { color: #64748b; font-size: 12px; margin-left: 8px; }
.legend-ray { color: #374151; }

.map-replay-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px 14px;
  margin-bottom: 8px;
  padding: 8px 10px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  font-size: 12px;
}

.replay-field {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #374151;
}

.replay-field select,
.replay-field input[type="number"] {
  padding: 3px 6px;
  border: 1px solid #cbd5e1;
  border-radius: 4px;
  font-size: 12px;
  min-width: 72px;
}

.replay-btn {
  padding: 4px 12px;
  border: 1px solid #94a3b8;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
  font-size: 12px;
}

.replay-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.map-replay-transport {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  margin-top: 8px;
  margin-bottom: 8px;
  padding: 10px 12px;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
}

.replay-play-btn {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border: none;
  border-radius: 50%;
  background: #2563eb;
  color: #fff;
  font-size: 18px;
  line-height: 1;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(37, 99, 235, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  margin-top: 14px;
}

.replay-play-btn:hover:not(:disabled) {
  background: #1d4ed8;
}

.replay-play-btn:disabled {
  background: #94a3b8;
  cursor: not-allowed;
  box-shadow: none;
}

.replay-track-wrap {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.replay-track-labels {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
  gap: 8px;
}

.replay-cursor-time {
  color: #2563eb;
  font-weight: 600;
  font-size: 12px;
  flex-shrink: 0;
}

.replay-range {
  width: 100%;
  height: 8px;
  margin: 0;
  cursor: pointer;
  accent-color: #2563eb;
}

.replay-range:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.replay-hint {
  color: #64748b;
  font-size: 11px;
}

.target-detail-panel {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  align-items: center;
  margin-bottom: 8px;
  padding: 8px 10px;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  font-size: 13px;
}

.clear-btn {
  margin-left: auto;
  padding: 4px 10px;
  font-size: 12px;
  border: 1px solid #93c5fd;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}

.map-panel-full {
  height: min(68vh, calc(100vh - 180px));
  min-height: 420px;
  position: relative;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  overflow: hidden;
}

.map { width: 100%; height: 100%; min-height: 400px; }

.map-inspect-panel {
  margin-top: 10px;
  padding: 10px 12px;
  background: #f8fafc;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 13px;
}

.map-inspect-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
}

.map-inspect-line { margin: 4px 0; color: #334155; }
.map-inspect-hint { margin: 6px 0 0; color: #64748b; font-size: 12px; }
.map-inspect-meta { margin: 4px 0 0; color: #475569; font-size: 12px; }

.inspect-check {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  cursor: pointer;
  user-select: none;
}

.map-detail-table-wrap {
  margin-top: 10px;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  background: #fff;
  overflow: hidden;
}

.map-detail-title {
  margin: 0;
  padding: 8px 12px;
  font-size: 13px;
  background: #f1f5f9;
  border-bottom: 1px solid #e2e8f0;
}

.map-detail-bounds {
  margin-left: 8px;
  font-weight: normal;
  color: #0f766e;
  font-size: 12px;
}

.map-detail-scroll {
  max-height: 220px;
  overflow: auto;
}

.map-detail-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}

.map-detail-table th,
.map-detail-table td {
  padding: 6px 8px;
  border-bottom: 1px solid #f1f5f9;
  text-align: left;
  white-space: nowrap;
}

.map-detail-table th {
  background: #fafafa;
  position: sticky;
  top: 0;
  z-index: 1;
}

.map-detail-table tr.row-start td { background: #ecfdf5; }
.map-detail-table tr.row-end td { background: #fff7ed; }

.time-tag {
  display: inline-block;
  margin-left: 6px;
  padding: 0 5px;
  border-radius: 3px;
  font-size: 10px;
  font-weight: 600;
}

.time-tag.start { background: #10b981; color: #fff; }
.time-tag.end { background: #f59e0b; color: #fff; }

.stats-panel { margin-bottom: 12px; border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px; background: #fafafa; }

.stats-panel h4 { margin: 0 0 8px; font-size: 14px; }

.stats-hint { margin: 0 0 8px; font-size: 12px; color: #6b7280; }

.stats-panel table { width: 100%; border-collapse: collapse; font-size: 13px; }

.stats-panel th, .stats-panel td { border: 1px solid #e5e7eb; padding: 6px; text-align: left; }

.stats-panel tbody tr { cursor: pointer; }

.stats-panel tbody tr.row-active { background: #eff6ff; outline: 2px solid #2563eb; }

.point-panel { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-bottom: 12px; border: 1px solid #bfdbfe; border-radius: 6px; padding: 8px; background: #eff6ff; font-size: 13px; }

.color-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; vertical-align: middle; }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }

.chart-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.chart-caption {
  margin: 0;
  padding: 0 2px 2px;
  font-size: 12px;
  line-height: 1.45;
  color: #64748b;
}

.rhythm-section { margin-top: 12px; border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px; background: #fafafa; }

.rhythm-section h4 { margin: 0 0 8px; font-size: 14px; }

.rhythm-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }

.panel { height: 300px; border: 1px solid #e5e7eb; border-radius: 6px; }

</style>


