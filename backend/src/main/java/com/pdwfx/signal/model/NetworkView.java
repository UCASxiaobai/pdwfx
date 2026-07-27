package com.pdwfx.signal.model;

import java.util.ArrayList;
import java.util.List;

public class NetworkView {
    private int networkId;
    private double freq;
    private String networkType;
    /** D03 / D04 / D05 / UNKNOWN */
    private String commLinkChannel;
    private String commLinkChannelLabel;
    private String commLinkReason;
    /** 通信链研判过程证据（各波道得分与否决原因），供调试 */
    private List<String> commLinkEvidence = new ArrayList<>();
    private double networkConfidence;
    private String freqBand;
    private double centerFreq;
    private double freqMin;
    private double freqMax;
    private String freqRange;
    private double freqStability;
    /** SAME_FREQ | MULTI_FREQ | HOPPING */
    private String commMode;
    private String stationType;
    private int signalCount;
    private int targetCount;
    private int activeTargetCount;
    private int mainStationCount;
    private int subStationCount;
    private Double networkPeriod;
    private double networkDutyCycle;
    private String networkLoadLevel;
    private boolean lowConfidence;
    private long updatedAt;
    private String updateMode = "FULL";
    private List<SeriesPoint> rawAzimuthSeries = new ArrayList<>();
    private List<SeriesPoint> rawSignalSeries = new ArrayList<>();
    private List<TargetView> targets = new ArrayList<>();
    private AnalysisSummary analysisSummary = new AnalysisSummary();
    /** DBSCAN | POSITION_MATCH */
    private String targetClusteringMethod;

    public int getNetworkId() { return networkId; }
    public void setNetworkId(int networkId) { this.networkId = networkId; }
    public double getFreq() { return freq; }
    public void setFreq(double freq) { this.freq = freq; }
    public String getNetworkType() { return networkType; }
    public void setNetworkType(String networkType) { this.networkType = networkType; }
    public String getCommLinkChannel() { return commLinkChannel; }
    public void setCommLinkChannel(String commLinkChannel) { this.commLinkChannel = commLinkChannel; }
    public String getCommLinkChannelLabel() { return commLinkChannelLabel; }
    public void setCommLinkChannelLabel(String commLinkChannelLabel) { this.commLinkChannelLabel = commLinkChannelLabel; }
    public String getCommLinkReason() { return commLinkReason; }
    public void setCommLinkReason(String commLinkReason) { this.commLinkReason = commLinkReason; }
    public List<String> getCommLinkEvidence() { return commLinkEvidence; }
    public void setCommLinkEvidence(List<String> commLinkEvidence) {
        this.commLinkEvidence = commLinkEvidence != null ? commLinkEvidence : new ArrayList<>();
    }
    public double getNetworkConfidence() { return networkConfidence; }
    public void setNetworkConfidence(double networkConfidence) { this.networkConfidence = networkConfidence; }
    public String getFreqBand() { return freqBand; }
    public void setFreqBand(String freqBand) { this.freqBand = freqBand; }
    public double getCenterFreq() { return centerFreq; }
    public void setCenterFreq(double centerFreq) { this.centerFreq = centerFreq; }
    public double getFreqMin() { return freqMin; }
    public void setFreqMin(double freqMin) { this.freqMin = freqMin; }
    public double getFreqMax() { return freqMax; }
    public void setFreqMax(double freqMax) { this.freqMax = freqMax; }
    public String getFreqRange() { return freqRange; }
    public void setFreqRange(String freqRange) { this.freqRange = freqRange; }
    public double getFreqStability() { return freqStability; }
    public void setFreqStability(double freqStability) { this.freqStability = freqStability; }
    public String getCommMode() { return commMode; }
    public void setCommMode(String commMode) { this.commMode = commMode; }
    public String getStationType() { return stationType; }
    public void setStationType(String stationType) { this.stationType = stationType; }
    public int getSignalCount() { return signalCount; }
    public void setSignalCount(int signalCount) { this.signalCount = signalCount; }
    public int getTargetCount() { return targetCount; }
    public void setTargetCount(int targetCount) { this.targetCount = targetCount; }
    public int getActiveTargetCount() { return activeTargetCount; }
    public void setActiveTargetCount(int activeTargetCount) { this.activeTargetCount = activeTargetCount; }
    public int getMainStationCount() { return mainStationCount; }
    public void setMainStationCount(int mainStationCount) { this.mainStationCount = mainStationCount; }
    public int getSubStationCount() { return subStationCount; }
    public void setSubStationCount(int subStationCount) { this.subStationCount = subStationCount; }
    public Double getNetworkPeriod() { return networkPeriod; }
    public void setNetworkPeriod(Double networkPeriod) { this.networkPeriod = networkPeriod; }
    public double getNetworkDutyCycle() { return networkDutyCycle; }
    public void setNetworkDutyCycle(double networkDutyCycle) { this.networkDutyCycle = networkDutyCycle; }
    public String getNetworkLoadLevel() { return networkLoadLevel; }
    public void setNetworkLoadLevel(String networkLoadLevel) { this.networkLoadLevel = networkLoadLevel; }
    public boolean isLowConfidence() { return lowConfidence; }
    public void setLowConfidence(boolean lowConfidence) { this.lowConfidence = lowConfidence; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdateMode() { return updateMode; }
    public void setUpdateMode(String updateMode) { this.updateMode = updateMode; }
    public List<SeriesPoint> getRawAzimuthSeries() { return rawAzimuthSeries; }
    public void setRawAzimuthSeries(List<SeriesPoint> rawAzimuthSeries) { this.rawAzimuthSeries = rawAzimuthSeries; }
    public List<SeriesPoint> getRawSignalSeries() { return rawSignalSeries; }
    public void setRawSignalSeries(List<SeriesPoint> rawSignalSeries) { this.rawSignalSeries = rawSignalSeries; }
    public List<TargetView> getTargets() { return targets; }
    public void setTargets(List<TargetView> targets) { this.targets = targets; }
    public AnalysisSummary getAnalysisSummary() { return analysisSummary; }
    public void setAnalysisSummary(AnalysisSummary analysisSummary) { this.analysisSummary = analysisSummary; }
    public String getTargetClusteringMethod() { return targetClusteringMethod; }
    public void setTargetClusteringMethod(String targetClusteringMethod) {
        this.targetClusteringMethod = targetClusteringMethod;
    }
}
