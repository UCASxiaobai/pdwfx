package com.scenefinder.web;

import java.util.ArrayList;
import java.util.List;

/**
 * 时间重合场景合并：导出联合 CSV → 一次信号分析 → 按成员场景展开报表行。
 */
public class SceneOverlapProcessRequest {

    private String sourceCsvPath;
    private String outputDir;
    private List<Integer> sceneRanks = new ArrayList<>();
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

    public List<Integer> getSceneRanks() {
        return sceneRanks;
    }

    public void setSceneRanks(List<Integer> sceneRanks) {
        this.sceneRanks = sceneRanks != null ? sceneRanks : new ArrayList<>();
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
