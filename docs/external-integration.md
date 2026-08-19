# 外部模块集成接口说明

> 供**其他系统 / 其他 Java 模块**调用信号分析服务。只描述输入、输出与调用方式；算法细节见 `docs/algorithm-design.md`。

**服务地址（默认）**：`http://localhost:18080`  
**Java 入口类**：`com.pdwfx.signal.api.SignalAnalysisFacade`（Spring `@Service`，可注入）

**逐条编批（其他前端按点取方位/时间/频率/类型/波道）**：见 **[detection-batch-api.md](./detection-batch-api.md)**。

---

## 1. 调用流程

推荐 **两步式**（大文件友好）；也可在 ① 阶段 **预分析全部网络**（见 `preloadAll` / §2.5）：

```
┌─────────────┐   ① 提交数据    ┌──────────────────────────┐
│  外部模块   │ ──────────────▶ │ AnalyzeSessionResponse   │
│             │                 │  analysisId + 网络摘要列表 │
└─────────────┘                 │  (+ 可选：网络类型/波道)   │
       │                        └──────────────────────────┘
       │  ② 按需取单网结果（可多次、可并行；① 已预分析则②为缓存命中）
       ▼
┌──────────────────────────┐
│ NetworkResultResponse    │  ← 推荐：目标表 + 文字结论 + 网络类型
│ 或 NetworkView（含图表）  │
└──────────────────────────┘
```

| 步骤 | 作用 | 典型耗时 |
|------|------|----------|
| ① 分析 | 分网、生成 session | 与数据量成正比 |
| ①′ 预分析（可选） | 全部或指定网：编批、平台类型、**通信链波道 → `networkType`** | 与网络数×单网复杂度成正比；2000+ 网可能十余分钟 |
| ② 详情 | 单网完整结果（含图表）；已预分析则几乎即时 | 未预分析时单网数秒～数十秒 |

**注意**：`analysisId` 保存在服务端内存 session 中，重启服务后失效。大文件请勿使用 `analyzeFull` 一次拉全量详情（含图表，体积极大）。

---

## 2. REST API

### 2.1 上传文件分析

