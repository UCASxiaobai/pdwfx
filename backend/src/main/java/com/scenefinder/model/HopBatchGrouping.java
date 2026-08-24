package com.scenefinder.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次建轨+换频分析得到的全部测向编批（含噪声单轨，不受展示条数截断）。
 */
public class HopBatchGrouping {

    private Instant windowStart;
    private Instant windowEnd;
    private int noiseSkippedCount;
    private int hopTrackCount;
    private final List<HopBatch> batches = new ArrayList<HopBatch>();

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public void setWindowEnd(Instant windowEnd) {
        this.windowEnd = windowEnd;
    }

    public int getNoiseSkippedCount() {
        return noiseSkippedCount;
    }

    public void setNoiseSkippedCount(int noiseSkippedCount) {
        this.noiseSkippedCount = noiseSkippedCount;
    }

    public int getHopTrackCount() {
        return hopTrackCount;
    }

    public void setHopTrackCount(int hopTrackCount) {
        this.hopTrackCount = hopTrackCount;
    }

    public List<HopBatch> getBatches() {
        return batches;
    }

    public void add(HopBatch batch) {
        if (batch != null) {
            batches.add(batch);
        }
    }

    public boolean isEmpty() {
        return batches.isEmpty();
    }

    public Map<String, String> labels() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (HopBatch batch : batches) {
            if (batch.getBatchId() != null && batch.getLabel() != null) {
                map.put(batch.getBatchId(), batch.getLabel());
            }
        }
        return map;
    }
}
