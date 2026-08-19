package com.pdwfx.stream.model;

import java.time.LocalDateTime;

/**
 * B108 / 定频 PDW 单条解析结果，供流式落盘 CSV 与后续场景/信号分析使用。
 * <p>
 * 字段由 {@link com.pdwfx.stream.k187.K187B108Parser} 从 FFHead + FFData 映射而来。
 * </p>
 */
public class PdwRecord {

    /** 侦获时刻（本地时间）；由 FF 系统时 + qTsc（10µs）换算，并按联调加 8 小时 */
    private LocalDateTime detectTime;

    /** 载频，单位 MHz；原始 pLzx（Hz）÷ 1e6 */
    private double freqMhz;

    /** 真北方位角，单位 °；DOA 融合后再按航向修正，原始 0.1° 计数 ÷ 10 */
    private double azimuthDeg;

    /** 信号幅度，单位 dB；对应 FFData.fD */
    private double signalLevelDb;

    /** 信号带宽，单位 kHz；原始 gMdk（Hz）÷ 1000 */
    private double signalBwKhz;

    /** 平台/测站经度，单位 °；来自 FFHead，原始微度 ÷ 1e6 */
    private double longitude;

    /** 平台/测站纬度，单位 °；来自 FFHead，原始微度 ÷ 1e6 */
    private double latitude;

    /**
     * 信号驻留原始计数，单位 10µs（对应表字段 nSignalTime / nSingnalTime）。
     * 分析侧换算为毫秒：raw × 0.01。
     */
    private long nSignalTime10us;

    /**
     * 信号起始时间原始计数，单位 10µs（对应表字段 nSignalStartTime / qTsc）。
     * 用于相对 FF 基准时刻叠加纳秒偏移，不是驻留时长。
     */
    private long nSignalStartTime10us;

    /** 俯仰角 °（FFHead pitch 原始值 ÷ attitudeScale） */
    private double pitchDeg;

    /** 横滚角 °（FFHead roll 原始值 ÷ attitudeScale） */
    private double rollDeg;

    /** 航向 °（FFHead course ÷ 100，与 DOA 真北修正一致） */
    private double courseDeg;

    public LocalDateTime getDetectTime() { return detectTime; }
    public void setDetectTime(LocalDateTime detectTime) { this.detectTime = detectTime; }
    public double getFreqMhz() { return freqMhz; }
    public void setFreqMhz(double freqMhz) { this.freqMhz = freqMhz; }
    public double getAzimuthDeg() { return azimuthDeg; }
    public void setAzimuthDeg(double azimuthDeg) { this.azimuthDeg = azimuthDeg; }
    public double getSignalLevelDb() { return signalLevelDb; }
    public void setSignalLevelDb(double signalLevelDb) { this.signalLevelDb = signalLevelDb; }
    public double getSignalBwKhz() { return signalBwKhz; }
    public void setSignalBwKhz(double signalBwKhz) { this.signalBwKhz = signalBwKhz; }
    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public long getNSignalTime10us() { return nSignalTime10us; }
    public void setNSignalTime10us(long nSignalTime10us) { this.nSignalTime10us = nSignalTime10us; }
    public long getNSignalStartTime10us() { return nSignalStartTime10us; }
    public void setNSignalStartTime10us(long nSignalStartTime10us) { this.nSignalStartTime10us = nSignalStartTime10us; }
    public double getPitchDeg() { return pitchDeg; }
    public void setPitchDeg(double pitchDeg) { this.pitchDeg = pitchDeg; }
    public double getRollDeg() { return rollDeg; }
    public void setRollDeg(double rollDeg) { this.rollDeg = rollDeg; }
    public double getCourseDeg() { return courseDeg; }
    public void setCourseDeg(double courseDeg) { this.courseDeg = courseDeg; }
}
