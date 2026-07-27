package com.scenefinder.web;

public class EnsureSessionRequest {

    private String analysisId;
    private String exportedCsvPath;
    private double freqTolerance = 0.01;

    public String getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(String analysisId) {
        this.analysisId = analysisId;
    }

    public String getExportedCsvPath() {
        return exportedCsvPath;
    }

    public void setExportedCsvPath(String exportedCsvPath) {
        this.exportedCsvPath = exportedCsvPath;
    }

    public double getFreqTolerance() {
        return freqTolerance;
    }

    public void setFreqTolerance(double freqTolerance) {
        this.freqTolerance = freqTolerance;
    }
}
