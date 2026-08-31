<!-- vue2-done -->
<template>
  <AppShell mode="main">
    <div class="pdwfx-page direct-network">
      <header class="header">
        <h2>网络详情 · Agent 深链</h2>
        <p v-if="analysisId">Session: {{ analysisId }} · Network #{{ networkId }}</p>
        <p v-if="error" class="cet36-error">{{ error }}</p>
        <p v-else-if="loading" class="cet36-hint">正在加载网络详情…</p>
      </header>
      <AnalystWorkbench v-if="network" :key="workbenchKey" :network="network" />
    </div>
  </AppShell>
</template>

<script>
import AppShell from "./shell/AppShell.vue";
import AnalystWorkbench from "./AnalystWorkbench.vue";
import { fetchNetworkDetail } from "@/api/pdwfx";
import { normalizeNetwork } from "@/scene/signalUi.js";

export default {
  name: "DirectNetworkView",
  components: { AppShell, AnalystWorkbench },
  props: {
    analysisId: { type: String, required: true },
    networkId: { type: Number, required: true }
  },
  data() {
    return {
      loading: false,
      error: "",
      network: null,
      workbenchKey: ""
    };
  },
  watch: {
    analysisId: "loadNetwork",
    networkId: "loadNetwork"
  },
  mounted() {
    this.loadNetwork();
  },
  methods: {
    async loadNetwork() {
      if (!this.analysisId || this.networkId == null) return;
      this.loading = true;
      this.error = "";
      this.network = null;
      this.workbenchKey = `${this.analysisId}-${this.networkId}`;
      try {
        const raw = await fetchNetworkDetail(this.analysisId, this.networkId);
        this.network = normalizeNetwork(raw);
      } catch (e) {
        this.error = (e && e.message) || String(e);
      } finally {
        this.loading = false;
      }
    }
  }
};
</script>

<style scoped>
.direct-network .header h2 {
  margin: 0 0 4px;
  font-size: 18px;
  color: var(--theme-text-accent);
}
.direct-network .header p {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--theme-text-secondary);
}
</style>
