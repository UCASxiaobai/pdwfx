package com.scenefinder.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 方位场景发现算法的全部可配置参数。
 * <p>
 * 绑定前缀 {@code scene-finder}，默认值见 {@code application.yml}。
 * API / 命令行传入的非空字段会覆盖此处默认值（见 {@link com.scenefinder.service.SceneFinderService#mergeOptions}）。
 * </p>
 *
 * <h3>参数分组</h3>
 * <ul>
 *   <li><b>时间分帧与建轨</b>：frameSeconds、bearingClusterGapDeg、associationGateDeg、maxMissedFrames、minTrackSeconds、minTrackPoints</li>
 *   <li><b>频段</b>：freqMin/freqMax（可选预过滤）、freqClusterGapMhz（建轨）、sceneFreqBandGapMhz（场景评分同频分组，通常与页面 freqTolerance 一致）</li>
 *   <li><b>场景评分</b>：windowSeconds、windowStepSeconds、minTracksInScene、minSeparationDeg、mergeMaxGapSeconds</li>
 *   <li><b>输出</b>：topKTrackScenes、topKPollingScenes（分项 Top-K）、topKScenes（兼容）、outputDir</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "scene-finder")
public class SceneFinderProperties {

    // ---------- 时间分帧与建轨 ----------

    /**
     * 时间分帧间隔（秒）。同一帧内的检测点一起做方位聚类与跨帧关联。
     * 越小时间分辨率越高，但计算量增大；典型 0.5。
     */
    private double frameSeconds = 0.5;

    /**
     * 单帧内方位聚类间隙（度）。相邻检测点方位差超过该值则分为不同簇（不同方位目标）。
     * 过小会把同一目标拆成多簇；过大可能把相近目标合并。
     */
    private double bearingClusterGapDeg = 4.0;

    /**
     * 跨帧轨迹关联门控（度）。预测方位与当前帧簇方位差超过该值则不关联。
     * 需大于典型帧间方位变化，又小于“换目标”的跳变。
     */
    private double associationGateDeg = 6.0;

    /**
     * 轨迹允许连续丢失的最大帧数。超过则轨迹结束进入“已完成”列表。
     * 与 frameSeconds 相乘可得最长无检测间隔 ≈ maxMissedFrames × frameSeconds 秒。
     */
    private int maxMissedFrames = 10;

    /**
     * 确认轨迹的最短持续时间（秒）。短于此的轨迹丢弃，避免噪声片段进入场景评分。
     */
    private double minTrackSeconds = 30.0;

    /**
     * 确认轨迹的最少关联帧数（命中次数）。与 minTrackSeconds 同时满足才保留。
     */
    private int minTrackPoints = 20;

    /**
     * 跨帧关联允许的最大方位变化率（度/秒）。超过则视为不同目标，避免噪声点被串成陡降轨迹。
     */
    private double maxTrackBearingRateDegPerSec = 12.0;

    /**
     * 建轨后按方位跳变/时间空隙切分片段时，相邻点方位变化率超过此值则断开。
     */
    private double trackJumpSplitDegPerSec = 14.0;

    /** 建轨切分：相邻观测间隔超过此值（秒）则断开。 */
    private double trackJumpSplitGapSec = 4.0;

    // ---------- 场景评分（滑动时间窗） ----------

    /**
     * 场景评分滑动窗口长度（秒）。每个候选场景对应一个 [start, start+windowSeconds] 时间窗。
     */
    private double windowSeconds = 120.0;

    /**
     * 滑动窗口步进（秒）。步进越小候选场景越多、计算越慢；典型为 windowSeconds 的 1/4。
     */
    private double windowStepSeconds = 30.0;

    /**
     * 单个场景内至少需要的轨迹条数。窗口内有效轨迹数少于此值则跳过。
     */
    private int minTracksInScene = 1;

    /**
     * 连续轨迹场景输出条数上限（与轮询分项配额，避免统一 Top-K 被高分轮询占满）。
     */
    private int topKTrackScenes = 50;

    /**
     * 多设备轮询通信场景输出条数上限。
     */
    private int topKPollingScenes = 50;

