package com.scenefinder.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BearingMathTest {

    @Test
    void countBearingClustersMergesOnlyDuplicates() {
        assertEquals(3, BearingMath.countBearingClusters(Arrays.asList(100.0, 101.0, 102.0), 0.5));
        assertEquals(1, BearingMath.countBearingClusters(Arrays.asList(100.0, 100.2, 100.4), 0.5));
        assertEquals(1, BearingMath.countBearingClusters(Arrays.asList(100.0, 103.0, 106.0), 4.0));
    }

    @Test
    void largestClusterFractionUsesSameMergeRule() {
        List<Double> tight = Arrays.asList(100.0, 100.2, 100.4);
        assertEquals(1.0, BearingMath.largestClusterFraction(tight, 0.5), 0.001);

        List<Double> threeDevices = Arrays.asList(100.0, 101.0, 102.0);
        assertEquals(1.0 / 3.0, BearingMath.largestClusterFraction(threeDevices, 0.5), 0.001);
    }
}
