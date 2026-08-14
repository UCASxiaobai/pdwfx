package com.scenefinder.service;

/**
 * 分析参数覆盖项（CLI / REST 未传字段为 null，与 application.yml 合并）。
 */
public class AnalyzeOptions {

    private final Double frameSeconds;
    private final Double bearingClusterGapDeg;
    private final Double associationGateDeg;
    private final Integer maxMissedFrames;
    private final Double minTrackSeconds;
    private final Integer minTrackPoints;
    private final Double windowSeconds;
    private final Double windowStepSeconds;
    private final Integer minTracksInScene;
    private final Integer topKScenes;
    private final Integer topKTrackScenes;
    private final Integer topKPollingScenes;
    private final Double minSeparationDeg;
    private final Double freqMin;
    private final Double freqMax;
    /** 同频容差：同时用于建轨分频与场景评分分频（与信号分析 freqTolerance 一致） */
    private final Double freqTolerance;
    private final Double freqClusterGapMhz;
    private final Double sceneFreqBandGapMhz;
    private final Double mergeMaxGapSeconds;
    private final String outputDir;
    /** 是否构建全量频段散点（importScatter），关闭可加快场景筛选 */
    private final Boolean enableImportScatter;
    /**
     * 流式全段窗：每个频段只评 [minTime,maxTime] 一次，不做滑动窗。
     * null 表示不覆盖配置默认值。
     */
    private final Boolean fullSpanWindow;

    public AnalyzeOptions(
            Double frameSeconds,
            Double bearingClusterGapDeg,
            Double associationGateDeg,
            Integer maxMissedFrames,
            Double minTrackSeconds,
            Integer minTrackPoints,
            Double windowSeconds,
            Double windowStepSeconds,
            Integer minTracksInScene,
            Integer topKScenes,
            Integer topKTrackScenes,
            Integer topKPollingScenes,
            Double minSeparationDeg,
            Double freqMin,
            Double freqMax,
            Double freqTolerance,
            Double freqClusterGapMhz,
            Double sceneFreqBandGapMhz,
            Double mergeMaxGapSeconds,
            String outputDir,
            Boolean enableImportScatter,
            Boolean fullSpanWindow
    ) {
        this.frameSeconds = frameSeconds;
        this.bearingClusterGapDeg = bearingClusterGapDeg;
        this.associationGateDeg = associationGateDeg;
        this.maxMissedFrames = maxMissedFrames;
        this.minTrackSeconds = minTrackSeconds;
        this.minTrackPoints = minTrackPoints;
        this.windowSeconds = windowSeconds;
        this.windowStepSeconds = windowStepSeconds;
        this.minTracksInScene = minTracksInScene;
        this.topKScenes = topKScenes;
        this.topKTrackScenes = topKTrackScenes;
        this.topKPollingScenes = topKPollingScenes;
        this.minSeparationDeg = minSeparationDeg;
        this.freqMin = freqMin;
        this.freqMax = freqMax;
        this.freqTolerance = freqTolerance;
        this.freqClusterGapMhz = freqClusterGapMhz;
        this.sceneFreqBandGapMhz = sceneFreqBandGapMhz;
        this.mergeMaxGapSeconds = mergeMaxGapSeconds;
        this.outputDir = outputDir;
        this.enableImportScatter = enableImportScatter;
        this.fullSpanWindow = fullSpanWindow;
    }

    public static AnalyzeOptions defaults() {
        return new AnalyzeOptions(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    public Double getFrameSeconds() {
        return frameSeconds;
    }

    public Double getBearingClusterGapDeg() {
        return bearingClusterGapDeg;
    }

    public Double getAssociationGateDeg() {
        return associationGateDeg;
    }

    public Integer getMaxMissedFrames() {
        return maxMissedFrames;
    }

    public Double getMinTrackSeconds() {
        return minTrackSeconds;
    }

    public Integer getMinTrackPoints() {
        return minTrackPoints;
    }

    public Double getWindowSeconds() {
        return windowSeconds;
    }

    public Double getWindowStepSeconds() {
        return windowStepSeconds;
    }

    public Integer getMinTracksInScene() {
        return minTracksInScene;
    }

    public Integer getTopKScenes() {
        return topKScenes;
    }

    public Integer getTopKTrackScenes() {
        return topKTrackScenes;
    }

    public Integer getTopKPollingScenes() {
        return topKPollingScenes;
    }

    public Double getMinSeparationDeg() {
        return minSeparationDeg;
    }

    public Double getFreqMin() {
        return freqMin;
    }

    public Double getFreqMax() {
        return freqMax;
    }

    public Double getFreqTolerance() {
        return freqTolerance;
    }

    public Double getFreqClusterGapMhz() {
        return freqClusterGapMhz;
    }

    public Double getSceneFreqBandGapMhz() {
        return sceneFreqBandGapMhz;
    }

    public Double getMergeMaxGapSeconds() {
        return mergeMaxGapSeconds;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public Boolean getEnableImportScatter() {
        return enableImportScatter;
    }

    public Boolean getFullSpanWindow() {
        return fullSpanWindow;
    }
}
