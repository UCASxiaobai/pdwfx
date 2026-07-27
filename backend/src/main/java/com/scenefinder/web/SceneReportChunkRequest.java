package com.scenefinder.web;

/**
 * 按场景增量汇总（避免一次请求处理全部场景导致超时）。
 */
public class SceneReportChunkRequest {

    private String outputDir;
    private int sceneRank;
    private String analysisId;
    private String sceneType;
    private boolean preloadAll = true;
    /** 会话失效时用于重新分析的导出 CSV */
    private String exportedCsvPath;
    private double freqTolerance = 0.01;

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public int getSceneRank() {
        return sceneRank;
    }

    public void setSceneRank(int sceneRank) {
        this.sceneRank = sceneRank;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(String analysisId) {
        this.analysisId = analysisId;
    }

    public String getSceneType() {
        return sceneType;
    }

    public void setSceneType(String sceneType) {
        this.sceneType = sceneType;
    }

    public boolean isPreloadAll() {
        return preloadAll;
    }

    public void setPreloadAll(boolean preloadAll) {
        this.preloadAll = preloadAll;
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
