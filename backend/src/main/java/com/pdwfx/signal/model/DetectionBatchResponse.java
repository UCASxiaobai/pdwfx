package com.pdwfx.signal.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 逐条侦测编批导出：{@code detections} 为全量点，{@code batches} 为目标列表。
 */
public class DetectionBatchResponse {

    private String analysisId;
    private int batchCount;
    private int detectionCount;
    private int unassignedCount;
    private long elapsedMs;
    private List<DetectionBatchSummary> batches = new ArrayList<DetectionBatchSummary>();
    private List<DetectionBatchRow> detections = new ArrayList<DetectionBatchRow>();

    public String getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(String analysisId) {
        this.analysisId = analysisId;
    }

    public int getBatchCount() {
        return batchCount;
    }

    public void setBatchCount(int batchCount) {
        this.batchCount = batchCount;
    }

    public int getDetectionCount() {
        return detectionCount;
    }

    public void setDetectionCount(int detectionCount) {
        this.detectionCount = detectionCount;
    }

    public int getUnassignedCount() {
        return unassignedCount;
    }

    public void setUnassignedCount(int unassignedCount) {
        this.unassignedCount = unassignedCount;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public List<DetectionBatchSummary> getBatches() {
        return batches;
    }

    public void setBatches(List<DetectionBatchSummary> batches) {
        this.batches = batches != null ? batches : new ArrayList<DetectionBatchSummary>();
    }

    public List<DetectionBatchRow> getDetections() {
        return detections;
    }

    public void setDetections(List<DetectionBatchRow> detections) {
        this.detections = detections != null ? detections : new ArrayList<DetectionBatchRow>();
    }
}
