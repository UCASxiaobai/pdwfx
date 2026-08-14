package com.pdwfx.stream.model;

/** 一批（成功建轨的一条目标）标签。 */
public class StreamTrackLabel {
    private String batchId;
    private String streamBatchId;
    private int sceneRank;
    private String sceneType;
    private String analysisId;
    private int networkId;
    private String targetId;
    private String targetType;
    private String targetTypeLabel;
    private double dutyCycle;
    private double trafficSharePct;
    private String channel;
    private String channelLabel;
    private double freqMhz;
    private int detectCount;
    private Long detectStartMs;
    private Long detectEndMs;
    /** 分析目标方位序列均值（度），用于与场景轨迹按方位匹配标注 */
    private Double meanAzimuthDeg;
    private String role;
    private double confidence;
    private String targetTypeReason;
    /** 该目标占用的波道（逗号/顿号分隔，与主流程报表一致） */
    private String targetChannelsUsed;
    private String locateMethod;
    private String locateMethodLabel;

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getStreamBatchId() { return streamBatchId; }
    public void setStreamBatchId(String streamBatchId) { this.streamBatchId = streamBatchId; }
    public int getSceneRank() { return sceneRank; }
    public void setSceneRank(int sceneRank) { this.sceneRank = sceneRank; }
    public String getSceneType() { return sceneType; }
    public void setSceneType(String sceneType) { this.sceneType = sceneType; }
    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }
    public int getNetworkId() { return networkId; }
    public void setNetworkId(int networkId) { this.networkId = networkId; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetTypeLabel() { return targetTypeLabel; }
    public void setTargetTypeLabel(String targetTypeLabel) { this.targetTypeLabel = targetTypeLabel; }
    public double getDutyCycle() { return dutyCycle; }
    public void setDutyCycle(double dutyCycle) { this.dutyCycle = dutyCycle; }
    public double getTrafficSharePct() { return trafficSharePct; }
    public void setTrafficSharePct(double trafficSharePct) { this.trafficSharePct = trafficSharePct; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getChannelLabel() { return channelLabel; }
    public void setChannelLabel(String channelLabel) { this.channelLabel = channelLabel; }
    public double getFreqMhz() { return freqMhz; }
    public void setFreqMhz(double freqMhz) { this.freqMhz = freqMhz; }
    public int getDetectCount() { return detectCount; }
    public void setDetectCount(int detectCount) { this.detectCount = detectCount; }
    public Long getDetectStartMs() { return detectStartMs; }
    public void setDetectStartMs(Long detectStartMs) { this.detectStartMs = detectStartMs; }
    public Long getDetectEndMs() { return detectEndMs; }
    public void setDetectEndMs(Long detectEndMs) { this.detectEndMs = detectEndMs; }
    public Double getMeanAzimuthDeg() { return meanAzimuthDeg; }
    public void setMeanAzimuthDeg(Double meanAzimuthDeg) { this.meanAzimuthDeg = meanAzimuthDeg; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getTargetTypeReason() { return targetTypeReason; }
    public void setTargetTypeReason(String targetTypeReason) { this.targetTypeReason = targetTypeReason; }
    public String getTargetChannelsUsed() { return targetChannelsUsed; }
    public void setTargetChannelsUsed(String targetChannelsUsed) { this.targetChannelsUsed = targetChannelsUsed; }
    public String getLocateMethod() { return locateMethod; }
    public void setLocateMethod(String locateMethod) { this.locateMethod = locateMethod; }
    public String getLocateMethodLabel() { return locateMethodLabel; }
    public void setLocateMethodLabel(String locateMethodLabel) { this.locateMethodLabel = locateMethodLabel; }
}
