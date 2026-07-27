package com.pdwfx.signal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 外源定位导入结果。
 */
public class ExternalTargetImportResponse {
    private String format = "EXTERNAL_TARGET_LOCATE";
    private String fileName;
    private int totalCount;
    private int mapSampleCount;
    private List<ExternalTargetFix> fixes = new ArrayList<>();

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getMapSampleCount() { return mapSampleCount; }
    public void setMapSampleCount(int mapSampleCount) { this.mapSampleCount = mapSampleCount; }
    public List<ExternalTargetFix> getFixes() { return fixes; }
    public void setFixes(List<ExternalTargetFix> fixes) {
        this.fixes = fixes != null ? fixes : Collections.emptyList();
    }
}
