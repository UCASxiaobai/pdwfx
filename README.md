# 通信侦获信号分析系统（SpringBoot + Vue）

## 目录结构

- `backend`：SpringBoot 后端（Excel导入 + 网络划分 + 目标识别）
- `frontend`：Vue3 前端（网络列表 + 网络详情 + 多层可视化）
- `docs/external-integration.md`：**外部模块集成**（输入/输出、REST、Java Facade）
- `docs/interface-api.md`：完整三段式接口文档
- `docs/algorithm-design.md`：算法设计文档

## 后端能力

- Excel 导入：Apache POI 解析指定字段
- 通信网络划分：
  - 同频：按 `FREQ` + `freqTolerance` 分组
  - 异频：多频点 + 时间/方位/调制关联合并
  - 定跳频：频点序列模式识别
- 目标识别：
  - 方位轨迹拆分：`AZIMUTH + DETECT_TIMESSS`
  - 强度辅助拆分：同方位簇内再按 `SIGNAL_LEVEL` 聚类
- 固定/移动目标判定（Mahalanobis + 协方差椭圆 + 卡尔曼，抑制测向假移动）：
  - 漂移在椭圆内且收敛 → `GROUND`
  - 连续偏移超出椭圆且有航迹 → `AIR`
- 主从站（时序行为：频次/占空比/先发/响应/中心性，幅度+SNR 为辅证）：
  - 综合时序得分最高 → `MASTER`
- 平台类型：
  - `GROUND` 固定站 / `AIR` 飞机 / `AWACS` 预警机
- 主从站判断：
  - `score = SIGNAL_LEVEL * 0.6 + SNR * 0.4`
  - 最高分为 `MASTER`，其余为 `SLAVE`

## 输出结构

后端接口返回结构化 JSON：

- `networkCount`
- `networks[]`
  - `networkId`
  - `freq`
  - `commMode`（SAME_FREQ / MULTI_FREQ / HOPPING）
  - `stationType`
  - `targets[]`
    - `targetId`
    - `targetType`（GROUND / AIR / AWACS）
    - `convergence`（CONVERGING / NOT_CONVERGING / UNKNOWN）
    - `role`
    - `confidence`
    - `azimuthSeries[]`（前端绘图）
    - `signalSeries[]`（前端绘图）

## 运行方式

### 1) 启动后端

```bash
cd backend
mvn spring-boot:run
```

默认端口：`18080`

### 2) 启动前端

```bash
cd frontend
npm install
npm run dev
```

默认端口：`5173`

### 3) 启动流式模块（独立进程，可选）

本地需有 `stream/` 目录（`pdwfx-stream`）。先启 backend，再启 stream：

```bash
cd stream
mvn spring-boot:run
```

或双击 `scripts/start-stream.bat`。HTTP `19080` / TCP `19090`。

#### IntelliJ IDEA 注意（常见报错）

若出现 `找不到或无法加载主类 com.pdwfx.stream.StreamApplication.java`：

1. **Main class 不要带 `.java`**，必须是：`com.pdwfx.stream.StreamApplication`
2. **Use classpath of module** 选 `pdwfx-stream`，不要选 `signal-analysis`（backend）
3. Working directory：`$PROJECT_DIR$/stream`
4. 若模块列表没有 `pdwfx-stream`：右键 `stream/pom.xml` → **Add as Maven Project**，再 Maven Reload

推荐直接用运行配置 **`StreamApplication`**（Maven `spring-boot:run`），避免主类/classpath 配错。

## 接口

- `POST /api/signals/analyze`
  - `multipart/form-data`
  - 参数：
    - `file`：Excel 文件
    - `freqTolerance`：频率容差（默认 `0.5`）

## 工程说明

- 算法流程解耦为独立服务：
  - `ExcelImportService`
  - `SignalAnalysisService`
- 面向 `100k+` 数据场景采用线性/排序分组流程，避免全量笛卡尔匹配
- 详细算法设计见 `docs/algorithm-design.md`
- **人工调试排错**见 `docs/debugging-guide.md`
- 前端多层绘制：
  - 方位轨迹 / 信号强度 / 频率变化 / 拓扑 / 地图
