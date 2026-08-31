<!-- vue2-done -->
<template>
  <div id="app-root">
    <DirectNetworkView
      v-if="deepLink"
      :analysis-id="deepLink.analysisId"
      :network-id="deepLink.networkId"
    />
    <StreamSituationView v-else-if="streamMode" />
    <PrcFfFreqScatterTool v-else-if="ffScatterMode" />
    <AppShell v-else mode="main">
      <div class="pdwfx-page">
        <SceneWorkflow />
      </div>
    </AppShell>
  </div>
</template>

<script>
import AppShell from "./components/shell/AppShell.vue";
import DirectNetworkView from "./components/DirectNetworkView.vue";
import SceneWorkflow from "./components/SceneWorkflow.vue";
import StreamSituationView from "./components/StreamSituationView.vue";
import PrcFfFreqScatterTool from "./components/PrcFfFreqScatterTool.vue";

export default {
  name: "App",
  components: {
    AppShell,
    DirectNetworkView,
    SceneWorkflow,
    StreamSituationView,
    PrcFfFreqScatterTool
  },
  data() {
    return {
      deepLink: null,
      streamMode: false,
      ffScatterMode: false
    };
  },
  mounted() {
    this.parseHash();
    window.addEventListener("hashchange", this.parseHash);
  },
  beforeDestroy() {
    window.removeEventListener("hashchange", this.parseHash);
  },
  methods: {
    parseHash() {
      const hash = window.location.hash || "";
      if (hash.startsWith("#/stream")) {
        this.deepLink = null;
        this.streamMode = true;
        this.ffScatterMode = false;
        return;
      }
      if (hash.startsWith("#/ff-scatter")) {
        this.deepLink = null;
        this.streamMode = false;
        this.ffScatterMode = true;
        return;
      }
      this.streamMode = false;
      this.ffScatterMode = false;
      if (!hash.startsWith("#/network")) {
        this.deepLink = null;
        return;
      }
      const query = hash.includes("?") ? hash.split("?")[1] : "";
      const params = new URLSearchParams(query);
      const analysisId = params.get("analysisId");
      const networkId = Number(params.get("networkId"));
      if (analysisId && !Number.isNaN(networkId)) {
        this.deepLink = { analysisId, networkId };
      } else {
        this.deepLink = null;
      }
    }
  }
};
</script>
