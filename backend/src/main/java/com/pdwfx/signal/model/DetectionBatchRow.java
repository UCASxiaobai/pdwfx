package com.pdwfx.signal.model;

/**
 * 单条侦测的编批结果，供外部前端按目标着色/筛选。
 * 同一 {@code batchId} 表示同一目标。
 */
public class DetectionBatchRow {

    /** 编批号，从 1 起；同一目标相同。未编入任何目标时为 0 */
    private int batchId;
    /** 网内目标编号，如 T1；未编批时为空 */
    private String targetId;
    private int networkId;
    /** 侦测时间戳（毫秒，对应 zcsj） */
    private long detectTimeMs;
    /** 侦测时间（本地 ISO，含毫秒） */
    private String detectTime;
    /** 侦测方位（度） */
    private double azimuthDeg;
    /** 频率（MHz） */
    private double freqMhz;
    /** AIR / AWACS / GROUND；未编批时为空 */
    private String targetType;
    /** 飞机 / 预警机 / 地面站 */
    private String targetTypeLabel;
    /** 占用波道编码 D01–D06 / UNKNOWN */
    private String channel;
    /** 波道中文名 */
    private String channelLabel;
    /** 源记录 ID（如 ROW-n） */
    private String signalId;

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

    public long getDetectTimeMs() {
        return detectTimeMs;
    }

    public void setDetectTimeMs(long detectTimeMs) {
        this.detectTimeMs = detectTimeMs;
    }

    public String getDetectTime() {
        return detectTime;
    }

    public void setDetectTime(String detectTime) {
        this.detectTime = detectTime;
    }

    public double getAzimuthDeg() {
        return azimuthDeg;
    }

    public void setAzimuthDeg(double azimuthDeg) {
        this.azimuthDeg = azimuthDeg;
    }

    public double getFreqMhz() {
        return freqMhz;
    }

    public void setFreqMhz(double freqMhz) {
        this.freqMhz = freqMhz;
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

    public String getSignalId() {
        return signalId;
    }

    public void setSignalId(String signalId) {
        this.signalId = signalId;
    }
}
