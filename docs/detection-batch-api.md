# 逐条侦测编批接口

> 给**其他软件前端**调用：一次分析后返回每条侦测的编批号、方位、时间、频率、目标类型、占用波道。  
> 同一 `batchId` = 同一目标。默认服务地址 `http://localhost:18080`。

相关总览见 [external-integration.md](./external-integration.md)。

---

## 1. 推荐调用

一次上传、一次拿到全部点（内部会先分网、编批、识别类型与波道，再展开）：

```
POST /api/signals/analyze/detections
Content-Type: multipart/form-data
```

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `file` | file | 是 | `.csv` / `.xlsx`，列映射同现有分析接口（`PL`/`FREQ`、`XHFW`/`AZIMUTH`、`ZCSJ` 等） |
| `freqTolerance` | double | 否 | 同频容差 MHz，默认 `0.01` |
| `includeUnassigned` | boolean | 否 | 是否包含未编入任何目标的点，默认 `true`（这些点 `batchId=0`） |
| `referenceFile` | file | 否 | 外源定位参考，与 `/api/signals/analyze` 相同 |

JSON 等价入口：

```
POST /api/signals/analyze/detections/json
Content-Type: application/json
```

请求体与 `POST /api/signals/analyze/json` 相同（`signals[]` + 可选 `freqTolerance`），查询参数 `includeUnassigned` 同上。

已有分析会话时（先调过 `/api/signals/analyze`）：

```
GET /api/signals/analysis/{analysisId}/detections?includeUnassigned=true
```

会话不存在返回 HTTP `404`。未预构建的网络会在本接口内按需分析。

**超时**：大文件可能需数分钟到数十分钟，客户端请把超时设长。CORS 已对 `/api/**` 放开。

---

## 2. 响应 `DetectionBatchResponse`

```json
{
  "analysisId": "a1b2c3d4e5f67890abcdef1234567890",
  "batchCount": 2,
  "detectionCount": 4,
  "unassignedCount": 1,
  "elapsedMs": 1280,
  "batches": [
    {
      "batchId": 1,
      "targetId": "T1",
      "networkId": 21,
      "targetType": "AWACS",
      "targetTypeLabel": "预警机",
      "channel": "D03",
      "channelLabel": "D03 预警机态势广播",
      "freqMhz": 306.925,
      "detectCount": 2
    }
  ],
  "detections": [
    {
      "batchId": 1,
      "targetId": "T1",
      "networkId": 21,
      "detectTimeMs": 1751511156234,
      "detectTime": "2025-07-03T11:25:06.234",
      "azimuthDeg": 186.05,
      "freqMhz": 306.925,
      "targetType": "AWACS",
      "targetTypeLabel": "预警机",
      "channel": "D03",
      "channelLabel": "D03 预警机态势广播",
      "signalId": "ROW-12"
    }
  ]
}
```

### 2.1 顶层

| 字段 | 类型 | 说明 |
|------|------|------|
| `analysisId` | string | 本次分析会话 ID |
| `batchCount` | int | 目标批次数（不含 `batchId=0`） |
| `detectionCount` | int | `detections` 条数 |
| `unassignedCount` | int | 未编批点数 |
| `elapsedMs` | long | 一次接口为分析+展开总耗时；GET 已有会话则为展开耗时 |
| `batches` | array | 目标列表，便于按目标切换 |
| `detections` | array | **每条侦测一行** |

### 2.2 `batches[]` 目标摘要

| 字段 | 类型 | 说明 |
|------|------|------|
| `batchId` | int | 编批号，从 1 起，全响应唯一 |
| `targetId` | string | 网内目标号，如 `T1` |
| `networkId` | int | 所属网络 |
| `targetType` | string | `AIR` / `AWACS` / `GROUND` |
| `targetTypeLabel` | string | `飞机` / `预警机` / `地面站` |
| `channel` | string | 占用波道 `D01`–`D06` 或 `UNKNOWN` |
| `channelLabel` | string | 波道中文名 |
| `freqMhz` | double | 该目标主频（MHz） |
| `detectCount` | int | 该批侦测条数 |

### 2.3 `detections[]` 单条侦测

| 字段 | 类型 | 说明 |
|------|------|------|
| `batchId` | int | **编批结果**：相同目标相同；未编入目标为 `0` |
| `targetId` | string | 网内目标号；未编批为空 |
| `networkId` | int | 所属网络 |
| `detectTimeMs` | long | 侦测时间戳（毫秒，对应 `ZCSJ`） |
| `detectTime` | string | 本地时间 `yyyy-MM-dd'T'HH:mm:ss.SSS` |
| `azimuthDeg` | double | 侦测方位（度，对应 `XHFW`） |
| `freqMhz` | double | 频率（MHz，对应 `PL`） |
| `targetType` | string | 目标类型编码；未编批为空 |
| `targetTypeLabel` | string | 目标类型中文 |
| `channel` | string | 占用波道编码（该网络通信链研判结果） |
| `channelLabel` | string | 占用波道中文 |
| `signalId` | string | 源记录 ID（如 `ROW-n`） |

前端按 `batchId` 分组即可把同一目标画成一条轨迹；用 `batches` 做目标切换（例如只显示 `targetType=AWACS` 的批）。

---

## 3. Java 调用

```java
@Inject
private SignalAnalysisFacade facade;

AnalyzeSessionResponse session = facade.analyzeFromFile(file, 0.01);
facade.preloadAllNetworks(session.getAnalysisId());
DetectionBatchResponse rows = facade.exportDetections(session.getAnalysisId(), true);
```

类：`com.pdwfx.signal.api.SignalAnalysisFacade#exportDetections`。

---

## 4. curl 示例

```bash
curl -X POST "http://localhost:18080/api/signals/analyze/detections?freqTolerance=0.01" \
  -F "file=@/path/to/pdw.csv"
```

```bash
curl -X POST "http://localhost:18080/api/signals/analyze/detections/json" \
  -H "Content-Type: application/json" \
  -d "{\"freqTolerance\":0.01,\"signals\":[{\"detectTimesss\":1751511156234,\"freq\":306.925,\"azimuth\":186.05,\"signalLevel\":60}]}"
```
