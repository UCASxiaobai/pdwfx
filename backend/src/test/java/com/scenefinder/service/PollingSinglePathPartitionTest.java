package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SourceRowRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollingSinglePathPartitionTest {

    private final MultiDevicePollingDetectorService detector = new MultiDevicePollingDetectorService();
    private final PollingTrackBuilderService pollingBuilder = new PollingTrackBuilderService();
    private final TrackBuilderService trackBuilder = new TrackBuilderService();
    private final AirHypothesisArbiter arbiter = new AirHypothesisArbiter();

    @Test
    void callsignGroupClaimsPointsAndLeavesSingleTxRemainder() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = buildCallsignPlusSingleTx();

        List<BearingTrack> firstPass = trackBuilder.buildTracks(points, props);
        List<BearingTrack> persistent = persistentTracks(firstPass);
        List<BearingTrack> airHyp = airTracks(firstPass);
        assertFalse(persistent.isEmpty(), "90° dense interrogator should be GROUND/AWACS");

        Set<SourceRowRef> persistentRows = trackRows(persistent);
        List<DetectionPoint> airPoints = remainderOf(points, persistentRows);

        List<QualityScene> scenes = detector.findPollingScenes(airPoints, persistent, props);
        scenes = arbiter.selectPollingWindows(scenes, airHyp, props);
        assertFalse(scenes.isEmpty(), "continuous interrogator + 3 responders should still be polling");
        assertNotNull(scenes.get(0).getInterrogatorTrackId());

        PollingTrackBuilderService.LaneBuildResult built =
                pollingBuilder.buildLaneTracks(scenes.get(0), airPoints, props, 100);
        assertEquals(3, built.getTracks().size(), "lanes are aircraft only");
        assertEquals(3, built.getResponderLaneCount());
        assertEquals(4, built.getChannelTargetCount(), "1 referenced interrogator + 3 aircraft");
        assertNotNull(built.getInterrogatorTrackId());

        for (BearingTrack t : built.getTracks()) {
            assertEquals("AIR", t.getSuggestedPlatformType());
            assertEquals("RESPONDER", t.getPollingRole());
            double mean = meanBearing(t);
            assertTrue(mean >= 99.0 && mean <= 104.0, "lane must not be AWACS bearing, got " + mean);
        }

        Set<SourceRowRef> claimed = new HashSet<SourceRowRef>(built.getRowToTrackId().keySet());
        assertFalse(claimed.isEmpty());
        for (SourceRowRef row : claimed) {
            assertFalse(persistentRows.contains(row), "AWACS points must stay on continuous track");
        }

        List<DetectionPoint> leftoverAir = remainderOf(airPoints, claimed);
        for (DetectionPoint p : leftoverAir) {
            assertTrue(p.getFrequencyMhz() > 350.0,
                    "leftover air should be the 400MHz single-tx group, got "
                            + p.getFrequencyMhz() + " MHz bearing=" + p.getBearingDeg());
        }

        List<BearingTrack> singleTracks = trackBuilder.buildTracks(leftoverAir, props);
        for (BearingTrack t : singleTracks) {
            for (com.scenefinder.model.TrackObservation obs : t.getObservations()) {
                assertFalse(claimed.contains(obs.sourceRow()), "single-path track must not reuse polling rows");
            }
        }

        List<Double> responderMeans = new ArrayList<Double>();
        for (BearingTrack t : built.getTracks()) {
            responderMeans.add(meanBearing(t));
        }
        assertEquals(3, responderMeans.size());
        responderMeans.sort(Double::compareTo);
        assertTrue(responderMeans.get(1) - responderMeans.get(0) >= 0.5);
        assertTrue(responderMeans.get(2) - responderMeans.get(1) >= 0.5);
    }

    @Test
    void symmetricPollingHasEmptyRemainderWhenNoExtraPoints() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = buildSymmetricPolling(new double[] {100.0, 105.0, 110.0}, 8.0, 13);
        List<QualityScene> scenes = detector.findPollingScenes(points, props);
        assertFalse(scenes.isEmpty());
        PollingTrackBuilderService.LaneBuildResult built =
                pollingBuilder.buildLaneTracks(scenes.get(0), points, props, 10);
        assertEquals(3, built.getLaneCount());
        Set<SourceRowRef> claimed = new HashSet<SourceRowRef>(built.getRowToTrackId().keySet());
        int leftover = 0;
        for (DetectionPoint p : points) {
            if (!claimed.contains(p.sourceRow())) {
                leftover++;
            }
        }
        List<BearingTrack> singleTracks = trackBuilder.buildTracks(remainderOf(points, claimed), props);
        assertTrue(singleTracks.isEmpty() || leftover == 0 || singleTracks.stream().noneMatch(t ->
                t.getObservations().stream().anyMatch(o -> claimed.contains(o.sourceRow()))));
    }

    @Test
    void pureSingleTxHasNoPollingClaim() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = new ArrayList<DetectionPoint>();
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        long row = 0;
        for (int sec = 0; sec <= 40; sec++) {
            points.add(new DetectionPoint(
                    "synthetic.csv",
                    row++,
                    Instant.ofEpochMilli(baseMs + sec * 1000L),
                    40.0 + sec * 0.2,
                    280.0,
                    null
            ));
        }
        List<QualityScene> scenes = detector.findPollingScenes(points, props);
        assertTrue(scenes.isEmpty());
        List<BearingTrack> tracks = trackBuilder.buildTracks(points, props);
        assertFalse(tracks.isEmpty());
    }

    private static List<DetectionPoint> remainderOf(List<DetectionPoint> points, Set<SourceRowRef> claimed) {
        List<DetectionPoint> remainder = new ArrayList<DetectionPoint>();
        for (DetectionPoint p : points) {
            if (!claimed.contains(p.sourceRow())) {
                remainder.add(p);
            }
        }
        return remainder;
    }

    private static List<BearingTrack> persistentTracks(List<BearingTrack> tracks) {
        List<BearingTrack> out = new ArrayList<BearingTrack>();
        for (BearingTrack t : tracks) {
            if (MultiDevicePollingDetectorService.isPersistentPlatform(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<BearingTrack> airTracks(List<BearingTrack> tracks) {
        List<BearingTrack> out = new ArrayList<BearingTrack>();
        for (BearingTrack t : tracks) {
            if (!MultiDevicePollingDetectorService.isPersistentPlatform(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static Set<SourceRowRef> trackRows(List<BearingTrack> tracks) {
        Set<SourceRowRef> rows = new HashSet<SourceRowRef>();
        for (BearingTrack t : tracks) {
            for (com.scenefinder.model.TrackObservation obs : t.getObservations()) {
                rows.add(obs.sourceRow());
            }
        }
        return rows;
    }

    private static List<DetectionPoint> buildCallsignPlusSingleTx() {
        List<DetectionPoint> points = new ArrayList<DetectionPoint>();
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        long row = 0;
        double pollFreq = 240.55;
        int rounds = 13;
        double periodSec = 8.0;
        long spanMs = Math.round(rounds * periodSec * 1000.0);
        for (long t = 0; t <= spanMs; t += 200L) {
            points.add(new DetectionPoint(
                    "synthetic.csv",
                    row++,
                    Instant.ofEpochMilli(baseMs + t),
                    90.0,
                    pollFreq,
                    null,
                    20.0
            ));
        }
        double[] aircraft = {100.0, 101.0, 102.0};
        for (int round = 0; round < rounds; round++) {
            long burstStart = baseMs + Math.round(round * periodSec * 1000.0);
            for (int bi = 0; bi < aircraft.length; bi++) {
                points.add(new DetectionPoint(
                        "synthetic.csv",
                        row++,
                        Instant.ofEpochMilli(burstStart + 80L + bi * 120L),
                        aircraft[bi] + round * 0.04,
                        pollFreq,
                        null,
                        2.0
                ));
            }
        }
        for (int sec = 0; sec <= 40; sec++) {
            points.add(new DetectionPoint(
                    "synthetic.csv",
                    row++,
                    Instant.ofEpochMilli(baseMs + sec * 1000L),
                    50.0 + sec * 0.15,
                    400.125,
                    null
            ));
        }
        return points;
    }

    private static List<DetectionPoint> buildSymmetricPolling(double[] bearings, double periodSec, int rounds) {
        List<DetectionPoint> points = new ArrayList<DetectionPoint>();
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        long row = 0;
        for (int round = 0; round < rounds; round++) {
            long burstStart = baseMs + Math.round(round * periodSec * 1000.0);
            for (int bi = 0; bi < bearings.length; bi++) {
                points.add(new DetectionPoint(
                        "synthetic.csv",
                        row++,
                        Instant.ofEpochMilli(burstStart + bi * 200L),
                        bearings[bi] + round * 0.05,
                        240.55,
                        null
                ));
            }
        }
        return points;
    }

    private static double meanBearing(BearingTrack track) {
        return track.getObservations().stream()
                .mapToDouble(com.scenefinder.model.TrackObservation::getBearingDeg)
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
        props.setFullSpanWindow(true);
        props.setDutyPriorityEnabled(true);
        props.setFrameSeconds(0.5);
        props.setAssociationGateDeg(6.0);
        props.setBearingClusterGapDeg(4.0);
        props.setMinTrackSeconds(2.0);
        props.setMinTrackPoints(3);
        return props;
    }
}
