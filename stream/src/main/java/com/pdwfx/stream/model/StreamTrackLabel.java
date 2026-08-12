package com.pdwfx.stream.model;

/** 一批（成功建轨的一条目标）标签。 */
public class StreamTrackLabel {
    private String batchId;
    private String streamBatchId;
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

    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getStreamBatchId() { return streamBatchId; }
    public void setStreamBatchId(String streamBatchId) { this.streamBatchId = streamBatchId; }
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
}
