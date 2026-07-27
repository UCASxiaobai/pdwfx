package com.scenefinder.model;

import java.time.Instant;
import java.util.List;

/**
 * 从 {@code scene_summary.csv} 解析的单条场景摘要（用于向后端转发）。
 */
public class SceneSummaryEntry {

    private final int rank;
    private final SceneType sceneType;
    private final Instant windowStart;
    private final Instant windowEnd;
    private final double freqMinMhz;
    private final double freqMaxMhz;
    private final int distinctDeviceCount;
    private final double pollingPeriodSec;
    private final List<Integer> trackIds;
    private final String annotation;

    public SceneSummaryEntry(
            int rank,
            SceneType sceneType,
            Instant windowStart,
            Instant windowEnd,
            double freqMinMhz,
            double freqMaxMhz,
            int distinctDeviceCount,
            double pollingPeriodSec,
            List<Integer> trackIds,
            String annotation
    ) {
        this.rank = rank;
        this.sceneType = sceneType;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.freqMinMhz = freqMinMhz;
        this.freqMaxMhz = freqMaxMhz;
        this.distinctDeviceCount = distinctDeviceCount;
        this.pollingPeriodSec = pollingPeriodSec;
        this.trackIds = trackIds;
        this.annotation = annotation;
    }

    public int getRank() {
        return rank;
    }

    public SceneType getSceneType() {
        return sceneType;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public double getFreqMinMhz() {
        return freqMinMhz;
    }

    public double getFreqMaxMhz() {
        return freqMaxMhz;
    }

    public int getDistinctDeviceCount() {
        return distinctDeviceCount;
    }

    public double getPollingPeriodSec() {
        return pollingPeriodSec;
    }

    public List<Integer> getTrackIds() {
        return trackIds;
    }

    public String getAnnotation() {
        return annotation;
    }
}
