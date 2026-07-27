# 人工调试参考手册

> 面向开发/分析人员现场排错。算法原理见 [algorithm-design.md](./algorithm-design.md)，代码索引见该文档 §11。

---

## 1. 环境与启动

### 1.1 端口约定

| 服务 | 端口 | 配置位置 |
|------|------|----------|
| 后端 | **18080** | `backend/src/main/resources/application.yml` |
| 前端 | **5173** | `frontend/vite.config.js`（代理 `/api` → 18080） |

### 1.2 启动命令（Windows PowerShell）

```powershell
# 后端（大文件建议 4G 堆）
$env:MAVEN_OPTS="-Xms512m -Xmx4g"
cd backend
mvn spring-boot:run

# 前端（另开终端）
cd frontend
npm run dev
```

浏览器访问：**http://localhost:5173/**

### 1.3 启动后自检

```powershell
# 后端是否在监听
netstat -ano | findstr ":18080"

# 前端是否在监听
netstat -ano | findstr ":5173"
```

- 后端日志出现 `Started SignalAnalysisApplication` 且 `Tomcat started on port(s): 18080` 为正常。
- 若 `Port 18080 was already in use`：旧进程未退出，先杀进程再启（见 §2.1）。

### 1.4 重启（改 Java 代码后必须重启后端）

```powershell
# 停 18080 / 5173 / 5174
foreach ($p in 18080,5173,5174) {
  Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
}
Start-Sleep -Seconds 2
# 再分别启动后端、前端
```

**注意：** 仅刷新浏览器不会加载新的 Java 逻辑；改后端后必须重启并 **重新导入分析**。

---

## 2. 常见故障速查

| 现象 | 可能原因 | 处理 |
|------|----------|------|
| 点击「导入并分析」无请求 | 前端未代理 / 后端未起 | 查 5173、18080；F12 看 Network |
| `分析失败: Format specifier '%.2f'` | `String.format` 占位符与参数个数不一致 | 查 `MasterSlaveAnalysisService` 等结论生成处 |
| 端口占用启动失败 | 重复启动 | `netstat -ano \| findstr ":18080"` → `Stop-Process -Id <pid> -Force` |
| 前端 5174 而非 5173 | 5173 已被占用 | 用 5174 或释放 5173 |
| 改了算法结果不变 | 未重启后端 / 用了旧 session | 重启 + 重新上传分析 |
| 图表不随点击网络更新 | 详情 API 未调 / 组件未重建 | 看 Network 是否有 `GET .../networks/{id}`；`App.vue` 的 `:key="networkId"` |
| `counts is not iterable` | OpenLayers `Map` 覆盖 JS 原生 `Map` | 已改为 `OlMap` 别名，确认 `AnalystWorkbench.vue` |
| 大 CSV OOM | 堆太小 / 未流式 | `MAVEN_OPTS=-Xmx4g`；单文件勿超配置上限 |
| 高亮切换失效 | 选中线 z 轴过高挡住其他线 | 已统一 `z: idx`；硬刷新 Ctrl+Shift+R |
| 幅度一条曲线变两条 | 旧逻辑按幅度值排序误拆 | 现仅「时间重叠+双轨稳定」才拆，见 §4.3 |
| 全部判固定站 | 定位字段空，走了旧方位退化 | 现规则：无法形成椭圆 → **AIR** |
| 看不到误差椭圆结论 | `DWJD/DWD` 有效点 < 6 | 检查 CSV 定位列（§3.2） |

---

## 3. 接口与数据流（调试时对照 Network 面板）

### 3.1 调用顺序

```
1. POST /api/signals/analyze
   参数: file, freqTolerance（默认 UI 填 0.01~0.1）
   返回: { analysisId, networkCount, networks[]摘要 }

2. GET /api/signals/analysis/{analysisId}/networks/{networkId}
   返回: NetworkView 全量（targets、图表序列、analysisSummary）
```

前端：`App.vue` 负责步骤 1 和列表；`AnalystWorkbench.vue` 用步骤 2 的数据绘图。

### 3.2 CSV 字段映射（PrcFf 表格格式）

| 业务含义 | 表格列名 | 模型字段 |
|----------|----------|----------|
| 频率 | `PL` | `freq` |
| 方位 | `XHFW` | `azimuth` |
| 幅度 | `XHFD` | `signalLevel` |
| 信噪比 | `KXD` | `snr` |
| 时间 | `ZCSJ` | `detectTime` / `detectTimesss`（表格格式以此为准） |
| 驻留 | `nSignalTime` | `signalDwellMs`（单位 10µs，ms = 值 × 0.01） |
| 目标经度 | `DWJD` | `targetLon` |
| 目标纬度 | `DWD` / `DWWD` | `targetLat` |

