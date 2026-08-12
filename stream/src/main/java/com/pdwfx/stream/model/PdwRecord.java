package com.pdwfx.stream.model;

import java.time.LocalDateTime;

/** B108 解析后的单条定频 PDW（落盘/分析输入）。 */
public class PdwRecord {
    private LocalDateTime detectTime;
    private double freqMhz;
    private double azimuthDeg;
    private double signalLevelDb;
    private double signalBwKhz;
    private double longitude;
    private double latitude;
    private long nSignalTime10us;
    private long nSignalStartTime10us;

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
}
