<!-- vue2-done -->
<template>
  <section class="signal-explorer cet36-panel">
    <header class="cet36-panel__title">
      <div>
        <h3 class="cet36-panel__title-text">信号分析 · 网络研判</h3>
        <p class="hint">
          各优质场景已导出为 CSV 并完成分网研判；左侧选场景与网络，右侧查看目标表与图表。
        </p>
      </div>
    </header>

    <div class="explorer-body cet36-panel__body">
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
              侦获 {{ n.signalCount || 0 }} · 目标 {{ n.targetCount != null ? n.targetCount : "—" }}
            </div>
          </div>
        </div>
      </aside>

      <main v-if="selected" class="detail">
        <p v-if="detailLoading" class="loading">正在加载网络详情…</p>
        <template v-else>
          <h4>
            场景 #{{ localSceneRank }} · 网络 #{{ selected.networkId }}
            ({{ formatFreq(selected.freq) }} MHz) — {{ commModeLabel(selected.commMode) }}
            <span v-if="selected.commLinkChannelLabel"> — {{ selected.commLinkChannelLabel }}</span>
          </h4>
          <table class="cet36-table">
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
                <td>{{ t.burstCount != null ? t.burstCount : "-" }}</td>
                <td>{{ convergenceLabel(t.convergence) }}</td>
                <td>{{ ellipseLabel(t) }}</td>
                <td
                  :style="{
                    color: t.role === 'MASTER' ? '#f78989' : 'var(--theme-text-primary)',
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

<script>
import AnalystWorkbench from "./AnalystWorkbench.vue";
import { fetchNetworkDetail } from "@/api/pdwfx";
import { sceneTypeLabel, sortScenesForDisplay } from "@/scene/sceneFilters.js";
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
} from "@/scene/signalUi.js";

export default {
  name: "SignalNetworkExplorer",
  components: { AnalystWorkbench },
  props: {
    sceneItems: { type: Array, default: function () { return []; } },
    externalTargetFixes: { type: Array, default: function () { return []; } },
    externalFixMeta: { type: String, default: "" },
    dfMatchResult: { type: Object, default: null },
    initialSceneRank: { type: Number, default: null },
    initialNetworkId: { type: Number, default: null }
  },
  data: function () {
    return {
      localSceneRank: null,
      freqSearch: "",
      networkTypeFilter: "",
      commLinkFilter: "",
      targetTypeFilter: "",
      activeNetworkId: null,
      activeTargetId: null,
      selected: null,
      detailLoading: false,
      workbenchKey: "",
      loadSeq: 0
    };
  },
  computed: {
    sortedSceneItems: function () {
      return sortScenesForDisplay(this.sceneItems);
    },
    currentItem: function () {
      const want = Number(this.localSceneRank);
      const self = this;
      return this.sortedSceneItems.find(function (x) { return Number(x.rank) === want; });
    },
    analysisId: function () {
      return (this.currentItem && this.currentItem.session && this.currentItem.session.analysisId) || "";
    },
    networks: function () {
      const list = (this.currentItem && this.currentItem.session && this.currentItem.session.networks) || [];
      return list.slice().sort(function (a, b) { return (b.signalCount || 0) - (a.signalCount || 0); });
    },
    filteredNetworks: function () {
      var list = this.networks;
      const q = this.freqSearch.trim();
      if (q) {
        const num = Number(q);
        const isNum = !Number.isNaN(num);
        list = list.filter(function (n) {
          if (String(n.networkId) === q) return true;
          const f = formatFreq(n.freq);
          if (f.indexOf(q) >= 0) return true;
          return isNum && Math.abs(n.freq - num) <= 0.5;
        });
      }
      if (this.networkTypeFilter) {
        const filter = this.networkTypeFilter;
        list = list.filter(function (n) { return displayNetworkType(n) === filter; });
      }
      if (this.commLinkFilter) {
        const filter = this.commLinkFilter;
        list = list.filter(function (n) {
          return (n.commLinkChannelLabel || n.commLinkChannel || "") === filter;
        });
      }
      if (this.targetTypeFilter) {
        const type = this.targetTypeFilter;
        list = list.filter(function (n) { return this.networkHasTargetType(n, type); }.bind(this));
      }
      return list;
    },
    networkTypeOptions: function () {
      return uniqueValues(this.networks.map(function (n) { return displayNetworkType(n); }));
    },
    commLinkOptions: function () {
      return uniqueValues(
        this.networks
          .map(function (n) { return n.commLinkChannelLabel || n.commLinkChannel || ""; })
          .filter(Boolean)
      );
    },
    networkTypeStats: function () {
      return countBy(this.filteredNetworks, function (n) { return displayNetworkType(n); });
    },
    commLinkStats: function () {
      return countBy(
        this.filteredNetworks,
        function (n) { return n.commLinkChannelLabel || n.commLinkChannel || "未研判"; }
      );
    }
  },
  watch: {
    sceneItems: {
      handler: function (items) {
        if (!items || !items.length) return;
        const want = Number(this.localSceneRank);
        const self = this;
        if (this.localSceneRank == null || !items.some(function (x) { return Number(x.rank) === want; })) {
          this.localSceneRank = Number(this.initialSceneRank != null ? this.initialSceneRank : items[0].rank);
        }
        if (this.initialNetworkId != null) {
          this.selectNetwork({ networkId: this.initialNetworkId });
        }
      },
      immediate: true
    }
  },
  created: function () {
    this.networkCache = new Map();
  },
  methods: {
    sceneTypeLabel: sceneTypeLabel,
    commModeLabel: commModeLabel,
    convergenceLabel: convergenceLabel,
    displayNetworkType: displayNetworkType,
    ellipseLabel: ellipseLabel,
    formatEmissionShare: formatEmissionShare,
    formatFreq: formatFreq,
    formatMetric: formatMetric,
    formatStatMap: formatStatMap,
    targetTypeLabel: targetTypeLabel,
    networkHasTargetType: function (n, type) {
      if (type === "GROUND") return (n.groundTargetCount || 0) > 0;
      if (type === "AWACS") return (n.awacsTargetCount || 0) > 0;
      if (type === "AIR") return (n.airTargetCount || 0) > 0;
      return true;
    },
    onSceneChange: function () {
      this.activeNetworkId = null;
      this.selected = null;
      this.freqSearch = "";
      this.networkTypeFilter = "";
      this.commLinkFilter = "";
      this.targetTypeFilter = "";
      const first = this.filteredNetworks[0];
      if (first) this.selectNetwork(first);
    },
    selectNetwork: async function (summary) {
      if (!this.analysisId) return;
      const seq = ++this.loadSeq;
      this.activeNetworkId = summary.networkId;
      this.detailLoading = true;
      const cacheKey = this.analysisId + "-" + summary.networkId;
      this.workbenchKey = cacheKey;
      if (this.networkCache.has(cacheKey)) {
        this.selected = this.networkCache.get(cacheKey);
        this.detailLoading = false;
        return;
      }
      try {
        const raw = await fetchNetworkDetail(this.analysisId, summary.networkId);
        if (seq !== this.loadSeq) return;
        const norm = normalizeNetwork(raw);
        this.networkCache.set(cacheKey, norm);
        this.selected = norm;
        const self = this;
        const card = this.networks.find(function (x) { return x.networkId === summary.networkId; });
        if (card && norm) {
          card.targetCount = norm.targetCount != null ? norm.targetCount : (norm.targets || []).length;
          card.networkType = norm.networkType;
          card.commLinkChannelLabel = norm.commLinkChannelLabel;
        }
      } finally {
        if (seq === this.loadSeq) this.detailLoading = false;
      }
    },
    locateMethodLabel: function (m) {
      const map = { BEARING: "方位推算", CSV: "CSV定位", MIXED: "融合定位", NONE: "无定位" };
      return map[m] || m || "—";
    },
    focusTarget: function (targetId) {
      this.activeTargetId = targetId;
      const wb = this.$refs.workbenchRef;
      if (wb && wb.selectTarget) wb.selectTarget(targetId, null, true);
    },
    focusNetwork: function (sceneRank, networkId, targetId) {
      this.activeTargetId = targetId || null;
      this.localSceneRank = Number(sceneRank);
      this.selectNetwork({ networkId: networkId });
    }
  }
};
</script>

