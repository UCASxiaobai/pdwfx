package com.scenefinder.web;

import java.util.List;

/**
 * 单场景：导出 CSV → 信号分析 → 汇总表格（避免批量创建会话后被驱逐）。
 */
public class SceneProcessRequest {

    private String sourceCsvPath;
    private String outputDir;
    private int sceneRank;
    private double freqTolerance = 0.01;
    private boolean preloadAll = true;
    private boolean useSceneEndpoint;

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

    public int getSceneRank() {
        return sceneRank;
    }

    public void setSceneRank(int sceneRank) {
        this.sceneRank = sceneRank;
    }

    public double getFreqTolerance() {
        return freqTolerance;
    }

    public void setFreqTolerance(double freqTolerance) {
        this.freqTolerance = freqTolerance;
    }

    public boolean isPreloadAll() {
        return preloadAll;
    }

    public void setPreloadAll(boolean preloadAll) {
        this.preloadAll = preloadAll;
    }

    public boolean isUseSceneEndpoint() {
        return useSceneEndpoint;
    }

    public void setUseSceneEndpoint(boolean useSceneEndpoint) {
        this.useSceneEndpoint = useSceneEndpoint;
    }
}
