<template>
  <div class="df-match-panel">
    <p class="panel-desc">
      上传测向 CSV（PrcFf / PDW）与外源定位 CSV（Lq 雷情），按下方门限进行批级关联。
      匹配完成后，地图上已锁定批的定位点将与对应测向批同色显示；未关联点为灰色三角。
    </p>

    <div class="file-row">
      <label class="file-pick">
        测向文件
        <input type="file" accept=".csv" @change="onBearingPick" />
        <span class="file-name">{{ bearingFile?.name || "未选择" }}</span>
      </label>
      <label class="file-pick">
        定位文件
        <input type="file" accept=".csv" @change="onLocatePick" />
        <span class="file-name">{{ locateFile?.name || "未选择" }}</span>
      </label>
    </div>

    <details class="match-params" open>
      <summary>匹配超参数</summary>
      <div class="param-grid">
        <label class="param-check">
          <span class="check-row">
            <input v-model="matchParams.ignoreTimeDimension" type="checkbox" />
            <span>忽略时间维度</span>
          </span>
          <span class="field-hint">勾选后单帧匹配不校验测向–定位时差，仅按方位/距离门限关联</span>
        </label>
        <label>
          测向–定位时间窗 (秒)
          <input
            v-model.number="matchParams.timeThresholdSec"
            type="number"
            step="0.5"
            min="0.1"
            :disabled="matchParams.ignoreTimeDimension"
            title="测向时刻与定位点允许的最大时差"
          />
          <span class="field-hint">
            {{ matchParams.ignoreTimeDimension ? "已忽略时差，本项不生效" : "单帧关联：定位须在测向时刻 ± 该值内" }}
          </span>
        </label>
        <label>
          批持续最短时间 (秒)
          <input
            v-model.number="matchParams.sustainDurationSec"
            type="number"
            step="1"
            min="1"
            title="同一测向批须持续指向同一目标的最短时间"
          />
          <span class="field-hint">批级锁定：窗内每条测向均须指向同一目标</span>
        </label>
        <label>
          方位门限 (°)
          <input
            v-model.number="matchParams.angleThresholdDeg"
            type="number"
            step="0.1"
            min="0.1"
            title="几何方位与测向方位允许的最大偏差"
          />
        </label>
        <label>
          距离门限 (km)
          <input
            v-model.number="matchParams.distanceThresholdKm"
            type="number"
            step="10"
            min="1"
            title="平台到定位点的最大斜距"
          />
        </label>
      </div>
      <button type="button" class="text-btn" @click="resetParams">恢复默认</button>
    </details>

    <div class="actions">
      <button
        type="button"
        class="primary"
        :disabled="!canRun || loading"
        @click="runMatch"
      >
        {{ loading ? "匹配中…" : "执行测向–定位匹配" }}
      </button>
      <span v-if="error" class="error">{{ error }}</span>
    </div>

    <section v-if="result" class="result-block">
      <p class="summary">
        测向 {{ result.measurementCount }} 条 · 定位航迹 {{ result.trajectoryDeviceCount }} 条 ·
        已匹配测向点 {{ result.matchedPointCount }} 条 ·
        锁定批号 {{ batchRows.length }} 个
      </p>
      <table v-if="batchRows.length" class="batch-table">
        <thead>
          <tr>
            <th>测向批号</th>
            <th>锁定目标 (mbmc)</th>
            <th>目标内码 (mbnm)</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in batchRows" :key="row.batchId">
            <td>{{ row.batchId }}</td>
            <td>{{ row.deviceId }}</td>
            <td>{{ row.targetId || "—" }}</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="empty">无批号通过持续性与几何门限</p>
    </section>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from "vue";
import { DEFAULT_DF_MATCH_PARAMS } from "../scene/dfMatchConfig.js";
import { fetchDirectionFindingMatch } from "../scene/sceneApi.js";

const emit = defineEmits(["match-result"]);

const props = defineProps({
  /** 从工作流上传区传入的 CSV，用于一键填充测向/定位文件 */
  uploadFiles: { type: Array, default: () => [] }
});

const matchParams = reactive({ ...DEFAULT_DF_MATCH_PARAMS });
const bearingFile = ref(null);
const locateFile = ref(null);
const loading = ref(false);
const error = ref("");
const result = ref(null);

