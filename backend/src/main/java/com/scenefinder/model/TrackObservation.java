package com.scenefinder.model;

import java.time.Instant;

/**
 * 轨迹上的一次观测（通常对应一帧内某方位簇关联到该轨迹的所有检测点之一）。
 */
public class TrackObservation {

    private final Instant time;
    private final double bearingDeg;
    private final double frequencyMhz;
    private final String sourceFile;
    private final long rowIndex;

    public TrackObservation(
            Instant time,
            double bearingDeg,
            double frequencyMhz,
            String sourceFile,
            long rowIndex
    ) {
        this.time = time;
        this.bearingDeg = bearingDeg;
        this.frequencyMhz = frequencyMhz;
        this.sourceFile = sourceFile;
        this.rowIndex = rowIndex;
    }

    public SourceRowRef sourceRow() {
        return new SourceRowRef(sourceFile, rowIndex);
    }

    public Instant getTime() {
        return time;
    }

    public double getBearingDeg() {
        return bearingDeg;
    }

    public double getFrequencyMhz() {
        return frequencyMhz;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public long getRowIndex() {
        return rowIndex;
    }
}
