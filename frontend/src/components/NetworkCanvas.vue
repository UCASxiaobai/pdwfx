<template>
  <div class="canvas-wrap" v-if="network">
    <div class="stack">
      <canvas ref="azCanvas" width="920" height="420"></canvas>
      <canvas ref="sigCanvas" width="920" height="420"></canvas>
      <canvas ref="labelCanvas" width="920" height="420"></canvas>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from "vue";

const props = defineProps({
  network: { type: Object, default: null }
});

const azCanvas = ref(null);
const sigCanvas = ref(null);
const labelCanvas = ref(null);

function draw() {
  if (!props.network) return;
  const targets = props.network.targets || [];
  if (!targets.length) return;
  const points = targets.flatMap((t) => t.azimuthSeries || []);
  const ts = points.map((p) => p.t);
  const tMin = Math.min(...ts);
  const tMax = Math.max(...ts);
  const width = 920;
  const height = 420;
  const pad = 40;
  const mapX = (t) => pad + ((t - tMin) / Math.max(1, tMax - tMin)) * (width - pad * 2);
  const mapY = (v, min, max) => height - pad - ((v - min) / Math.max(1, max - min)) * (height - pad * 2);
  const palette = ["#2563eb", "#16a34a", "#f97316", "#e11d48", "#7c3aed", "#0f766e"];

  const azCtx = azCanvas.value.getContext("2d");
  const sigCtx = sigCanvas.value.getContext("2d");
  const lblCtx = labelCanvas.value.getContext("2d");
  [azCtx, sigCtx, lblCtx].forEach((ctx) => ctx.clearRect(0, 0, width, height));

  azCtx.strokeStyle = "#d1d5db";
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
      lblCtx.fillStyle = target.role === "MASTER" ? "#b91c1c" : "#1f2937";
      lblCtx.font = target.role === "MASTER" ? "bold 14px sans-serif" : "12px sans-serif";
      lblCtx.fillText(`${target.targetId} ${target.role}`, x, y);
    }
  });
}

onMounted(draw);
watch(() => props.network, draw, { deep: true });
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
