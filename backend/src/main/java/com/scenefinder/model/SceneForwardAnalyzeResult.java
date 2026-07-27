package com.scenefinder.model;

import java.util.List;

/**
 * 将筛选场景转发至 pdwfx backend 的批量结果。
 */
public class SceneForwardAnalyzeResult {

    private final String sourceCsv;
    private final String outputDir;
    private final String backendBaseUrl;
    private final double freqTolerance;
    private final boolean usedSceneEndpoint;
    private final List<SceneForwardItemResult> scenes;

    public SceneForwardAnalyzeResult(
            String sourceCsv,
            String outputDir,
            String backendBaseUrl,
            double freqTolerance,
            boolean usedSceneEndpoint,
            List<SceneForwardItemResult> scenes
    ) {
        this.sourceCsv = sourceCsv;
        this.outputDir = outputDir;
        this.backendBaseUrl = backendBaseUrl;
        this.freqTolerance = freqTolerance;
        this.usedSceneEndpoint = usedSceneEndpoint;
        this.scenes = scenes;
    }

    public String getSourceCsv() {
        return sourceCsv;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public String getBackendBaseUrl() {
        return backendBaseUrl;
    }

    public double getFreqTolerance() {
        return freqTolerance;
    }

    public boolean isUsedSceneEndpoint() {
        return usedSceneEndpoint;
    }

    public List<SceneForwardItemResult> getScenes() {
        return scenes;
    }
}
