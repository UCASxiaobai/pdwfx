package com.scenefinder.model;

/**
 * 场景转发 + 信号分析后的扁平化一行（网络 × 目标），供前端表格、筛选与报告导出。
 */
public class AnalysisReportRow {

    private int sceneRank;
    private String sceneType;
    private String windowStart;
    private String windowEnd;
    private double sceneFreqCenterMhz;
    private double sceneFreqMinMhz;
    private double sceneFreqMaxMhz;
    private double sceneScore;

    private String analysisId;
    private int networkId;
    private double networkFreqMhz;
    private String commMode;
    private String networkType;
    private String commLinkChannel;
    private String commLinkChannelLabel;
    private int signalCount;
    private int networkTargetCount;

    private String targetId;
    private String targetType;
    private String role;
    private double confidence;
    private double emissionSharePct;
    private Double periodMs;
    private Double burstDurationMeanMs;
    private Double avgDutyCycle;
    private String convergence;

    private Double targetLocateLon;
    private Double targetLocateLat;
    private String locateMethod;
    private String locateMethodLabel;
    private String detectStartTime;
    private String detectEndTime;
    private int detectCount;
    /** 该目标在报表范围内占用的全部波道（逗号分隔） */
    private String targetChannelsUsed;

    public int getSceneRank() {
        return sceneRank;
    }

    public void setSceneRank(int sceneRank) {
        this.sceneRank = sceneRank;
    }

    public String getSceneType() {
        return sceneType;
    }

    public void setSceneType(String sceneType) {
        this.sceneType = sceneType;
    }

    public String getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(String windowStart) {
        this.windowStart = windowStart;
    }

    public String getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(String windowEnd) {
        this.windowEnd = windowEnd;
    }

    public double getSceneFreqCenterMhz() {
        return sceneFreqCenterMhz;
    }

    public void setSceneFreqCenterMhz(double sceneFreqCenterMhz) {
        this.sceneFreqCenterMhz = sceneFreqCenterMhz;
    }

    public double getSceneFreqMinMhz() {
        return sceneFreqMinMhz;
    }

    public void setSceneFreqMinMhz(double sceneFreqMinMhz) {
        this.sceneFreqMinMhz = sceneFreqMinMhz;
    }

    public double getSceneFreqMaxMhz() {
        return sceneFreqMaxMhz;
    }

    public void setSceneFreqMaxMhz(double sceneFreqMaxMhz) {
        this.sceneFreqMaxMhz = sceneFreqMaxMhz;
    }

    public double getSceneScore() {
        return sceneScore;
    }

    public void setSceneScore(double sceneScore) {
        this.sceneScore = sceneScore;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(String analysisId) {
        this.analysisId = analysisId;
    }

    public int getNetworkId() {
        return networkId;
    }

    public void setNetworkId(int networkId) {
        this.networkId = networkId;
    }

    public double getNetworkFreqMhz() {
        return networkFreqMhz;
    }

    public void setNetworkFreqMhz(double networkFreqMhz) {
        this.networkFreqMhz = networkFreqMhz;
    }

    public String getCommMode() {
        return commMode;
    }

    public void setCommMode(String commMode) {
        this.commMode = commMode;
    }

    public String getNetworkType() {
        return networkType;
    }

    public void setNetworkType(String networkType) {
        this.networkType = networkType;
    }

    public String getCommLinkChannel() {
        return commLinkChannel;
    }

    public void setCommLinkChannel(String commLinkChannel) {
        this.commLinkChannel = commLinkChannel;
    }

    public String getCommLinkChannelLabel() {
        return commLinkChannelLabel;
    }

    public void setCommLinkChannelLabel(String commLinkChannelLabel) {
        this.commLinkChannelLabel = commLinkChannelLabel;
    }

    public int getSignalCount() {
        return signalCount;
    }

    public void setSignalCount(int signalCount) {
        this.signalCount = signalCount;
    }

    public int getNetworkTargetCount() {
        return networkTargetCount;
    }

    public void setNetworkTargetCount(int networkTargetCount) {
        this.networkTargetCount = networkTargetCount;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public double getEmissionSharePct() {
        return emissionSharePct;
    }

    public void setEmissionSharePct(double emissionSharePct) {
        this.emissionSharePct = emissionSharePct;
    }

    public Double getPeriodMs() {
        return periodMs;
    }

    public void setPeriodMs(Double periodMs) {
        this.periodMs = periodMs;
    }

    public Double getBurstDurationMeanMs() {
        return burstDurationMeanMs;
    }

    public void setBurstDurationMeanMs(Double burstDurationMeanMs) {
        this.burstDurationMeanMs = burstDurationMeanMs;
    }

    public Double getAvgDutyCycle() {
        return avgDutyCycle;
    }

    public void setAvgDutyCycle(Double avgDutyCycle) {
        this.avgDutyCycle = avgDutyCycle;
    }

    public String getConvergence() {
        return convergence;
    }

    public void setConvergence(String convergence) {
        this.convergence = convergence;
    }

    public Double getTargetLocateLon() {
        return targetLocateLon;
    }

    public void setTargetLocateLon(Double targetLocateLon) {
        this.targetLocateLon = targetLocateLon;
    }

    public Double getTargetLocateLat() {
        return targetLocateLat;
    }

    public void setTargetLocateLat(Double targetLocateLat) {
        this.targetLocateLat = targetLocateLat;
    }

    public String getLocateMethod() {
        return locateMethod;
    }

    public void setLocateMethod(String locateMethod) {
        this.locateMethod = locateMethod;
    }

    public String getLocateMethodLabel() {
        return locateMethodLabel;
    }

    public void setLocateMethodLabel(String locateMethodLabel) {
        this.locateMethodLabel = locateMethodLabel;
    }

    public String getDetectStartTime() {
        return detectStartTime;
    }

    public void setDetectStartTime(String detectStartTime) {
        this.detectStartTime = detectStartTime;
    }

    public String getDetectEndTime() {
        return detectEndTime;
    }

    public void setDetectEndTime(String detectEndTime) {
        this.detectEndTime = detectEndTime;
    }

    public int getDetectCount() {
        return detectCount;
    }

    public void setDetectCount(int detectCount) {
        this.detectCount = detectCount;
    }

    public String getTargetChannelsUsed() {
        return targetChannelsUsed;
    }

    public void setTargetChannelsUsed(String targetChannelsUsed) {
        this.targetChannelsUsed = targetChannelsUsed;
    }
}
