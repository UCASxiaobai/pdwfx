# 前置 / 核心 / 后置 接口文档

> **外部模块对接**请优先阅读 **[external-integration.md](./external-integration.md)**（仅输入/输出与 REST/Java 调用）。
>
> 本文定义信号分析系统的三段式接口边界：**前置**负责从原始数据中筛选高质量可分析样本；**核心**以 Java 类型与 REST API 完成编批与识别；**后置**消费分析结果做图表与报告展示。

```
[外部数据源] ──前置──▶ List<DetectSignal> ──核心(Java+REST)──▶ AnalyzeSessionResponse / NetworkView ──后置──▶ 图表 / 报告
```

---

## 0. 对外集成：函数一览 + 多频输入 + 周期/burst 含义

### 0.1 推荐入口：`SignalAnalysisFacade`

| 函数 | 作用 | 返回 |
|------|------|------|
| `importSignalsFromFile(file)` | **输入**：CSV/Excel，每行可有不同频率 | `List<DetectSignal>` |
| `importSignalsFromJson(request)` | **输入**：JSON `signals[]`，多频可混在同一请求 | `List<DetectSignal>` |
| `analyzeFromFile(file, tol)` | 文件 → 分网 + session | `AnalyzeSessionResponse` |
| `analyzeFromJson(request)` | JSON → 分网 + session | `AnalyzeSessionResponse` |
| `analyzeSignals(signals, tol)` | 已有 Java 列表时直接分析 | `AnalyzeSessionResponse` |
| `analyzeFull(signals, tol)` | 一次返回全部网络详情（大文件慎用） | `NetworkAnalysisResponse` |
| `getNetworkDetail(sessionId, networkId)` | **输出**：单网目标、周期、burst、图表序列 | `NetworkView` |
| `getNetworkResult(sessionId, networkId)` | **输出**：单网业务结果（含 **networkType**、通信链，无图表） | `NetworkResultResponse` |
| `exportDetections(sessionId, includeUnassigned)` | **输出**：逐条编批（方位/时间/频率/类型/波道） | `DetectionBatchResponse` |
| `preloadNetworks(sessionId, ids)` | 预构建指定网，回写列表摘要 | `AnalyzeSessionResponse` |
| `preloadAllNetworks(sessionId)` | 预构建全部网（**网络类型/波道**批量研判） | `AnalyzeSessionResponse` |

