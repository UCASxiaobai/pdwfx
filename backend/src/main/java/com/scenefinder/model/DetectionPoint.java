package com.scenefinder.model;

import java.time.Instant;

/**
 * CSV 中的一条有效检测记录（经频率过滤与 nSignalTime 场景有效性过滤后参与建轨）。
 * <p>
 * 由 {@link com.scenefinder.service.CsvDetectionReader} 从 pl / xhfw / zcsj 等列解析。
 * </p>
 */
public class DetectionPoint {

    /** 源 CSV 绝对路径，用于溯源与 SourceRowRef */
    private final String sourceFile;

    /** 源文件内行号（含表头偏移后的业务行索引，由读取器赋值） */
    private final long rowIndex;

    /** 侦获时刻（UTC Instant，由 zcsj 按系统默认时区解析） */
    private final Instant time;

    /** 测向方位，单位 °（列 xhfw） */
    private final double bearingDeg;

    /** 载频，单位 MHz（列 pl） */
    private final double frequencyMhz;

    /** 原始 CSV 整行文本，便于排查与回写 */
    private final String rawLine;

    /**
     * 驻留时长 ms（由 nSignalTime×0.01）；占空比粗估用，可为 0。
     */
    private final double signalDwellMs;

    public DetectionPoint(
            String sourceFile,
            long rowIndex,
            Instant time,
            double bearingDeg,
            double frequencyMhz,
            String rawLine
    ) {
        this(sourceFile, rowIndex, time, bearingDeg, frequencyMhz, rawLine, 0d);
    }

    public DetectionPoint(
            String sourceFile,
            long rowIndex,
            Instant time,
            double bearingDeg,
            double frequencyMhz,
            String rawLine,
            double signalDwellMs
    ) {
        this.sourceFile = sourceFile;
        this.rowIndex = rowIndex;
        this.time = time;
        this.bearingDeg = bearingDeg;
        this.frequencyMhz = frequencyMhz;
        this.rawLine = rawLine;
        this.signalDwellMs = signalDwellMs;
    }

    public SourceRowRef sourceRow() {
        return new SourceRowRef(sourceFile, rowIndex);
    }

    /** 点唯一键：源文件 + 行号 */
    public String pointKey() {
        return sourceFile + "|" + rowIndex;
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

    public double getSignalDwellMs() {
        return signalDwellMs;
    }
}
