<template>
  <section class="signal-explorer">
    <div class="explorer-header">
      <h3>信号分析 · 网络研判</h3>
      <p class="hint">
        各优质场景已导出为 CSV 并完成分网研判；左侧选场景与网络，右侧查看目标表与图表。
      </p>
    </div>

    <div class="explorer-body">
      <aside class="list">
        <label class="scene-pick">
          优质场景
          <select v-model.number="localSceneRank" @change="onSceneChange">
            <option v-for="item in sortedSceneItems" :key="item.rank" :value="item.rank">
              #{{ item.rank }} {{ sceneTypeLabel(item.sceneType) }}
            </option>
          </select>
        </label>

        <h4>网络列表 ({{ filteredNetworks.length }}/{{ networks.length }})</h4>
        <div class="filters">
          <input
            v-model.trim="freqSearch"
            class="freq-search"
            type="text"
            placeholder="频率 / 网络ID"
          />
          <select v-model="networkTypeFilter" class="filter-select">
            <option value="">全部网络类型</option>
            <option v-for="t in networkTypeOptions" :key="t" :value="t">{{ t }}</option>
          </select>
          <select v-model="commLinkFilter" class="filter-select">
            <option value="">全部波道</option>
            <option v-for="c in commLinkOptions" :key="c" :value="c">{{ c }}</option>
          </select>
          <select v-model="targetTypeFilter" class="filter-select">
            <option value="">含目标类型</option>
            <option value="GROUND">含地面站</option>
            <option value="AWACS">含预警机</option>
            <option value="AIR">含飞机</option>
          </select>
        </div>
        <div class="stats-bar">
          <span>网络类型: {{ formatStatMap(networkTypeStats) }}</span>
          <span>波道: {{ formatStatMap(commLinkStats) }}</span>
        </div>
        <div v-if="!filteredNetworks.length" class="empty">无匹配网络</div>
        <div class="cards">
          <div
            v-for="n in filteredNetworks"
            :key="n.networkId"
            class="card"
            :class="{ active: activeNetworkId === n.networkId }"
            @click="selectNetwork(n)"
          >
            <div>ID: {{ n.networkId }}</div>
            <div>freq: {{ formatFreq(n.freq) }}</div>
            <div class="type-line">{{ displayNetworkType(n) }}</div>
            <div>通信: {{ commModeLabel(n.commMode) }}</div>
            <div>
              侦获 {{ n.signalCount || 0 }} · 目标 {{ n.targetCount || "—" }}
            </div>
          </div>
        </div>
      </aside>

      <main class="detail" v-if="selected">
        <p v-if="detailLoading" class="loading">正在加载网络详情…</p>
        <template v-else>
          <h4>
            场景 #{{ localSceneRank }} · 网络 #{{ selected.networkId }}
            ({{ formatFreq(selected.freq) }} MHz) — {{ commModeLabel(selected.commMode) }}
            <span v-if="selected.commLinkChannelLabel"> — {{ selected.commLinkChannelLabel }}</span>
          </h4>
          <table>
            <thead>
              <tr>
                <th>targetId</th>
                <th>targetType</th>
                <th>定位(经,纬)</th>
                <th>定位方式</th>
                <th>流量占比%</th>
                <th>主周期(ms)</th>
                <th>平均驻留(ms)</th>
                <th>占空比%</th>
                <th>burst</th>
                <th>convergence</th>
                <th>定位椭圆</th>
                <th>role</th>
                <th>confidence</th>
                <th>点数</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="t in selected.targets || []"
                :key="t.targetId"
                :class="{ 'row-active': activeTargetId === t.targetId }"
                @click="focusTarget(t.targetId)"
              >
                <td>{{ t.targetId }}</td>
                <td>{{ targetTypeLabel(t.targetType) }}</td>
                <td>
                  <template v-if="t.locateLon != null">{{ Number(t.locateLon).toFixed(4) }}, {{ Number(t.locateLat).toFixed(4) }}</template>
                  <template v-else>—</template>
                </td>
                <td>{{ locateMethodLabel(t.locateMethod) }}</td>
                <td>{{ formatEmissionShare(t.emissionSharePct) }}</td>
                <td>{{ formatMetric(t.periodMs, 0) }}</td>
                <td>{{ formatMetric(t.burstDurationMeanMs, 1) }}</td>
                <td>{{ formatMetric(t.avgDutyCycle, 1) }}</td>
                <td>{{ t.burstCount ?? "-" }}</td>
                <td>{{ convergenceLabel(t.convergence) }}</td>
                <td>{{ ellipseLabel(t) }}</td>
                <td
                  :style="{
                    color: t.role === 'MASTER' ? '#b91c1c' : '#111827',
                    fontWeight: t.role === 'MASTER' ? 700 : 400
                  }"
                >
                  {{ t.role }}
                </td>
                <td>{{ t.confidence }}</td>
                <td>{{ (t.azimuthSeries || []).length }}</td>
              </tr>
            </tbody>
          </table>
          <section v-if="selected.analysisSummary" class="summary">
            <h5>网络分析结论</h5>
            <ul class="conclusion-list">
              <li
                v-for="(x, i) in selected.analysisSummary.networkConclusions"
                :key="'n' + i"
              >
                {{ x }}
              </li>
            </ul>
          </section>
          <AnalystWorkbench
            :key="workbenchKey"
            ref="workbenchRef"
            :network="selected"
            :highlight-target-id="activeTargetId"
            :external-target-fixes="externalTargetFixes"
            :external-fix-meta="externalFixMeta"
            :df-match-result="dfMatchResult"
          />
        </template>
      </main>
      <main v-else class="detail detail-empty">
        <p>请在左侧选择网络查看研判详情与图表</p>
      </main>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from "vue";
