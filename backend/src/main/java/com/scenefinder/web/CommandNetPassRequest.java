package com.scenefinder.web;

import java.util.ArrayList;
import java.util.List;

/**
 * 预警机指挥网二次分析入参：一次场景信号分析完成后调用。
 */
public class CommandNetPassRequest {

    private String sourceCsvPath;
    private String outputDir;
    private double freqTolerance = 0.01;
    private List<String> analysisIds = new ArrayList<String>();
    private boolean preloadAll = true;

    public String getSourceCsvPath() {
        return sourceCsvPath;
    }

    public void setSourceCsvPath(String sourceCsvPath) {
        this.sourceCsvPath = sourceCsvPath;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public double getFreqTolerance() {
        return freqTolerance;
    }

    public void setFreqTolerance(double freqTolerance) {
        this.freqTolerance = freqTolerance;
    }

    public List<String> getAnalysisIds() {
        return analysisIds;
    }

    public void setAnalysisIds(List<String> analysisIds) {
        this.analysisIds = analysisIds != null ? analysisIds : new ArrayList<String>();
    }

    public boolean isPreloadAll() {
        return preloadAll;
    }

    public void setPreloadAll(boolean preloadAll) {
        this.preloadAll = preloadAll;
    }
}
