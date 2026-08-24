package com.pdwfx.signal.model;

import com.scenefinder.service.DirectionFindingMatcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DirectionFindingMatchResponse {
    /** 测向批 → 锁定航迹 deviceId（mbmc） */
    private Map<String, String> batchToDevice = new LinkedHashMap<>();
    /** 测向批 → 目标内码 mbnm */
    private Map<String, String> batchToTargetId = new LinkedHashMap<>();
    private List<MatchedDfPoint> matchedPoints = new ArrayList<>();
    /** 测向批 → 该批全部测向点（供地图批查看） */
    private Map<String, List<BearingMeasurementRow>> measurementsByBatch = new LinkedHashMap<>();
    private int measurementCount;
    private int trajectoryDeviceCount;
    private int matchedPointCount;
    private DirectionFindingMatcher.MatchConfig config;
    /** 测向批 → 展示名（如 目标1）；无换频编批时为空 */
    private Map<String, String> batchLabels = new LinkedHashMap<>();

    public Map<String, String> getBatchToDevice() { return batchToDevice; }
    public void setBatchToDevice(Map<String, String> batchToDevice) {
        this.batchToDevice = batchToDevice != null ? batchToDevice : new LinkedHashMap<>();
    }
    public Map<String, String> getBatchToTargetId() { return batchToTargetId; }
    public void setBatchToTargetId(Map<String, String> batchToTargetId) {
        this.batchToTargetId = batchToTargetId != null ? batchToTargetId : new LinkedHashMap<>();
    }
    public List<MatchedDfPoint> getMatchedPoints() { return matchedPoints; }
    public void setMatchedPoints(List<MatchedDfPoint> matchedPoints) {
        this.matchedPoints = matchedPoints != null ? matchedPoints : new ArrayList<>();
    }
    public Map<String, List<BearingMeasurementRow>> getMeasurementsByBatch() { return measurementsByBatch; }
    public void setMeasurementsByBatch(Map<String, List<BearingMeasurementRow>> measurementsByBatch) {
        this.measurementsByBatch = measurementsByBatch != null ? measurementsByBatch : new LinkedHashMap<>();
    }
    public int getMeasurementCount() { return measurementCount; }
    public void setMeasurementCount(int measurementCount) { this.measurementCount = measurementCount; }
    public int getTrajectoryDeviceCount() { return trajectoryDeviceCount; }
    public void setTrajectoryDeviceCount(int trajectoryDeviceCount) {
        this.trajectoryDeviceCount = trajectoryDeviceCount;
    }
    public int getMatchedPointCount() { return matchedPointCount; }
    public void setMatchedPointCount(int matchedPointCount) { this.matchedPointCount = matchedPointCount; }
    public DirectionFindingMatcher.MatchConfig getConfig() { return config; }
    public void setConfig(DirectionFindingMatcher.MatchConfig config) { this.config = config; }
    public Map<String, String> getBatchLabels() { return batchLabels; }
    public void setBatchLabels(Map<String, String> batchLabels) {
        this.batchLabels = batchLabels != null ? batchLabels : new LinkedHashMap<>();
    }

    public static class MatchedDfPoint {
        private String batchId;
        private String signalId;
        private long timeMs;
        private double bearing;
        private String deviceId;
        private String targetId;

        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }
        public String getSignalId() { return signalId; }
        public void setSignalId(String signalId) { this.signalId = signalId; }
        public long getTimeMs() { return timeMs; }
        public void setTimeMs(long timeMs) { this.timeMs = timeMs; }
        public double getBearing() { return bearing; }
        public void setBearing(double bearing) { this.bearing = bearing; }
        public String getDeviceId() { return deviceId; }
        public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
        public String getTargetId() { return targetId; }
        public void setTargetId(String targetId) { this.targetId = targetId; }
    }

    public static class BearingMeasurementRow {
        private String batchId;
        private String signalId;
        private long timeMs;
        private double bearing;
        private double rxLon;
        private double rxLat;
        private int freqHz;

        public String getBatchId() { return batchId; }
        public void setBatchId(String batchId) { this.batchId = batchId; }
        public String getSignalId() { return signalId; }
        public void setSignalId(String signalId) { this.signalId = signalId; }
        public long getTimeMs() { return timeMs; }
        public void setTimeMs(long timeMs) { this.timeMs = timeMs; }
        public double getBearing() { return bearing; }
        public void setBearing(double bearing) { this.bearing = bearing; }
        public double getRxLon() { return rxLon; }
        public void setRxLon(double rxLon) { this.rxLon = rxLon; }
        public double getRxLat() { return rxLat; }
        public void setRxLat(double rxLat) { this.rxLat = rxLat; }
        public int getFreqHz() { return freqHz; }
        public void setFreqHz(int freqHz) { this.freqHz = freqHz; }
    }
}
