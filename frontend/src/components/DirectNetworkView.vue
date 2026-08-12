<template>
  <div class="direct-network">
    <header class="header">
      <h2>网络详情 · Agent 深链</h2>
      <p v-if="analysisId">Session: {{ analysisId }} · Network #{{ networkId }}</p>
      <p v-if="error" class="error">{{ error }}</p>
      <p v-else-if="loading" class="hint">正在加载网络详情…</p>
    </header>

    <AnalystWorkbench
      v-if="network"
      :key="workbenchKey"
      :network="network"
    />
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from "vue";
import AnalystWorkbench from "./AnalystWorkbench.vue";
import { fetchNetworkDetail } from "../scene/sceneApi.js";
import { normalizeNetwork } from "../scene/signalUi.js";

const props = defineProps({
  analysisId: { type: String, required: true },
  networkId: { type: Number, required: true },
});

const loading = ref(false);
const error = ref("");
const network = ref(null);
const workbenchKey = ref("");

async function loadNetwork() {
  if (!props.analysisId || props.networkId == null) return;
  loading.value = true;
  error.value = "";
  network.value = null;
  workbenchKey.value = `${props.analysisId}-${props.networkId}`;
  try {
    const raw = await fetchNetworkDetail(props.analysisId, props.networkId);
    network.value = normalizeNetwork(raw);
  } catch (e) {
    error.value = e.message || String(e);
  } finally {
    loading.value = false;
  }
}

watch(
  () => [props.analysisId, props.networkId],
  () => loadNetwork(),
  { immediate: true }
);

onMounted(loadNetwork);
</script>

<style scoped>
.direct-network {
  padding: 12px 16px 24px;
  font-family: Arial, sans-serif;
}

.header h2 {
  margin: 0 0 4px;
  font-size: 18px;
}

.header p {
  margin: 0 0 8px;
  color: #475569;
  font-size: 13px;
}

.error {
  color: #b91c1c;
}

.hint {
  color: #64748b;
}
</style>
