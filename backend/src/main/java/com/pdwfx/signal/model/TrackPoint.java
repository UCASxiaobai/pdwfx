package com.pdwfx.signal.model;

public class TrackPoint {
    private long t;
    private Double platformLon;
    private Double platformLat;
    private Double targetLon;
    private Double targetLat;
    private double azimuth;
    /** 测向批号（如 1139@589.0000） */
    private String batchId;
    private double freq;
    private double signalLevel;
    private double signalDwellMs;

    public long getT() { return t; }
    public void setT(long t) { this.t = t; }
    public Double getPlatformLon() { return platformLon; }
    public void setPlatformLon(Double platformLon) { this.platformLon = platformLon; }
    public Double getPlatformLat() { return platformLat; }
    public void setPlatformLat(Double platformLat) { this.platformLat = platformLat; }
    public Double getTargetLon() { return targetLon; }
    public void setTargetLon(Double targetLon) { this.targetLon = targetLon; }
    public Double getTargetLat() { return targetLat; }
    public void setTargetLat(Double targetLat) { this.targetLat = targetLat; }
    public double getAzimuth() { return azimuth; }
    public void setAzimuth(double azimuth) { this.azimuth = azimuth; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public double getFreq() { return freq; }
    public void setFreq(double freq) { this.freq = freq; }
    public double getSignalLevel() { return signalLevel; }
    public void setSignalLevel(double signalLevel) { this.signalLevel = signalLevel; }
    public double getSignalDwellMs() { return signalDwellMs; }
    public void setSignalDwellMs(double signalDwellMs) { this.signalDwellMs = signalDwellMs; }
}
