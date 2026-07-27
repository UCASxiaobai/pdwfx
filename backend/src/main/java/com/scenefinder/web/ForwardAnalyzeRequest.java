package com.scenefinder.web;

import java.util.List;

public class ForwardAnalyzeRequest {

    private String sourceCsvPath;
    private String outputDir;
    private List<Integer> sceneRanks;
    private String backendBaseUrl;
    private Double freqTolerance;
    private Boolean useSceneEndpoint;

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
        this.sceneRanks = sceneRanks;
    }

    public String getBackendBaseUrl() {
        return backendBaseUrl;
    }

    public void setBackendBaseUrl(String backendBaseUrl) {
        this.backendBaseUrl = backendBaseUrl;
    }

    public Double getFreqTolerance() {
        return freqTolerance;
    }

    public void setFreqTolerance(Double freqTolerance) {
        this.freqTolerance = freqTolerance;
    }

    public Boolean getUseSceneEndpoint() {
        return useSceneEndpoint;
    }

    public void setUseSceneEndpoint(Boolean useSceneEndpoint) {
        this.useSceneEndpoint = useSceneEndpoint;
    }
}