    /**
     * 兼容旧配置：当 CLI/API 仅传 {@code topKScenes} 且未传分项时，同时作为轨迹与轮询的 Top-K。
     */
    private int topKScenes = 50;

    /**
     * 场景中位方位分离度（度）的软阈值。低于该值时得分会小幅扣分（非硬性过滤）。
     * 用于偏好“多目标方位拉开”的时段，但数据本身分离很小时仍可入选。
     */
    private double minSeparationDeg = 3.0;

    // ---------- 频段（可选预过滤 + 自动分组） ----------

    /**
     * CSV 读取时的频率下限（MHz，列 pl）。仅保留 frequency &gt;= freqMin 的检测点。
     * 设为 0 表示不限制下限；与 freqMax 配合可手动缩小分析范围。
     */
    private double freqMin = 200;

    /**
     * CSV 读取时的频率上限（MHz）。仅保留 frequency &lt;= freqMax 的检测点。
     */
    private double freqMax = 1000;

    /**
     * 建轨阶段的频段/设备聚类间隙（MHz）。检测点先按此间隙划频段，各频段内独立建轨；
     * 跨帧关联时主频差超过该值也不匹配。Web 上传时由页面「频率容差」统一覆盖。
     */
    private double freqClusterGapMhz = 0.01;

    /**
     * 场景评分阶段的同频分组间隙（MHz），与 {@link #freqClusterGapMhz} 对齐。
     * 将轨迹主频按此间隙分成若干同频块，每块内独立滑动窗口评分，输出同频优质场景。
     * Web 上传时由页面「频率容差」统一覆盖。
     */
    private double sceneFreqBandGapMhz = 0.01;

    /**
     * 合并相邻候选场景时允许的最大时间空隙（秒）。两窗口同一频段、轨迹集合相似，
     * 且仅相邻（空隙 ≤ 此值）或重叠时可合并为更长连续场景。
     */
    private double mergeMaxGapSeconds = 30.0;

    // ---------- 多设备轮询通信检测 ----------

    /** 合并为同一「轮次」的最大时间跨度（秒），略大于典型帧间隔。 */
    private double pollingBurstCoalesceSec = 0.5;

    /**
     * 轮次内合并「同一设备重复测向」的方位间隙（度）。
     * 相邻测向差 ≤ 此值视为同一设备；大于则计为不同并发设备（默认 0.5°，贴近测向误差）。
     */
    private double pollingBurstBearingGapDeg = 0.5;

    /** 一轮 burst 内至少需要的并发设备数（可区分方位簇数）。 */
    private int pollingMinBearingsPerBurst = 2;

    /**
     * 一轮内各并发方位簇心之间的最小间隔（度）。用于排除单目标测向噪声被拆成多簇。
     */
    private double pollingMinInterClusterSeparationDeg = 1.0;

    /**
     * 窗内按估计周期对齐、且每轮含 ≥{@link #pollingMinBearingsPerBurst} 点位的轮次下限。
     * 当且仅当该条件满足才视为轮询（排除两条连续轨迹偶然重叠）。
     */
    private int pollingMinAlignedMultiBursts = 5;

    /**
     * 周期轮次中至少需要的稳定方位槽位数（各槽在多个对齐轮次内方位波动 ≤ {@link #pollingMaxSlotBearingStdDeg}）。
     */
    private int pollingMinStableBearingSlots = 2;

    /** 稳定方位槽在单槽内的方位标准差上限（度）。 */
    private double pollingMaxSlotBearingStdDeg = 1.5;

    /**
     * 一轮内方位跨度下限（度）。≤0 表示不启用；>0 时用于排除方位过于集中的一轮。
     * 默认 0：轮询以「多簇 + 非单簇主导 + 周期重复」为主，不要求方位总跨度拉开。
     */
    private double pollingMinBurstBearingSpanDeg = 0.0;

    /**
     * 单方位簇最多占一轮检测点的比例；超过则视为单设备主导（非多设备轮询）。
     */
    private double pollingMaxDominantClusterFraction = 0.72;

    /** 轮询场景评分滑动窗长度（秒）。 */
    private double pollingWindowSeconds = 90.0;

    /** 轮询场景滑动步进（秒）。 */
    private double pollingWindowStepSeconds = 20.0;

