package com.scenefinder.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectionFindingMatcherTest {

    private static void addPointOnRay(
            DirectionFindingMatcher.DeviceTrajectory traj,
            long time,
            double rxLon,
            double rxLat,
            double bearingDeg,
            double distM
    ) {
        double brng = Math.toRadians(bearingDeg);
        double lat1 = Math.toRadians(rxLat);
        double lon1 = Math.toRadians(rxLon);
        double r = 6371000.0;
        double d = distM / r;
        double lat2 = Math.asin(
                Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brng)
        );
        double lon2 = lon1 + Math.atan2(
                Math.sin(brng) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2)
        );
        traj.addPoint(new DirectionFindingMatcher.DeviceTrack(
                traj.deviceId,
                time,
                Math.toDegrees(lon2),
                Math.toDegrees(lat2)
        ));
    }

    private static DirectionFindingMatcher.DeviceTrajectory trajectoryForBatch(
            String deviceId,
            List<DirectionFindingMatcher.Measurement> batch,
            double distM
    ) {
        DirectionFindingMatcher.DeviceTrajectory traj = new DirectionFindingMatcher.DeviceTrajectory(deviceId);
        for (DirectionFindingMatcher.Measurement m : batch) {
            addPointOnRay(traj, m.time, m.rxLon, m.rxLat, m.bearing, distM);
        }
        return traj;
    }

    @Test
    void sustainedTenSecondsLocksDeviceAndSkipsOutlier() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            long t = i * 1000L;
            double bearing = i == 5 ? 90.0 : 58.0 + i * 0.1;
            batch.add(new DirectionFindingMatcher.Measurement(t, 116.0, 40.0, bearing, 100, "CXX-001"));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory traj = new DirectionFindingMatcher.DeviceTrajectory("DEV001");
        for (int i = 0; i <= 20; i++) {
            if (i == 5) {
                continue;
            }
            DirectionFindingMatcher.Measurement m = batch.get(i);
            addPointOnRay(traj, m.time, m.rxLon, m.rxLat, m.bearing, 30_000);
        }
        trajectories.put("DEV001", traj);

        DirectionFindingMatcher.MatchConfig config = new DirectionFindingMatcher.MatchConfig();
        config.sustainDurationMs = 10_000L;
        config.angleThresholdDeg = 10.0;
        config.maxTimeDeltaMs = 2000L;
        config.distanceThresholdM = 50_000;
        config.retroactiveOnConfirm = true;

        Map<DirectionFindingMatcher.Measurement, String> result =
                DirectionFindingMatcher.match(batch, trajectories, config);

        assertNull(result.get(batch.get(5)));
        assertEquals("DEV001", result.get(batch.get(16)));
        assertEquals("DEV001", result.get(batch.get(20)));

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(batch, trajectories, config);
        assertEquals("DEV001", summary.get("CXX-001"));
    }

    @Test
    void sustainDurationRequiresTenSeconds() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        for (int i = 0; i <= 5; i++) {
            batch.add(new DirectionFindingMatcher.Measurement(i * 1000L, 116.0, 40.0, 58.0, 100, "B1"));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        trajectories.put("DEV001", trajectoryForBatch("DEV001", batch, 30_000));

        DirectionFindingMatcher.MatchConfig config = new DirectionFindingMatcher.MatchConfig();
        config.sustainDurationMs = 10_000L;
        config.angleThresholdDeg = 10.0;
        config.maxTimeDeltaMs = 2000L;
        config.distanceThresholdM = 50_000;

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(batch, trajectories, config);
        assertFalse(summary.containsKey("B1"));
    }

    @Test
    void rejectsLocateFixOutsideTimeThreshold() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            batch.add(new DirectionFindingMatcher.Measurement(5000 + i * 1000L, 116.0, 40.0, 58.0, 100, "B1"));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory traj = new DirectionFindingMatcher.DeviceTrajectory("DEV001");
        traj.addPoint(new DirectionFindingMatcher.DeviceTrack("DEV001", 1000, 116.1, 40.05));
        trajectories.put("DEV001", traj);

        DirectionFindingMatcher.MatchConfig config = new DirectionFindingMatcher.MatchConfig();
        config.sustainDurationMs = 10_000L;
        config.angleThresholdDeg = 1.0;
        config.maxTimeDeltaMs = 2000L;
        config.distanceThresholdM = 50_000;

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(batch, trajectories, config);
        assertFalse(summary.containsKey("B1"));
    }

    @Test
    void matchesWhenIgnoreTimeDespiteLargeTimeDelta() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            batch.add(new DirectionFindingMatcher.Measurement(5000 + i * 1000L, 116.0, 40.0, 58.0, 100, "B1"));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory traj = new DirectionFindingMatcher.DeviceTrajectory("DEV001");
        traj.addPoint(new DirectionFindingMatcher.DeviceTrack("DEV001", 1000, 116.1, 40.05));
        trajectories.put("DEV001", traj);

        DirectionFindingMatcher.MatchConfig config = new DirectionFindingMatcher.MatchConfig();
        config.sustainDurationMs = 10_000L;
        config.angleThresholdDeg = 1.0;
        config.maxTimeDeltaMs = 2000L;
        config.distanceThresholdM = 50_000;
        config.ignoreTimeDimension = true;

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(batch, trajectories, config);
        assertEquals("DEV001", summary.get("B1"));
    }

    @Test
    void matchesWithinTwoSecondTimeThresholdAndTenSecondSustain() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            batch.add(new DirectionFindingMatcher.Measurement(1000 + i * 1000L, 116.0, 40.0, 58.0, 100, "B1"));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        trajectories.put("DEV001", trajectoryForBatch("DEV001", batch, 30_000));

        DirectionFindingMatcher.MatchConfig config = new DirectionFindingMatcher.MatchConfig();
        config.sustainDurationMs = 10_000L;
        config.angleThresholdDeg = 1.0;
        config.maxTimeDeltaMs = 2000L;
        config.distanceThresholdM = 50_000;

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(batch, trajectories, config);
        assertEquals("DEV001", summary.get("B1"));
    }

    @Test
    void groupsByVirtualFreqBatchWhenNoBatchId() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            batch.add(new DirectionFindingMatcher.Measurement(1000 + i * 1000L, 116.0, 40.0, 58.0 + i * 0.05, 200));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        trajectories.put("DEV001", trajectoryForBatch("DEV001", batch, 30_000));

        DirectionFindingMatcher.MatchConfig config = new DirectionFindingMatcher.MatchConfig();
        config.sustainDurationMs = 10_000L;
        config.angleThresholdDeg = 10.0;
        config.maxTimeDeltaMs = 2000L;
        config.distanceThresholdM = 50_000;

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(batch, trajectories, config);
        assertTrue(summary.containsKey("freq:200"));
    }
}
