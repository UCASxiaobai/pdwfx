package com.pdwfx.signal.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AnalyzeSessionResponse {
    private String analysisId;
    private int networkCount;
    private List<NetworkSummary> networks = new ArrayList<>();
    /** 外源定位点（地图抽样） */
    private List<ExternalTargetFix> externalTargetFixes = Collections.emptyList();
    private int externalTargetFixTotalCount;

    public String getAnalysisId() { return analysisId; }
    public void setAnalysisId(String analysisId) { this.analysisId = analysisId; }
    public int getNetworkCount() { return networkCount; }
    public void setNetworkCount(int networkCount) { this.networkCount = networkCount; }
    public List<NetworkSummary> getNetworks() { return networks; }
    public void setNetworks(List<NetworkSummary> networks) { this.networks = networks; }
    public List<ExternalTargetFix> getExternalTargetFixes() { return externalTargetFixes; }
    public void setExternalTargetFixes(List<ExternalTargetFix> externalTargetFixes) {
        this.externalTargetFixes = externalTargetFixes != null ? externalTargetFixes : Collections.emptyList();
    }
    public int getExternalTargetFixTotalCount() { return externalTargetFixTotalCount; }
    public void setExternalTargetFixTotalCount(int externalTargetFixTotalCount) {
        this.externalTargetFixTotalCount = externalTargetFixTotalCount;
    }
}