    /** 窗内至少需要的有效轮询轮次数。 */
    private int pollingMinBurstsInWindow = 5;

    /** 估计轮询周期的下限（秒）。 */
    private double pollingPeriodMinSec = 5.0;

    /** 估计轮询周期的上限（秒）。 */
    private double pollingPeriodMaxSec = 20.0;

    /** 周期间隔相对中位数的容差比例（如 0.15 表示 ±15%）。 */
    private double pollingPeriodToleranceRatio = 0.18;

    /** 周期间隔一致性最低得分（0~1），低于则丢弃候选窗。 */
    private double pollingMinPeriodicityScore = 0.55;

    /**
     * 单个方位槽位/轮询目标在场景时间窗轮次中至少出现的比例（0~1）。
     * 默认 0.8：统计窗内对齐轮次数 N，仅保留在至少 0.8×N 轮中出现的点位。
     */
    private double pollingMinSlotRoundCoverageRatio = 0.8;

    /**
     * 融合去重：同类型两窗时间重叠占较短窗比例超过该值，且轨迹/周期内容相似，才丢弃低分者。
     * 默认 0.92；勿设过低（如 0.65），否则滑动窗步进 30s、窗长 120s 时相邻窗会被链式删光。
     */
    private double sceneFusionOverlapSuppressRatio = 0.92;

    /**
     * 轮询散点图每个场景最多绘制的检测点数（超出则等间隔抽样），避免全频段大数据堆内存。
     */
    private int maxVisualizationScatterPoints = 6000;

    /**
     * analyze API 响应内联 visualization JSON 的检测点上限；超出则仅写 visualization-data.json，由前端按需拉取。
     */
    private int inlineVisualizationMaxDetections = 200000;

    /**
     * 是否在场景筛选结果中构建全量频段散点（importScatter）。关闭可显著加快上传后预筛速度。
     */
    private boolean enableImportScatter = false;

    /**
     * 全段时间窗：每个频段只对 [minTime,maxTime] 评一次分，不做滑动窗。
     * 流式封批分析应开启；事后主流程默认关闭。
     */
    private boolean fullSpanWindow = false;

    // ---------- 输出 ----------

    /**
     * 分析结果输出目录（相对或绝对路径）。生成 scene_summary.csv、tracks.csv、visualization.html 等。
     */
    private String outputDir = "./output";

    public double getFrameSeconds() {
        return frameSeconds;
    }

    public void setFrameSeconds(double frameSeconds) {
        this.frameSeconds = frameSeconds;
    }

    public double getBearingClusterGapDeg() {
        return bearingClusterGapDeg;
    }

    public void setBearingClusterGapDeg(double bearingClusterGapDeg) {
        this.bearingClusterGapDeg = bearingClusterGapDeg;
    }

    public double getAssociationGateDeg() {
        return associationGateDeg;
    }

    public void setAssociationGateDeg(double associationGateDeg) {
        this.associationGateDeg = associationGateDeg;
    }

    public int getMaxMissedFrames() {
        return maxMissedFrames;
    }

    public void setMaxMissedFrames(int maxMissedFrames) {
        this.maxMissedFrames = maxMissedFrames;
    }

    public double getMinTrackSeconds() {
        return minTrackSeconds;
    }

    public void setMinTrackSeconds(double minTrackSeconds) {
        this.minTrackSeconds = minTrackSeconds;
    }

    public int getMinTrackPoints() {
        return minTrackPoints;
    }

    public void setMinTrackPoints(int minTrackPoints) {
        this.minTrackPoints = minTrackPoints;
    }

    public double getMaxTrackBearingRateDegPerSec() {
        return maxTrackBearingRateDegPerSec;
    }

    public void setMaxTrackBearingRateDegPerSec(double maxTrackBearingRateDegPerSec) {
        this.maxTrackBearingRateDegPerSec = maxTrackBearingRateDegPerSec;
    }

    public double getTrackJumpSplitDegPerSec() {
        return trackJumpSplitDegPerSec;
    }

    public void setTrackJumpSplitDegPerSec(double trackJumpSplitDegPerSec) {
        this.trackJumpSplitDegPerSec = trackJumpSplitDegPerSec;
    }

