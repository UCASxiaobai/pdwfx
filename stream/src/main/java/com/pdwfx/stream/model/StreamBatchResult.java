package com.pdwfx.stream.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StreamBatchResult {
    private String streamBatchId;
    private String csvPath;
    /** 首个成功场景的 analysisId（兼容旧前端深链） */
    private String analysisId;
    /** SCENE_FILTER / PROCESS_SCENE / DONE / FAILED */
    private String status;
    private String error;
    private Instant finishedAt;
    private int networkCount;
    private int trackCount;
    private String sceneOutputDir;
    private String sourceCsvOnBackend;
    private int sceneCount;
    private int trackSceneCount;
    private int pollingSceneCount;
    private int totalDetections;
    private int confirmedTracks;
    private String annotationPath;
    private List<String> analysisIds = new ArrayList<>();
    private List<StreamSceneSummary> scenes = new ArrayList<>();
    private List<StreamTrackLabel> labels = new ArrayList<>();
    /** 与主流程报表行同构，供目标类型/波道图表 */
    private List<Map<String, Object>> reportRows = new ArrayList<>();
    /** 波道/频点/目标类型占用汇总 */
    private Map<String, Object> occupancy = new LinkedHashMap<>();
    /** 指挥网二次：skipped / skipReason / awacsPanels（供流式页按预警机展示） */
    private Map<String, Object> commandNetPass = new LinkedHashMap<>();

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
    public String getSceneOutputDir() { return sceneOutputDir; }
    public void setSceneOutputDir(String sceneOutputDir) { this.sceneOutputDir = sceneOutputDir; }
    public String getSourceCsvOnBackend() { return sourceCsvOnBackend; }
    public void setSourceCsvOnBackend(String sourceCsvOnBackend) { this.sourceCsvOnBackend = sourceCsvOnBackend; }
    public int getSceneCount() { return sceneCount; }
    public void setSceneCount(int sceneCount) { this.sceneCount = sceneCount; }
    public int getTrackSceneCount() { return trackSceneCount; }
    public void setTrackSceneCount(int trackSceneCount) { this.trackSceneCount = trackSceneCount; }
    public int getPollingSceneCount() { return pollingSceneCount; }
    public void setPollingSceneCount(int pollingSceneCount) { this.pollingSceneCount = pollingSceneCount; }
    public int getTotalDetections() { return totalDetections; }
    public void setTotalDetections(int totalDetections) { this.totalDetections = totalDetections; }
    public int getConfirmedTracks() { return confirmedTracks; }
    public void setConfirmedTracks(int confirmedTracks) { this.confirmedTracks = confirmedTracks; }
    public String getAnnotationPath() { return annotationPath; }
    public void setAnnotationPath(String annotationPath) { this.annotationPath = annotationPath; }
    public List<String> getAnalysisIds() { return analysisIds; }
    public void setAnalysisIds(List<String> analysisIds) { this.analysisIds = analysisIds; }
    public List<StreamSceneSummary> getScenes() { return scenes; }
    public void setScenes(List<StreamSceneSummary> scenes) { this.scenes = scenes; }
    public List<StreamTrackLabel> getLabels() { return labels; }
    public void setLabels(List<StreamTrackLabel> labels) { this.labels = labels; }
    public List<Map<String, Object>> getReportRows() { return reportRows; }
    public void setReportRows(List<Map<String, Object>> reportRows) {
        this.reportRows = reportRows != null ? reportRows : new ArrayList<Map<String, Object>>();
    }
    public Map<String, Object> getOccupancy() { return occupancy; }
    public void setOccupancy(Map<String, Object> occupancy) {
        this.occupancy = occupancy != null ? occupancy : new LinkedHashMap<String, Object>();
    }
    public Map<String, Object> getCommandNetPass() { return commandNetPass; }
    public void setCommandNetPass(Map<String, Object> commandNetPass) {
        this.commandNetPass = commandNetPass != null ? commandNetPass : new LinkedHashMap<String, Object>();
    }
}