**固定/移动判定依赖 `targetLon/targetLat`。** 若这两列为空，结论会写「无法形成误差椭圆 → 飞机」。

### 3.3 会话缓存

- `AnalysisSessionService` 内存最多保留 **20** 个 `analysisId`。
- 重启后端后旧 `analysisId` 失效，需重新分析。

---

## 4. 分析结果人工核对

### 4.1 看结论字段（最直观）

每个目标在 `analysisSummary.targetConclusions` 和 `TargetView` 中：

| 字段 | 含义 |
|------|------|
| `convergenceDetail` | 定位点数量、Mahalanobis、椭圆收敛等 |
| `targetTypeReason` | 为何判 GROUND / AIR / AWACS |
| `roleReason` | 为何判 MASTER / SLAVE（含发射时间占比） |

网络级 `networkConclusions` 前两行会写明当前使用的总判据。

### 4.2 目标数量偏多 / 偏少

| 症状 | 查什么 | 调哪里 |
|------|--------|--------|
| 同频一个网拆成太多目标 | 方位平行轨拆分 | `SignalAnalysisService`：`splitParallelTracks` 的 `minSeparation`（默认 5°） |
| 幅度误拆成两个目标 | 幅度二次拆分 | `splitParallelAmplitudeTracks`；阈值见 §5 |
| 网络太多 | 频率容差太小 | 前端 `freqTolerance` 调大（如 0.1） |
| 异频未合并 | 时间/方位不满足 | `mergeMultiFreqNetworks` / `canMergeAsMultiFreq` |

### 4.3 幅度「一条曲线变两条」

**原因（历史 bug）：** 按幅度值排序聚类，时变幅度被切成高档/低档两簇。

**现行逻辑：** `splitParallelAmplitudeTracks()` 仅当同时满足才拆分：

1. 整体极差 ≥ **18 dB**
2. 样本 ≥ **120**
3. 拟合时间-幅度趋势后，残差呈两档且均值差 ≥ **6 dB**
4. 两档 **时间重叠比例 ≥ 25%**（并行双轨，非先后起伏）
5. 每档幅度标准差 ≤ **10 dB**（各自稳定）

仍误拆 → 提高 `MIN_SIGNAL_LEVEL_SPLIT_RANGE` 或 `MIN_AMP_TRACK_TIME_OVERLAP`。

### 4.4 固定站 / 飞机判错

| 结论文案 | 含义 |
|----------|------|
| 无法形成误差椭圆 → 飞机 | 定位点 < 6，**按业务规则直接 AIR** |
| 固定目标特征 / 椭圆收敛 | Mahalanobis 内点 + 收敛 → GROUND |
| 移动目标特征 / 超出椭圆 | → AIR |
| AWACS | AIR + MASTER + 强信号 + 方位缓漂 |

**目视测向线汇聚 ≠ 椭圆收敛**：地图射线 = 载机经纬 + `AZIMUTH`；椭圆 = `DWJD/DWD` 随时间前后半段离散度。方位很稳但定位点仍散 → 可显示「椭圆不收敛」且 `convergenceDetail` 会注明测向 σ 小。若前后段定位点本就很紧，现已按「已收敛」处理（不必再缩小 25%）。

代码：`MotionClassificationService.assess()` → `SignalAnalysisService.applyTargetType()`。

### 4.5 主从判错

主从由 **流量占比(55%) + 占空比(40%)** 决定：

- **流量占比** = 各目标活跃发射量 / 同网合计（优先 Σ `nSignalTime` 驻留 ms，否则节奏占空比×观测窗）
- **占空比** = `targets[].avgDutyCycle`（节奏分析，与平台类型同源）
- 先发/响应/幅度仅写入 `roleReason` 供核对，**不参与**主从排序

看 `roleReason` 中「流量占比」「占空比」是否与图上发射持续时间一致。

代码：`MasterSlaveAnalysisService.assignRoles()`。

---

## 5. 关键参数一览（改阈值时对照）

文件：`backend/.../SignalAnalysisService.java`（除非另注）

