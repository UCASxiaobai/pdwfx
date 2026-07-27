package com.pdwfx.signal.model;

/**
 * 外源已知目标定位点（如雷情导入 CSV）。
 */
public class ExternalTargetFix {
    private int rowNum;
    private String platformId;
    private long detectTimeMs;
    private double longitude;
    private double latitude;
    /** 敌我属性：我 / 敌 / 友 / 无属性 */
    private String affiliation;
    private String targetTypeName;
    private String targetId;
    private String targetName;
    private String modelCode;
    private String sourceFile;

    public int getRowNum() { return rowNum; }
    public void setRowNum(int rowNum) { this.rowNum = rowNum; }
    public String getPlatformId() { return platformId; }
    public void setPlatformId(String platformId) { this.platformId = platformId; }
    public long getDetectTimeMs() { return detectTimeMs; }
    public void setDetectTimeMs(long detectTimeMs) { this.detectTimeMs = detectTimeMs; }
    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public String getAffiliation() { return affiliation; }
    public void setAffiliation(String affiliation) { this.affiliation = affiliation; }
    public String getTargetTypeName() { return targetTypeName; }
    public void setTargetTypeName(String targetTypeName) { this.targetTypeName = targetTypeName; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }
    public String getModelCode() { return modelCode; }
    public void setModelCode(String modelCode) { this.modelCode = modelCode; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
}
