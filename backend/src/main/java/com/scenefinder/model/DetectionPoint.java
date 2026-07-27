package com.scenefinder.model;

import java.time.Instant;

/**
 * CSV 中的一条有效检测记录（经频率过滤后参与建轨）。
 */
public class DetectionPoint {

    private final String sourceFile;
    private final long rowIndex;
    private final Instant time;
    private final double bearingDeg;
    private final double frequencyMhz;
    private final String rawLine;

    public DetectionPoint(
            String sourceFile,
            long rowIndex,
            Instant time,
            double bearingDeg,
            double frequencyMhz,
            String rawLine
    ) {
        this.sourceFile = sourceFile;
        this.rowIndex = rowIndex;
        this.time = time;
        this.bearingDeg = bearingDeg;
        this.frequencyMhz = frequencyMhz;
        this.rawLine = rawLine;
    }

    public SourceRowRef sourceRow() {
        return new SourceRowRef(sourceFile, rowIndex);
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public long getRowIndex() {
        return rowIndex;
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

    public String getRawLine() {
        return rawLine;
    }
}
