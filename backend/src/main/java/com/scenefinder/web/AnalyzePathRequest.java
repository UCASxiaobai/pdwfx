package com.scenefinder.web;

import com.scenefinder.service.AnalyzeOptions;

public class AnalyzePathRequest {

    private String csvPath;
    private Double frameSeconds;
    private Double bearingClusterGapDeg;
    private Double associationGateDeg;
    private Integer maxMissedFrames;
    private Double minTrackSeconds;
    private Integer minTrackPoints;
    private Double windowSeconds;
    private Double windowStepSeconds;
    private Integer minTracksInScene;
    private Integer topKScenes;
    private Integer topKTrackScenes;
    private Integer topKPollingScenes;
    private Double minSeparationDeg;
    private Double freqMin;
    private Double freqMax;
    private Double freqTolerance;
    private Double freqClusterGapMhz;
    private Double sceneFreqBandGapMhz;
    private Double mergeMaxGapSeconds;
    private String outputDir;
    private Boolean fullSpanWindow;

    public String getCsvPath() {
        return csvPath;
    }

    public void setCsvPath(String csvPath) {
        this.csvPath = csvPath;
    }

    public Double getFrameSeconds() {
        return frameSeconds;
    }

    public void setFrameSeconds(Double frameSeconds) {
        this.frameSeconds = frameSeconds;
    }

    public Double getBearingClusterGapDeg() {
        return bearingClusterGapDeg;
    }

    public void setBearingClusterGapDeg(Double bearingClusterGapDeg) {
        this.bearingClusterGapDeg = bearingClusterGapDeg;
    }

    public Double getAssociationGateDeg() {
        return associationGateDeg;
    }

    public void setAssociationGateDeg(Double associationGateDeg) {
        this.associationGateDeg = associationGateDeg;
    }

    public Integer getMaxMissedFrames() {
        return maxMissedFrames;
    }

    public void setMaxMissedFrames(Integer maxMissedFrames) {
        this.maxMissedFrames = maxMissedFrames;
    }

    public Double getMinTrackSeconds() {
        return minTrackSeconds;
    }

    public void setMinTrackSeconds(Double minTrackSeconds) {
        this.minTrackSeconds = minTrackSeconds;
    }

    public Integer getMinTrackPoints() {
        return minTrackPoints;
    }

    public void setMinTrackPoints(Integer minTrackPoints) {
        this.minTrackPoints = minTrackPoints;
    }

    public Double getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(Double windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public Double getWindowStepSeconds() {
        return windowStepSeconds;
    }

    public void setWindowStepSeconds(Double windowStepSeconds) {
        this.windowStepSeconds = windowStepSeconds;
    }

    public Integer getMinTracksInScene() {
        return minTracksInScene;
    }

    public void setMinTracksInScene(Integer minTracksInScene) {
        this.minTracksInScene = minTracksInScene;
    }

    public Integer getTopKScenes() {
        return topKScenes;
    }

    public void setTopKScenes(Integer topKScenes) {
        this.topKScenes = topKScenes;
    }

    public Integer getTopKTrackScenes() {
        return topKTrackScenes;
    }

    public void setTopKTrackScenes(Integer topKTrackScenes) {
        this.topKTrackScenes = topKTrackScenes;
    }

    public Integer getTopKPollingScenes() {
        return topKPollingScenes;
    }

    public void setTopKPollingScenes(Integer topKPollingScenes) {
        this.topKPollingScenes = topKPollingScenes;
    }

    public Double getMinSeparationDeg() {
        return minSeparationDeg;
    }

    public void setMinSeparationDeg(Double minSeparationDeg) {
        this.minSeparationDeg = minSeparationDeg;
    }

    public Double getFreqMin() {
        return freqMin;
    }

    public void setFreqMin(Double freqMin) {
        this.freqMin = freqMin;
    }

    public Double getFreqMax() {
        return freqMax;
    }

    public void setFreqMax(Double freqMax) {
        this.freqMax = freqMax;
    }

    public Double getFreqTolerance() {
        return freqTolerance;
    }

    public void setFreqTolerance(Double freqTolerance) {
        this.freqTolerance = freqTolerance;
    }

    public Double getFreqClusterGapMhz() {
        return freqClusterGapMhz;
    }

    public void setFreqClusterGapMhz(Double freqClusterGapMhz) {
        this.freqClusterGapMhz = freqClusterGapMhz;
    }

    public Double getSceneFreqBandGapMhz() {
        return sceneFreqBandGapMhz;
    }

    public void setSceneFreqBandGapMhz(Double sceneFreqBandGapMhz) {
        this.sceneFreqBandGapMhz = sceneFreqBandGapMhz;
    }

    public Double getMergeMaxGapSeconds() {
        return mergeMaxGapSeconds;
    }

    public void setMergeMaxGapSeconds(Double mergeMaxGapSeconds) {
        this.mergeMaxGapSeconds = mergeMaxGapSeconds;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public Boolean getFullSpanWindow() {
        return fullSpanWindow;
    }

    public void setFullSpanWindow(Boolean fullSpanWindow) {
        this.fullSpanWindow = fullSpanWindow;
    }

    public AnalyzeOptions toOptions() {
        return new AnalyzeOptions(
                frameSeconds, bearingClusterGapDeg, associationGateDeg, maxMissedFrames,
                minTrackSeconds, minTrackPoints, windowSeconds, windowStepSeconds,
                minTracksInScene, topKScenes, topKTrackScenes, topKPollingScenes,
                minSeparationDeg, freqMin, freqMax, freqTolerance,
                freqClusterGapMhz, sceneFreqBandGapMhz, mergeMaxGapSeconds, outputDir,
                null,
                fullSpanWindow
        );
    }
}
