package com.scenefinder.service;

import com.scenefinder.model.DetectionPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PollingBurstClusteringTest {

    @Test
    void separatesDistinctBearingsRegardlessOfSeparation() {
        List<DetectionPoint> burst = Arrays.asList(
                pdw(100.0),
                pdw(120.0),
                pdw(140.0),
                pdw(160.0),
                pdw(180.0)
        );
        List<PollingBurstClustering.BearingCluster> clusters =
                PollingBurstClustering.clusterConcurrentTargets(burst, 0.12);
        assertEquals(5, clusters.size());
    }

    @Test
    void mergesOnlyDuplicateBearingJitter() {
        List<DetectionPoint> burst = Arrays.asList(
                pdw(100.0),
                pdw(100.05),
                pdw(105.0)
        );
        List<PollingBurstClustering.BearingCluster> clusters =
                PollingBurstClustering.clusterConcurrentTargets(burst, 0.12);
        assertEquals(2, clusters.size());
    }

    private static DetectionPoint pdw(double bearing) {
        return new DetectionPoint("t.csv", 0, Instant.parse("2025-07-03T10:00:00Z"), bearing, 240.0, null);
    }
}
