package com.pdwfx.stream.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class StreamBatchResult {
    private String streamBatchId;
    private String csvPath;
    private String analysisId;
    private String status; // DONE / FAILED
    private String error;
    private Instant finishedAt;
    private int networkCount;
    private int trackCount;
    private List<StreamTrackLabel> labels = new ArrayList<>();

    public String getStreamBatchId() { return streamBatchId; }
    public void setStreamBatchId(String streamBatchId) { this.streamBatchId = streamBatchId; }
    public String getCsvPath() { return csvPath; }
    public void setCsvPath(String csvPath) { this.csvPath = csvPath; }
    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public int getNetworkCount() { return networkCount; }
    public void setNetworkCount(int networkCount) { this.networkCount = networkCount; }
    public int getTrackCount() { return trackCount; }
    public void setTrackCount(int trackCount) { this.trackCount = trackCount; }
    public List<StreamTrackLabel> getLabels() { return labels; }
    public void setLabels(List<StreamTrackLabel> labels) { this.labels = labels; }
}
