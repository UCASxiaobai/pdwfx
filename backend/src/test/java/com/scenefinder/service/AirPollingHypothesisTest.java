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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连续轨与轮询双假设：预警机只走连续轨；飞机点择优占用。
 */
class AirPollingHypothesisTest {

    private final MultiDevicePollingDetectorService detector = new MultiDevicePollingDetectorService();
    private final PollingTrackBuilderService pollingBuilder = new PollingTrackBuilderService();
    private final TrackBuilderService trackBuilder = new TrackBuilderService();
    private final AirHypothesisArbiter arbiter = new AirHypothesisArbiter();

    @Test
    void awacsPlusPeriodicAircraftSelectsPollingWithoutAwacsLanes() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = new ArrayList<DetectionPoint>();
        long row = 0;
        row = addAwacs(points, row, 90.0, 240.55, 13 * 8);
        addPeriodicAircraft(points, row, new double[] {100.0, 101.0, 102.0}, 8.0, 13, 240.55);

        Partition p = run(points, props);
        assertFalse(p.polling.isEmpty(), "AWACS + 3 periodic aircraft should select polling");
        assertTrue(p.lanes >= 2);
        for (BearingTrack t : p.laneTracks) {
            double mean = meanBearing(t);
            assertTrue(mean >= 99.0 && mean <= 104.0, "lane must not include AWACS azimuth, got " + mean);
        }
    }

    @Test
    void awacsOnlyHasNoPollingScene() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = new ArrayList<DetectionPoint>();
        addAwacs(points, 0, 90.0, 240.55, 80);
        Partition p = run(points, props);
        assertTrue(p.polling.isEmpty(), "dense AWACS alone must not become a polling scene");
        assertFalse(p.persistent.isEmpty());
    }

    @Test
    void twoDegreePeriodicSlotsBeatMergedAirTrack() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = new ArrayList<DetectionPoint>();
        long row = 0;
        row = addAwacs(points, row, 88.0, 240.55, 13 * 8);
        addPeriodicAircraft(points, row, new double[] {100.0, 102.0}, 8.0, 13, 240.55);

        Partition p = run(points, props);
        assertFalse(p.polling.isEmpty(), "2° periodic slots should win over a merged 6° air track");
        assertTrue(p.lanes >= 2);
    }

    @Test
    void smoothAircraftWithoutPeriodicSlotsStaysContinuous() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = new ArrayList<DetectionPoint>();
        long row = 0;
        row = addAwacs(points, row, 50.0, 240.55, 50);
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        for (int sec = 0; sec <= 50; sec++) {
            points.add(new DetectionPoint(
                    "synthetic.csv",
                    row++,
                    Instant.ofEpochMilli(baseMs + sec * 1000L),
                    124.5 + sec * 0.08,
                    240.55,
                    null,
                    2.0
            ));
        }
        Partition p = run(points, props);
        assertTrue(p.polling.isEmpty(), "smooth filled aircraft track should not be polling");
        assertFalse(p.leftoverAir.isEmpty() || p.airHyp.isEmpty());
    }

    @Test
    void singleResponderSlotDropsPollingWindow() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = new ArrayList<DetectionPoint>();
        long row = 0;
        row = addAwacs(points, row, 90.0, 240.55, 13 * 8);
        addPeriodicAircraft(points, row, new double[] {100.0}, 8.0, 13, 240.55);

        Partition p = run(points, props);
        assertTrue(p.polling.isEmpty() || p.lanes < 2, "one aircraft slot must not build a polling scene");
    }

    @Test
    void emptyPersistentListRejectsPollingEvenIfPatternExists() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> aircraft = new ArrayList<DetectionPoint>();
        addPeriodicAircraft(aircraft, 0, new double[] {100.0, 105.0, 110.0}, 8.0, 13, 240.55);
        List<QualityScene> isolated = detector.findPollingScenes(aircraft, props);
        assertFalse(isolated.isEmpty(), "pattern detector still sees 3-slot polling");
        List<QualityScene> withGate = detector.findPollingScenes(
                aircraft, new ArrayList<BearingTrack>(), props);
        assertTrue(withGate.isEmpty(), "no overlapping AWACS/GROUND track → drop polling");
    }

    private Partition run(List<DetectionPoint> points, SceneFinderProperties props) {
        List<BearingTrack> firstPass = trackBuilder.buildTracks(points, props);
        List<BearingTrack> persistent = new ArrayList<BearingTrack>();
        List<BearingTrack> airHyp = new ArrayList<BearingTrack>();
        for (BearingTrack t : firstPass) {
            if (MultiDevicePollingDetectorService.isPersistentPlatform(t)) {
                persistent.add(t);
            } else {
                airHyp.add(t);
            }
        }
        Set<SourceRowRef> persistentRows = new HashSet<SourceRowRef>();
        for (BearingTrack t : persistent) {
            for (com.scenefinder.model.TrackObservation obs : t.getObservations()) {
                persistentRows.add(obs.sourceRow());
            }
        }
        List<DetectionPoint> airPoints = new ArrayList<DetectionPoint>();
        for (DetectionPoint p : points) {
            if (!persistentRows.contains(p.sourceRow())) {
                airPoints.add(p);
            }
        }
        List<QualityScene> raw = detector.findPollingScenes(airPoints, persistent, props);
        List<QualityScene> kept = arbiter.selectPollingWindows(raw, airHyp, props);
        List<BearingTrack> laneTracks = new ArrayList<BearingTrack>();
        Set<SourceRowRef> claimed = new HashSet<SourceRowRef>();
        int nextId = 100;
        List<QualityScene> polling = new ArrayList<QualityScene>();
        for (QualityScene scene : kept) {
            PollingTrackBuilderService.LaneBuildResult built =
                    pollingBuilder.buildLaneTracks(scene, airPoints, props, nextId);
            if (built.getTracks().size() < 2) {
                continue;
            }
            nextId += built.getTracks().size();
            laneTracks.addAll(built.getTracks());
            claimed.addAll(built.getRowToTrackId().keySet());
            polling.add(scene);
        }
        List<DetectionPoint> leftoverAir = new ArrayList<DetectionPoint>();
        for (DetectionPoint p : airPoints) {
            if (!claimed.contains(p.sourceRow())) {
                leftoverAir.add(p);
            }
        }
        return new Partition(persistent, airHyp, polling, laneTracks, leftoverAir);
    }

    private static final class Partition {
        final List<BearingTrack> persistent;
        final List<BearingTrack> airHyp;
        final List<QualityScene> polling;
        final List<BearingTrack> laneTracks;
        final List<DetectionPoint> leftoverAir;
        final int lanes;

        Partition(List<BearingTrack> persistent, List<BearingTrack> airHyp, List<QualityScene> polling,
                  List<BearingTrack> laneTracks, List<DetectionPoint> leftoverAir) {
            this.persistent = persistent;
            this.airHyp = airHyp;
            this.polling = polling;
            this.laneTracks = laneTracks;
            this.leftoverAir = leftoverAir;
            this.lanes = laneTracks.size();
        }
    }

    private static long addAwacs(List<DetectionPoint> points, long row, double bearing, double freq, int spanSec) {
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        long spanMs = spanSec * 1000L;
        for (long t = 0; t <= spanMs; t += 200L) {
            points.add(new DetectionPoint(
                    "synthetic.csv",
                    row++,
                    Instant.ofEpochMilli(baseMs + t),
                    bearing,
                    freq,
                    null,
                    20.0
            ));
        }
        return row;
    }

    private static long addPeriodicAircraft(
            List<DetectionPoint> points,
            long row,
            double[] bearings,
            double periodSec,
            int rounds,
            double freq
    ) {
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        for (int round = 0; round < rounds; round++) {
            long burstStart = baseMs + Math.round(round * periodSec * 1000.0);
            for (int bi = 0; bi < bearings.length; bi++) {
                points.add(new DetectionPoint(
                        "synthetic.csv",
                        row++,
                        Instant.ofEpochMilli(burstStart + 80L + bi * 120L),
                        bearings[bi] + round * 0.04,
                        freq,
                        null,
                        2.0
                ));
            }
        }
        return row;
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
        props.setPollingWindowSeconds(90.0);
        props.setPollingWindowStepSeconds(20.0);
        props.setPollingMinBurstsInWindow(5);
        props.setPollingPeriodMinSec(5.0);
        props.setPollingPeriodMaxSec(20.0);
        props.setPollingPeriodToleranceRatio(0.18);
        props.setPollingMinPeriodicityScore(0.55);
        props.setPollingMinSlotRoundCoverageRatio(0.8);
        props.setFreqClusterGapMhz(0.01);
        props.setDutyPriorityEnabled(true);
        props.setDutyAwacsMinPct(4.0);
        props.setDutyGroundMinPct(25.0);
        props.setFrameSeconds(0.5);
        props.setAssociationGateDeg(6.0);
        props.setDutyAirAssociationGateDeg(6.0);
        props.setBearingClusterGapDeg(4.0);
        props.setMinTrackSeconds(2.0);
        props.setMinTrackPoints(3);
        return props;
    }
}
