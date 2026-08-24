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
        DirectionFindingMatcher.Measurement probe = batch.get(0);
        addPointOnRay(traj, 1000, probe.rxLon, probe.rxLat, probe.bearing, 30_000);
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

    @Test
    void fineRejectsEightMinuteClockSkewButCoarseLocks() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            batch.add(new DirectionFindingMatcher.Measurement(1000 + i * 1000L, 116.0, 40.0, 58.0, 100, "B1"));
        }
        DirectionFindingMatcher.Measurement probe = batch.get(0);
        long locateTime = probe.time + 8L * 60L * 1000L;

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory traj = new DirectionFindingMatcher.DeviceTrajectory("DEV001");
        addPointOnRay(traj, locateTime, probe.rxLon, probe.rxLat, probe.bearing, 30_000);
        trajectories.put("DEV001", traj);

        DirectionFindingMatcher.MatchConfig fine = new DirectionFindingMatcher.MatchConfig();
        fine.sustainDurationMs = 10_000L;
        fine.angleThresholdDeg = 1.0;
        fine.maxTimeDeltaMs = 2000L;
        fine.distanceThresholdM = 50_000;
        assertFalse(DirectionFindingMatcher.matchBatchSummary(batch, trajectories, fine).containsKey("B1"));

        DirectionFindingMatcher.MatchConfig coarse = new DirectionFindingMatcher.MatchConfig();
        coarse.coarseMatch = true;
        coarse.minHits = 1;
        coarse.angleThresholdDeg = 1.0;
        coarse.maxTimeDeltaMs = 600_000L;
        coarse.distanceThresholdM = 50_000;
        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(batch, trajectories, coarse);
        assertEquals("DEV001", summary.get("B1"));
    }

    @Test
    void coarseLocksSparseLocateWithoutTenSecondSustain() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        for (int i = 0; i <= 3; i++) {
            batch.add(new DirectionFindingMatcher.Measurement(i * 1000L, 116.0, 40.0, 58.0, 100, "B1"));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory traj = new DirectionFindingMatcher.DeviceTrajectory("DEV001");
        DirectionFindingMatcher.Measurement probe = batch.get(1);
        addPointOnRay(traj, probe.time, probe.rxLon, probe.rxLat, probe.bearing, 30_000);
        trajectories.put("DEV001", traj);

        DirectionFindingMatcher.MatchConfig fine = new DirectionFindingMatcher.MatchConfig();
        fine.sustainDurationMs = 10_000L;
        fine.angleThresholdDeg = 1.0;
        fine.maxTimeDeltaMs = 2000L;
        fine.distanceThresholdM = 50_000;
        assertFalse(DirectionFindingMatcher.matchBatchSummary(batch, trajectories, fine).containsKey("B1"));

        DirectionFindingMatcher.MatchConfig coarse = new DirectionFindingMatcher.MatchConfig();
        coarse.coarseMatch = true;
        coarse.minHits = 1;
        coarse.angleThresholdDeg = 1.0;
        coarse.maxTimeDeltaMs = 2000L;
        coarse.distanceThresholdM = 50_000;
        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(batch, trajectories, coarse);
        assertEquals("DEV001", summary.get("B1"));

        Map<DirectionFindingMatcher.Measurement, String> points =
                DirectionFindingMatcher.match(batch, trajectories, coarse);
        assertTrue(points.size() >= 1);
    }

    @Test
    void coarseMinHitsTwoRejectsSingleAlignedFrame() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        batch.add(new DirectionFindingMatcher.Measurement(1000L, 116.0, 40.0, 58.0, 100, "B1"));
        batch.add(new DirectionFindingMatcher.Measurement(2000L, 116.0, 40.0, 170.0, 100, "B1"));

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory traj = new DirectionFindingMatcher.DeviceTrajectory("DEV001");
        addPointOnRay(traj, 1000L, 116.0, 40.0, 58.0, 30_000);
        trajectories.put("DEV001", traj);

        DirectionFindingMatcher.MatchConfig coarse = new DirectionFindingMatcher.MatchConfig();
        coarse.coarseMatch = true;
        coarse.minHits = 2;
        coarse.angleThresholdDeg = 1.0;
        coarse.maxTimeDeltaMs = 2000L;
        coarse.distanceThresholdM = 50_000;
        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(batch, trajectories, coarse);
        assertFalse(summary.containsKey("B1"));
    }

    @Test
    void timeIndexAgreesWithLinearScanAndCoarseLock() {
        List<DirectionFindingMatcher.Measurement> batch = new ArrayList<>();
        for (int i = 0; i <= 8; i++) {
            batch.add(new DirectionFindingMatcher.Measurement(i * 1000L, 116.0, 40.0, 58.0, 100, "B1"));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory traj = new DirectionFindingMatcher.DeviceTrajectory("DEV001");
        for (int i = 0; i <= 8; i++) {
            DirectionFindingMatcher.Measurement m = batch.get(i);
            addPointOnRay(traj, m.time, m.rxLon, m.rxLat, m.bearing, 30_000);
        }
        addPointOnRay(traj, -3_600_000L, 116.0, 40.0, 10.0, 30_000);
        addPointOnRay(traj, 3_600_000L, 116.0, 40.0, 170.0, 30_000);
        trajectories.put("DEV001", traj);

        DirectionFindingMatcher.MatchConfig coarse = new DirectionFindingMatcher.MatchConfig();
        coarse.coarseMatch = true;
        coarse.minHits = 1;
        coarse.angleThresholdDeg = 1.0;
        coarse.maxTimeDeltaMs = 600_000L;
        coarse.distanceThresholdM = 50_000;

        for (DirectionFindingMatcher.Measurement m : batch) {
            assertTrue(DirectionFindingMatcher.sameAlignmentAsLinear(m, traj, coarse));
        }

        DirectionFindingMatcher.MatchAllResult all =
                DirectionFindingMatcher.matchAll(batch, trajectories, coarse);
        assertEquals("DEV001", all.batchSummary.get("B1"));
        assertEquals(all.pointMatches.size(), DirectionFindingMatcher.match(batch, trajectories, coarse).size());
        assertEquals("DEV001", DirectionFindingMatcher.matchBatchSummary(batch, trajectories, coarse).get("B1"));
        for (DirectionFindingMatcher.Measurement m : batch) {
            assertEquals("DEV001", all.pointMatches.get(m));
        }
    }

    @Test
    void exclusiveAssignGivesSecondBatchItsIdleTarget() {
        List<DirectionFindingMatcher.Measurement> ms = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ms.add(new DirectionFindingMatcher.Measurement(1000 + i * 1000L, 116.0, 40.0, 58.0, 100, "hop:1"));
        }
        for (int i = 0; i < 5; i++) {
            ms.add(new DirectionFindingMatcher.Measurement(20000 + i * 1000L, 116.0, 40.0, 58.0, 200, "hop:2"));
        }
        for (int i = 0; i < 4; i++) {
            ms.add(new DirectionFindingMatcher.Measurement(30000 + i * 1000L, 116.0, 40.0, 170.0, 200, "hop:2"));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory targetA = new DirectionFindingMatcher.DeviceTrajectory("A");
        DirectionFindingMatcher.DeviceTrajectory targetB = new DirectionFindingMatcher.DeviceTrajectory("B");
        for (DirectionFindingMatcher.Measurement m : ms) {
            if (Math.abs(m.bearing - 58.0) < 1e-6) {
                addPointOnRay(targetA, m.time, m.rxLon, m.rxLat, 58.0, 30_000);
            } else {
                addPointOnRay(targetB, m.time, m.rxLon, m.rxLat, 170.0, 30_000);
            }
        }
        trajectories.put("A", targetA);
        trajectories.put("B", targetB);

        DirectionFindingMatcher.MatchConfig coarse = new DirectionFindingMatcher.MatchConfig();
        coarse.coarseMatch = true;
        coarse.minHits = 1;
        coarse.angleThresholdDeg = 1.0;
        coarse.maxTimeDeltaMs = 2000L;
        coarse.distanceThresholdM = 50_000;
        coarse.exclusiveAssign = true;

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(ms, trajectories, coarse);
        assertEquals("A", summary.get("hop:1"));
        assertEquals("B", summary.get("hop:2"));
    }

    @Test
    void exclusiveAssignKeepsOneBatchWhenBothPreferSameTarget() {
        List<DirectionFindingMatcher.Measurement> ms = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ms.add(new DirectionFindingMatcher.Measurement(1000 + i * 1000L, 116.0, 40.0, 58.0, 286_000_000, "hop:1"));
        }
        for (int i = 0; i < 3; i++) {
            ms.add(new DirectionFindingMatcher.Measurement(20000 + i * 1000L, 116.0, 40.0, 58.0, 300_000_000, "hop:2"));
        }

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory targetA = new DirectionFindingMatcher.DeviceTrajectory("A");
        for (DirectionFindingMatcher.Measurement m : ms) {
            addPointOnRay(targetA, m.time, m.rxLon, m.rxLat, 58.0, 30_000);
        }
        trajectories.put("A", targetA);

        DirectionFindingMatcher.MatchConfig coarse = new DirectionFindingMatcher.MatchConfig();
        coarse.coarseMatch = true;
        coarse.minHits = 1;
        coarse.angleThresholdDeg = 1.0;
        coarse.maxTimeDeltaMs = 2000L;
        coarse.distanceThresholdM = 50_000;
        coarse.exclusiveAssign = true;

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(ms, trajectories, coarse);
        assertEquals("A", summary.get("hop:1"));
        assertFalse(summary.containsKey("hop:2"));
    }

    @Test
    void sameHitsSmallerCrossTrackWins() {
        List<DirectionFindingMatcher.Measurement> batch1 = new ArrayList<>();
        List<DirectionFindingMatcher.Measurement> batch2 = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            batch1.add(new DirectionFindingMatcher.Measurement(1000 + i * 1000L, 116.0, 40.0, 58.0, 100, "B1"));
            batch2.add(new DirectionFindingMatcher.Measurement(10000 + i * 1000L, 116.0, 40.0, 58.25, 100, "B2"));
        }
        List<DirectionFindingMatcher.Measurement> ms = new ArrayList<>();
        ms.addAll(batch1);
        ms.addAll(batch2);

        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        DirectionFindingMatcher.DeviceTrajectory targetA = new DirectionFindingMatcher.DeviceTrajectory("A");
        for (DirectionFindingMatcher.Measurement m : batch1) {
            addPointOnRay(targetA, m.time, m.rxLon, m.rxLat, 58.0, 30_000);
        }
        for (DirectionFindingMatcher.Measurement m : batch2) {
            addPointOnRay(targetA, m.time, m.rxLon, m.rxLat, 58.0, 30_000);
        }
        trajectories.put("A", targetA);

        DirectionFindingMatcher.MatchConfig coarse = new DirectionFindingMatcher.MatchConfig();
        coarse.coarseMatch = true;
        coarse.minHits = 1;
        coarse.angleThresholdDeg = 1.0;
        coarse.maxTimeDeltaMs = 2000L;
        coarse.distanceThresholdM = 50_000;
        coarse.exclusiveAssign = true;

        List<DirectionFindingMatcher.BatchTargetCandidate> fromB1 =
                DirectionFindingMatcher.collectBatchCandidates(batch1, trajectories, coarse);
        List<DirectionFindingMatcher.BatchTargetCandidate> fromB2 =
                DirectionFindingMatcher.collectBatchCandidates(batch2, trajectories, coarse);
        assertEquals(1, fromB1.size());
        assertEquals(1, fromB2.size());
        assertEquals(fromB1.get(0).hits, fromB2.get(0).hits);
        assertTrue(fromB1.get(0).avgCrossTrackM < fromB2.get(0).avgCrossTrackM);

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(ms, trajectories, coarse);
        assertEquals("A", summary.get("B1"));
        assertFalse(summary.containsKey("B2"));
    }

    @Test
    void hopBatchMergesTwoFreqsToOneLock() {
        List<DirectionFindingMatcher.Measurement> ms = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            int freq = i < 6 ? 286_000_000 : 300_000_000;
            ms.add(new DirectionFindingMatcher.Measurement(1000 + i * 1000L, 116.0, 40.0, 58.0, freq, "hop:1"));
        }
        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        trajectories.put("A", trajectoryForBatch("A", ms, 30_000));

        DirectionFindingMatcher.MatchConfig config = new DirectionFindingMatcher.MatchConfig();
        config.sustainDurationMs = 10_000L;
        config.angleThresholdDeg = 1.0;
        config.maxTimeDeltaMs = 2000L;
        config.distanceThresholdM = 50_000;
        config.exclusiveAssign = true;

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(ms, trajectories, config);
        assertEquals(1, summary.size());
        assertEquals("A", summary.get("hop:1"));
    }

    @Test
    void exclusiveOffAllowsTwoBatchesToLockSameTarget() {
        List<DirectionFindingMatcher.Measurement> ms = new ArrayList<>();
        for (int i = 0; i <= 10; i++) {
            ms.add(new DirectionFindingMatcher.Measurement(1000 + i * 1000L, 116.0, 40.0, 58.0, 100, "F1"));
            ms.add(new DirectionFindingMatcher.Measurement(1000 + i * 1000L, 116.0, 40.0, 58.0, 200, "F2"));
        }
        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajectories = new HashMap<>();
        trajectories.put("A", trajectoryForBatch("A", ms, 30_000));

        DirectionFindingMatcher.MatchConfig config = new DirectionFindingMatcher.MatchConfig();
        config.sustainDurationMs = 10_000L;
        config.angleThresholdDeg = 1.0;
        config.maxTimeDeltaMs = 2000L;
        config.distanceThresholdM = 50_000;
        config.exclusiveAssign = false;

        Map<String, String> summary = DirectionFindingMatcher.matchBatchSummary(ms, trajectories, config);
        assertEquals("A", summary.get("F1"));
        assertEquals("A", summary.get("F2"));
    }
}
