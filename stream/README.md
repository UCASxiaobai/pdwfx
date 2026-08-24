# pdwfx-stream（独立流式模块）

与原有 `backend`（信号分析 / 场景筛选）**进程分离**，不修改原有代码路径。

## 能力

1. TCP 接收流：以 **PDWHead3** 报文头 `0x7E8118E7` 组帧，按 `curPackLen` 切包  
2. 解析 Head3，用其中 **infoType** 过滤后解析 FFHead/FFData 定频点并落盘  
3. 按**数据时间**滚动封批；落盘为 **PDW 表**（`pl,xhfw,zcsj,...`，供场景筛选）；本批内去重  

4. **自动分析流水线**（封批后）：  
   - `POST /api/scenes/analyze-upload`（`fullSpanWindow=true`：整批一段时间窗，不做滑动切分）  
   - 对筛出场景升序 `POST /api/scenes/process-scene`（`preloadAll=true`：与主流程同一套目标类型/波道研判）  
   - 汇总目标标签、`reportRows`、占用统计，写入批级 **annotations JSON**（供显示软件消费）  
5. 前端 `#/stream` 做开发期校验：本批全局散点、场景方位轨迹（叠目标类型）、目标类型/波道图表、场景摘要、目标标签，并可深链网络研判页  

## 流水线

```
TCP → CSV 封批 → analyze-upload（建轨/场景，fullSpanWindow + enableImportScatter）
                → 场景时间窗对齐为本批检测起止
                → process-scene × N（信号分析）
                → stream-data/annotations/{batchId}.json
                → #/stream 可视化校验
```

状态阶段：`SCENE_FILTER` → `PROCESS_SCENE` → `DONE` / `FAILED`。

## 在 IntelliJ IDEA 中运行

本仓库有两个独立 Maven 工程：`backend`（signal-analysis）与 `stream`（pdwfx-stream）。  
IDEA 工程根目录请打开 **`pdwfx`**（不要只打开 `stream` 子目录）。

1. **Maven** 工具窗口 → 刷新，确认出现 **`pdwfx-stream`** 与 **`signal-analysis`**。  
2. JDK：**File → Project Structure → Project** 选 **1.8**。  
3. 先跑 **`SignalAnalysisApplication`**（端口 18080），再跑 **`StreamApplication`**（HTTP 19080 / TCP 19090）。  
4. 前端 `npm run dev`，打开 `http://localhost:5173/#/stream`。

命令行备选：

```bash
cd stream
mvn spring-boot:run
```

## 配置

见 `src/main/resources/application.yml`：

| 配置 | 说明 |
|------|------|
| `stream.tcp.port` | 对端推送端口 |
| `stream.batch.dir` | 落盘根目录（`inbox/processing/done/failed/open/annotations`） |
| `stream.batch.duration-minutes` | 切批时长（数据时间） |
| `stream.batch.dedup-enabled` | 落盘本批去重（默认 true） |
| `stream.analyze.base-url` | 原 backend 地址 |
| `stream.analyze.preload-all` | 流式默认 `false`（加快批处理） |
| `stream.analyze.scene.full-span-window` | 流式全段窗（默认 true；每频段评本批 [min,max] 一次） |
| `stream.analyze.scene.window-seconds` | 仅 `full-span-window=false` 时的滑动窗长 |
| `stream.analyze.scene.top-k-track-scenes` | TOP-K 连续轨迹场景 |
| `stream.analyze.scene.top-k-polling-scenes` | TOP-K 轮询场景 |
| `stream.analyze.annotation.dir` | 标注目录（空则 `{batch.dir}/annotations`） |
| `stream.display.push-url` | 显示软件推送 URL（空则本期不推） |

每批在 backend 侧使用独立 `outputDir=stream-batch-{batchId}`，避免互相覆盖。

**时间窗：** 流式与通信侦获主流程默认 `fullSpanWindow=true`（各频段先对本批该频段跨度评一次，融合后把所有场景的时间窗**强制对齐为本批检测起止**；可视化横轴同批）。主流程页面可取消勾选，改回滑动窗做多角度对比。

## API

- `GET http://localhost:19080/api/stream/status`  
- `GET http://localhost:19080/api/stream/batches`  
- `GET http://localhost:19080/api/stream/batches/{id}`  
- `GET http://localhost:19080/api/stream/batches/{id}/annotation` — 批级标注 JSON  

前端入口：`#/stream`（轨迹图请求 backend `GET /api/scenes/visualization-data?outputDir=...`）。

## 标注 JSON 契约

路径：`{batch.dir}/annotations/{streamBatchId}.json`

```json
{
  "streamBatchId": "batch_...",
  "csvPath": "...",
  "sceneOutputDir": "stream-batch-...",
  "sourceCsvOnBackend": "...",
  "status": "DONE",
  "timeRange": { "startMs": 0, "endMs": 0 },
  "scenes": [
    {
      "rank": 1,
      "sceneType": "TRACK_CONTINUOUS",
      "analysisId": "...",
      "networkCount": 1,
      "trackCount": 3,
      "skipped": false
    }
  ],
  "labels": [
    {
      "batchId": "...-s1-1-T1",
      "streamBatchId": "...",
      "sceneRank": 1,
      "sceneType": "TRACK_CONTINUOUS",
      "analysisId": "...",
      "targetType": "AIR",
      "targetTypeLabel": "飞机",
      "dutyCycle": 0.12,
      "trafficSharePct": 40.0,
      "channel": "...",
      "channelLabel": "...",
      "freqMhz": 123.456,
      "detectCount": 100,
      "detectStartMs": 0,
      "detectEndMs": 0
    }
  ],
  "occupancy": {
    "channels": [{ "channel": "...", "label": "...", "targetCount": 1, "trafficSharePctSum": 40.0 }],
    "freqs": [{ "freqMhz": 123.456, "targetCount": 1, "detectCount": 100 }],
    "targetTypes": [{ "type": "AIR", "label": "飞机", "targetCount": 1 }]
  },
  "reportRows": [ { "sceneRank": 1, "targetType": "AIR", "commLinkChannel": "D01", "...": "..." } ]
}
```

`sceneType`：`TRACK_CONTINUOUS`（连续轨迹）或 `MULTI_DEVICE_POLLING`（轮询）。

## Win7 说明

- 默认落盘、单 worker 串行分析，降低内存与 CPU 峰值  
- JVM 参数见 `pom.xml`：`-Xms256m -Xmx1g`  
