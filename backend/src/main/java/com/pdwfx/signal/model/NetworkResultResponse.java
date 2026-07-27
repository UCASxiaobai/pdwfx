package com.pdwfx.signal.model;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 单网完整业务结果（目标表 + 文字结论），不含图表时序，供外部模块直接消费。
 */
public class NetworkResultResponse {
    private int networkId;
    private double freq;
    private String networkType;
    private String commLinkChannel;
    private String commLinkChannelLabel;
    private String commLinkReason;
    private List<String> commLinkEvidence = new ArrayList<>();
    private double networkConfidence;
    private String freqBand;
    private double centerFreq;
    private double freqMin;
    private double freqMax;
    private String freqRange;
    private double freqStability;
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
    private List<TargetSummary> targets = new ArrayList<>();
    private AnalysisSummary analysisSummary = new AnalysisSummary();

    public static NetworkResultResponse from(NetworkView view) {
        NetworkResultResponse r = new NetworkResultResponse();
        r.networkId = view.getNetworkId();
        r.freq = view.getFreq();
        r.networkType = view.getNetworkType();
        r.commLinkChannel = view.getCommLinkChannel();
        r.commLinkChannelLabel = view.getCommLinkChannelLabel();
        r.commLinkReason = view.getCommLinkReason();
        if (view.getCommLinkEvidence() != null) {
            r.commLinkEvidence = view.getCommLinkEvidence();
        }
        r.networkConfidence = view.getNetworkConfidence();
        r.freqBand = view.getFreqBand();
        r.centerFreq = view.getCenterFreq();
        r.freqMin = view.getFreqMin();
        r.freqMax = view.getFreqMax();
        r.freqRange = view.getFreqRange();
        r.freqStability = view.getFreqStability();
        r.commMode = view.getCommMode();
        r.stationType = view.getStationType();
        r.signalCount = view.getSignalCount();
        r.targetCount = view.getTargetCount();
        r.activeTargetCount = view.getActiveTargetCount();
        r.mainStationCount = view.getMainStationCount();
        r.subStationCount = view.getSubStationCount();
        r.networkPeriod = view.getNetworkPeriod();
        r.networkDutyCycle = view.getNetworkDutyCycle();
        r.networkLoadLevel = view.getNetworkLoadLevel();
        r.lowConfidence = view.isLowConfidence();
        r.updatedAt = view.getUpdatedAt();
        if (view.getTargets() != null) {
            r.targets = view.getTargets().stream().map(TargetSummary::from).collect(Collectors.toList());
        }
        if (view.getAnalysisSummary() != null) {
            r.analysisSummary = view.getAnalysisSummary();
        }
        return r;
    }

    public int getNetworkId() { return networkId; }
    public double getFreq() { return freq; }
    public String getNetworkType() { return networkType; }
    public String getCommLinkChannel() { return commLinkChannel; }
    public String getCommLinkChannelLabel() { return commLinkChannelLabel; }
    public String getCommLinkReason() { return commLinkReason; }
    public List<String> getCommLinkEvidence() { return commLinkEvidence; }
    public double getNetworkConfidence() { return networkConfidence; }
    public String getFreqBand() { return freqBand; }
    public double getCenterFreq() { return centerFreq; }
    public double getFreqMin() { return freqMin; }
    public double getFreqMax() { return freqMax; }
    public String getFreqRange() { return freqRange; }
    public double getFreqStability() { return freqStability; }
    public String getCommMode() { return commMode; }
    public String getStationType() { return stationType; }
    public int getSignalCount() { return signalCount; }
    public int getTargetCount() { return targetCount; }
    public int getActiveTargetCount() { return activeTargetCount; }
    public int getMainStationCount() { return mainStationCount; }
    public int getSubStationCount() { return subStationCount; }
    public Double getNetworkPeriod() { return networkPeriod; }
    public double getNetworkDutyCycle() { return networkDutyCycle; }
    public String getNetworkLoadLevel() { return networkLoadLevel; }
    public boolean isLowConfidence() { return lowConfidence; }
    public long getUpdatedAt() { return updatedAt; }
    public List<TargetSummary> getTargets() { return targets; }
    public AnalysisSummary getAnalysisSummary() { return analysisSummary; }
}