import AnalystWorkbench from "./AnalystWorkbench.vue";
import { fetchNetworkDetail } from "../scene/sceneApi.js";
import { sceneTypeLabel, sortScenesForDisplay } from "../scene/sceneFilters.js";
import {
  commModeLabel,
  convergenceLabel,
  countBy,
  displayNetworkType,
  ellipseLabel,
  formatEmissionShare,
  formatFreq,
  formatMetric,
  formatStatMap,
  normalizeNetwork,
  targetTypeLabel,
  uniqueValues
} from "../scene/signalUi.js";

const props = defineProps({
  /** forwardItems: { rank, sceneType, session }[] */
  sceneItems: { type: Array, default: () => [] },
  externalTargetFixes: { type: Array, default: () => [] },
  externalFixMeta: { type: String, default: "" },
  dfMatchResult: { type: Object, default: null },
  initialSceneRank: { type: Number, default: null },
  initialNetworkId: { type: Number, default: null }
});

const localSceneRank = ref(null);
const freqSearch = ref("");
const networkTypeFilter = ref("");
const commLinkFilter = ref("");
const targetTypeFilter = ref("");
const activeNetworkId = ref(null);
const activeTargetId = ref(null);
const selected = ref(null);
const detailLoading = ref(false);
const workbenchKey = ref("");
const workbenchRef = ref(null);
let loadSeq = 0;
const networkCache = new Map();

const sortedSceneItems = computed(() => sortScenesForDisplay(props.sceneItems));

const currentItem = computed(() => {
  const want = Number(localSceneRank.value);
  return sortedSceneItems.value.find((x) => Number(x.rank) === want);
});

const analysisId = computed(() => currentItem.value?.session?.analysisId || "");

const networks = computed(() => {
  const list = currentItem.value?.session?.networks || [];
  return [...list].sort((a, b) => (b.signalCount || 0) - (a.signalCount || 0));
});

function networkHasTargetType(n, type) {
  if (type === "GROUND") return (n.groundTargetCount || 0) > 0;
  if (type === "AWACS") return (n.awacsTargetCount || 0) > 0;
  if (type === "AIR") return (n.airTargetCount || 0) > 0;
  return true;
}

const filteredNetworks = computed(() => {
  let list = networks.value;
  const q = freqSearch.value.trim();
  if (q) {
    const num = Number(q);
    const isNum = !Number.isNaN(num);
    list = list.filter((n) => {
      if (String(n.networkId) === q) return true;
      const f = formatFreq(n.freq);
      if (f.includes(q)) return true;
      return isNum && Math.abs(n.freq - num) <= 0.5;
    });
  }
  if (networkTypeFilter.value) {
    list = list.filter((n) => displayNetworkType(n) === networkTypeFilter.value);
  }
  if (commLinkFilter.value) {
    list = list.filter(
      (n) =>
        (n.commLinkChannelLabel || n.commLinkChannel || "") === commLinkFilter.value
    );
  }
  if (targetTypeFilter.value) {
    list = list.filter((n) => networkHasTargetType(n, targetTypeFilter.value));
  }
  return list;
});

const networkTypeOptions = computed(() =>
  uniqueValues(networks.value.map((n) => displayNetworkType(n)))
);
const commLinkOptions = computed(() =>
  uniqueValues(
    networks.value.map((n) => n.commLinkChannelLabel || n.commLinkChannel || "").filter(Boolean)
  )
);
const networkTypeStats = computed(() =>
  countBy(filteredNetworks.value, (n) => displayNetworkType(n))
);
const commLinkStats = computed(() =>
  countBy(
    filteredNetworks.value,
    (n) => n.commLinkChannelLabel || n.commLinkChannel || "未研判"
  )
);

