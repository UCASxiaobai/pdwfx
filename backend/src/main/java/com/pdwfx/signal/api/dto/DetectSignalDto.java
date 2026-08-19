package com.pdwfx.signal.api.dto;

/**
 * 外部接口单条侦获记录（JSON）。每条可带不同 {@link #freq}，一批数据即多频输入。
 * <p>
 * 由 {@link com.pdwfx.signal.api.SignalInputMapper} 转为内部 {@link com.pdwfx.signal.model.DetectSignal}。
 * </p>
 */
public class DetectSignalDto {

    /** 可选业务 ID；缺省时映射为 ROW-行号 */
    private String id;

    /** 侦测时间戳，单位 ms；与 {@link #detectTime} 二选一 */
    private Long detectTimesss;

    /** ISO 或 {@code yyyy-MM-dd HH:mm:ss[.SSS]} 时间串；表格源字段 zcsj */
    private String detectTime;

    /** 频率 MHz，必填 */
    private Double freq;

    /** 方位 °，必填 */
    private Double azimuth;

    /** 幅度 dB，必填 */
    private Double signalLevel;

    /** 信噪比（可选） */
    private Double snr;

    /** 驻留 ms；若未传而提供 {@link #nSignalTime10us} 则自动换算 */
    private Double signalDwellMs;

    /** nSignalTime 原始值，单位 10µs（×0.01 → ms） */
    private Long nSignalTime10us;

    /** 目标定位经度 °（可选，误差椭圆） */
    private Double targetLon;

    /** 目标定位纬度 °（可选，误差椭圆） */
    private Double targetLat;

    /** 测站/平台经度 °（可选） */
    private Double longitude;

    /** 测站/平台纬度 °（可选） */
    private Double latitude;

    /** 调制样式（可选） */
    private String modulateStyle;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getDetectTimesss() { return detectTimesss; }
    public void setDetectTimesss(Long detectTimesss) { this.detectTimesss = detectTimesss; }
    public String getDetectTime() { return detectTime; }
    public void setDetectTime(String detectTime) { this.detectTime = detectTime; }
    public Double getFreq() { return freq; }
    public void setFreq(Double freq) { this.freq = freq; }
    public Double getAzimuth() { return azimuth; }
    public void setAzimuth(Double azimuth) { this.azimuth = azimuth; }
    public Double getSignalLevel() { return signalLevel; }
    public void setSignalLevel(Double signalLevel) { this.signalLevel = signalLevel; }
    public Double getSnr() { return snr; }
    public void setSnr(Double snr) { this.snr = snr; }
    public Double getSignalDwellMs() { return signalDwellMs; }
    public void setSignalDwellMs(Double signalDwellMs) { this.signalDwellMs = signalDwellMs; }
    public Long getNSignalTime10us() { return nSignalTime10us; }
    public void setNSignalTime10us(Long nSignalTime10us) { this.nSignalTime10us = nSignalTime10us; }
    public Double getTargetLon() { return targetLon; }
    public void setTargetLon(Double targetLon) { this.targetLon = targetLon; }
    public Double getTargetLat() { return targetLat; }
    public void setTargetLat(Double targetLat) { this.targetLat = targetLat; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public String getModulateStyle() { return modulateStyle; }
    public void setModulateStyle(String modulateStyle) { this.modulateStyle = modulateStyle; }
}
