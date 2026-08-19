package com.scenefinder.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个预警机种子的指挥网展示：该机占用窗内同频同时段建轨与分析行。
 */
public class CommandNetAwacsPanel {

    private String seedTargetId;
    /** 面板唯一键：种子目标 + 占用频点，避免异频合在同一筛选页。 */
    private String panelId;
    private String label;
    private String targetType;
    private String targetTypeLabel;
    private double freqMhz;
    private long tStartMs;
    private long tEndMs;
    private int displayRank;
    private List<AwacsOccupancyWindow> windows = new ArrayList<AwacsOccupancyWindow>();
    private Map<String, Object> trajectoryView = new LinkedHashMap<String, Object>();
    private List<AnalysisReportRow> reportRows = new ArrayList<AnalysisReportRow>();

    public String getSeedTargetId() {
        return seedTargetId;
    }

    public void setSeedTargetId(String seedTargetId) {
        this.seedTargetId = seedTargetId;
    }

    public String getPanelId() {
        return panelId;
    }

    public void setPanelId(String panelId) {
        this.panelId = panelId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
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

    public double getFreqMhz() {
        return freqMhz;
    }

    public void setFreqMhz(double freqMhz) {
        this.freqMhz = freqMhz;
    }

    public long getTStartMs() {
        return tStartMs;
    }

    public void setTStartMs(long tStartMs) {
        this.tStartMs = tStartMs;
    }

    public long getTEndMs() {
        return tEndMs;
    }

    public void setTEndMs(long tEndMs) {
        this.tEndMs = tEndMs;
    }

    public int getDisplayRank() {
        return displayRank;
    }

    public void setDisplayRank(int displayRank) {
        this.displayRank = displayRank;
    }

    public List<AwacsOccupancyWindow> getWindows() {
        return windows;
    }

    public void setWindows(List<AwacsOccupancyWindow> windows) {
        this.windows = windows != null ? windows : new ArrayList<AwacsOccupancyWindow>();
    }

    public Map<String, Object> getTrajectoryView() {
        return trajectoryView;
    }

    public void setTrajectoryView(Map<String, Object> trajectoryView) {
        this.trajectoryView = trajectoryView != null
                ? trajectoryView
                : new LinkedHashMap<String, Object>();
    }

    public List<AnalysisReportRow> getReportRows() {
        return reportRows;
    }

    public void setReportRows(List<AnalysisReportRow> reportRows) {
        this.reportRows = reportRows != null ? reportRows : new ArrayList<AnalysisReportRow>();
    }
}
