<!-- vue2-done -->
<template>
  <div class="df-match-panel">
    <p class="panel-desc">
      上传测向 CSV（PrcFf / PDW）与外源定位 CSV（Lq 雷情），按下方门限进行批级关联。
      精细匹配要求短时差与连续持续指向；粗匹配加宽时差、按整批计票，适合钟差与稀疏定位点。
      匹配完成后，地图上已锁定批的定位点将与对应测向批同色显示；未关联点为灰色三角。
      若已完成场景筛选且提供输出目录，测向批按换频链编批，并对定位目标做一对一互斥。
    </p>

    <div class="file-row">
      <label class="file-pick">
        测向文件
        <input type="file" accept=".csv" @change="onBearingPick" />
        <span class="file-name">{{ bearingFile ? bearingFile.name : "未选择" }}</span>
      </label>
      <label class="file-pick">
        定位文件
        <input type="file" accept=".csv" @change="onLocatePick" />
        <span class="file-name">{{ locateFile ? locateFile.name : "未选择" }}</span>
      </label>
    </div>

    <details class="match-params" open>
      <summary>匹配超参数</summary>
      <div class="mode-row">
        <label class="mode-opt">
          <input v-model="matchParams.mode" type="radio" value="fine" @change="onModeChange" />
          精细匹配
        </label>
        <label class="mode-opt">
          <input v-model="matchParams.mode" type="radio" value="coarse" @change="onModeChange" />
          粗匹配
        </label>
      </div>
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
            :step="matchParams.mode === 'coarse' ? 10 : 0.5"
            min="0.1"
            max="600"
            :disabled="matchParams.ignoreTimeDimension"
            title="测向时刻与定位点允许的最大时差"
          />
          <span class="field-hint">{{ timeWindowHint }}</span>
        </label>
        <label v-if="matchParams.mode === 'fine'">
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
        <label v-else>
          指向次数
          <input
            v-model.number="matchParams.minHits"
            type="number"
            step="1"
            min="1"
            title="锁定所需指向同一目标的测向帧数"
          />
          <span class="field-hint">1 表示只要指向即匹配，不再要求连续持续</span>
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
      <el-button type="text" size="mini" @click="resetParams">恢复默认</el-button>
    </details>

    <div class="actions">
      <el-button type="primary" size="small" :disabled="!canRun || loading" @click="runMatch">
        {{ loading ? "匹配中…" : "执行测向–定位匹配" }}
      </el-button>
      <span v-if="error" class="error">{{ error }}</span>
    </div>

    <section v-if="result" class="result-block">
      <p class="summary">
        测向 {{ result.measurementCount }} 条 · 定位航迹 {{ result.trajectoryDeviceCount }} 条 ·
        已匹配测向点 {{ result.matchedPointCount }} 条 ·
        锁定批号 {{ batchRows.length }} 个
      </p>
      <el-table v-if="batchRows.length" :data="batchRows" size="mini" border stripe class="batch-table">
        <el-table-column prop="displayId" label="测向批号" min-width="120" />
        <el-table-column prop="deviceId" label="锁定目标 (mbmc)" min-width="120" />
        <el-table-column label="目标内码 (mbnm)" min-width="100">
          <template slot-scope="scope">{{ scope.row.targetId || "—" }}</template>
        </el-table-column>
      </el-table>
      <p v-else class="empty">无批号通过匹配门限</p>
    </section>
  </div>
</template>

<script>
import { DEFAULT_DF_MATCH_PARAMS, paramsForDfMatchMode } from "@/scene/dfMatchConfig.js";
import { fetchDirectionFindingMatch } from "@/api/pdwfx";