<style scoped>
.signal-explorer {
  margin-top: 20px;
}
.explorer-header h3,
.cet36-panel__title-text {
  margin: 0 0 4px;
}
.hint {
  font-size: 12px;
  color: var(--theme-text-muted);
  margin: 0 0 12px;
  font-weight: normal;
}
.explorer-body {
  display: flex;
  gap: 16px;
  min-height: 70vh;
}
.list {
  width: 300px;
  flex-shrink: 0;
  border-right: 1px solid var(--theme-border);
  padding-right: 12px;
}
.scene-pick {
  display: flex;
  flex-direction: column;
  font-size: 12px;
  gap: 4px;
  margin-bottom: 10px;
  color: var(--theme-text-label);
}
.scene-pick select {
  padding: 6px 8px;
  border: 1px solid var(--theme-border-input);
  border-radius: 2px;
  background: var(--theme-bg-input);
  color: var(--theme-text-primary);
}
.list h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--theme-text-accent);
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
  border: 1px solid var(--theme-border-input);
  border-radius: 2px;
  font-size: 12px;
  background: var(--theme-bg-input);
  color: var(--theme-text-primary);
}
.stats-bar {
  font-size: 11px;
  color: var(--theme-text-secondary);
  margin-bottom: 8px;
  line-height: 1.4;
}
.cards {
  overflow-y: auto;
  max-height: 420px;
}
.card {
  border: 1px solid var(--theme-border);
  border-radius: 2px;
  padding: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  font-size: 12px;
  background: var(--theme-bg-panel-alt);
  color: var(--theme-text-secondary);
}
.card.active {
  border-color: var(--theme-border-focus);
  background: var(--theme-bg-table-current);
}
.type-line {
  font-weight: 600;
  color: var(--theme-text-accent);
}
.empty {
  color: var(--theme-text-muted);
  font-size: 12px;
}
.detail {
  flex: 1;
  min-width: 0;
  overflow: auto;
}
.detail-empty {
  color: var(--theme-text-muted);
  padding: 24px;
}
.detail h4 {
  margin: 0 0 10px;
  color: var(--theme-text-accent);
  font-size: 14px;
}
.loading {
  color: var(--theme-text-accent);
}
.summary {
  margin: 10px 0;
  padding: 8px;
  border: 1px solid var(--theme-border);
  border-radius: 2px;
  background: var(--theme-bg-panel-alt);
}
.summary h5 {
  margin: 0 0 6px;
  color: var(--theme-text-accent);
  font-size: 13px;
}
.conclusion-list {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--theme-text-secondary);
}
.detail tbody tr {
  cursor: pointer;
}
.detail tbody tr.row-active td {
  background: var(--theme-bg-table-current);
  outline: 2px solid var(--theme-border-focus);
}
</style>
