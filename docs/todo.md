todo 功能点 + bug 调试清单

> 本文用于人工调试和后续开发排期。算法入口见 `backend/src/main/java/com/pdwfx/signal/service/SignalAnalysisService.java`，
> 前端图表入口见 `frontend/src/components/AnalystWorkbench.vue`，详细调试手册见 `docs/debugging-guide.md`。

## 1. 图表联动：点击某个点，在其他图表中显示对应信息

**状态：已实现基础版。** 点击折线图/原始散点图/频率图中的点后，会同步选中目标，并在图表上方显示选中点信息；统计表对应目标行同步高亮。

### 现象

在某张图表中点击一个点（例如方位图、幅度图、原始散点图），其他图表没有同步显示该点/目标/时间的信息。

### 期望

- 点击任意图中的点后，其他图表同步同一行信息绘制的点。
- tooltip 中显示目标、时间、方位、幅度、频率等信息。
- 统计表中同步选中对应目标行。

### 实现建议

- 前端维护统一选中状态：
  - `selectedTargetId`
  - `selectedTime`
  - `selectedPoint`
- `AnalystWorkbench.vue` 中所有 ECharts 图表监听 `click` 事件。
- 点击折线点时，用 `seriesId` 定位目标；点击原始散点时，用时间最近原则匹配目标。
- 所有图表在 `drawCharts()` 中根据统一选中状态设置：
  - 当前目标加粗
  - 当前时间点附近加 symbol
  - 其他目标降低透明度

### 涉及文件

- `frontend/src/components/AnalystWorkbench.vue`
- `frontend/src/App.vue`

## 2. 合并后抖动较剧烈，应判断为两个批

**状态：已实现基础版。** `mergeSimilarTrajectoryClusters()` 合并后增加残差抖动检查，若合并簇相对拟合线残差标准差过大，会重新尝试拆成两个平行轨迹批。

### 现象

部分轨迹被合并后，方位曲线抖动明显，视觉上更像两条不同批次/目标被错误合在一起。

### 期望

合并后如果轨迹残差、方位跳变或幅度波动明显超阈值，应重新拆成两个目标/批。

### 判断依据

- 方位轨迹残差标准差过大。
- 相邻点方位跳变频繁超过阈值。
- 原始散点中存在两条平行或交叉轨迹。
- 合并前后同一时间段存在多个明显方位带。

### 实现建议

- 在 `mergeSimilarTrajectoryClusters()` 后增加合并质量检查。
- 若合并后残差标准差超过阈值，回退合并或重新拆分。
- 可新增方法：
  - `isMergedTrackTooJittery(...)`
  - `splitJitteryMergedTrack(...)`

### 涉及文件

- `backend/src/main/java/com/pdwfx/signal/service/SignalAnalysisService.java`

## 3. 找不到图中的点形状

**状态：已实现基础版。** 选中点使用更大的 `diamond` 标记，选中目标点变大并加边框，其他目标降低透明度。

### 现象

图中点太密、颜色太接近或 symbol 太小，人工不容易定位当前选中的具体点。

### 期望

- 被点击点有明显形状。
- 选中目标点更大，其他目标变淡。
- tooltip 和上方信息栏显示点的目标、时间、数值、来源图表。

### 涉及文件

- `frontend/src/components/AnalystWorkbench.vue`

## 4. 上下图对不上

**状态：已实现基础版。** 后端给每个目标增加 `rawAzimuthSeries` / `rawSignalSeries`，前端下方原始散点图按目标着色，颜色与上方目标折线一致。

### 现象

从截图看，上方“时间-方位图”已经按目标拆成多条折线，例如 T1/T2/T3/T4/T5；
下方“原始散点-时间方位”仍显示全网原始散点，包含更多原始方位带。
上方信息点丢失，有些方位点下面有，上面无

### 期望

上下图应明确区分，数据应一致



### 可能原因

