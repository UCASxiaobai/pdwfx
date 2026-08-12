<template>
  <div class="stream-page">
    <header class="head">
      <div>
        <h2>流式态势（独立模块）</h2>
        <p class="sub">
          TCP 收包 → 落盘切批 → 调用原分析服务；不经过场景筛选。
          <a href="#/">返回主流程</a>
        </p>
      </div>
      <button type="button" class="refresh" :disabled="loading" @click="reload">
        {{ loading ? "刷新中…" : "刷新" }}
      </button>
    </header>

    <p v-if="error" class="error">{{ error }}</p>

    <section class="card">
      <h3>运行状态</h3>
      <div v-if="status" class="grid">
        <div>TCP：{{ status.tcpRunning ? `监听 ${status.tcpPort}` : "未启动" }}</div>
        <div>收包：{{ status.packetsIn }} 包 / {{ formatBytes(status.bytesIn) }}</div>
        <div>解析记录：{{ status.parse?.records ?? 0 }}（丢弃非B108 {{ status.parse?.skippedOtherType ?? 0 }} · DOA失败 {{ status.parse?.skippedDoa ?? 0 }}）</div>
        <div>已写行：{{ status.writtenRows }} · 已封批：{{ status.sealedBatches }}</div>
        <div>分析队列：{{ status.queueSize }} / {{ status.maxQueueFiles }}{{ status.queuePaused ? "（暂停收包）" : "" }}</div>
        <div>正在分析：{{ status.analyzingFile || "—" }}</div>
        <div>落盘目录：{{ status.batchDir }}</div>
        <div>分析服务：{{ status.analyzeBaseUrl }}</div>
      </div>
      <p v-else class="hint">无法连接流式模块（默认 http://localhost:19080）。请先启动 stream 进程。</p>
    </section>

    <section class="card">
      <h3>最近批结果</h3>
      <table v-if="batches.length" class="tbl">
        <thead>
          <tr>
            <th>批 ID</th>
            <th>状态</th>
            <th>网络</th>
            <th>轨迹批</th>
            <th>analysisId</th>
            <th>完成时间</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="b in batches"
            :key="b.streamBatchId"
            :class="{ on: selected?.streamBatchId === b.streamBatchId }"
            @click="selected = b"
          >
            <td>{{ b.streamBatchId }}</td>
            <td>{{ b.status }}</td>
            <td>{{ b.networkCount }}</td>
            <td>{{ b.trackCount }}</td>
            <td>
              <a
                v-if="b.analysisId && b.networkCount"
                :href="`#/network?analysisId=${encodeURIComponent(b.analysisId)}&networkId=1`"
              >{{ b.analysisId.slice(0, 8) }}…</a>
              <span v-else>{{ b.analysisId || "—" }}</span>
            </td>
            <td>{{ formatTime(b.finishedAt) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="hint">暂无已分析批次</p>
    </section>

    <section v-if="selected" class="card">
      <h3>批标签 · {{ selected.streamBatchId }}</h3>
      <p v-if="selected.error" class="error">{{ selected.error }}</p>
      <table v-if="selected.labels?.length" class="tbl">
        <thead>
          <tr>
            <th>批号</th>
            <th>目标类型</th>
            <th>占空比</th>
            <th>流量%</th>
            <th>波道</th>
            <th>频点 MHz</th>
            <th>点数</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in selected.labels" :key="l.batchId">
            <td>{{ l.batchId }}</td>
            <td>{{ l.targetTypeLabel || l.targetType || "—" }}</td>
            <td>{{ pct(l.dutyCycle) }}</td>
            <td>{{ num(l.trafficSharePct) }}</td>
            <td>{{ l.channelLabel || l.channel || "—" }}</td>
            <td>{{ num(l.freqMhz, 3) }}</td>
            <td>{{ l.detectCount }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="hint">该批无轨迹标签</p>
    </section>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from "vue";

const STREAM_BASE = (typeof window !== "undefined" && window.__STREAM_API_BASE__) || "http://localhost:19080";

const loading = ref(false);
const error = ref("");
const status = ref(null);
const batches = ref([]);
const selected = ref(null);
let timer = null;

async function reload() {
  loading.value = true;
  error.value = "";
  try {
    const [sRes, bRes] = await Promise.all([
      fetch(`${STREAM_BASE}/api/stream/status`),
      fetch(`${STREAM_BASE}/api/stream/batches`)
    ]);
    if (!sRes.ok) throw new Error(`status HTTP ${sRes.status}`);
    if (!bRes.ok) throw new Error(`batches HTTP ${bRes.status}`);
    status.value = await sRes.json();
    batches.value = await bRes.json();
    if (selected.value) {
      selected.value =
        batches.value.find((x) => x.streamBatchId === selected.value.streamBatchId) || selected.value;
    }
  } catch (e) {
    status.value = null;
    error.value = e?.message || String(e);
  } finally {
    loading.value = false;
  }
}

function formatBytes(n) {
  const v = Number(n) || 0;
  if (v < 1024) return `${v} B`;
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`;
  return `${(v / 1024 / 1024).toFixed(2)} MB`;
}

function formatTime(iso) {
  if (!iso) return "—";
  try {
    return new Date(iso).toLocaleString("zh-CN", { hour12: false });
  } catch {
    return String(iso);
  }
}

function num(v, digits = 1) {
  const n = Number(v);
  return Number.isFinite(n) ? n.toFixed(digits) : "—";
}

function pct(v) {
  const n = Number(v);
  if (!Number.isFinite(n)) return "—";
  return `${(n <= 1 ? n * 100 : n).toFixed(1)}%`;
}

onMounted(() => {
  reload();
  timer = setInterval(reload, 5000);
});

onUnmounted(() => {
  if (timer) clearInterval(timer);
});
</script>

<style scoped>
.stream-page { font-family: Arial, sans-serif; padding: 16px; max-width: 1200px; }
.head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.sub { color: #6b7280; font-size: 13px; margin: 4px 0 0; }
.sub a { margin-left: 8px; }
.refresh { padding: 6px 12px; }
.card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px 14px; margin-top: 14px; }
.card h3 { margin: 0 0 10px; font-size: 15px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 8px 16px; font-size: 13px; }
.tbl { width: 100%; border-collapse: collapse; font-size: 13px; }
.tbl th, .tbl td { border-bottom: 1px solid #e5e7eb; padding: 6px 8px; text-align: left; }
.tbl tr { cursor: pointer; }
.tbl tr.on { background: #eff6ff; }
.hint { color: #6b7280; font-size: 13px; }
.error { color: #b91c1c; font-size: 13px; }
</style>
