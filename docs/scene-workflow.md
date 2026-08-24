# 场景筛选与信号分析一体化流程

## 运行

```powershell
cd backend
mvn spring-boot:run

cd frontend
npm install
npm run dev
```

浏览器打开 `http://localhost:5173`，单页完成上传 → 场景筛选 → 信号分析 → 结果查看。

## 流程

1. **上传**：选择单个/多个 CSV，或选择文件夹（上传其中全部 `.csv`）。
2. **场景参数**：默认**全段时间窗**（与流式态势相同：各频段按数据实际跨度评一次）；可关掉后改用滑动窗。频段、TOP-K 等。
3. **信号参数**：频率容差、是否预加载全部网络详情。
4. **开始分析**：场景筛选后，各场景导出 CSV 自动作为信号分析输入。
5. **查看**：汇总表格/统计图（筛选、导出 Word）+ 按场景/网络浏览研判图表。

## 主要 API

| 接口 | 说明 |
|------|------|
| `POST /api/scenes/analyze-upload` | 多文件 `files` + 筛选参数 |
| `POST /api/scenes/forward-analyze` | 场景转发信号分析 |
| `POST /api/scenes/build-report` | 聚合目标级明细行 |