- `rawAzimuthSeries` 是网络级原始散点，没有目标归属颜色。
- `azimuthSeries` 是目标级折线，经过过滤、降采样、聚类和合并。
- 上下图使用的点集不同：
  - 上图：`targets[].azimuthSeries`
  - 下图：`network.rawAzimuthSeries`
- 图表降采样数量不同，导致时间轴和点密度看起来不一致。

### 实现建议

- 后端 `TargetView` 增加目标级原始散点：
  - `rawAzimuthSeries`
  - `rawSignalSeries`
- 前端 `buildScatterOption()` 按 `targets[]` 分 series 绘制。
- 点击任意点时，统一更新 `selectedTargetId` / `selectedPoint`。
- 上下图同一目标使用同一颜色。

## 5. 用注解写

### 现状

核心算法文件已补充 Javadoc / 注释：

- `SignalAnalysisService.java`
- `MotionClassificationService.java`
- `MasterSlaveAnalysisService.java`
- `ExcelImportService.java`
- `SignalAnalysisController.java`
- `AnalystWorkbench.vue`

### 后续要求

新增或调整算法时，必须同步补充：
比如@Resource，使用springboot，使用@Data
- 类注释：说明职责和调用关系。
- 方法注释：说明输入、输出、判据、阈值。
- 关键阈值注释：说明业务含义和调参影响。
- 文档同步：更新 `docs/algorithm-design.md` 和 `docs/debugging-guide.md`。

## 6. 建议开发顺序

1. 修复“上下图对不上”：让原始散点按目标着色，并支持选中目标高亮。
2. 实现跨图点击联动：点击任意图点，同步其他图和统计表。
3. 优化点形状和 tooltip。
4. 增加合并后抖动检测，避免错误合批。
5. 持续补充注释和调试文档。
6. 数据的 信息 频率306.925少 》230°方位的数据 — **已修复**：取消 `filterScatteredPoints`，编批阶段保留全部原始点
7. fitLine 函数的应为曲线 — **已实现**：`AzimuthTrajectoryService` — DBSCAN 分轨迹 + RANSAC 直线 + 自然样条（弯曲段）；折线图输出拟合采样曲线
8. 制定 前置和后置的接口文档 — **已完成**：见 `docs/interface-api.md`
9. 对发射周期的分析，分析出周期长度
10. 驻留时间/发射周期 均值 来作为占空比计算
11. 输出分析的置信度
12. 将最终结果输出给其他模块，需要带置信度、频段、频率、目标个数、目标类型、



增加目标类型判断规则，增加通信链属性研判 — **已实现**（`CommunicationLinkAnalysisService`）
占空比门限限制： 预警机>4%;地面站≥25% — **已实现**
次级判断预警机发射间隔限制 <2s — **已实现**（PRI&lt;2000ms 且占空比∈[4%,25%)）

通信链属性研判方法；根据方式和属性判断网络类型链属性 —（`CommunicationLinkAnalysisService`）

地空数传指挥引导网（D01）— **已实现**：仅地面站+小飞机(非预警机)、主从、非D04双驻留
异频数传指挥引导网（D02）— **已实现**：`MULTI_FREQ`、仅小飞机、无预警机/地面、长机主僚机从
D03预警机态势广播 — **已实现**：仅预警机单发；定频/跳频分述；驻留24～28ms、PRI80～1900ms、占空比3.3～10.8%
D04地空数传指挥 — **已实现**：仅预警机+地面站；双驻留18.5/63.4ms；网占空~32.5%
D05空空数传指挥引导 — **已实现**：预警机+战机、无地面；驻留18～39ms；PRI/占空比分主从校验
D06跨区数传指挥协同网 — **已实现**：仅地面站+小飞机(非预警机)，与D01区分(弱主从/非D04)
以上均不符 → **不明**

输出字段：`commLinkChannel` / `commLinkChannelLabel` / `commLinkReason` / `commLinkEvidence`；`networkType` 在研判成功时改为波道文案。接口说明见 `docs/external-integration.md` §4.0、§2.5～2.6。