package com.scenefinder.model;

/**
 * 一次分析标出的预警机在某一频点上的占用时段（两端已加 pad）。
 */
public class AwacsOccupancyWindow {

    private final double freqCenterMhz;
    private final double freqToleranceMhz;
    private final long tStartMs;
    private final long tEndMs;
    private final String seedTargetId;

    public AwacsOccupancyWindow(
            double freqCenterMhz,
            double freqToleranceMhz,
            long tStartMs,
            long tEndMs,
            String seedTargetId
    ) {
        this.freqCenterMhz = freqCenterMhz;
        this.freqToleranceMhz = freqToleranceMhz;
        this.tStartMs = tStartMs;
        this.tEndMs = tEndMs;
        this.seedTargetId = seedTargetId;
    }

    public boolean matches(double freqMhz, long timeMs) {
        if (timeMs < tStartMs || timeMs > tEndMs) {
            return false;
        }
        return Math.abs(freqMhz - freqCenterMhz) <= freqToleranceMhz;
    }

    public double getFreqCenterMhz() {
        return freqCenterMhz;
    }

    public double getFreqToleranceMhz() {
        return freqToleranceMhz;
    }

    public long getTStartMs() {
        return tStartMs;
    }

    public long getTEndMs() {
        return tEndMs;
    }

    public String getSeedTargetId() {
        return seedTargetId;
    }
}
