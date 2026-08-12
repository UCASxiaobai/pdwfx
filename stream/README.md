# pdwfx-stream（独立流式模块）

与原有 `backend`（信号分析 / 场景筛选）**进程分离**，不修改原有代码路径。

## 能力

1. TCP 监听接收 K187 PDW 二进制流（**仅保存信息类别 B108**；魔数/类型不对的包直接丢弃）  
2. 按 `readPWData` 的 **B108** 逻辑解析定频点  
3. 按**数据时间**滚动 5 分钟落盘为 STANDARD CSV  
4. 单线程调用原服务 `POST /api/signals/analyze`（跳过场景筛选）  
5. 生成批标签（目标类型 / 占空比 / 流量 / 波道 / 频点）供态势页查看  

## 在 IntelliJ IDEA 中运行

本仓库有两个独立 Maven 工程：`backend`（signal-analysis）与 `stream`（pdwfx-stream）。  
IDEA 工程根目录请打开 **`pdwfx`**（不要只打开 `stream` 子目录）。

### 推荐：直接用已有运行配置（Maven）

运行配置里选 **`StreamApplication`**（内部是 `mvn spring-boot:run`，不依赖 IDEA 是否已解析全部依赖）。

1. **Maven** 工具窗口 → 刷新，确认出现 **`pdwfx-stream`** 与 **`signal-analysis`**。  
   - 若没有：右键 `stream/pom.xml` → **Add as Maven Project**（`backend/pom.xml` 同理）。
2. JDK：**File → Project Structure → Project** 选 **1.8**。
3. 先跑 **`SignalAnalysisApplication`**（端口 18080），再跑 **`StreamApplication`**（HTTP 19080 / TCP 19090）。

若要用主类直接跑：选 **`StreamApplication (main)`**，须先完成 Maven 刷新，使模块 `pdwfx-stream` 的依赖呈绿色。

命令行备选：

```bash
cd stream
mvn spring-boot:run
```

## 配置

见 `src/main/resources/application.yml`：

- `stream.tcp.port`：对端推送端口  
- `stream.batch.dir`：落盘根目录（`inbox/processing/done/failed/open`）  
- `stream.batch.duration-minutes`：切批时长（数据时间）  
- `stream.analyze.base-url`：原 backend 地址  

## API

- `GET http://localhost:19080/api/stream/status`  
- `GET http://localhost:19080/api/stream/batches`  
- `GET http://localhost:19080/api/stream/batches/{id}`  

前端入口：`#/stream`（流式态势页）。

## Win7 说明

- 默认落盘、单 worker 串行分析，降低内存与 CPU 峰值  
- JVM 参数见 `pom.xml`：`-Xms256m -Xmx1g`  