```
POST /api/signals/analyze
Content-Type: multipart/form-data
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | file | 是 | `.csv` 或 `.xlsx` |
| `freqTolerance` | double | 否 | 同频容差 MHz，默认 `0.1` |
| `preloadNetworkIds` | string | 否 | 分析后立即构建指定网，如 `473` 或 `230,76`；响应中该网带完整摘要（含 `networkType`、波道、目标计数） |
| `preloadAll` | boolean | 否 | 默认 `false`；为 `true` 时等价于分析后调用 §2.6，**全部网络**写回 `networkType` / `commLink*` / 目标统计（耗时长） |

**响应**：`AnalyzeSessionResponse`（见 §4.1）

---

### 2.2 JSON 批量分析（程序集成推荐）

```
POST /api/signals/analyze/json
Content-Type: application/json
```

**请求体** `AnalyzeSignalsRequest`：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `signals` | `DetectSignalDto[]` | 是 | 侦获记录列表，**可混合多频** |
| `freqTolerance` | double | 否 | 默认 `0.1` MHz |
| `preloadNetworkIds` | string | 否 | 同 §2.1，如 `"473"` |
| `preloadAll` | boolean | 否 | 查询参数，同 §2.1 |

**响应**：`AnalyzeSessionResponse`（见 §4.1）

---

### 2.3 获取单网详情（含图表，供 UI）

```
GET /api/signals/analysis/{analysisId}/networks/{networkId}
```

| 路径参数 | 说明 |
|----------|------|
| `analysisId` | 步骤 ① 返回的 session ID（32 位十六进制，**不是** networkId） |
| `networkId` | 网络 ID（如 `230`） |

**响应**：`NetworkView`（见 §4.4）；不存在时 HTTP `404`。

---

### 2.4 获取单网业务结果（外部模块推荐）

```
GET /api/signals/analysis/{analysisId}/networks/{networkId}/result
```

与界面「目标表 + 网络/目标分析结果 + 异常提示」一致，**不含**图表时序。

**响应**：`NetworkResultResponse`（见 §4.2）；不存在时 HTTP `404`。

---

### 2.5 预构建指定网络（回写网络类型与目标摘要）

```
POST /api/signals/analysis/{analysisId}/preload?networkIds=473,230
```

| 参数 | 说明 |
|------|------|
| `analysisId` | 步骤 ① 返回的 session ID |
| `networkIds` | 逗号分隔的网络 ID，必填 |

**响应**：`AnalyzeSessionResponse`（`networks[]` 中对应项已填充 `networkType`、`commLinkChannel*`、`groundTargetCount` 等）；session 无效时 `404`。

---

### 2.6 预构建全部网络（批量网络类型研判）

```
POST /api/signals/analysis/{analysisId}/preload-all
```

对 session 内**每个网络**执行完整编批与通信链研判，将结果写回 `networks[]` 摘要（**不含**图表时序，图表仍在 GET 详情时从缓存返回）。

**响应**：`AnalyzeSessionResponse`；大文件（如 2000+ 网）可能耗时很长，建议客户端超时 ≥30 分钟或异步轮询业务侧任务。

前端默认勾选「导入后分析全部网络属性」即：① 分网 → ② 调用本接口。

---

### 2.7 逐条侦测编批（其他软件前端）

一次返回每条侦测的编批号、方位、时间、频率、目标类型、占用波道。详见 **[detection-batch-api.md](./detection-batch-api.md)**。

```
POST /api/signals/analyze/detections
POST /api/signals/analyze/detections/json
GET  /api/signals/analysis/{analysisId}/detections
```

同一 `batchId` 表示同一目标；`batchId=0` 为未编批点。

---

## 3. 输入

### 3.1 JSON 单条记录 `DetectSignalDto`

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `detectTime` | string | 二选一 | ISO 时间，如 `2025-07-03T11:25:06.234` 或 `2025-07-03 11:25:06.234` |
| `detectTimesss` | long | 二选一 | 侦测时间戳 ms（zcsj） |
| `freq` | double | **是** | 频率 MHz |
| `azimuth` | double | **是** | 方位 ° |
| `signalLevel` | double | **是** | 幅度 dB |
| `nSignalTime10us` | long | 否 | 驻留原始值，单位 **10µs** |
| `signalDwellMs` | double | 否 | 驻留 ms（与上式二选一） |
| `snr` | double | 否 | 信噪比 |
| `targetLon` / `targetLat` | double | 否 | 交叉定位（固定/移动判定） |
| `longitude` / `latitude` | double | 否 | 载机坐标 |
| `modulateStyle` | string | 否 | 调制样式（异频合并） |
| `id` | string | 否 | 外部 ID；缺省为 `ROW-n` |

**多频**：同一 `signals[]` 内每条 `freq` 可不同，**不必按频率分文件**。系统按 `freqTolerance` 分网。

**驻留换算**：`signalDwellMs = nSignalTime10us × 0.01`

---

### 3.2 CSV / Excel 列映射

| 原始列（任一同义列） | JSON/内部字段 | 必填 |
|----------------------|---------------|------|
| `FREQ` / `PL` | `freq` | 是 |
| `AZIMUTH` / `XHFW` | `azimuth` | 是 |
| `SIGNAL_LEVEL` / `XHFD` | `signalLevel` | 是 |
| `ZCSJ` / `DETECT_TIMESSS` | `detectTimesss` / `detectTime` | 是 |
| `nSignalTime` | `nSignalTime10us` → ms | 否 |
| `SNR` | `snr` | 否 |
| `DWJD` / `TARGET_LON` | `targetLon` | 否 |
| `DWD` / `TARGET_LAT` | `targetLat` | 否 |
| `MODULATE_STYLE` | `modulateStyle` | 否 |

---

### 3.3 请求示例

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

---

## 4. 输出

### 4.0 网络类型与通信链波道

研判在单网**完整分析**（预加载或 GET 详情）后写入下列字段。对外展示时 **`networkType` 与波道文案一致**（研判成功时取波道业务名，不再使用分网阶段的「未知/数据链」占位）。

| `commLinkChannel` | `commLinkChannelLabel`（示例） | `networkType`（研判成功时） |
|-------------------|-------------------------------|---------------------------|
| `D01` | D01 地空数传指挥引导 | 地空数传指挥引导网 |
| `D02` | D02 异频数传指挥引导 | 异频数传指挥引导网 |
| `D03` | D03 预警机态势广播 | 预警机态势广播 |
| `D04` | D04 地空数传指挥 | 地空数传指挥 |
| `D05` | D05 空空数传指挥引导 | 空空数传指挥引导 |
| `D06` | D06 跨区数传指挥协同 | 跨区数传指挥协同网 |
| `UNKNOWN` | 不明 | 不明 |

| 字段 | 说明 |
|------|------|
| `commLinkChannel` | 波道编码 `D01`～`D06` 或 `UNKNOWN` |
| `commLinkChannelLabel` | 波道中文全称（含编码前缀） |
| `commLinkReason` | 研判结论一句化说明（占空比、驻留、平台组合等） |
| `commLinkEvidence` | 字符串数组，调试/排错用：平台组合、各波道得分、D04 否决原因、最终选用（仅详情/`/result` 返回，列表摘要不含） |
| `networkType` | **网络类型展示名**；与上表「研判成功时」列一致；未研判前为 `未知` |
| `groundTargetCount` / `awacsTargetCount` / `airTargetCount` | 含地面站 / 预警机 / 飞机目标个数（预分析后回写列表） |

算法与门限见 `docs/todo.md`、`docs/algorithm-design.md`（`CommunicationLinkAnalysisService`）。

---

### 4.1 `AnalyzeSessionResponse` — 分析摘要

步骤 ① 返回；用于网络列表。仅分网时 `networkType` 多为 `未知`、`targetCount` 为 `0`；调用 §2.5 / §2.6、`preloadNetworkIds` / `preloadAll=true` 或 GET 详情后，对应字段回写。

```json
{
  "analysisId": "a1b2c3d4e5f67890abcdef1234567890",
  "networkCount": 284,
  "networks": [
    {
      "networkId": 473,
      "freq": 306.925,
      "networkType": "地空数传指挥",
      "commLinkChannel": "D04",
      "commLinkChannelLabel": "D04 地空数传指挥",
      "commLinkReason": "D04：仅预警机+地面站轮流；短帧~18.5ms/长帧~63.4ms；…",
      "networkConfidence": 0.69,
      "commMode": "SAME_FREQ",
      "signalCount": 3294,
      "targetCount": 4,
      "groundTargetCount": 1,
      "awacsTargetCount": 3,
      "airTargetCount": 0,
      "targets": [
        {
          "targetId": "T1",
          "targetType": "AWACS",
          "targetTypeLabel": "预警机",
          "emissionSharePct": 42.0,
          "periodMs": 2120,
          "burstDurationMeanMs": 25.6,
          "avgDutyCycle": 5.3,
          "burstCount": 109,
          "convergenceLabel": "不收敛",
          "ellipseLabel": "无椭圆",
          "role": "MASTER",
          "confidence": 0.55,
          "evidenceSignalCount": 651
        }
      ],
      "analysisSummary": {
        "networkConclusions": ["该网络共识别 4 个目标", "..."],
        "targetConclusions": ["Target T1：...", "..."],
        "anomalies": ["识别到 4 条方位/幅度轨迹..."]
      }
    }
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `analysisId` | string | Session ID，后续 GET 详情必带 |
| `networkCount` | int | 通信网络数量 |
| `networks` | array | 各网摘要（`NetworkSummary`） |

**`NetworkSummary` 字段**

| 字段 | 说明 |
|------|------|
| `networkId` | 网络 ID |
| `freq` | 代表频率 MHz |
| `networkType` | **网络类型**（展示用）；研判成功时为 §4.0 表中文案；仅分网时为 `未知` |
| `commLinkChannel` | 通信链波道编码 `D01`～`D06` / `UNKNOWN` |
| `commLinkChannelLabel` | 波道中文标签 |
| `commLinkReason` | 通信链研判依据（一句话） |
| `groundTargetCount` | 地面站目标数（预分析后） |
| `awacsTargetCount` | 预警机目标数（预分析后） |
| `airTargetCount` | 飞机目标数（预分析后） |
| `networkConfidence` | 网络识别置信度 0~1 |
| `freqBand` | 225-400MHz / L波段 / S波段 / 未知 |
| `freqRange` | 频率范围文案 |
| `freqStability` | 频率稳定性 0~1 |
| `commMode` | `SAME_FREQ` / `MULTI_FREQ` / `HOPPING` |
| `stationType` | `GROUND` / `AIR` / `MIXED` |
| `signalCount` | 原始 PDW 条数 |
| `targetCount` | 目标数（预加载或 GET 详情后回写） |
| `targets` | 目标摘要列表（`TargetSummary`，预加载或 GET 后才有） |
| `analysisSummary` | 文字结论（同上） |

---

### 4.2 `NetworkResultResponse` — 单网业务结果（推荐）

`GET .../networks/{networkId}/result` 返回；**外部模块对接首选**。

结构与界面一致：

| 区块 | JSON 路径 |
|------|-----------|
| 网络标量 | 根级 `networkId`, `freq`, **`networkType`**, **`commLinkChannel*`**, `networkPeriod`, `mainStationCount`… |
| 研判证据 | `commLinkEvidence[]`（调试，见 §4.0） |
| 目标表 | `targets[]`（`TargetSummary`） |
| 网络分析结果 | `analysisSummary.networkConclusions` |
| 目标分析结果 | `analysisSummary.targetConclusions` |
| 异常提示 | `analysisSummary.anomalies` |

**根级网络类型字段（`NetworkResultResponse` 与 `NetworkView` 共有）**

| 字段 | 说明 |
|------|------|
| `networkType` | 网络类型展示名（§4.0） |
| `commLinkChannel` | 波道编码 |
| `commLinkChannelLabel` | 波道中文名 |
| `commLinkReason` | 研判依据 |
| `commLinkEvidence` | 研判过程证据（字符串数组，调试排错） |

**`TargetSummary` 字段（对应界面目标表）**

| 字段 | 界面列 |
|------|--------|
| `targetId` | targetId |
| `targetType` / `targetTypeLabel` | targetType（中文：预警机/飞机/地面站） |
| `emissionSharePct` | 流量占比% |
| `periodMs` | 主周期(ms) |
| `burstDurationMeanMs` | 平均驻留(ms) |
| `avgDutyCycle` | 占空比% |
| `burstCount` | burst |
| `convergenceLabel` | convergence |
| `ellipseLabel` | 定位椭圆 |
| `role` | role |
| `confidence` | confidence |
| `chartPointCount` / `evidenceSignalCount` | 点数（UI 用 chart 点数；`evidenceSignalCount` 为原始 PDW 数） |
| `roleReason` / `targetTypeReason` | 判定说明 |

---

### 4.3 业务结论字段速查（详情阶段）

外部模块通常从 `NetworkResultResponse` 或 `NetworkView` 取以下字段：

| 用途 | 字段 |
|------|------|
| 网络类型 / 通信链 | **`networkType`**, **`commLinkChannel`**, **`commLinkChannelLabel`**, **`commLinkReason`**, `commLinkEvidence` |
| 通信模式 / 站型 | `commMode`, `stationType` |
| 目标类型统计 | `groundTargetCount`, `awacsTargetCount`, `airTargetCount`（摘要）；`targets[].targetType` |
| 置信度 | `networkConfidence`, `targets[].confidence`, `lowConfidence` |
| 主从 | `targets[].role`（`MASTER`/`SLAVE`）, `roleReason`, `mainStationCount` |
| 平台类型 | `targets[].targetType`（`GROUND`/`AIR`/`AWACS`）, `targetTypeReason` |
| 定位收敛 | `targets[].convergence`, `convergenceDetail`, `ellipseStatus` |
| 节奏 | `targets[].periodMs`, `burstCount`, `avgDutyCycle`, `bursts[]` |
| 流量（主从） | `targets[].emissionSharePct`（同网合计 100%） |
| 文字报告 | `analysisSummary.networkConclusions`, `targetConclusions`, `anomalies` |

---

### 4.4 `NetworkView` — 单网完整结果（含图表）

步骤 ② 返回；后置展示、报告、BI 的主数据源。

| 字段 | 类型 | 说明 |
|------|------|------|
| `networkId` | int | 网络 ID |
| `freq` | double | 代表频率 MHz |
| `centerFreq` | double | 中心频率 |
| `freqMin` / `freqMax` / `freqRange` | double / string | 频率范围 |
| `freqStability` | double | 0~1 |
| `networkType` | string | 网络类型（§4.0；与 `commLinkChannelLabel` 业务名一致） |
| `commLinkChannel` | string | `D01`～`D06` / `UNKNOWN` |
| `commLinkChannelLabel` | string | 波道中文名 |
| `commLinkReason` | string | 研判依据 |
| `commLinkEvidence` | string[] | 各波道得分与选用过程（调试） |
| `networkConfidence` | double | 0~1 |
| `lowConfidence` | boolean | 低置信度标识 |
| `commMode` | string | 同频 / 异频 / 跳频 |
| `stationType` | string | 地面 / 空中 / 混合 |
| `signalCount` | int | PDW 总数 |
| `targetCount` / `activeTargetCount` | int | 目标数 / 活跃目标数 |
| `mainStationCount` / `subStationCount` | int | 主站 / 从站数 |
| `networkPeriod` | double | 网络主周期 ms |
| `networkDutyCycle` | double | 网络平均占空比 % |
| `networkLoadLevel` | string | 低 / 中 / 高 / 拥塞 |
| `updatedAt` | long | 更新时间戳 ms |
| `updateMode` | string | 当前为 `FULL` |
| `targets` | array | 目标列表（见下） |
| `analysisSummary` | object | 文字结论（见 §4.6） |
| `rawAzimuthSeries` / `rawSignalSeries` | array | 全网原始散点（可选） |

---

### 4.4 `TargetView` — 单目标

| 字段 | 类型 | 说明 |
|------|------|------|
| `targetId` | string | 如 `T1` |
| `targetType` | string | `GROUND` / `AIR` / `AWACS` |
| `targetTypeReason` | string | 平台判定说明 |
| `role` | string | `MASTER` / `SLAVE` / `UNKNOWN` |
| `roleReason` | string | 主从判定说明（含流量占比、占空比） |
| `confidence` / `targetConfidence` | double | 目标识别置信度 0~1（同值） |
| `lowConfidence` | boolean | 低置信度 |
| `evidenceSignalCount` | int | 支撑该目标的 PDW 数 |
| `convergence` | string | `CONVERGING` / `NOT_CONVERGING` / `UNKNOWN` |
| `convergenceDetail` | string | 收敛说明 |
| `ellipseStatus` | string | `NO_ELLIPSE` / `HAS_ELLIPSE` |
| `ellipseConverging` | boolean | 误差椭圆是否收敛 |
| `emissionSharePct` | double | **流量占比 %**（同网合计 100%） |
| `periodMs` | double | 主周期 ms |
| `estimatedPriMs` | double | 脉冲 PRI ms |
| `burstCount` | int | burst 个数 |
| `burstDurationMeanMs` | double | 平均驻留 ms |
| `avgDutyCycle` / `maxDutyCycle` | double | 占空比 % |
| `periodConfidence` / `burstConfidence` | double | 周期 / burst 置信度 0~1 |
| `avgSignalLevel` / `avgSnr` | double | 平均幅度 / SNR |
| `bursts` | array | burst 明细（见 §4.5） |
| `azimuthSeries` / `signalSeries` / `freqSeries` | array | 时序折线 |
| `trackPoints` | array | 地图航迹点 |
| `toaIntervalSeries` 等 | array | 其他分析图表序列（可选） |

---

### 4.5 公共子结构

**`SeriesPoint`**（时序通用）

```json
{ "t": 1751513396287, "v": 193.5 }
```

- `t`：毫秒时间戳
- `v`：数值（方位 ° / 幅度 dB / 频率 MHz 等，视字段而定）

**`BurstWindow`**

| 字段 | 说明 |
|------|------|
| `start` / `end` | burst 起止时间戳 ms |
| `durationMs` | 驻留 ms |
| `pulseCount` | 脉冲数 |
| `meanPriMs` | 平均 PRI ms |
| `meanFreq` | 平均频率 MHz |

**`TrackPoint`**

| 字段 | 说明 |
|------|------|
| `t` | 时间戳 ms |
| `platformLon` / `platformLat` | 载机位置 |
| `targetLon` / `targetLat` | 交叉定位 |
| `azimuth` | 方位 ° |

---

### 4.6 `AnalysisSummary` — 文字结论

| 字段 | 类型 | 说明 |
|------|------|------|
| `networkConclusions` | string[] | 网络级结论 |
| `targetConclusions` | string[] | 各目标结论（定位/平台/主从/轨迹） |
| `anomalies` | string[] | 异常提示 |

---

## 5. Java 同进程集成

适用于与本服务同一 Spring Boot 工程，或作为依赖 jar 引入。

```java
@Autowired SignalAnalysisFacade facade;

// 方式 A：JSON 请求体
AnalyzeSessionResponse session = facade.analyzeFromJson(request);

// 方式 B：已有 List<DetectSignal>
AnalyzeSessionResponse session2 = facade.analyzeSignals(signals, 0.1);

// 方式 C：文件
AnalyzeSessionResponse session3 = facade.analyzeFromFile(multipartFile, 0.1);

// 取单网详情
NetworkView network = facade.getNetworkDetail(session.getAnalysisId(), 42);
```

| 方法 | 输入 | 输出 |
|------|------|------|
| `importSignalsFromFile(file)` | MultipartFile | `List<DetectSignal>` |
| `importSignalsFromJson(request)` | `AnalyzeSignalsRequest` | `List<DetectSignal>` |
| `analyzeFromFile(file, tol)` | 文件 + 容差 | `AnalyzeSessionResponse` |
| `analyzeFromJson(request)` | JSON 请求 | `AnalyzeSessionResponse` |
| `analyzeSignals(signals, tol)` | 信号列表 + 容差 | `AnalyzeSessionResponse` |
| `getNetworkDetail(sessionId, networkId)` | session + 网 ID | `NetworkView`（含图表） |
| `getNetworkResult(sessionId, networkId)` | session + 网 ID | `NetworkResultResponse`（目标表+结论） |
| `preloadNetworks(sessionId, ids)` | 预构建指定网 | `AnalyzeSessionResponse`（摘要回写 **networkType** / 目标） |
| `preloadAllNetworks(sessionId)` | 预构建全部网 | `AnalyzeSessionResponse`（同上，批量） |
| `analyzeFull(signals, tol)` | 信号列表 | `NetworkAnalysisResponse`（**全量详情，大文件慎用**） |

---

## 6. 调用示例

### 6.1 curl — 文件

```bash
curl -F "file=@PrcFf1139.csv" -F "freqTolerance=0.1" \
  http://localhost:18080/api/signals/analyze
```

### 6.2 curl — JSON

```bash
curl -X POST http://localhost:18080/api/signals/analyze/json \
  -H "Content-Type: application/json" \
  -d "{\"freqTolerance\":0.1,\"signals\":[{\"detectTime\":\"2025-07-03T11:25:06.234\",\"freq\":269.65,\"azimuth\":99.9,\"signalLevel\":63}]}"
```

### 6.3 curl — 单网业务结果（推荐外部模块）

```bash
# 分析时预加载 network 473（响应里直接带 networkType / 波道 / 目标摘要）
curl.exe -F "file=@PrcFf1139.csv" -F "freqTolerance=0.01" -F "preloadNetworkIds=473" `
  http://localhost:18080/api/signals/analyze

# 或：分析后一次性预分析全部网络（列表即可筛选网络类型）
curl.exe -F "file=@PrcFf1139.csv" -F "freqTolerance=0.01" http://localhost:18080/api/signals/analyze
curl.exe -X POST "http://localhost:18080/api/signals/analysis/{analysisId}/preload-all"

# 单网业务结果（含 networkType、commLinkEvidence）
curl.exe "http://localhost:18080/api/signals/analysis/{analysisId}/networks/473/result"
```

### 6.4 curl — 单网详情（含图表，供 UI）

```bash
curl http://localhost:18080/api/signals/analysis/{analysisId}/networks/1
```

### 6.4 自动化验证

项目根目录执行（需后端已启动）：

```bash
node scripts/verify-api.mjs
```

---

## 7. 错误与约束

| 情况 | 行为 |
|------|------|
| `signals` 为空 | HTTP 400 / `IllegalArgumentException` |
| 缺少 `freq`/`azimuth`/`signalLevel` | HTTP 400，指明行号 |
| 缺少时间字段 | HTTP 400 |
| `analysisId` 无效或过期 | HTTP 404 |
| `networkId` 不存在 | HTTP 404 |
| 超大单网详情 | 响应体较大（含多条时序）；建议按需拉取 |

**Session 生命周期**：当前为进程内存存储；服务重启后需重新分析。

**参数 `freqTolerance`**：相邻频率差 ≤ 容差的 PDW 划入同一频簇再分网；典型值 `0.1` MHz，超大稀疏网可试 `5.0`。

---

## 8. 返回结果怎么读

接口分 **两次返回**，用途不同；外部模块按下面顺序看即可。

### 8.1 第一次：`AnalyzeSessionResponse`（网络列表）

```json
{
  "analysisId": "1f73d1dc-....",
  "networkCount": 809,
  "networks": [
    {
      "networkId": 76,
      "freq": 306.925,
      "commMode": "SAME_FREQ",
      "networkType": "未知",
      "commLinkChannel": null,
      "networkConfidence": 0.68,
      "signalCount": 6715,
      "targetCount": 0
    }
  ]
}
```

| 看什么 | 字段 | 说明 |
|--------|------|------|
| 下次请求的钥匙 | `analysisId` | 必须保存，GET 详情要用 |
| 选哪个网 | `networks[].networkId` | 与 `signalCount` 一起决定优先级 |
| **网络类型** | **`networkType`** | 预分析或 GET 详情后为 §4.0 文案；仅分网时为 `未知` |
| **通信链波道** | **`commLinkChannel`**, **`commLinkChannelLabel`**, **`commLinkReason`** | 与 `networkType` 同时回写 |
| 含哪些平台 | **`groundTargetCount`**, **`awacsTargetCount`**, **`airTargetCount`** | 预分析后可用于筛选统计 |
| 是否同频/跳频 | `commMode` | `SAME_FREQ` / `MULTI_FREQ` / `HOPPING` |
| 网络可信度 | `networkConfidence` | 0~1，越高越可靠 |
| 目标数 | `targetCount` | 仅分网时常为 `0`；预分析后与详情一致 |

仅分网时**没有** `targets`、主从、周期、图表；调用 `preload-all` 或 GET 详情后列表会带 **网络类型与目标类型计数**。

---

### 8.2 第二次：`NetworkView`（单网详情）

请求：`GET .../analysis/{analysisId}/networks/{networkId}`

建议 **从上到下** 读：

```
NetworkView
├── ① analysisSummary     ← 先看：人类可读结论（报告直接用）
├── ② 网络级标量字段       ← 频率、模式、主周期、占空比、主从数量
├── ③ targets[]           ← 每个目标的判定 + 指标
└── ④ *Series / trackPoints ← 要画图再取（体积大）
```

#### ① 文字结论（最快看懂）

```json
"analysisSummary": {
  "networkConclusions": [
    "该网络共识别 2 个目标",
    "网络类型：数据链，频段：225-400MHz，网络置信度 0.72",
    "其中 MASTER: 1 个, SLAVE: 1 个"
  ],
  "targetConclusions": [
    "Target T1：【平台】地面站 — …；【主从】MASTER — 主站：流量占比65.0%…"
  ],
  "anomalies": [
    "识别到 2 条方位/幅度轨迹，疑似多个目标共频通信"
  ]
}
```

- **`networkConclusions`**：网络整体结论（类型、周期、主从统计）
- **`targetConclusions`**：每个目标一条，含平台/主从/周期/burst/轨迹
- **`anomalies`**：异常提示；为空数组时会有「未发现明显异常」

只做报告、不做图表时，**读 `analysisSummary` + `targets` 里的标量字段就够**。

#### ② 网络级字段

| 字段 | 含义 |
|------|------|
| `networkType` | **网络类型**：地空数传指挥、预警机态势广播、空空数传指挥引导、不明 等（§4.0） |
| `commLinkChannel` | `D01`～`D06` / `UNKNOWN` |
| `commLinkChannelLabel` | 波道中文名 |
| `commLinkReason` | 研判一句说明 |
| `commLinkEvidence` | 调试证据列表（各波道得分、是否仅地面+预警机等） |
| `commMode` | 同频 / 异频 / 跳频 |
| `stationType` | `GROUND` / `AIR` / `MIXED` |
| `networkConfidence` | 网络识别置信度 0~1 |
| `lowConfidence` | `true` 表示需人工复核 |
| `networkPeriod` | 网络主周期 ms |
| `networkDutyCycle` | 网络平均占空比 % |
| `mainStationCount` / `subStationCount` | 主站 / 从站个数 |
| `targetCount` / `activeTargetCount` | 详情加载后的目标数 |

#### ③ 每个目标 `targets[]` — 核心字段

| 字段 | 怎么看 |
|------|--------|
| `role` | **`MASTER`** = 主站，**`SLAVE`** = 从站 |
| `roleReason` | 主从依据（流量占比%、占空比%、综合得分） |
| `targetType` | **`GROUND`** 地面 / **`AIR`** 飞机 / **`AWACS`** 预警机 |
| `targetTypeReason` | 平台判定说明（占空比阈值等） |
| `confidence` | 目标类型置信度 0~1 |
| `lowConfidence` | `true` 时谨慎采用 |
| `emissionSharePct` | **流量占比 %**，同网各目标合计 100% |
| `periodMs` | **主周期** ms（发一阵、停一阵的节奏） |
| `estimatedPriMs` | **PRI** ms（脉冲间隔，比主周期更细） |
| `burstCount` | burst 段数 |
| `burstDurationMeanMs` | 平均驻留 ms |
| `avgDutyCycle` | 占空比 %（观测窗内活跃时间占比） |
| `convergence` | 定位：`CONVERGING` / `NOT_CONVERGING` / `UNKNOWN` |
| `ellipseStatus` | `HAS_ELLIPSE` / `NO_ELLIPSE` |
| `bursts[]` | 每段 burst 的起止时间、驻留、脉冲数 |

**主从怎么判**：看 `role` + `roleReason`；数值上重点比 `emissionSharePct` 和 `avgDutyCycle`。

**节奏怎么判**：`periodMs`（宏观）+ `burstCount` / `avgDutyCycle`（活跃度）+ `bursts[]`（分段明细）。

#### ④ 时序与地图（可选，体量大）

| 字段 | 用途 |
|------|------|
| `azimuthSeries` / `signalSeries` / `freqSeries` | 时间-方位/幅度/频率折线 |
| `rawAzimuthSeries` / `rawSignalSeries` | 原始散点 |
| `trackPoints` | 地图航迹（含交叉定位点） |
| `priHistogram` / `toaIntervalSeries` 等 | 节奏分析辅助图 |

时序点统一格式：`{ "t": 1751513396287, "v": 193.5 }` — `t` 毫秒时间戳，`v` 数值。

---

### 8.3 和前端界面对应关系

| 界面位置 | JSON 路径 |
|----------|-----------|
| 左侧网络列表 | 第一次 `networks[]`（预分析后含 `networkType`、波道、地/预/机计数） |
| 网络标题/类型 | `networkType` 或 `commLinkChannelLabel`；筛选按 `networkType` |
| 通信链证据区 | `commLinkEvidence[]`（仅详情） |
| 列表筛选统计 | `networkType`、`commLinkChannelLabel`、`groundTargetCount` 等 |
| 目标表「主从」列 | `targets[].role` |
| 目标表「流量占比%」 | `targets[].emissionSharePct` |
| 目标表「主周期 / 驻留 / burst」 | `periodMs`, `burstDurationMeanMs`, `burstCount` |
| 下方文字结论区 | `analysisSummary` |
| 各图表 | `targets[].*Series`, `trackPoints` |

---

### 8.4 本地快速看一份真实 JSON

后端启动后（`18080`）：

```bash
# 1. 分析，记下 analysisId 和 networkId
curl -F "file=@sample-table-signals.csv" -F "freqTolerance=0.1" \
  http://localhost:18080/api/signals/analyze

# 2. 拉详情（替换 id）
curl http://localhost:18080/api/signals/analysis/{analysisId}/networks/1
```

或用浏览器插件 / `jq` 格式化 JSON；前端 http://localhost:5173 上传后 F12 网络面板也能看到同样结构。

---

## 9. 相关文档

| 文档 | 内容 |
|------|------|
| `docs/interface-api.md` | 完整三段式接口（含前置/后置/图表映射） |
| `docs/algorithm-design.md` | 编批、主从、占空比等算法 |
| `docs/debugging-guide.md` | 联调排错 |
