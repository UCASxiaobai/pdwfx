package com.scenefinder.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景筛选 + 转发分析 + 网络详情聚合后的完整报告。
 */
public class SceneAnalysisReport {

    private SceneFinderResult sceneResult;
    private SceneForwardAnalyzeResult forwardResult;
    private List<AnalysisReportRow> rows = new ArrayList<>();
    private int networkDetailCount;
    private long buildTimeMs;

    public SceneFinderResult getSceneResult() {
        return sceneResult;
    }

    public void setSceneResult(SceneFinderResult sceneResult) {
        this.sceneResult = sceneResult;
    }

    public SceneForwardAnalyzeResult getForwardResult() {
        return forwardResult;
    }

    public void setForwardResult(SceneForwardAnalyzeResult forwardResult) {
        this.forwardResult = forwardResult;
    }

    public List<AnalysisReportRow> getRows() {
        return rows;
    }

    public void setRows(List<AnalysisReportRow> rows) {
        this.rows = rows != null ? rows : new ArrayList<>();
    }

    public int getNetworkDetailCount() {
        return networkDetailCount;
    }

    public void setNetworkDetailCount(int networkDetailCount) {
        this.networkDetailCount = networkDetailCount;
    }

    public long getBuildTimeMs() {
        return buildTimeMs;
    }

    public void setBuildTimeMs(long buildTimeMs) {
        this.buildTimeMs = buildTimeMs;
    }
}