| 参数 | 默认值 | 作用 |
|------|--------|------|
| `freqTolerance` | UI 传入，常用 0.01~0.1 | 同频编批 (MHz) |
| 方位轨迹容差 | 5° | `clusterByAzimuthTrajectory` |
| 平行方位最小分离 | 5° | `splitParallelTracks` |
| `MIN_SIGNAL_LEVEL_SPLIT_RANGE` | 18 dB | 幅度拆分最低极差 |
| `SIGNAL_LEVEL_GAP` | 6 dB | 两档幅度均值差 |
| `MIN_SIGNAL_SAMPLES_FOR_SPLIT` | 120 | 幅度拆分最少点数 |
| `MIN_AMP_TRACK_TIME_OVERLAP` | 0.25 | 两幅度档时间重叠下限 |
| `MAX_LEVEL_STD_FOR_STABLE_TRACK` | 10 dB | 单档内稳定上限 |
| `MAX_ANALYSIS_POINTS` | 12000 | 分析采样上限 |
| `MAX_CHART_POINTS` | 800 | 图表点数上限 |

文件：`MotionClassificationService.java`

| 参数 | 默认值 | 作用 |
|------|--------|------|
| `MIN_COORDS` | 6 | 形成椭圆最少定位点 |
| `CHI2_95_2D` | 5.991 | Mahalanobis 门限 |
| `CONVERGENCE_RATIO` | 0.75 | 后半/前半离散度比 |

文件：`MasterSlaveAnalysisService.java`

| 参数 | 默认值 | 作用 |
|------|--------|------|
| `BURST_GAP_MS` | 45000 | 发射簇间隔 |
| `RESPONSE_WINDOW_MS` | 90000 | 从站响应窗口 |

---

## 6. 前端调试要点

| 模块 | 文件 | 说明 |
|------|------|------|
| 上传与列表 | `frontend/src/App.vue` | `analyze()`、`selectNetwork()` |
| 图表与高亮 | `frontend/src/components/AnalystWorkbench.vue` | `drawCharts()`、`selectTarget()` |
| API 代理 | `frontend/vite.config.js` | `/api` → 18080 |

**图表：**

- 折线图数据：`targets[].azimuthSeries` / `signalSeries`（按目标分线）
- 散点图数据：`rawAzimuthSeries` / `rawSignalSeries`（**全网未分目标**，多条轨迹时会呈多条带，属正常）
- 同目标连线：`connectNulls: true`（不因短暂空档断线）
- 切换网络：`:key="selected.networkId"` 强制重建图表

**高亮：** 点击线 / 图例 / 统计表行 → `selectedTargetId` → 方位图与幅度图同步加粗。

---

## 7. 后端日志

`SignalAnalysisController` 典型输出：

```
开始分析文件: xxx.csv, 频率容差: 0.01
导入完成, 共 N 条, 耗时 X ms
分析完成, 网络数 M, session=..., 总耗时 Y ms
加载网络详情: session=..., networkId=K
```

- 导入条数异常 → 查 `ExcelImportService`、表头是否识别（`ZBXH`+`XHFW` 为表格格式）
- 分析极慢 → 数据量接近 30 万，看 `MAX_ANALYSIS_POINTS` 采样是否生效

---

## 8. 推荐调试流程

```
1. 确认 18080 / 5173 正常
2. 小样本 CSV 先跑通（sample-table-signals.csv）
3. F12 → Network：analyze 200、networks/{id} 200
4. 看 analysisSummary.targetConclusions 是否与图表一致
5. 结果不对 → 按 §4 对症状查参数 / 数据列
6. 改 Java → 编译 mvn compile → 重启后端 → 必须重新 analyze
7. 改 Vue → 保存后热更新，必要时 Ctrl+Shift+R
```

---

## 9. 代码入口速查

| 想改什么 | 打开哪个文件 / 方法 |
|----------|---------------------|
| 同频/异频编批 | `SignalAnalysisService.splitByFreq` / `mergeMultiFreqNetworks` |
| 方位拆目标 | `clusterByAzimuthTrajectory` / `splitParallelTracks*` |
| 幅度拆目标 | `clusterBySignalLevel` / `splitParallelAmplitudeTracks` |
| 固定/飞机 | `MotionClassificationService.assess` |
| 主从 | `MasterSlaveAnalysisService.assignRoles` |
| 预警机 | `SignalAnalysisService.refineAwacsType` |
| 结论文案 | `buildSummary` / `buildTargetConclusion` |
| CSV 列映射 | `ExcelImportService.mapTableRecord` |
| REST | `SignalAnalysisController` |

---

## 10. VS Code / Cursor 调试

`.vscode/launch.json` 可配置 Spring Boot 启动。若与命令行 `mvn spring-boot:run` 同时运行会 **端口冲突**，只保留一种方式。

---

*文档随项目迭代更新；算法变更后请同步检查 §4、§5 是否与代码一致。*
