package com.scenefinder.model;

import java.util.LinkedHashSet;
import java.util.Set;

/** 同频点（容差内）的源 CSV 行集合，用于场景按频拆分分析。 */
public class FreqRowGroup {

    private final double centerMhz;
    private final Set<SourceRowRef> rowRefs;

    public FreqRowGroup(double centerMhz, Set<SourceRowRef> rowRefs) {
        this.centerMhz = centerMhz;
        this.rowRefs = rowRefs != null ? rowRefs : new LinkedHashSet<>();
    }

    public double getCenterMhz() {
        return centerMhz;
    }

    public Set<SourceRowRef> getRowRefs() {
        return rowRefs;
    }
}
