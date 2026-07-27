package com.scenefinder.model;

/**
 * 单个场景转发结果。
 */
public class SceneForwardItemResult {

    private final int rank;
    private final SceneType sceneType;
    private final String exportedCsvPath;
    private final int exportedRowCount;
    private final String backendResponseJson;
    /** 本单元通信频点中心（MHz）；整场景未拆频时为 null */
    private final Double freqCenterMhz;

    public SceneForwardItemResult(
            int rank,
            SceneType sceneType,
            String exportedCsvPath,
            int exportedRowCount,
            String backendResponseJson
    ) {
        this(rank, sceneType, exportedCsvPath, exportedRowCount, backendResponseJson, null);
    }

    public SceneForwardItemResult(
            int rank,
            SceneType sceneType,
            String exportedCsvPath,
            int exportedRowCount,
            String backendResponseJson,
            Double freqCenterMhz
    ) {
        this.rank = rank;
        this.sceneType = sceneType;
        this.exportedCsvPath = exportedCsvPath;
        this.exportedRowCount = exportedRowCount;
        this.backendResponseJson = backendResponseJson;
        this.freqCenterMhz = freqCenterMhz;
    }

    public int getRank() {
        return rank;
    }

    public SceneType getSceneType() {
        return sceneType;
    }

    public String getExportedCsvPath() {
        return exportedCsvPath;
    }

    public int getExportedRowCount() {
        return exportedRowCount;
    }

    public String getBackendResponseJson() {
        return backendResponseJson;
    }

    public Double getFreqCenterMhz() {
        return freqCenterMhz;
    }
}
