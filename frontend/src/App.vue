<template>
  <DirectNetworkView
    v-if="deepLink"
    :analysis-id="deepLink.analysisId"
    :network-id="deepLink.networkId"
  />
  <StreamSituationView v-else-if="streamMode" />
  <PrcFfFreqScatterTool v-else-if="ffScatterMode" />
  <div v-else class="layout">
    <p class="nav-links">
      <a href="#/stream">流式态势（独立模块）</a>
      ·
      <a href="#/ff-scatter">PrcFf 原始频率标绘</a>
    </p>
    <SceneWorkflow />
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from "vue";
import DirectNetworkView from "./components/DirectNetworkView.vue";
import SceneWorkflow from "./components/SceneWorkflow.vue";
import StreamSituationView from "./components/StreamSituationView.vue";
import PrcFfFreqScatterTool from "./components/PrcFfFreqScatterTool.vue";

const deepLink = ref(null);
const streamMode = ref(false);
const ffScatterMode = ref(false);

function parseHash() {
  const hash = window.location.hash || "";
  if (hash.startsWith("#/stream")) {
    deepLink.value = null;
    streamMode.value = true;
    ffScatterMode.value = false;
    return;
  }
  if (hash.startsWith("#/ff-scatter")) {
    deepLink.value = null;
    streamMode.value = false;
    ffScatterMode.value = true;
    return;
  }
  streamMode.value = false;
  ffScatterMode.value = false;
  if (!hash.startsWith("#/network")) {
    deepLink.value = null;
    return;
  }
  const query = hash.includes("?") ? hash.split("?")[1] : "";
  const params = new URLSearchParams(query);
  const analysisId = params.get("analysisId");
  const networkId = Number(params.get("networkId"));
  if (analysisId && !Number.isNaN(networkId)) {
    deepLink.value = { analysisId, networkId };
  } else {
    deepLink.value = null;
  }
}

onMounted(() => {
  parseHash();
  window.addEventListener("hashchange", parseHash);
});

onUnmounted(() => {
  window.removeEventListener("hashchange", parseHash);
});
</script>

<style>
.layout {
  font-family: Arial, sans-serif;
  padding: 16px;
}
.nav-links {
  margin: 0 0 12px;
  font-size: 13px;
}
.nav-links a {
  color: #1d4ed8;
}
</style>
