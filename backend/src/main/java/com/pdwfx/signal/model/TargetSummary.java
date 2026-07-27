package com.pdwfx.signal.model;

/**
 * 外部模块用目标摘要（对应界面目标表），不含图表时序序列。
 */
public class TargetSummary {
    private String targetId;
    private String targetType;
    private String targetTypeLabel;
    private double emissionSharePct;
    private Double periodMs;
    private Double estimatedPriMs;
    private double burstDurationMeanMs;
    private double avgDutyCycle;
    private int burstCount;
    private String convergence;
    private String convergenceLabel;
    private String ellipseStatus;
    private String ellipseLabel;
    private String role;
    private double confidence;
    private boolean lowConfidence;
    private int evidenceSignalCount;
    private int chartPointCount;
    private String targetTypeReason;
    private String roleReason;
    private String convergenceDetail;
    private Double locateLon;
    private Double locateLat;
    private String locateMethod;
    private String locateMethodLabel;
    private Long detectStartMs;
    private Long detectEndMs;
    private int detectCount;

    public static TargetSummary from(TargetView t) {
        TargetSummary s = new TargetSummary();
        s.targetId = t.getTargetId();
        s.targetType = t.getTargetType();
        s.targetTypeLabel = targetTypeLabel(t.getTargetType());
        s.emissionSharePct = t.getEmissionSharePct();
        s.periodMs = t.getPeriodMs();
        s.estimatedPriMs = t.getEstimatedPriMs();
        s.burstDurationMeanMs = t.getBurstDurationMeanMs();
        s.avgDutyCycle = t.getAvgDutyCycle();
        s.burstCount = t.getBurstCount();
        s.convergence = t.getConvergence();
        s.convergenceLabel = convergenceLabel(t.getConvergence());
        s.ellipseStatus = t.getEllipseStatus();
        s.ellipseLabel = ellipseLabel(t);
        s.role = t.getRole();
        s.confidence = t.getConfidence();
        s.lowConfidence = t.isLowConfidence();
        s.evidenceSignalCount = t.getEvidenceSignalCount();
        s.chartPointCount = t.getAzimuthSeries() != null ? t.getAzimuthSeries().size() : 0;
        s.targetTypeReason = t.getTargetTypeReason();
        s.roleReason = t.getRoleReason();
        s.convergenceDetail = t.getConvergenceDetail();
        s.locateLon = t.getLocateLon();
        s.locateLat = t.getLocateLat();
        s.locateMethod = t.getLocateMethod();
        s.locateMethodLabel = locateMethodLabel(t.getLocateMethod());
        s.detectStartMs = t.getDetectStartMs();
        s.detectEndMs = t.getDetectEndMs();
        s.detectCount = t.getDetectCount();
        return s;
    }

    private static String locateMethodLabel(String method) {
        if (method == null) return "—";
        switch (method) {
            case "BEARING": return "方位推算";
            case "CSV": return "CSV定位";
            case "MIXED": return "融合定位";
            case "NONE": return "无定位";
            default: return method;
        }
    }

    private static String targetTypeLabel(String type) {
        if (type == null) return "未知";
        switch (type) {
            case "GROUND": return "地面站";
            case "AWACS": return "预警机";
            case "AIR": return "飞机";
            default: return type;
        }
    }

    private static String convergenceLabel(String c) {
        if ("CONVERGING".equals(c)) return "收敛";
        if ("NOT_CONVERGING".equals(c)) return "不收敛";
        return "未知";
    }

    private static String ellipseLabel(TargetView t) {
        if ("NO_ELLIPSE".equals(t.getEllipseStatus())) return "无椭圆";
        if (Boolean.TRUE.equals(t.getEllipseConverging())) return "椭圆收敛";
        if (Boolean.FALSE.equals(t.getEllipseConverging())) return "椭圆不收敛";
        return "有椭圆";
    }

    public String getTargetId() { return targetId; }
    public String getTargetType() { return targetType; }
    public String getTargetTypeLabel() { return targetTypeLabel; }
    public double getEmissionSharePct() { return emissionSharePct; }
    public Double getPeriodMs() { return periodMs; }
    public Double getEstimatedPriMs() { return estimatedPriMs; }
    public double getBurstDurationMeanMs() { return burstDurationMeanMs; }
    public double getAvgDutyCycle() { return avgDutyCycle; }
    public int getBurstCount() { return burstCount; }
    public String getConvergence() { return convergence; }
    public String getConvergenceLabel() { return convergenceLabel; }
    public String getEllipseStatus() { return ellipseStatus; }
    public String getEllipseLabel() { return ellipseLabel; }
    public String getRole() { return role; }
    public double getConfidence() { return confidence; }
    public boolean isLowConfidence() { return lowConfidence; }
    public int getEvidenceSignalCount() { return evidenceSignalCount; }
    public int getChartPointCount() { return chartPointCount; }
    public String getTargetTypeReason() { return targetTypeReason; }
    public String getRoleReason() { return roleReason; }
    public String getConvergenceDetail() { return convergenceDetail; }
    public Double getLocateLon() { return locateLon; }
    public Double getLocateLat() { return locateLat; }
    public String getLocateMethod() { return locateMethod; }
    public String getLocateMethodLabel() { return locateMethodLabel; }
    public Long getDetectStartMs() { return detectStartMs; }
    public Long getDetectEndMs() { return detectEndMs; }
    public int getDetectCount() { return detectCount; }
}
