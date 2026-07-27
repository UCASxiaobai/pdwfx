package com.pdwfx.signal.model;

import java.util.ArrayList;
import java.util.List;

public class AnalysisSummary {
    private List<String> networkConclusions = new ArrayList<>();
    private List<String> targetConclusions = new ArrayList<>();
    private List<String> anomalies = new ArrayList<>();

    public List<String> getNetworkConclusions() { return networkConclusions; }
    public void setNetworkConclusions(List<String> networkConclusions) { this.networkConclusions = networkConclusions; }
    public List<String> getTargetConclusions() { return targetConclusions; }
    public void setTargetConclusions(List<String> targetConclusions) { this.targetConclusions = targetConclusions; }
    public List<String> getAnomalies() { return anomalies; }
    public void setAnomalies(List<String> anomalies) { this.anomalies = anomalies; }
}