let abortCtrl = null;

const canRun = computed(() => Boolean(bearingFile.value && locateFile.value));

const batchRows = computed(() => {
  const r = result.value;
  if (!r?.batchToDevice) return [];
  const targetMap = r.batchToTargetId || {};
  return Object.entries(r.batchToDevice).map(([batchId, deviceId]) => ({
    batchId,
    deviceId,
    targetId: targetMap[batchId] || ""
  }));
});

function resetParams() {
  Object.assign(matchParams, DEFAULT_DF_MATCH_PARAMS);
}

function onBearingPick(ev) {
  bearingFile.value = ev.target.files?.[0] || null;
  result.value = null;
  error.value = "";
}

function onLocatePick(ev) {
  locateFile.value = ev.target.files?.[0] || null;
  result.value = null;
  error.value = "";
}

function guessFiles(files) {
  if (!files?.length) return;
  let bearing = null;
  let locate = null;
  for (const f of files) {
    const n = (f.name || "").toLowerCase();
    if (/lq|雷情|locate|df_match|1139/.test(n) && !/prc|ff|pdw/.test(n)) {
      locate = locate || f;
    } else if (/prc|ff|pdw|测向|bearing/.test(n)) {
      bearing = bearing || f;
    }
  }
  if (!bearing && files.length === 1) bearing = files[0];
  if (!locate && files.length >= 2) {
    locate = files.find((f) => f !== bearing) || null;
  }
  if (bearing && !bearingFile.value) bearingFile.value = bearing;
  if (locate && !locateFile.value) locateFile.value = locate;
}

watch(
  () => props.uploadFiles,
  (files) => guessFiles(files),
  { immediate: true, deep: true }
);

async function runMatch() {
  if (!canRun.value) return;
  abortCtrl?.abort();
  abortCtrl = new AbortController();
  loading.value = true;
  error.value = "";
  result.value = null;
  try {
    result.value = await fetchDirectionFindingMatch(
      bearingFile.value,
      locateFile.value,
      matchParams,
      abortCtrl.signal
    );
    emit("match-result", result.value);
  } catch (e) {
    if (e?.name !== "AbortError") {
      error.value = e?.message || "匹配失败";
    }
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.df-match-panel {
  font-size: 13px;
}
.panel-desc {
  margin: 0 0 10px;
  color: #6b7280;
  font-size: 12px;
}
.file-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 12px;
}
.file-pick {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #4b5563;
  cursor: pointer;
}
.file-pick input[type="file"] {
  max-width: 220px;
  font-size: 11px;
}
.file-name {
  color: #111827;
  font-weight: 500;
}
.match-params {
  margin-bottom: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 8px 10px;
  background: #f9fafb;
}
.match-params summary {
  cursor: pointer;
  font-weight: 600;
  color: #374151;
  margin-bottom: 8px;
}
.param-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
  margin-bottom: 8px;
}
.param-grid label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #4b5563;
}
.param-grid input[type="number"] {
  padding: 6px 8px;
  border: 1px solid #d1d5db;
  border-radius: 6px;
}
.param-grid input[type="number"]:disabled {
  background: #f3f4f6;
  color: #9ca3af;
}
.param-check {
  grid-column: 1 / -1;
}
.param-check input[type="checkbox"] {
  width: auto;
  margin: 0;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.field-hint {
  font-size: 11px;
  color: #9ca3af;
  font-weight: normal;
}
.text-btn {
  background: none;
  border: none;
  color: #2563eb;
  cursor: pointer;
  font-size: 12px;
  padding: 0;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.primary {
  padding: 8px 16px;
  background: #1d4ed8;
  color: #fff;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.error {
  color: #dc2626;
  font-size: 12px;
}
.result-block {
  border-top: 1px solid #e5e7eb;
  padding-top: 12px;
}
.summary {
  margin: 0 0 10px;
  color: #374151;
}
.batch-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.batch-table th,
.batch-table td {
  border: 1px solid #e5e7eb;
  padding: 6px 8px;
  text-align: left;
}
.batch-table th {
  background: #f3f4f6;
  font-weight: 600;
}
.empty {
  color: #9ca3af;
  margin: 0;
}
</style>
