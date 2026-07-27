package com.scenefinder.model;

import com.pdwfx.signal.model.AnalyzeSessionResponse;

import java.util.ArrayList;
import java.util.List;

/** 单场景内一个通信频点的分析单元（时间窗 + 频点，独立 analysisId）。 */
public class SceneProcessUnit {

    private double freqCenterMhz;
    private String exportedCsvPath;
    private int exportedRowCount;
    private AnalyzeSessionResponse session;
    private List<AnalysisReportRow> reportRows = new ArrayList<>();
    private int networkCount;

    public double getFreqCenterMhz() {
        return freqCenterMhz;
    }

    public void setFreqCenterMhz(double freqCenterMhz) {
        this.freqCenterMhz = freqCenterMhz;
    }

    public String getExportedCsvPath() {
        return exportedCsvPath;
    }

    public void setExportedCsvPath(String exportedCsvPath) {
        this.exportedCsvPath = exportedCsvPath;
    }

    public int getExportedRowCount() {
        return exportedRowCount;
    }

    public void setExportedRowCount(int exportedRowCount) {
        this.exportedRowCount = exportedRowCount;
    }

    public AnalyzeSessionResponse getSession() {
        return session;
    }

    public void setSession(AnalyzeSessionResponse session) {
        this.session = session;
    }

    public List<AnalysisReportRow> getReportRows() {
        return reportRows;
    }

    public void setReportRows(List<AnalysisReportRow> reportRows) {
        this.reportRows = reportRows != null ? reportRows : new ArrayList<>();
    }

    public int getNetworkCount() {
        return networkCount;
    }

    public void setNetworkCount(int networkCount) {
        this.networkCount = networkCount;
    }
}
