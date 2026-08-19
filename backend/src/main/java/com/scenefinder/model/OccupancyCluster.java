package com.scenefinder.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 同频占用窗合并后的指挥网簇：一条二次场景对应一簇。
 */
public class OccupancyCluster {

    private final double freqCenterMhz;
    private final double freqMinMhz;
    private final double freqMaxMhz;
    private final double freqToleranceMhz;
    private final long tStartMs;
    private final long tEndMs;
    private final List<AwacsOccupancyWindow> windows;

    public OccupancyCluster(
            double freqCenterMhz,
            double freqMinMhz,
            double freqMaxMhz,
            double freqToleranceMhz,
            long tStartMs,
            long tEndMs,
            List<AwacsOccupancyWindow> windows
    ) {
        this.freqCenterMhz = freqCenterMhz;
        this.freqMinMhz = freqMinMhz;
        this.freqMaxMhz = freqMaxMhz;
        this.freqToleranceMhz = freqToleranceMhz;
        this.tStartMs = tStartMs;
        this.tEndMs = tEndMs;
        if (windows == null) {
            this.windows = Collections.emptyList();
        } else {
            this.windows = Collections.unmodifiableList(new ArrayList<AwacsOccupancyWindow>(windows));
        }
    }

    public boolean matchesPoint(double freqMhz, long timeMs) {
        for (AwacsOccupancyWindow window : windows) {
            if (window.matches(freqMhz, timeMs)) {
                return true;
            }
        }
        return false;
    }

    public double getFreqCenterMhz() {
        return freqCenterMhz;
    }

    public double getFreqMinMhz() {
        return freqMinMhz;
    }

    public double getFreqMaxMhz() {
        return freqMaxMhz;
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

    public List<AwacsOccupancyWindow> getWindows() {
        return windows;
    }
}
