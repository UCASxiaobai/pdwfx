package com.pdwfx.signal.model;

import java.time.LocalDateTime;

public class DetectSignal {
    private String id;
    private String dataFileId;
    private String dataType;
    private String locPlatId;
    private String antennaSelect;
    private LocalDateTime detectTime;
    private long detectTimesss;
    /** 单条 PDW 驻留时间（ms），来自 nSignalTime，单位 10µs */
    private double signalDwellMs;
    private double freq;
    private double signalBw;
    private double signalLevel;
    private String modulateStyle;
    private String modulateDimension;
    private double bitRate;
    private double azimuth;
    private double relAzimuth;
    private double snr;
    private Double longitude;
    private Double latitude;
    private Double targetLon;
    private Double targetLat;
    private String equipId;
    private String clzt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getDataFileId() { return dataFileId; }
    public void setDataFileId(String dataFileId) { this.dataFileId = dataFileId; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getLocPlatId() { return locPlatId; }
    public void setLocPlatId(String locPlatId) { this.locPlatId = locPlatId; }
    public String getAntennaSelect() { return antennaSelect; }
    public void setAntennaSelect(String antennaSelect) { this.antennaSelect = antennaSelect; }
    public LocalDateTime getDetectTime() { return detectTime; }
    public void setDetectTime(LocalDateTime detectTime) { this.detectTime = detectTime; }
    public long getDetectTimesss() { return detectTimesss; }
    public void setDetectTimesss(long detectTimesss) { this.detectTimesss = detectTimesss; }
    public double getSignalDwellMs() { return signalDwellMs; }
    public void setSignalDwellMs(double signalDwellMs) { this.signalDwellMs = signalDwellMs; }
    public double getFreq() { return freq; }
    public void setFreq(double freq) { this.freq = freq; }
    public double getSignalBw() { return signalBw; }
    public void setSignalBw(double signalBw) { this.signalBw = signalBw; }
    public double getSignalLevel() { return signalLevel; }
    public void setSignalLevel(double signalLevel) { this.signalLevel = signalLevel; }
    public String getModulateStyle() { return modulateStyle; }
    public void setModulateStyle(String modulateStyle) { this.modulateStyle = modulateStyle; }
    public String getModulateDimension() { return modulateDimension; }
    public void setModulateDimension(String modulateDimension) { this.modulateDimension = modulateDimension; }
    public double getBitRate() { return bitRate; }
    public void setBitRate(double bitRate) { this.bitRate = bitRate; }
    public double getAzimuth() { return azimuth; }
    public void setAzimuth(double azimuth) { this.azimuth = azimuth; }
    public double getRelAzimuth() { return relAzimuth; }
    public void setRelAzimuth(double relAzimuth) { this.relAzimuth = relAzimuth; }
    public double getSnr() { return snr; }
    public void setSnr(double snr) { this.snr = snr; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getTargetLon() { return targetLon; }
    public void setTargetLon(Double targetLon) { this.targetLon = targetLon; }
    public Double getTargetLat() { return targetLat; }
    public void setTargetLat(Double targetLat) { this.targetLat = targetLat; }
    public String getEquipId() { return equipId; }
    public void setEquipId(String equipId) { this.equipId = equipId; }
    public String getClzt() { return clzt; }
    public void setClzt(String clzt) { this.clzt = clzt; }
}
