package com.scenefinder.model;

import java.util.ArrayList;
import java.util.List;

public class SceneReportChunkResponse {

    private int sceneRank;
    private int networkCount;
    private int rowCount;
    private long elapsedMs;
    private List<AnalysisReportRow> rows = new ArrayList<>();

    public int getSceneRank() {
        return sceneRank;
    }

    public void setSceneRank(int sceneRank) {
        this.sceneRank = sceneRank;
    }

    public int getNetworkCount() {
        return networkCount;
    }

    public void setNetworkCount(int networkCount) {
        this.networkCount = networkCount;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public List<AnalysisReportRow> getRows() {
        return rows;
    }

    public void setRows(List<AnalysisReportRow> rows) {
        this.rows = rows != null ? rows : new ArrayList<>();
    }
}