    public double getTrackJumpSplitGapSec() {
        return trackJumpSplitGapSec;
    }

    public void setTrackJumpSplitGapSec(double trackJumpSplitGapSec) {
        this.trackJumpSplitGapSec = trackJumpSplitGapSec;
    }

    public double getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(double windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public double getWindowStepSeconds() {
        return windowStepSeconds;
    }

    public void setWindowStepSeconds(double windowStepSeconds) {
        this.windowStepSeconds = windowStepSeconds;
    }

    public int getMinTracksInScene() {
        return minTracksInScene;
    }

    public void setMinTracksInScene(int minTracksInScene) {
        this.minTracksInScene = minTracksInScene;
    }

    public int getTopKTrackScenes() {
        return topKTrackScenes;
    }

    public void setTopKTrackScenes(int topKTrackScenes) {
        this.topKTrackScenes = topKTrackScenes;
    }

    public int getTopKPollingScenes() {
        return topKPollingScenes;
    }

    public void setTopKPollingScenes(int topKPollingScenes) {
        this.topKPollingScenes = topKPollingScenes;
    }

    public int getTopKScenes() {
        return topKScenes;
    }

    public void setTopKScenes(int topKScenes) {
        this.topKScenes = topKScenes;
    }

    /** 将 source 中全部算法参数复制到本对象（用于 mergeOptions 基线）。 */
    public void copyFrom(SceneFinderProperties source) {
        setFrameSeconds(source.getFrameSeconds());
        setBearingClusterGapDeg(source.getBearingClusterGapDeg());
        setAssociationGateDeg(source.getAssociationGateDeg());
        setMaxMissedFrames(source.getMaxMissedFrames());
        setMinTrackSeconds(source.getMinTrackSeconds());
        setMinTrackPoints(source.getMinTrackPoints());
        setMaxTrackBearingRateDegPerSec(source.getMaxTrackBearingRateDegPerSec());
        setTrackJumpSplitDegPerSec(source.getTrackJumpSplitDegPerSec());
        setTrackJumpSplitGapSec(source.getTrackJumpSplitGapSec());
        setWindowSeconds(source.getWindowSeconds());
        setWindowStepSeconds(source.getWindowStepSeconds());
        setMinTracksInScene(source.getMinTracksInScene());
        setTopKTrackScenes(source.getTopKTrackScenes());
        setTopKPollingScenes(source.getTopKPollingScenes());
        setTopKScenes(source.getTopKScenes());
        setMinSeparationDeg(source.getMinSeparationDeg());
        setFreqMin(source.getFreqMin());
        setFreqMax(source.getFreqMax());
        setFreqClusterGapMhz(source.getFreqClusterGapMhz());
        setSceneFreqBandGapMhz(source.getSceneFreqBandGapMhz());
        setMergeMaxGapSeconds(source.getMergeMaxGapSeconds());
        setPollingBurstCoalesceSec(source.getPollingBurstCoalesceSec());
        setPollingBurstBearingGapDeg(source.getPollingBurstBearingGapDeg());
        setPollingMinBearingsPerBurst(source.getPollingMinBearingsPerBurst());
        setPollingMinInterClusterSeparationDeg(source.getPollingMinInterClusterSeparationDeg());
        setPollingMinAlignedMultiBursts(source.getPollingMinAlignedMultiBursts());
        setPollingMinStableBearingSlots(source.getPollingMinStableBearingSlots());
        setPollingMaxSlotBearingStdDeg(source.getPollingMaxSlotBearingStdDeg());
        setPollingMinBurstBearingSpanDeg(source.getPollingMinBurstBearingSpanDeg());
        setPollingMaxDominantClusterFraction(source.getPollingMaxDominantClusterFraction());
        setPollingWindowSeconds(source.getPollingWindowSeconds());
        setPollingWindowStepSeconds(source.getPollingWindowStepSeconds());
        setPollingMinBurstsInWindow(source.getPollingMinBurstsInWindow());
        setPollingPeriodMinSec(source.getPollingPeriodMinSec());
        setPollingPeriodMaxSec(source.getPollingPeriodMaxSec());
        setPollingPeriodToleranceRatio(source.getPollingPeriodToleranceRatio());
        setPollingMinPeriodicityScore(source.getPollingMinPeriodicityScore());
        setPollingMinSlotRoundCoverageRatio(source.getPollingMinSlotRoundCoverageRatio());
        setSceneFusionOverlapSuppressRatio(source.getSceneFusionOverlapSuppressRatio());
        setMaxVisualizationScatterPoints(source.getMaxVisualizationScatterPoints());
        setInlineVisualizationMaxDetections(source.getInlineVisualizationMaxDetections());
        setEnableImportScatter(source.isEnableImportScatter());
        setFullSpanWindow(source.isFullSpanWindow());
        setOutputDir(source.getOutputDir());
    }

    public double getMinSeparationDeg() {
        return minSeparationDeg;
    }

    public void setMinSeparationDeg(double minSeparationDeg) {
        this.minSeparationDeg = minSeparationDeg;
    }

    public double getFreqMin() {
        return freqMin;
    }

    public void setFreqMin(double freqMin) {
        this.freqMin = freqMin;
    }

    public double getFreqMax() {
        return freqMax;
    }

    public void setFreqMax(double freqMax) {
        this.freqMax = freqMax;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public double getFreqClusterGapMhz() {
        return freqClusterGapMhz;
    }

    public void setFreqClusterGapMhz(double freqClusterGapMhz) {
        this.freqClusterGapMhz = freqClusterGapMhz;
    }

    public double getSceneFreqBandGapMhz() {
        return sceneFreqBandGapMhz;
    }

    public void setSceneFreqBandGapMhz(double sceneFreqBandGapMhz) {
        this.sceneFreqBandGapMhz = sceneFreqBandGapMhz;
    }

    public double getMergeMaxGapSeconds() {
        return mergeMaxGapSeconds;
    }

    public void setMergeMaxGapSeconds(double mergeMaxGapSeconds) {
        this.mergeMaxGapSeconds = mergeMaxGapSeconds;
    }

    public double getPollingBurstCoalesceSec() {
        return pollingBurstCoalesceSec;
    }

    public void setPollingBurstCoalesceSec(double pollingBurstCoalesceSec) {
        this.pollingBurstCoalesceSec = pollingBurstCoalesceSec;
    }

    public double getPollingBurstBearingGapDeg() {
        return pollingBurstBearingGapDeg;
    }

    public void setPollingBurstBearingGapDeg(double pollingBurstBearingGapDeg) {
        this.pollingBurstBearingGapDeg = pollingBurstBearingGapDeg;
    }

    public int getPollingMinBearingsPerBurst() {
        return pollingMinBearingsPerBurst;
    }

    public void setPollingMinBearingsPerBurst(int pollingMinBearingsPerBurst) {
        this.pollingMinBearingsPerBurst = pollingMinBearingsPerBurst;
    }

    public double getPollingMinInterClusterSeparationDeg() {
        return pollingMinInterClusterSeparationDeg;
    }

    public void setPollingMinInterClusterSeparationDeg(double pollingMinInterClusterSeparationDeg) {
        this.pollingMinInterClusterSeparationDeg = pollingMinInterClusterSeparationDeg;
    }

    public int getPollingMinAlignedMultiBursts() {
        return pollingMinAlignedMultiBursts;
    }

    public void setPollingMinAlignedMultiBursts(int pollingMinAlignedMultiBursts) {
        this.pollingMinAlignedMultiBursts = pollingMinAlignedMultiBursts;
    }

    public int getPollingMinStableBearingSlots() {
        return pollingMinStableBearingSlots;
    }

    public void setPollingMinStableBearingSlots(int pollingMinStableBearingSlots) {
        this.pollingMinStableBearingSlots = pollingMinStableBearingSlots;
    }

    public double getPollingMaxSlotBearingStdDeg() {
        return pollingMaxSlotBearingStdDeg;
    }

    public void setPollingMaxSlotBearingStdDeg(double pollingMaxSlotBearingStdDeg) {
        this.pollingMaxSlotBearingStdDeg = pollingMaxSlotBearingStdDeg;
    }

    public double getPollingMinBurstBearingSpanDeg() {
        return pollingMinBurstBearingSpanDeg;
    }

    public void setPollingMinBurstBearingSpanDeg(double pollingMinBurstBearingSpanDeg) {
        this.pollingMinBurstBearingSpanDeg = pollingMinBurstBearingSpanDeg;
    }

    public double getPollingMaxDominantClusterFraction() {
        return pollingMaxDominantClusterFraction;
    }

    public void setPollingMaxDominantClusterFraction(double pollingMaxDominantClusterFraction) {
        this.pollingMaxDominantClusterFraction = pollingMaxDominantClusterFraction;
    }

    public double getPollingWindowSeconds() {
        return pollingWindowSeconds;
    }

    public void setPollingWindowSeconds(double pollingWindowSeconds) {
        this.pollingWindowSeconds = pollingWindowSeconds;
    }

    public double getPollingWindowStepSeconds() {
        return pollingWindowStepSeconds;
    }

    public void setPollingWindowStepSeconds(double pollingWindowStepSeconds) {
        this.pollingWindowStepSeconds = pollingWindowStepSeconds;
    }

    public int getPollingMinBurstsInWindow() {
        return pollingMinBurstsInWindow;
    }

    public void setPollingMinBurstsInWindow(int pollingMinBurstsInWindow) {
        this.pollingMinBurstsInWindow = pollingMinBurstsInWindow;
    }

    public double getPollingPeriodMinSec() {
        return pollingPeriodMinSec;
    }

    public void setPollingPeriodMinSec(double pollingPeriodMinSec) {
        this.pollingPeriodMinSec = pollingPeriodMinSec;
    }

    public double getPollingPeriodMaxSec() {
        return pollingPeriodMaxSec;
    }

    public void setPollingPeriodMaxSec(double pollingPeriodMaxSec) {
        this.pollingPeriodMaxSec = pollingPeriodMaxSec;
    }

    public double getPollingPeriodToleranceRatio() {
        return pollingPeriodToleranceRatio;
    }

    public void setPollingPeriodToleranceRatio(double pollingPeriodToleranceRatio) {
        this.pollingPeriodToleranceRatio = pollingPeriodToleranceRatio;
    }

    public double getPollingMinPeriodicityScore() {
        return pollingMinPeriodicityScore;
    }

    public void setPollingMinPeriodicityScore(double pollingMinPeriodicityScore) {
        this.pollingMinPeriodicityScore = pollingMinPeriodicityScore;
    }

    public double getPollingMinSlotRoundCoverageRatio() {
        return pollingMinSlotRoundCoverageRatio;
    }

    public void setPollingMinSlotRoundCoverageRatio(double pollingMinSlotRoundCoverageRatio) {
        this.pollingMinSlotRoundCoverageRatio = pollingMinSlotRoundCoverageRatio;
    }

    public double getSceneFusionOverlapSuppressRatio() {
        return sceneFusionOverlapSuppressRatio;
    }

    public void setSceneFusionOverlapSuppressRatio(double sceneFusionOverlapSuppressRatio) {
        this.sceneFusionOverlapSuppressRatio = sceneFusionOverlapSuppressRatio;
    }

    public int getMaxVisualizationScatterPoints() {
        return maxVisualizationScatterPoints;
    }

    public void setMaxVisualizationScatterPoints(int maxVisualizationScatterPoints) {
        this.maxVisualizationScatterPoints = maxVisualizationScatterPoints;
    }

    public int getInlineVisualizationMaxDetections() {
        return inlineVisualizationMaxDetections;
    }

    public void setInlineVisualizationMaxDetections(int inlineVisualizationMaxDetections) {
        this.inlineVisualizationMaxDetections = inlineVisualizationMaxDetections;
    }

    public boolean isEnableImportScatter() {
        return enableImportScatter;
    }

    public void setEnableImportScatter(boolean enableImportScatter) {
        this.enableImportScatter = enableImportScatter;
    }

    public boolean isFullSpanWindow() {
        return fullSpanWindow;
    }

    public void setFullSpanWindow(boolean fullSpanWindow) {
        this.fullSpanWindow = fullSpanWindow;
    }
}
