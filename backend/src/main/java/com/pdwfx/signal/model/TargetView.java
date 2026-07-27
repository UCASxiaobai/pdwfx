package com.pdwfx.signal.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;

public class TargetView {
    private String targetId;
    private String targetType;
    /** CONVERGING | NOT_CONVERGING | UNKNOWN */
    private String convergence;
    /** 收敛判定说明（含样本数、前后段离散度等） */
    private String convergenceDetail;
    /** 是否具备误差椭圆：NO_ELLIPSE | HAS_ELLIPSE */
    private String ellipseStatus;
    /** 误差椭圆是否收敛（仅 HAS_ELLIPSE 时有意义） */
    private Boolean ellipseConverging;
    private Double earlySpread;
    private Double lateSpread;
    /** 平台类型判定说明 */
    private String targetTypeReason;
    /** 主从角色判定说明 */
    private String roleReason;
    private String role;
    private double confidence;
    private double burstConfidence;
    private double signalStability;
    private boolean lowConfidence;
    private int evidenceSignalCount;
    @JsonIgnore
    private double score;
    private double avgSignalLevel;
    private double avgSnr;
    /** 同网归一化发射时间占比（百分数，本网各目标合计 100%） */
    private double emissionSharePct;
    /** 目标级原始/分析点散点，用于前端下方原始散点图按目标着色 */
    private List<SeriesPoint> rawAzimuthSeries = new ArrayList<>();
    private List<SeriesPoint> rawSignalSeries = new ArrayList<>();
    private List<SeriesPoint> azimuthSeries = new ArrayList<>();
    private List<SeriesPoint> signalSeries = new ArrayList<>();
    private List<SeriesPoint> freqSeries = new ArrayList<>();
    private List<TrackPoint> trackPoints = new ArrayList<>();
    /** 估计 PRI（毫秒） */
    private Double estimatedPriMs;
    /** PRI 抖动（%） */
    private double priJitterPct;
    /** 主发送周期（毫秒） */
    private Double periodMs;
    /** 周期稳定度 0~1，越高越稳定 */
    private double periodStability;
    /** 周期置信度 0~1 */
    private double periodConfidence;
    private double burstDurationMeanMs;
    private double burstDurationStdMs;
    private int burstCount;
    private double pulseCountMean;
    private double meanPriMs;
    private double priStdMs;
    private double avgDutyCycle;
    private double maxDutyCycle;
    private double freqDrift;
    private double doaDrift;
    private double amplitudeMean;
    private double amplitudeStd;
    private List<SeriesPoint> toaIntervalSeries = new ArrayList<>();
    private List<SeriesPoint> priHistogram = new ArrayList<>();
    private List<SeriesPoint> jitterSeries = new ArrayList<>();
    private List<SeriesPoint> periodErrorSeries = new ArrayList<>();
    private List<SeriesPoint> dutyCycleTrend = new ArrayList<>();
    private List<SeriesPoint> burstTimelineSeries = new ArrayList<>();
    private List<BurstWindow> bursts = new ArrayList<>();
    /** 方位定位经度（由本机位置+测向推算，或与 CSV 定位融合） */
    private Double locateLon;
    /** 方位定位纬度 */
    private Double locateLat;
    /** BEARING | CSV | MIXED | NONE */
    private String locateMethod;
    private Long detectStartMs;
    private Long detectEndMs;
    private int detectCount;
    /** 该目标簇内出现的通信频率（MHz），用于异频合批后的分频展示 */
    private List<Double> commFreqMhzList = new ArrayList<>();

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getConvergence() { return convergence; }
    public void setConvergence(String convergence) { this.convergence = convergence; }
    public String getConvergenceDetail() { return convergenceDetail; }
    public void setConvergenceDetail(String convergenceDetail) { this.convergenceDetail = convergenceDetail; }
    public String getEllipseStatus() { return ellipseStatus; }
    public void setEllipseStatus(String ellipseStatus) { this.ellipseStatus = ellipseStatus; }
    public Boolean getEllipseConverging() { return ellipseConverging; }
    public void setEllipseConverging(Boolean ellipseConverging) { this.ellipseConverging = ellipseConverging; }
    public Double getEarlySpread() { return earlySpread; }
    public void setEarlySpread(Double earlySpread) { this.earlySpread = earlySpread; }
    public Double getLateSpread() { return lateSpread; }
    public void setLateSpread(Double lateSpread) { this.lateSpread = lateSpread; }
    public String getTargetTypeReason() { return targetTypeReason; }
    public void setTargetTypeReason(String targetTypeReason) { this.targetTypeReason = targetTypeReason; }
    public String getRoleReason() { return roleReason; }
    public void setRoleReason(String roleReason) { this.roleReason = roleReason; }
    public double getAvgSignalLevel() { return avgSignalLevel; }
    public void setAvgSignalLevel(double avgSignalLevel) { this.avgSignalLevel = avgSignalLevel; }
    public double getAvgSnr() { return avgSnr; }
    public void setAvgSnr(double avgSnr) { this.avgSnr = avgSnr; }
    public double getEmissionSharePct() { return emissionSharePct; }
    public void setEmissionSharePct(double emissionSharePct) { this.emissionSharePct = emissionSharePct; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    /** 输出给外部模块的目标类型识别置信度；与 confidence 保持一致。 */
    public double getTargetConfidence() { return confidence; }
    public double getBurstConfidence() { return burstConfidence; }
    public void setBurstConfidence(double burstConfidence) { this.burstConfidence = burstConfidence; }
    public double getSignalStability() { return signalStability; }
    public void setSignalStability(double signalStability) { this.signalStability = signalStability; }
    public boolean isLowConfidence() { return lowConfidence; }
    public void setLowConfidence(boolean lowConfidence) { this.lowConfidence = lowConfidence; }
    public int getEvidenceSignalCount() { return evidenceSignalCount; }
    public void setEvidenceSignalCount(int evidenceSignalCount) { this.evidenceSignalCount = evidenceSignalCount; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public List<SeriesPoint> getRawAzimuthSeries() { return rawAzimuthSeries; }
    public void setRawAzimuthSeries(List<SeriesPoint> rawAzimuthSeries) { this.rawAzimuthSeries = rawAzimuthSeries; }
    public List<SeriesPoint> getRawSignalSeries() { return rawSignalSeries; }
    public void setRawSignalSeries(List<SeriesPoint> rawSignalSeries) { this.rawSignalSeries = rawSignalSeries; }
    public List<SeriesPoint> getAzimuthSeries() { return azimuthSeries; }
    public void setAzimuthSeries(List<SeriesPoint> azimuthSeries) { this.azimuthSeries = azimuthSeries; }
    public List<SeriesPoint> getSignalSeries() { return signalSeries; }
    public void setSignalSeries(List<SeriesPoint> signalSeries) { this.signalSeries = signalSeries; }
    public List<SeriesPoint> getFreqSeries() { return freqSeries; }
    public void setFreqSeries(List<SeriesPoint> freqSeries) { this.freqSeries = freqSeries; }
    public List<TrackPoint> getTrackPoints() { return trackPoints; }
    public void setTrackPoints(List<TrackPoint> trackPoints) { this.trackPoints = trackPoints; }
    public Double getEstimatedPriMs() { return estimatedPriMs; }
    public void setEstimatedPriMs(Double estimatedPriMs) { this.estimatedPriMs = estimatedPriMs; }
    public double getPriJitterPct() { return priJitterPct; }
    public void setPriJitterPct(double priJitterPct) { this.priJitterPct = priJitterPct; }
    public Double getPeriodMs() { return periodMs; }
    public void setPeriodMs(Double periodMs) { this.periodMs = periodMs; }
    public double getPeriodStability() { return periodStability; }
    public void setPeriodStability(double periodStability) { this.periodStability = periodStability; }
    public double getPeriodConfidence() { return periodConfidence; }
    public void setPeriodConfidence(double periodConfidence) { this.periodConfidence = periodConfidence; }
    public double getBurstDurationMeanMs() { return burstDurationMeanMs; }
    public void setBurstDurationMeanMs(double burstDurationMeanMs) { this.burstDurationMeanMs = burstDurationMeanMs; }
    public double getBurstDurationStdMs() { return burstDurationStdMs; }
    public void setBurstDurationStdMs(double burstDurationStdMs) { this.burstDurationStdMs = burstDurationStdMs; }
    public int getBurstCount() { return burstCount; }
    public void setBurstCount(int burstCount) { this.burstCount = burstCount; }
    public double getPulseCountMean() { return pulseCountMean; }
    public void setPulseCountMean(double pulseCountMean) { this.pulseCountMean = pulseCountMean; }
    public double getMeanPriMs() { return meanPriMs; }
    public void setMeanPriMs(double meanPriMs) { this.meanPriMs = meanPriMs; }
    public double getPriStdMs() { return priStdMs; }
    public void setPriStdMs(double priStdMs) { this.priStdMs = priStdMs; }
    public double getAvgDutyCycle() { return avgDutyCycle; }
    public void setAvgDutyCycle(double avgDutyCycle) { this.avgDutyCycle = avgDutyCycle; }
    public double getMaxDutyCycle() { return maxDutyCycle; }
    public void setMaxDutyCycle(double maxDutyCycle) { this.maxDutyCycle = maxDutyCycle; }
    public double getFreqDrift() { return freqDrift; }
    public void setFreqDrift(double freqDrift) { this.freqDrift = freqDrift; }
    public double getDoaDrift() { return doaDrift; }
    public void setDoaDrift(double doaDrift) { this.doaDrift = doaDrift; }
    public double getAmplitudeMean() { return amplitudeMean; }
    public void setAmplitudeMean(double amplitudeMean) { this.amplitudeMean = amplitudeMean; }
    public double getAmplitudeStd() { return amplitudeStd; }
    public void setAmplitudeStd(double amplitudeStd) { this.amplitudeStd = amplitudeStd; }
    public List<SeriesPoint> getToaIntervalSeries() { return toaIntervalSeries; }
    public void setToaIntervalSeries(List<SeriesPoint> toaIntervalSeries) { this.toaIntervalSeries = toaIntervalSeries; }
    public List<SeriesPoint> getPriHistogram() { return priHistogram; }
    public void setPriHistogram(List<SeriesPoint> priHistogram) { this.priHistogram = priHistogram; }
    public List<SeriesPoint> getJitterSeries() { return jitterSeries; }
    public void setJitterSeries(List<SeriesPoint> jitterSeries) { this.jitterSeries = jitterSeries; }
    public List<SeriesPoint> getPeriodErrorSeries() { return periodErrorSeries; }
    public void setPeriodErrorSeries(List<SeriesPoint> periodErrorSeries) { this.periodErrorSeries = periodErrorSeries; }
    public List<SeriesPoint> getDutyCycleTrend() { return dutyCycleTrend; }
    public void setDutyCycleTrend(List<SeriesPoint> dutyCycleTrend) { this.dutyCycleTrend = dutyCycleTrend; }
    public List<SeriesPoint> getBurstTimelineSeries() { return burstTimelineSeries; }
    public void setBurstTimelineSeries(List<SeriesPoint> burstTimelineSeries) { this.burstTimelineSeries = burstTimelineSeries; }
    public List<BurstWindow> getBursts() { return bursts; }
    public void setBursts(List<BurstWindow> bursts) { this.bursts = bursts; }
    public Double getLocateLon() { return locateLon; }
    public void setLocateLon(Double locateLon) { this.locateLon = locateLon; }
    public Double getLocateLat() { return locateLat; }
    public void setLocateLat(Double locateLat) { this.locateLat = locateLat; }
    public String getLocateMethod() { return locateMethod; }
    public void setLocateMethod(String locateMethod) { this.locateMethod = locateMethod; }
    public Long getDetectStartMs() { return detectStartMs; }
    public void setDetectStartMs(Long detectStartMs) { this.detectStartMs = detectStartMs; }
    public Long getDetectEndMs() { return detectEndMs; }
    public void setDetectEndMs(Long detectEndMs) { this.detectEndMs = detectEndMs; }
    public int getDetectCount() { return detectCount; }
    public void setDetectCount(int detectCount) { this.detectCount = detectCount; }
    public List<Double> getCommFreqMhzList() { return commFreqMhzList; }
    public void setCommFreqMhzList(List<Double> commFreqMhzList) {
        this.commFreqMhzList = commFreqMhzList != null ? commFreqMhzList : new ArrayList<>();
    }
}
