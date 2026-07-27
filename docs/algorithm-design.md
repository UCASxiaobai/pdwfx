# 通信侦获信号分析算法设计

## 1. 概述

本系统对侦获通信信号 Excel 数据进行**编批（通信网络划分 + 目标细分）**和**识别关联处理（目标识别、平台判定、主从关联）**，输出结构化 JSON 及可视化数据。

## 2. 输入数据

| 字段 | 含义 |
|------|------|
| `DETECT_TIMESSS` | 侦获时间（毫秒） |
| `FREQ` | 频率 (MHz) |
| `AZIMUTH` | 方位角 (°) |
| `SIGNAL_LEVEL` | 信号幅度 (dB) |
| `SNR` | 信噪比 |
| `TARGET_LON` / `TARGET_LAT` | 交叉定位结果（可为空） |
| `LONGITUDE` / `LATITUDE` | 侦察平台位置 |
| `MODULATE_STYLE` / `BIT_RATE` | 调制样式、比特率 |

## 3. 处理流程

```
Excel 导入
    │
    ▼
[编批-1] 通信网络划分
         ├─ 同频：FREQ + 容差分组
         ├─ 异频：多频点 + 时间/方位/调制关联合并
         └─ 跳频：频点序列模式识别
    │
    ▼
[编批-2] 方位轨迹聚类 → 平行轨迹拆分 → 轨迹片段合并
    │
    ▼
[编批-3] 同方位簇内幅度曲线二次细分
    │
    ▼
[识别-1] 时间-方位 / 时间-幅度 / 时间-频率 可视化
    │
    ▼
[识别-2] 固定/移动：Mahalanobis + 协方差椭圆 + 卡尔曼
         ├─ 漂移在椭圆内、椭圆收敛 → GROUND
         └─ 超出椭圆、连续航迹 → AIR
    │
    ▼
[识别-3] 平台类型：GROUND / AIR / AWACS
    │
    ▼
[识别-4] 主从：时序行为 + 中心性（辅证幅度/SNR）
    │
    ▼
结构化 JSON + 分析结论
```

## 4. 编批：通信网络划分

### 4.1 同频通信 (`SAME_FREQ`)

按 `FREQ` 排序，在频率容差 `freqTolerance`（默认 0.1 MHz）内归为一网。

### 4.2 异频通信 (`MULTI_FREQ`)

同一通信网络内存在多个固定频点。合并条件（同时满足）：

- 两个频组的中心频率差 > `2 × freqTolerance`（确认为不同频点）
- 侦获时间重叠 ≥ 较短时间段 30%
- 重叠时段内平均方位差 ≤ 20°
- 调制样式一致（若字段存在）

### 4.3 定跳频通信 (`HOPPING`)

网络内信号频率快速变化。判定条件：

-  distinct 频点数量 > 1
-  频率时序平均变化量 > 0.8 MHz
-  唯一频点数 / 信号数 > 10%

## 5. 编批：目标细分

### 5.1 方位轨迹聚类

- **DBSCAN**：在归一化 (时间, 方位) 空间分多条轨迹，`eps_az≈5.5°`，时间窗自适应
- **RANSAC**：每簇内检测局部直线段（残差 ≤4°，内点率 ≥68%）
- **样条**：非直线或双向弧形段用自然三次样条；折线图 `azimuthSeries` 输出拟合采样曲线
- 平行轨（截距分离 ≥5°）二次拆分；时间断裂片段按拟合模型合并

实现：`AzimuthTrajectoryService.java`

### 5.2 幅度二次细分

同方位簇内，仅当存在 **两条时间重叠、各自幅度稳定** 的并行曲线（整体极差 ≥18 dB）才拆分；单条随时间变化的幅度曲线不再误拆。

## 6. 识别：固定站 / 移动目标

依据连续 `TARGET_LON/LAT`（或 `DWJD/DWD`）定位结果，估计 2×2 协方差（误差椭圆），并结合简化常速卡尔曼航迹，**优先用 Mahalanobis 门限抑制测向噪声造成的假移动**。

| 固定目标特征 | 判定逻辑 |
|--------------|----------|
| 位置漂移落在误差椭圆内 | Mahalanobis d² ≤ χ²(2,95%)≈5.99 的内点率 ≥ 82% |
| 误差椭圆逐渐收敛 | 后半段坐标离散度 < 前半段×0.75 且后段 < 0.01° |
| 长时间中心稳定 | 卡尔曼平滑航速极低 |

| 移动目标特征 | 判定逻辑 |
|--------------|----------|
| 连续偏移超出误差椭圆 | 内点率 < 55% 或 max d² > 2.5×门限 |
| 轨迹方向连续、稳定航向 | 卡尔曼航速 ≥ 阈值且航向稳定度 ≥ 0.55 |

定位点不足（<6）、**无法形成误差椭圆**时 → **AIR（飞机）**，不再用方位稳定判为固定站。

实现：`MotionClassificationService.java`

## 7. 识别：平台类型

| 类型 | 条件 |
|------|------|
| **GROUND** | 目标发射占空比 ≥ 25% |
| **AWACS** | 目标发射占空比 ≥ 5% 且 < 25% |
| **AIR** | 目标发射占空比 < 5% |

