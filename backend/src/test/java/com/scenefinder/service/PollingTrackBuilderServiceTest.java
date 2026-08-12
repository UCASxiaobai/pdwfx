package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.TrackObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingTrackBuilderServiceTest {

    private final MultiDevicePollingDetectorService detector = new MultiDevicePollingDetectorService();
    private final PollingTrackBuilderService builder = new PollingTrackBuilderService();

    @Test
    void deinterlacesPeriodicBurstsIntoSmoothLaneTracks() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = buildLanePoints(new double[] {100.0, 105.0, 110.0}, 8.0, 13);
        List<QualityScene> scenes = detector.findPollingScenes(points, props);
        assertFalse(scenes.isEmpty());

        PollingTrackBuilderService.LaneBuildResult built =
                builder.buildLaneTracks(scenes.get(0), points, props, 1000);

        assertEquals(3, built.getLaneCount());
        assertTrue(built.getAlignedRoundCount() >= 5);
        for (BearingTrack track : built.getTracks()) {
            assertTrue(track.getObservations().size() >= 3);
        }

        List<Double> laneMeans = new ArrayList<>();
        for (BearingTrack track : built.getTracks()) {
            laneMeans.add(meanBearing(track));
        }
        laneMeans.sort(Double::compareTo);
        assertTrue(laneMeans.get(1) - laneMeans.get(0) >= 0.8);
        assertTrue(laneMeans.get(2) - laneMeans.get(1) >= 0.8);
    }

    @Test
    void clusterCentersSeparateNearBearings() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = buildLanePoints(new double[] {100.0, 101.0, 102.0}, 8.0, 13);
        List<QualityScene> scenes = detector.findPollingScenes(points, props);
        assertFalse(scenes.isEmpty());

        PollingTrackBuilderService.LaneBuildResult built =
                builder.buildLaneTracks(scenes.get(0), points, props, 2000);

        assertEquals(3, built.getLaneCount());
        assertFalse(built.getRowToTrackId().isEmpty());
    }

    @Test
    void excludesSporadicTargetNotRepeatingAcrossWindow() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = buildLanePoints(new double[] {100.0, 105.0, 110.0}, 8.0, 13);
        // 仅在第 1、2 轮出现的偶发方位，不应计入整窗轮询参与者
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        long row = 1000;
        for (int round : new int[] {0, 1}) {
            long burstStart = baseMs + Math.round(round * 8.0 * 1000.0);
            points.add(new DetectionPoint(
                    "synthetic.csv",
                    row++,
                    Instant.ofEpochMilli(burstStart),
                    200.0,
                    240.55,
                    null
            ));
        }

        List<QualityScene> scenes = detector.findPollingScenes(points, props);
        assertFalse(scenes.isEmpty());

        PollingTrackBuilderService.LaneBuildResult built =
                builder.buildLaneTracks(scenes.get(0), points, props, 3000);

        assertEquals(3, built.getLaneCount());
        for (BearingTrack track : built.getTracks()) {
            int minHits = PollingAlignmentUtil.minRoundHits(
                    built.getAlignedRoundCount(), props.getPollingMinSlotRoundCoverageRatio());
            assertTrue(track.getObservations().size() >= minHits,
                    "persistent lane should appear in most rounds");
        }
    }

    @Test
    void excludesSporadicClustersWhenBurstCountVaries() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = buildLanePoints(new double[] {100.0, 105.0, 110.0}, 8.0, 13);
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        long row = 2000;
        // 偶发第 4 方位：仅在少数轮出现，不应建轨
        for (int round : new int[] {0, 1, 5}) {
            long burstStart = baseMs + Math.round(round * 8.0 * 1000.0);
            points.add(new DetectionPoint(
                    "synthetic.csv",
                    row++,
                    Instant.ofEpochMilli(burstStart),
                    200.0,
                    240.55,
                    null
            ));
        }

        List<QualityScene> scenes = detector.findPollingScenes(points, props);
        assertFalse(scenes.isEmpty());

        PollingTrackBuilderService.LaneBuildResult built =
                builder.buildLaneTracks(scenes.get(0), points, props, 4000);

        assertEquals(3, built.getLaneCount());
        for (BearingTrack track : built.getTracks()) {
            double mean = meanBearing(track);
            assertTrue(Math.abs(mean - 200.0) > 10.0, "sporadic 200° slot should be excluded");
        }
    }

    private static double meanBearing(com.scenefinder.model.BearingTrack track) {
        return track.getObservations().stream()
                .mapToDouble(TrackObservation::getBearingDeg)
                .average()
                .orElse(0);
    }

    private static SceneFinderProperties pollingProps() {
        SceneFinderProperties props = new SceneFinderProperties();
        props.setPollingBurstCoalesceSec(0.5);
        props.setPollingBurstBearingGapDeg(0.5);
        props.setPollingMinBearingsPerBurst(2);
        props.setPollingMinInterClusterSeparationDeg(1.0);
        props.setPollingMinAlignedMultiBursts(5);
        props.setPollingMinStableBearingSlots(2);
        props.setPollingMaxSlotBearingStdDeg(1.5);
        props.setPollingMinBurstBearingSpanDeg(0);
        props.setPollingMaxDominantClusterFraction(0.72);
        props.setPollingWindowSeconds(90.0);
        props.setPollingWindowStepSeconds(20.0);
        props.setPollingMinBurstsInWindow(5);
        props.setPollingPeriodMinSec(5.0);
        props.setPollingPeriodMaxSec(20.0);
        props.setPollingPeriodToleranceRatio(0.18);
        props.setPollingMinPeriodicityScore(0.55);
        props.setPollingMinSlotRoundCoverageRatio(0.8);
        props.setFreqClusterGapMhz(0.01);
        return props;
    }

    private static List<DetectionPoint> buildLanePoints(double[] bearings, double periodSec, int rounds) {
        List<DetectionPoint> points = new ArrayList<>();
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        long row = 0;
        for (int round = 0; round < rounds; round++) {
            long burstStart = baseMs + Math.round(round * periodSec * 1000.0);
            for (int bi = 0; bi < bearings.length; bi++) {
                double drift = round * 0.05;
                points.add(new DetectionPoint(
                        "synthetic.csv",
                        row++,
                        Instant.ofEpochMilli(burstStart + bi * 200L),
                        bearings[bi] + drift,
                        240.55,
                        null
                ));
            }
        }
        return points;
    }
}
