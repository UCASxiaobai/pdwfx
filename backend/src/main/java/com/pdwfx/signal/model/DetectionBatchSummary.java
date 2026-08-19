package com.pdwfx.signal.model;

/**
 * 一个编批（同一目标）的摘要，便于外部前端按目标切换。
 */
public class DetectionBatchSummary {

    private int batchId;
    private String targetId;
    private int networkId;
    private String targetType;
    private String targetTypeLabel;
    private String channel;
    private String channelLabel;
    private double freqMhz;
    private int detectCount;

    public int getBatchId() {
        return batchId;
    }

    public void setBatchId(int batchId) {
        this.batchId = batchId;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public int getNetworkId() {
        return networkId;
    }

    public void setNetworkId(int networkId) {
        this.networkId = networkId;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetTypeLabel() {
        return targetTypeLabel;
    }

    public void setTargetTypeLabel(String targetTypeLabel) {
        this.targetTypeLabel = targetTypeLabel;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getChannelLabel() {
        return channelLabel;
    }

    public void setChannelLabel(String channelLabel) {
        this.channelLabel = channelLabel;
    }

    public double getFreqMhz() {
        return freqMhz;
    }

    public void setFreqMhz(double freqMhz) {
        this.freqMhz = freqMhz;
    }

    public int getDetectCount() {
        return detectCount;
    }

    public void setDetectCount(int detectCount) {
        this.detectCount = detectCount;
    }
}