export default {
  name: "SceneDfMatchPanel",
  props: {
    uploadFiles: { type: Array, default: function () { return []; } },
    outputDir: { type: String, default: "" }
  },
  data: function () {
    return {
      matchParams: Object.assign({}, DEFAULT_DF_MATCH_PARAMS),
      bearingFile: null,
      locateFile: null,
      loading: false,
      error: "",
      result: null
    };
  },
  computed: {
    canRun: function () {
      return Boolean(this.bearingFile && this.locateFile);
    },
    isCoarse: function () {
      return this.matchParams.mode === "coarse";
    },
    timeWindowHint: function () {
      if (this.matchParams.ignoreTimeDimension) {
        return "已忽略时差，本项不生效";
      }
      if (this.isCoarse) {
        return "单帧关联：定位须在测向时刻 ± 该值内；可设至 600 秒（10 分钟）";
      }
      return "单帧关联：定位须在测向时刻 ± 该值内";
    },
    batchRows: function () {
      var r = this.result;
      if (!r || !r.batchToDevice) return [];
      var targetMap = r.batchToTargetId || {};
      var self = this;
      return Object.entries(r.batchToDevice).map(function (entry) {
        var batchId = entry[0];
        var deviceId = entry[1];
        var label = (r.batchLabels || {})[batchId];
        return {
          batchId: batchId,
          displayId: label ? label + " / " + batchId : batchId,
          deviceId: deviceId,
          targetId: targetMap[batchId] || ""
        };
      });
    }
  },
  watch: {
    uploadFiles: {
      immediate: true,
      deep: true,
      handler: function (files) {
        this.guessFiles(files);
      }
    }
  },
  methods: {
    resetParams: function () {
      Object.assign(this.matchParams, paramsForDfMatchMode(this.matchParams.mode || "fine"));
    },
    onModeChange: function () {
      Object.assign(this.matchParams, paramsForDfMatchMode(this.matchParams.mode));
    },
    onBearingPick: function (ev) {
      this.bearingFile = (ev.target.files && ev.target.files[0]) || null;
      this.result = null;
      this.error = "";
    },
    onLocatePick: function (ev) {
      this.locateFile = (ev.target.files && ev.target.files[0]) || null;
      this.result = null;
      this.error = "";
    },
    guessFiles: function (files) {
      if (!files || !files.length) return;
      var bearing = null;
      var locate = null;
      for (var i = 0; i < files.length; i++) {
        var f = files[i];
        var n = (f.name || "").toLowerCase();
        if (/lq|雷情|locate|df_match|1139/.test(n) && !/prc|ff|pdw/.test(n)) {
          locate = locate || f;
        } else if (/prc|ff|pdw|测向|bearing/.test(n)) {
          bearing = bearing || f;
        }
      }
      if (!bearing && files.length === 1 && !locate) bearing = files[0];
      if (!locate && files.length >= 2) {
        locate = files.find(function (x) { return x !== bearing; }) || null;
      }
      if (bearing) this.bearingFile = bearing;
      if (locate) this.locateFile = locate;
    },
    runMatch: function () {
      var self = this;
      if (!this.canRun) return Promise.resolve();
      if (this.abortCtrl) this.abortCtrl.abort();
      this.abortCtrl = new AbortController();
      this.loading = true;
      this.error = "";
      this.result = null;
      return fetchDirectionFindingMatch(
        this.bearingFile,
        this.locateFile,
        Object.assign({}, this.matchParams, { outputDir: this.outputDir || undefined }),
        this.abortCtrl.signal
      ).then(function (data) {
        self.result = data;
        self.$emit("match-result", data);
      }).catch(function (e) {
        if (!e || e.name !== "AbortError") {
          self.error = (e && e.message) || "匹配失败";
        }
      }).finally(function () {
        self.loading = false;
      });
    },
    runIfReady: function () {
      var self = this;
      if (!this.canRun || this.loading) return Promise.resolve(false);
      return this.runMatch().then(function () { return true; });
    }
  }
};
</script>

<style scoped>
.df-match-panel {
  font-size: 13px;
}
.panel-desc {
  margin: 0 0 10px;
  color: var(--theme-text-muted);
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
  color: var(--theme-text-secondary);
  cursor: pointer;
}
.file-pick input[type="file"] {
  max-width: 220px;
  font-size: 11px;
}
.file-name {
  color: var(--theme-text-primary);
  font-weight: 500;
}
.match-params {
  margin-bottom: 12px;
  border: 1px solid var(--theme-border);
  border-radius: 6px;
  padding: 8px 10px;
  background: var(--theme-bg-deep);
}
.match-params summary {
  cursor: pointer;
  font-weight: 600;
  color: var(--theme-text-secondary);
  margin-bottom: 8px;
}
.mode-row {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 10px;
}
.mode-opt {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--theme-text-secondary);
  cursor: pointer;
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
  color: var(--theme-text-secondary);
}
.param-grid input[type="number"] {
  padding: 6px 8px;
  border: 1px solid var(--theme-border-input);
  border-radius: 6px;
  background: var(--theme-bg-input);
  color: var(--theme-text-primary);
}
.param-grid input[type="number"]:disabled {
  background: var(--theme-bg-disabled);
  color: var(--theme-text-disabled);
}
.param-check {
  grid-column: 1 / -1;
}
.check-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.field-hint {
  font-size: 11px;
  color: var(--theme-text-muted);
  font-weight: normal;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.error {
  color: #ee6666;
  font-size: 12px;
}
.result-block {
  border-top: 1px solid var(--theme-border);
  padding-top: 12px;
}
.summary {
  margin: 0 0 10px;
  color: var(--theme-text-secondary);
}
.batch-table {
  width: 100%;
}
.empty {
  color: var(--theme-text-muted);
  margin: 0;
}
</style>