类路径：`com.pdwfx.signal.api.SignalAnalysisFacade`（Spring `@Service`，可注入调用）。  
**网络类型 / 通信链字段**详见 [external-integration.md §4.0](./external-integration.md#40-网络类型与通信链波道)。

### 0.2 多频数据怎么传？

**不要求按频率分文件。** 一批 `DetectSignal` / JSON `signals[]` 里，每条自带 `freq`（MHz）即可：

1. **分网**：相邻频率差 ≤ `freqTolerance`（默认 0.1 MHz）的归为一个网络。
2. **异频合并**：若两个频簇时间重叠、方位接近、调制一致，会合并为 `commMode=MULTI_FREQ` 的同一网络。
3. **跳频**：频点频繁跳变 → `commMode=HOPPING`。
4. **同频**：网内只有一个频簇 → `commMode=SAME_FREQ`。

REST 两种方式等价：

- `POST /api/signals/analyze` — multipart 文件（如 PrcFf 多频 CSV）
- `POST /api/signals/analyze/json` — 见 §4.3

### 0.3 周期、burst 是什么意思？（通俗版）

把某**目标**在一段时间内的 PDW 按 **zcsj（侦测时间）** 排成时间轴：

```
时间轴:  |--脉冲--脉冲--脉冲--|····空闲····|--脉冲--脉冲--|
         |<------ burst A ----->|            |<--- burst B -->|
```

| 概念 | 含义 | 怎么算 |
|------|------|--------|
| **脉冲 / PRI** | 同一次“发信”里，相邻两条 PDW 的到达间隔 | 所有 TOA 间隔的**中位数** → `estimatedPriMs` |
| **burst（突发块）** | 连续发信的一段；中间空太久就断开 | 相邻 PDW 间隔 > max(300ms, 3×PRI) 则切一刀，中间算一个 burst |
| **主周期 `periodMs`** | 通信节奏有多慢（发一阵、停一阵） | **≥2 个 burst**：各 burst **起点**之间间隔的中位数；**只有 1 个 burst**：用 PRI |
| **驻留** | 单次发信持续多久 | 有 `nSignalTime`：每条 PDW 驻留(10µs→ms)的均值；否则 burst 内时间跨度 |
| **占空比** | 观测窗里真正在发射的时间占比 | 所有 burst 活跃时长之和 ÷ (末 zcsj − 首 zcsj) × 100% |

**记忆口诀**：PRI 看“脉冲有多密”，burst 看“哪几段在连发”，主周期看“发一阵的间隔”，占空比看“总共开了多久”。

输出字段：`targets[].periodMs`、`burstCount`、`burstDurationMeanMs`、`avgDutyCycle`、`bursts[]`（每段起止与驻留）。

---

## 1. 前置接口（数据接入与质量筛选）

### 1.1 职责

- 从 CSV / Excel / 数据库导出文件中读取原始侦获记录
- 映射为统一 Java 模型 `DetectSignal`
- 过滤低质量、不可分析的数据行

### 1.2 输入

| 来源 | 格式 | 说明 |
|------|------|------|
| 文件上传 | `.csv` / `.xlsx` | 经 `POST /api/signals/analyze` 传入 |
| 程序集成 | `List<DetectSignal>` | `SignalAnalysisFacade.analyzeSignals()` 或 JSON `POST .../analyze/json` |
| JSON 批量 | `AnalyzeSignalsRequest` | 多频混合；见 §4.3 |

### 1.3 字段映射（`ExcelImportService`）

**标准表头**

| 原始列 | Java 字段 | 必填 | 说明 |
|--------|-----------|------|------|
| `FREQ` / `PL` | `freq` | 是 | 频率 (MHz)，编批主键 |
| `AZIMUTH` / `XHFW` | `azimuth` | 是 | 方位角 (°)，轨迹聚类主键 |
| `SIGNAL_LEVEL` / `XHFD` | `signalLevel` | 是 | 幅度 (dB) |
| `DETECT_TIMESSS` / `ZCSJ` | `detectTimesss` | 是 | 侦获时间 (ms)；**表格格式以 ZCSJ 为准** |
| `nSignalTime` | `signalDwellMs` | 表格 | 驻留时间，单位 **10µs**，导入为 ms：`nSignalTime × 0.01` |
| `SNR` | `snr` | 否 | 信噪比，主从辅证 |
| `TARGET_LON` / `DWJD` | `targetLon` | 否 | 交叉定位经度，固定/移动判定 |
| `TARGET_LAT` / `DWD` | `targetLat` | 否 | 交叉定位纬度 |
| `LONGITUDE` / `LATITUDE` | 平台位置 | 否 | 载机坐标 |
| `MODULATE_STYLE` | `modulateStyle` | 否 | 异频合并判据 |

**质量筛选建议（前置扩展点）**

| 规则 | 建议阈值 | 说明 |
|------|----------|------|
| 频率有效 | `freq > 0` | 丢弃空频点 |
| 方位有效 | `0 ≤ azimuth ≤ 360` | 丢弃异常方位 |
| 时间单调 | 同文件内可排序 | 缺失时间行丢弃 |
| 可选 SNR 门限 | `snr ≥ 3` | 低 SNR 行可标记或剔除 |
| 可选定位覆盖 | `targetLon/Lat` 非空 | 仅做运动分析时需要 |

实现入口：`ExcelImportService.parse(MultipartFile)` → `List<DetectSignal>`

### 1.4 前置输出类型

```java
List<DetectSignal> signals = excelImportService.parse(file);
// 每条 DetectSignal 即一条可分析侦获记录
```

---

## 2. 核心接口（Java 类 + REST）

中间层**统一使用 Java 类型**传递，避免在前端或外部系统中重复实现算法。

### 2.1 核心服务（对外优先用 Facade）

| 类 | 方法 | 输入 | 输出 |
|----|------|------|------|
| **`SignalAnalysisFacade`** | 见 §0.1 | 文件 / JSON / `List<DetectSignal>` | `AnalyzeSessionResponse` / `NetworkView` |
| `SignalAnalysisService` | `partitionNetworks` / `buildNetworkView` | 底层编批 | 供 Facade 内部使用 |
| `ExcelImportService` | `parse(file)` | 文件 | `List<DetectSignal>` |
| `MotionClassificationService` | `assess(signals)` | 单目标信号列表 | 固定/移动判定 |
| `MasterSlaveAnalysisService` | `assignRoles(contexts)` | 目标上下文列表 | 主从角色 |

### 2.2 REST API

#### POST `/api/signals/analyze`

上传文件并执行全量分析。

**请求**

```
Content-Type: multipart/form-data
- file:  CSV / Excel 文件
- freqTolerance: double，默认 0.1（MHz）
- preloadNetworkIds: string，可选，如 "473,230"
- preloadAll: boolean，可选，true 时分析后预构建全部网络（含 networkType / commLink*）
```

**响应：`AnalyzeSessionResponse`**（`networks[]` 为 `NetworkSummary`）

```json
{
  "analysisId": "uuid",
  "networkCount": 284,
  "networks": [
    {
      "networkId": 473,
      "freq": 306.925,
      "networkType": "地空数传指挥",
      "commLinkChannel": "D04",
      "commLinkChannelLabel": "D04 地空数传指挥",
      "commLinkReason": "D04：仅预警机+地面站轮流；…",
      "commMode": "SAME_FREQ",
      "signalCount": 3294,
      "targetCount": 4,
      "groundTargetCount": 1,
      "awacsTargetCount": 3,
      "airTargetCount": 0
    }
  ]
}
```

#### POST `/api/signals/analysis/{analysisId}/preload`

预构建指定网络：`?networkIds=473,230` → 回写 `NetworkSummary` 中的 **networkType**、波道与目标计数。

#### POST `/api/signals/analysis/{analysisId}/preload-all`

预构建 session 内全部网络（列表筛选/统计网络类型前需调用）。

#### GET `/api/signals/analysis/{analysisId}/networks/{networkId}/result`

单网业务结果 `NetworkResultResponse`（含 `networkType`、`commLinkEvidence[]`、`targets[]`、`analysisSummary`）。

#### POST `/api/signals/analyze/json`

程序集成：**多频**侦获一次提交（无需按频分文件）。

**请求体 `AnalyzeSignalsRequest`**

```json
{
  "freqTolerance": 0.1,
  "signals": [
    {
      "detectTime": "2025-07-03T11:25:06.234",
      "freq": 269.65,
      "azimuth": 99.9,
      "signalLevel": 63,
      "nSignalTime10us": 1461
    },
    {
      "detectTime": "2025-07-03T11:25:06.062",
      "freq": 370.0,
      "azimuth": 104.8,
      "signalLevel": 66,
      "nSignalTime10us": 13125
    }
  ]
}
```

必填：`detectTime` 或 `detectTimesss`、`freq`、`azimuth`、`signalLevel`。  
驻留：`signalDwellMs` 或 `nSignalTime10us`（10µs/单位）。

**响应**：同 `POST /analyze` → `AnalyzeSessionResponse`。

#### GET `/api/signals/analysis/{analysisId}/networks/{networkId}`

按 session 懒加载单网详情（含图表序列与结论）。

**响应：`NetworkView`** — 见 §2.3

### 2.3 核心 Java 模型

#### `DetectSignal` — 单条侦获记录（前置输出 / 核心输入）

| 字段 | 类型 | 说明 |
|------|------|------|
| `detectTimesss` | `long` | 时间戳 ms |
| `freq` | `double` | MHz |
| `azimuth` | `double` | ° |
| `signalLevel` | `double` | dB |
| `snr` | `double` | 信噪比 |
| `targetLon` / `targetLat` | `Double` | 交叉定位 |
| `longitude` / `latitude` | `Double` | 载机位置 |

#### `NetworkAnalysisResponse` — 全量分析结果

| 字段 | 类型 | 说明 |
|------|------|------|
| `networkCount` | `int` | 通信网络数 |
| `networks` | `List<NetworkView>` | 每网完整视图 |

#### `NetworkSummary` — 网络列表摘要（`AnalyzeSessionResponse.networks[]`）

| 字段 | 类型 | 说明 |
|------|------|------|
| `networkId` / `freq` / `signalCount` | — | 标识与规模 |
| **`networkType`** | `String` | **网络类型**（通信链研判文案，见 external-integration §4.0） |
| **`commLinkChannel`** | `String` | `D01`～`D06` / `UNKNOWN` |
| **`commLinkChannelLabel`** | `String` | 波道中文名 |
| **`commLinkReason`** | `String` | 研判依据 |
| **`groundTargetCount`** / **`awacsTargetCount`** / **`airTargetCount`** | `int` | 目标类型计数（预分析后） |
| `commMode` / `networkConfidence` | — | 通信模式、置信度 |
| `targets` / `analysisSummary` | — | 预加载或详情后可选填充 |

#### `NetworkView` — 单通信网络（后置主消费对象）

| 字段 | 类型 | 后置用途 |
|------|------|----------|
| `networkId` | `int` | 网络标识 |
| **`networkType`** | `String` | **网络类型**（地空数传指挥、预警机态势广播、异频数传指挥引导网、不明 等） |
| **`commLinkChannel`** | `String` | 波道编码 `D01`～`D06` / `UNKNOWN` |
| **`commLinkChannelLabel`** | `String` | 波道中文标签 |
| **`commLinkReason`** | `String` | 通信链研判说明 |
| **`commLinkEvidence`** | `List<String>` | 研判过程证据（调试） |
| `networkConfidence` | `double` | 网络识别置信度 0~1 |
| `lowConfidence` | `boolean` | 网络或目标存在低置信度结果 |
| `freq` | `double` | 中心频率 |
| `freqBand` | `String` | 225-400MHz / L波段 / S波段 / 未知 |
| `centerFreq` | `double` | 中心频率 |
| `freqMin` / `freqMax` / `freqRange` | `double` / `String` | 频率范围 |
| `freqStability` | `double` | 频率稳定性 0~1 |
| `commMode` | `String` | `SAME_FREQ` / `MULTI_FREQ` / `HOPPING` |
| `stationType` | `String` | `GROUND` / `AIR` / `MIXED` |
| `signalCount` | `int` | 原始信号数 |
| `targetCount` / `activeTargetCount` | `int` | 目标数 / 活跃目标数；**预分析或 GET 详情后**与编批一致 |
| `mainStationCount` / `subStationCount` | `int` | 主站 / 从站数量 |
| `networkPeriod` | `Double` | 网络主周期（ms） |
| `networkDutyCycle` | `double` | 网络平均占空比（%） |
| `networkLoadLevel` | `String` | 低 / 中 / 高 / 拥塞 |
| `updatedAt` / `updateMode` | `long` / `String` | 更新时间 / 输出模式（当前为 FULL，后续可扩展增量） |
| `rawAzimuthSeries` | `List<SeriesPoint>` | 全网原始方位散点 |
| `rawSignalSeries` | `List<SeriesPoint>` | 全网原始幅度散点 |
| `targets` | `List<TargetView>` | 细分目标列表 |
| `analysisSummary` | `AnalysisSummary` | 文字结论 |

#### `TargetView` — 单目标

| 字段 | 类型 | 后置用途 |
|------|------|----------|
| `targetId` | `String` | 如 `T1` |
| `targetType` | `String` | `GROUND` / `AIR` / `AWACS` |
| `targetConfidence` / `confidence` | `double` | 目标类型识别置信度 0~1 |
| `lowConfidence` | `boolean` | 低置信度目标标识 |
| `evidenceSignalCount` | `int` | 支撑该目标结论的原始 PDW 数 |
| `role` | `String` | `MASTER` / `SLAVE` / `UNKNOWN` |
| `convergence` | `String` | 定位收敛状态 |
| `azimuthSeries` | `List<SeriesPoint>` | 时间-方位折线 |
| `signalSeries` | `List<SeriesPoint>` | 时间-幅度折线 |
| `freqSeries` | `List<SeriesPoint>` | 时间-频率折线 |
| `rawAzimuthSeries` | `List<SeriesPoint>` | 目标级原始方位散点 |
| `rawSignalSeries` | `List<SeriesPoint>` | 目标级原始幅度散点 |
| `trackPoints` | `List<TrackPoint>` | 地图航迹 |
| `periodMs` / `estimatedPriMs` | `Double` | **主周期**（ms）：≥2 个 burst 时为 burst 起点间隔中位数；否则为脉冲 PRI 中位数。**PRI** 始终为脉冲间隔估计，用于排查单 burst 场景 |
| `periodStability` / `periodConfidence` | `double` | 周期稳定度、周期置信度（0~1） |
| `burstConfidence` | `double` | burst 提取置信度 0~1 |
| `burstCount` | `int` | 本目标识别出的 burst 个数 |
| `burstDurationMeanMs` / `burstDurationStdMs` | `double` | **平均驻留** / 标准差（ms）：有 `nSignalTime` 时为脉级驻留均值，否则为 burst TOA 跨度均值 |
| `pulseCountMean` | `double` | burst 平均脉冲数 |
| `meanPriMs` / `priStdMs` | `double` | PRI 均值/标准差（ms） |
| `avgDutyCycle` / `maxDutyCycle` | `double` | **占空比**（%）：`avg` = 观测窗内各 burst 活跃时长之和 / (末 TOA − 首 TOA)，用于平台类型；`max` = 各 burst 相对其时间槽的峰值占比 |
| `freqDrift` / `doaDrift` / `amplitudeStd` / `signalStability` | `double` | 频率、方位、幅度稳定性特征 |
| `toaIntervalSeries` | `List<SeriesPoint>` | TOA 间隔图 |
| `priHistogram` | `List<SeriesPoint>` | PRI 分布图（`t` 为 PRI ms，`v` 为计数） |
| `jitterSeries` | `List<SeriesPoint>` | jitter 曲线 |
| `periodErrorSeries` | `List<SeriesPoint>` | 周期时间轴/预测误差 |
| `burstTimelineSeries` | `List<SeriesPoint>` | 时间-频率 burst 图 |
| `dutyCycleTrend` | `List<SeriesPoint>` | 占空比趋势图 |
| `bursts` | `List<BurstWindow>` | burst 起止、驻留、脉冲数、均值 PRI/频率 |
| `targetTypeReason` / `roleReason` / `convergenceDetail` | `String` | 报告文案 |

#### `SeriesPoint` — 时序点（图表通用）

```json
{ "t": 1751513396287, "v": 193.5 }
```

- `t`：毫秒时间戳（ECharts x 轴）
- `v`：数值（方位 ° / 幅度 dB / 频率 MHz）

#### `AnalysisSummary` — 分析结论

| 字段 | 说明 |
|------|------|
| `networkConclusions` | 网络级结论列表 |
| `targetConclusions` | 各目标结论（定位/平台/主从/轨迹） |
| `anomalies` | 异常提示（如方位分叉） |

---

## 3. 后置接口（可视化与报告）

### 3.1 职责

- 消费 `NetworkView` / `AnalyzeSessionResponse`
- 渲染交互图表、地图、统计表
- 导出可读分析报告

### 3.2 图表映射（当前前端 `AnalystWorkbench.vue`）

| 图表 | 数据字段 | 说明 |
|------|----------|------|
| 时间-方位折线 | `targets[].azimuthSeries` | 按目标分色 |
| 时间-幅度折线 | `targets[].signalSeries` | 按目标分色 |
| 原始方位散点 | `targets[].rawAzimuthSeries` | 与折线同色 |
| 原始幅度散点 | `targets[].rawSignalSeries` | 与折线同色 |
| 时间-频率 | `targets[].freqSeries` | 跳频/异频 |
| TOA 间隔 | `targets[].toaIntervalSeries` | 目标通信节奏 |
| 时间-频率突发 | `targets[].burstTimelineSeries` | burst 起始时间与均值频率 |
| PRI 分布 | `targets[].priHistogram` | PRI 峰值与多周期 |
| jitter 曲线 | `targets[].jitterSeries` | 周期抖动 |
| 周期时间轴/预测误差 | `targets[].periodErrorSeries` | 周期规律偏差 |
| 占空比趋势 | `targets[].dutyCycleTrend` | 各 burst 相对时间槽的占空比 |
| 地图航迹 | `targets[].trackPoints` | OpenLayers |
| 主从拓扑 | `targets[].role` + 时序 | 拓扑图 |
| 统计表 | `targets[]` | **主周期(ms)**、**平均驻留(ms)**、**占空比%**、burst 数；占空比列 tooltip 含 PRI |

### 3.3 报告输出（可扩展）

后置模块可从 `AnalysisSummary` 直接生成：

- **网络摘要**：`networkConclusions`
- **目标卡片**：`targetConclusions`（每条含定位/平台/主从/轨迹）
- **异常清单**：`anomalies`

建议报告结构：

```markdown
# 通信网络 {networkId} 分析报告
- 频率：{freq} MHz，模式：{commMode}，目标数：{targets.length}

## 网络结论
{networkConclusions}

## 目标详情
{targetConclusions}

## 异常
{anomalies}
```

### 3.4 后置集成方式

| 方式 | 入口 | 适用 |
|------|------|------|
| Web UI | `GET .../networks/{id}` → Vue 组件 | 人工研判 |
| JSON 导出 | 保存 `NetworkView` JSON | 第三方 BI |
| 报告服务 | 读取 `analysisSummary` | 自动化报告 |

---

## 4. 端到端调用示例

### 4.1 HTTP（前置 + 核心 + 后置 UI）

```bash
# 1. 上传分析
curl -F "file=@PrcFf1139.csv" -F "freqTolerance=0.1" \
  http://localhost:18080/api/signals/analyze

# 2. 加载单网详情（后置 UI 数据源）
curl http://localhost:18080/api/signals/analysis/{analysisId}/networks/42
```

### 4.2 Java 程序集成（推荐 Facade）

```java
@Autowired SignalAnalysisFacade facade;

// 输入：文件（行内多频）
AnalyzeSessionResponse session = facade.analyzeFromFile(file, 0.1);

// 输入：已有 List<DetectSignal>（每条 freq 可不同）
AnalyzeSessionResponse session2 = facade.analyzeSignals(signals, 0.1);

// 输出：单网详情（含 periodMs、burst、targets）
NetworkView network = facade.getNetworkDetail(session.getAnalysisId(), 42);
```

### 4.3 HTTP JSON 多频示例

```bash
curl -X POST http://localhost:18080/api/signals/analyze/json \
  -H "Content-Type: application/json" \
  -d "{\"freqTolerance\":0.1,\"signals\":[{\"detectTime\":\"2025-07-03T11:25:06.234\",\"freq\":269.65,\"azimuth\":99.9,\"signalLevel\":63},{\"detectTime\":\"2025-07-03T11:25:06.062\",\"freq\":370.0,\"azimuth\":104.8,\"signalLevel\":66}]}"
```

---

## 5. 版本与扩展

| 扩展点 | 位置 | 说明 |
|--------|------|------|
| 前置质量规则 | `ExcelImportService` 或独立 `DataQualityFilter` | SNR/定位覆盖率门限 |
| 算法参数 | `SignalAnalysisService.analyze(..., freqTolerance)` | 频率容差 |
| 后置图表 | `AnalystWorkbench.vue` 或独立报表服务 | 新增图表类型 |
| Session 持久化 | `AnalysisSessionService` | 可替换为 Redis/DB |

算法细节见 `docs/algorithm-design.md`；调试见 `docs/debugging-guide.md`。