误差椭圆 / 固定移动判定为次要优先级：仅在占空比接近阈值或低占空目标同时满足“椭圆固定/收敛 + 测向≤1°”时作辅证，不覆盖“占空比≥25% → GROUND”的主结论。

实现：`SignalAnalysisService.classifyPlatformType()`；占空比来自 `CommunicationRhythmService.applyRhythmMetrics()`。

## 8. 主从站关联

**主站特征（时序综合评分，权重示例）：**

| 特征 | 权重倾向 | 说明 |
|------|----------|------|
| **发射时间占比** | **最高 (~50%)** | 占空比×0.55 + 活跃跨度×0.45，**在线越久越像主站** |
| 活跃跨度 | ~18% | 目标首尾时间占观测窗比例 |
| 占空比 | ~12% | 各发射簇累计时长 / 观测窗 |
| 先发/响应/中心性 | ~17% | 时序关联辅助 |
| 辅证 | 5% | `幅度×0.6+SNR×0.4` |

**从站特征：** 发射簇较少、占空比较低、相对主站滞后（中位时延）、时序关联度高但中心性低。

实现：`MasterSlaveAnalysisService.java`

## 9. 输出结构

```json
{
  "networkCount": 1,
  "networks": [{
    "networkId": 1,
    "freq": 306.925,
    "commMode": "SAME_FREQ",
    "stationType": "MIXED",
    "signalCount": 500,
    "targets": [{
      "targetId": "T1",
      "targetType": "AWACS",
      "role": "MASTER",
      "convergence": "NOT_CONVERGING",
      "confidence": 0.85,
      "azimuthSeries": [{"t": 1000, "v": 45.2}],
      "signalSeries": [{"t": 1000, "v": -62.3}],
      "freqSeries": [{"t": 1000, "v": 306.925}],
      "trackPoints": [{"t": 1000, "targetLon": 120.1, "targetLat": 30.2}]
    }],
    "analysisSummary": {
      "networkConclusions": ["该网络共识别 2 个目标", "通信模式：同频"],
      "targetConclusions": ["Target T1：【定位】交叉定位 N 点…；【平台】固定站 — …；【主从】MASTER — score=幅度×0.6+SNR×0.4…；【轨迹】…"],
      "anomalies": ["发现方位分叉，疑似多目标共频通信"]
    }
  }]
}
```

## 10. 可调参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `freqTolerance` | 0.1 | 同频容差 (MHz) |
| 方位轨迹容差 | 5° | 轨迹拟合残差 |
| 平行轨迹最小分离 | 5° | 拆分阈值 |
| 幅度细分阈值 | 18 dB（整体极差） | 二次拆分 |
| 幅度聚类间隔 | 6 dB | 簇内合并 |
| 收敛判定比例 | 0.75 | 后半/前半离散度比 |
| 收敛绝对阈值 | 0.01° | 收敛后坐标离散度上限 |

## 11. 代码实现索引

### 11.1 后端服务

| 阶段 | 方法 | 文件 |
|------|------|------|
| 入口 | `analyze()` | `SignalAnalysisService.java` |
| 同频编批 | `splitByFreq()` | 同上 |
| 异频合并 | `mergeMultiFreqNetworks()` | 同上 |
| 通信模式 | `detectCommMode()` | 同上 |
| 散点过滤 | `filterScatteredPoints()` | 同上 |
| 方位聚类 | `clusterByAzimuthTrajectory()` | 同上 |
| 平行方位拆分 | `splitParallelTracks*()` | 同上 |
| 轨迹合并 | `mergeSimilarTrajectoryClusters()` | 同上 |
| 幅度拆分 | `clusterBySignalLevel()` → `splitParallelAmplitudeTracks()` | 同上 |
| 固定/移动 | `MotionClassificationService.assess()` | `MotionClassificationService.java` |
| 平台类型 | `applyTargetType()` | `SignalAnalysisService.java` |
| 主从 | `MasterSlaveAnalysisService.assignRoles()` | `MasterSlaveAnalysisService.java` |
| 预警机 | `refineAwacsType()` | `SignalAnalysisService.java` |
| 结论文案 | `buildSummary()` / `buildTargetConclusion()` | `SignalAnalysisService.java` |
| 数据导入 | `ExcelImportService.parse()` | `ExcelImportService.java` |
| 会话缓存 | `AnalysisSessionService` | `AnalysisSessionService.java` |
| API | `SignalAnalysisController` | `controller/SignalAnalysisController.java` |

### 11.2 前端

| 功能 | 位置 |
|------|------|
| 上传分析、网络列表 | `frontend/src/App.vue` |
| 折线图/散点图/高亮 | `frontend/src/components/AnalystWorkbench.vue` |
| API 代理 | `frontend/vite.config.js` → `localhost:18080` |

### 11.3 关键模型字段

| 模型 | 字段 | 含义 |
|------|------|------|
| `TargetView` | `convergenceDetail` | 定位/椭圆判定说明 |
| `TargetView` | `targetTypeReason` | 固定站/飞机判据 |
| `TargetView` | `roleReason` | 主从判据（含发射时间占比） |
| `NetworkView` | `rawSignalSeries` | 全网原始幅度散点 |
| `NetworkView` | `targets[].signalSeries` | 单目标幅度折线 |