watch(
  () => props.sceneItems,
  (items) => {
    if (!items?.length) return;
    const want = Number(localSceneRank.value);
    if (localSceneRank.value == null || !items.some((x) => Number(x.rank) === want)) {
      localSceneRank.value = Number(props.initialSceneRank ?? items[0].rank);
    }
    if (props.initialNetworkId != null) {
      selectNetwork({ networkId: props.initialNetworkId });
    }
  },
  { immediate: true }
);

function onSceneChange() {
  activeNetworkId.value = null;
  selected.value = null;
  freqSearch.value = "";
  networkTypeFilter.value = "";
  commLinkFilter.value = "";
  targetTypeFilter.value = "";
  const first = filteredNetworks.value[0];
  if (first) selectNetwork(first);
}

async function selectNetwork(summary) {
  if (!analysisId.value) return;
  const seq = ++loadSeq;
  activeNetworkId.value = summary.networkId;
  detailLoading.value = true;
  const cacheKey = `${analysisId.value}-${summary.networkId}`;
  workbenchKey.value = cacheKey;
  if (networkCache.has(cacheKey)) {
    selected.value = networkCache.get(cacheKey);
    detailLoading.value = false;
    return;
  }
  try {
    const raw = await fetchNetworkDetail(analysisId.value, summary.networkId);
    if (seq !== loadSeq) return;
    const norm = normalizeNetwork(raw);
    networkCache.set(cacheKey, norm);
    selected.value = norm;
    const card = networks.value.find((x) => x.networkId === summary.networkId);
    if (card && norm) {
      card.targetCount = norm.targetCount ?? (norm.targets || []).length;
      card.networkType = norm.networkType;
      card.commLinkChannelLabel = norm.commLinkChannelLabel;
    }
  } finally {
    if (seq === loadSeq) detailLoading.value = false;
  }
}

function locateMethodLabel(m) {
  const map = { BEARING: "方位推算", CSV: "CSV定位", MIXED: "融合定位", NONE: "无定位" };
  return map[m] || m || "—";
}

function focusTarget(targetId) {
  activeTargetId.value = targetId;
  workbenchRef.value?.selectTarget?.(targetId, null, true);
}

function focusNetwork(sceneRank, networkId, targetId = null) {
  activeTargetId.value = targetId || null;
  localSceneRank.value = Number(sceneRank);
  selectNetwork({ networkId });
}

defineExpose({ focusNetwork });
</script>

<style scoped>
.signal-explorer {
  margin-top: 20px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 16px;
  background: #fafafa;
}
.explorer-header h3 {
  margin: 0 0 4px;
  font-size: 1.05rem;
}
.hint {
  font-size: 12px;
  color: #6b7280;
  margin: 0 0 12px;
}
.explorer-body {
  display: flex;
  gap: 16px;
  min-height: 70vh;
}
.list {
  width: 300px;
  flex-shrink: 0;
  border-right: 1px solid #e5e7eb;
  padding-right: 12px;
}
.scene-pick {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  gap: 4px;
  margin-bottom: 10px;
}
.scene-pick select {
  padding: 6px 8px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
}
.list h4 {
  margin: 0 0 8px;
  font-size: 13px;
}
.filters {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}
.freq-search,
.filter-select {
  width: 100%;
  box-sizing: border-box;
  padding: 6px 8px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  font-size: 12px;
}
.stats-bar {
  font-size: 11px;
  color: #4b5563;
  margin-bottom: 8px;
  line-height: 1.4;
}
.cards {
  overflow-y: auto;
  max-height: 420px;
}
.card {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  font-size: 12px;
  background: #fff;
}
.card.active {
  border-color: #2563eb;
  background: #eff6ff;
}
.type-line {
  font-weight: 600;
  color: #1d4ed8;
}
.empty {
  color: #9ca3af;
  font-size: 12px;
}
.detail {
  flex: 1;
  min-width: 0;
  overflow: auto;
}
.detail-empty {
  color: #9ca3af;
  padding: 24px;
}
.detail h4 {
  margin: 0 0 10px;
}
.loading {
  color: #2563eb;
}
.summary {
  margin: 10px 0;
  padding: 8px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  background: #fff;
}
.conclusion-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
}
.detail tbody tr {
  cursor: pointer;
}
.detail tbody tr.row-active {
  background: #eff6ff;
  outline: 2px solid #2563eb;
}
table {
  width: 100%;
  border-collapse: collapse;
  margin-bottom: 12px;
  font-size: 12px;
}
th,
td {
  border: 1px solid #e5e7eb;
  padding: 6px;
  text-align: left;
}
</style>
