package com.pdwfx.stream.model;

/** 一批中的单个场景摘要（供流式页与标注 JSON）。 */
public class StreamSceneSummary {
    private int rank;
    private String sceneType;
    private String analysisId;
    private int networkCount;
    private int trackCount;
    private Long windowStartMs;
    private Long windowEndMs;
    private Double freqCenterMhz;
    private Double freqMinMhz;
    private Double freqMaxMhz;
    private boolean skipped;
    private String skipReason;

    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }
    public String getSceneType() { return sceneType; }
    public void setSceneType(String sceneType) { this.sceneType = sceneType; }
    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }
    public int getNetworkCount() { return networkCount; }
    public void setNetworkCount(int networkCount) { this.networkCount = networkCount; }
    public int getTrackCount() { return trackCount; }
    public void setTrackCount(int trackCount) { this.trackCount = trackCount; }
    public Long getWindowStartMs() { return windowStartMs; }
    public void setWindowStartMs(Long windowStartMs) { this.windowStartMs = windowStartMs; }
    public Long getWindowEndMs() { return windowEndMs; }
    public void setWindowEndMs(Long windowEndMs) { this.windowEndMs = windowEndMs; }
    public Double getFreqCenterMhz() { return freqCenterMhz; }
    public void setFreqCenterMhz(Double freqCenterMhz) { this.freqCenterMhz = freqCenterMhz; }
    public Double getFreqMinMhz() { return freqMinMhz; }
    public void setFreqMinMhz(Double freqMinMhz) { this.freqMinMhz = freqMinMhz; }
    public Double getFreqMaxMhz() { return freqMaxMhz; }
    public void setFreqMaxMhz(Double freqMaxMhz) { this.freqMaxMhz = freqMaxMhz; }
    public boolean isSkipped() { return skipped; }
    public void setSkipped(boolean skipped) { this.skipped = skipped; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
}
