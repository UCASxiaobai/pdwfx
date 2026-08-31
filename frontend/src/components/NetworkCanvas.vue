<!-- vue2-done -->
<template>
  <div class="canvas-wrap" v-if="network">
    <div class="stack">
      <canvas ref="azCanvas" width="920" height="420"></canvas>
      <canvas ref="sigCanvas" width="920" height="420"></canvas>
      <canvas ref="labelCanvas" width="920" height="420"></canvas>
    </div>
  </div>
</template>

<script>
import { CET36_CHART_COLORS } from "@/scene/echartsTheme.js";

export default {
  name: "NetworkCanvas",
  props: {
    network: { type: Object, default: null }
  },
  watch: {
    network: {
      handler() {
        this.$nextTick(() => this.draw());
      },
      deep: true
    }
  },
  mounted() {
    this.draw();
  },
  methods: {
    draw() {
      if (!this.network) return;
      const targets = this.network.targets || [];
      if (!targets.length) return;
      const points = targets.flatMap((t) => t.azimuthSeries || []);
      const ts = points.map((p) => p.t);
      const tMin = Math.min(...ts);
      const tMax = Math.max(...ts);
      const width = 920;
      const height = 420;
      const pad = 40;
      const mapX = (t) => pad + ((t - tMin) / Math.max(1, tMax - tMin)) * (width - pad * 2);
      const mapY = (v, min, max) =>
        height - pad - ((v - min) / Math.max(1, max - min)) * (height - pad * 2);
      const palette = CET36_CHART_COLORS;

      const azCanvas = this.$refs.azCanvas;
      const sigCanvas = this.$refs.sigCanvas;
      const labelCanvas = this.$refs.labelCanvas;
      if (!azCanvas || !sigCanvas || !labelCanvas) return;

      const azCtx = azCanvas.getContext("2d");
      const sigCtx = sigCanvas.getContext("2d");
      const lblCtx = labelCanvas.getContext("2d");
      [azCtx, sigCtx, lblCtx].forEach((ctx) => ctx.clearRect(0, 0, width, height));

      azCtx.strokeStyle = "#2d6c83";
      azCtx.strokeRect(pad, pad, width - pad * 2, height - pad * 2);

      const azVals = targets.flatMap((t) => (t.azimuthSeries || []).map((p) => p.v));
      const sigVals = targets.flatMap((t) => (t.signalSeries || []).map((p) => p.v));
      const azMin = Math.min(...azVals);
      const azMax = Math.max(...azVals);
      const sigMin = Math.min(...sigVals);
      const sigMax = Math.max(...sigVals);

      targets.forEach((target, i) => {
        const c = palette[i % palette.length];
        const az = [...(target.azimuthSeries || [])].sort((a, b) => a.t - b.t);
        const sig = [...(target.signalSeries || [])].sort((a, b) => a.t - b.t);

        azCtx.strokeStyle = c;
        azCtx.lineWidth = 2;
        azCtx.beginPath();
        az.forEach((p, idx) => {
          const x = mapX(p.t);
          const y = mapY(p.v, azMin, azMax);
          if (idx === 0) azCtx.moveTo(x, y);
          else azCtx.lineTo(x, y);
        });
        azCtx.stroke();

        sigCtx.strokeStyle = c;
        sigCtx.globalAlpha = 0.65;
        sigCtx.lineWidth = 1.5;
        sigCtx.beginPath();
        sig.forEach((p, idx) => {
          const x = mapX(p.t);
          const y = mapY(p.v, sigMin, sigMax);
          if (idx === 0) sigCtx.moveTo(x, y);
          else sigCtx.lineTo(x, y);
        });
        sigCtx.stroke();
        sigCtx.globalAlpha = 1;

        const lastAz = az[az.length - 1];
        if (lastAz) {
          const x = mapX(lastAz.t) + 4;
          const y = mapY(lastAz.v, azMin, azMax);
          lblCtx.fillStyle = target.role === "MASTER" ? "#f78989" : "#b3d2d5";
          lblCtx.font =
            target.role === "MASTER" ? "bold 14px sans-serif" : "12px sans-serif";
          lblCtx.fillText(`${target.targetId} ${target.role}`, x, y);
        }
      });
    }
  }
};
</script>

<style scoped>
.stack {
  position: relative;
  width: 920px;
  height: 420px;
}
canvas {
  position: absolute;
  left: 0;
  top: 0;
}
</style>
