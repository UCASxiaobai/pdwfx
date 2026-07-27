package com.scenefinder.model;

import com.pdwfx.signal.model.AnalyzeSessionResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 单场景处理结果：转发信息 + 分析会话 + 汇总行。
 */
public class SceneProcessResponse {

    private int rank;
    /** 与本分析批次合并的场景编号（时间重合传递闭包） */
    private List<Integer> memberRanks = new ArrayList<>();
    private String sceneType;
    private String exportedCsvPath;
    private int exportedRowCount;
    private AnalyzeSessionResponse session;
    private List<AnalysisReportRow> reportRows = new ArrayList<>();
    private int networkCount;
    private long elapsedMs;
    /** 本组合并分析的时间跨度（成员场景窗并集） */
    private long spanStartEpochMs;
    private long spanEndEpochMs;
    /** 按通信频点拆分的独立分析单元（时间窗相同、频点不同） */
    private List<SceneProcessUnit> units = new ArrayList<>();
    /** 本场景被跳过（如无可用导出数据），不参与信号分析 */
    private boolean skipped;
    private String skipReason;

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public List<Integer> getMemberRanks() {
        return memberRanks;
    }

    public void setMemberRanks(List<Integer> memberRanks) {
        this.memberRanks = memberRanks != null ? memberRanks : new ArrayList<>();
    }

    public String getSceneType() {
        return sceneType;
    }

    public void setSceneType(String sceneType) {
        this.sceneType = sceneType;
    }

    public String getExportedCsvPath() {
        return exportedCsvPath;
    }

    public void setExportedCsvPath(String exportedCsvPath) {
        this.exportedCsvPath = exportedCsvPath;
    }

    public int getExportedRowCount() {
        return exportedRowCount;
    }

    public void setExportedRowCount(int exportedRowCount) {
        this.exportedRowCount = exportedRowCount;
    }

    public AnalyzeSessionResponse getSession() {
        return session;
    }

    public void setSession(AnalyzeSessionResponse session) {
        this.session = session;
    }

    public List<AnalysisReportRow> getReportRows() {
        return reportRows;
    }

    public void setReportRows(List<AnalysisReportRow> reportRows) {
        this.reportRows = reportRows != null ? reportRows : new ArrayList<>();
    }

    public int getNetworkCount() {
        return networkCount;
    }

    public void setNetworkCount(int networkCount) {
        this.networkCount = networkCount;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public long getSpanStartEpochMs() {
        return spanStartEpochMs;
    }

    public void setSpanStartEpochMs(long spanStartEpochMs) {
        this.spanStartEpochMs = spanStartEpochMs;
    }

    public long getSpanEndEpochMs() {
        return spanEndEpochMs;
    }

    public void setSpanEndEpochMs(long spanEndEpochMs) {
        this.spanEndEpochMs = spanEndEpochMs;
    }

    public List<SceneProcessUnit> getUnits() {
        return units;
    }

    public void setUnits(List<SceneProcessUnit> units) {
        this.units = units != null ? units : new ArrayList<>();
    }

    public boolean isSkipped() {
        return skipped;
    }

    public void setSkipped(boolean skipped) {
        this.skipped = skipped;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }
}
