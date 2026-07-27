package com.pdwfx.signal.model;



import java.util.ArrayList;

import java.util.List;

import java.util.stream.Collectors;



public class NetworkSummary {

    private int networkId;

    private double freq;

    private String networkType;

    private double networkConfidence;

    private String freqBand;

    private String freqRange;

    private double freqStability;

    private String commMode;

    private String stationType;

    private int signalCount;

    private int targetCount;

    private String commLinkChannel;

    private String commLinkChannelLabel;

    private String commLinkReason;

    private int groundTargetCount;
    private int awacsTargetCount;
    private int airTargetCount;

    /** 详情构建后填充：所属目标摘要（与界面目标表一致） */

    private List<TargetSummary> targets = new ArrayList<>();

    /** 详情构建后填充：网络/目标文字结论 */

    private AnalysisSummary analysisSummary;



    public void enrichFromView(NetworkView view) {

        if (view == null) return;

        setNetworkType(view.getNetworkType());

        setNetworkConfidence(view.getNetworkConfidence());

        setFreqBand(view.getFreqBand());

        setFreqRange(view.getFreqRange());

        setFreqStability(view.getFreqStability());

        setCommMode(view.getCommMode());

        setStationType(view.getStationType());

        setTargetCount(view.getTargetCount());

        setCommLinkChannel(view.getCommLinkChannel());

        setCommLinkChannelLabel(view.getCommLinkChannelLabel());

        setCommLinkReason(view.getCommLinkReason());

        if (view.getTargets() != null) {
            targets = view.getTargets().stream().map(TargetSummary::from).collect(Collectors.toList());
            groundTargetCount = 0;
            awacsTargetCount = 0;
            airTargetCount = 0;
            for (TargetView t : view.getTargets()) {
                if ("GROUND".equals(t.getTargetType())) groundTargetCount++;
                else if ("AWACS".equals(t.getTargetType())) awacsTargetCount++;
                else airTargetCount++;
            }
        }

        analysisSummary = view.getAnalysisSummary();

    }



    public int getNetworkId() { return networkId; }

    public void setNetworkId(int networkId) { this.networkId = networkId; }

    public double getFreq() { return freq; }

    public void setFreq(double freq) { this.freq = freq; }

    public String getNetworkType() { return networkType; }

    public void setNetworkType(String networkType) { this.networkType = networkType; }

    public double getNetworkConfidence() { return networkConfidence; }

    public void setNetworkConfidence(double networkConfidence) { this.networkConfidence = networkConfidence; }

    public String getFreqBand() { return freqBand; }

    public void setFreqBand(String freqBand) { this.freqBand = freqBand; }

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

    public String getCommLinkChannel() { return commLinkChannel; }

    public void setCommLinkChannel(String commLinkChannel) { this.commLinkChannel = commLinkChannel; }

    public String getCommLinkChannelLabel() { return commLinkChannelLabel; }

    public void setCommLinkChannelLabel(String commLinkChannelLabel) { this.commLinkChannelLabel = commLinkChannelLabel; }

    public String getCommLinkReason() { return commLinkReason; }

    public void setCommLinkReason(String commLinkReason) { this.commLinkReason = commLinkReason; }

    public int getGroundTargetCount() { return groundTargetCount; }
    public void setGroundTargetCount(int groundTargetCount) { this.groundTargetCount = groundTargetCount; }
    public int getAwacsTargetCount() { return awacsTargetCount; }
    public void setAwacsTargetCount(int awacsTargetCount) { this.awacsTargetCount = awacsTargetCount; }
    public int getAirTargetCount() { return airTargetCount; }
    public void setAirTargetCount(int airTargetCount) { this.airTargetCount = airTargetCount; }

    public List<TargetSummary> getTargets() { return targets; }

    public void setTargets(List<TargetSummary> targets) { this.targets = targets != null ? targets : new ArrayList<>(); }

    public AnalysisSummary getAnalysisSummary() { return analysisSummary; }

    public void setAnalysisSummary(AnalysisSummary analysisSummary) { this.analysisSummary = analysisSummary; }

}


