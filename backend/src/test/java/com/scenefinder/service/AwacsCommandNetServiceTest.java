package com.scenefinder.service;

import com.pdwfx.signal.model.SeriesPoint;
import com.pdwfx.signal.model.TargetView;
import com.scenefinder.model.AwacsOccupancyWindow;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.OccupancyCluster;
import com.scenefinder.model.SceneSummaryEntry;
import com.scenefinder.model.SceneType;
import com.scenefinder.model.TrackObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AwacsCommandNetServiceTest {

    private final AwacsCommandNetService service = new AwacsCommandNetService();

    @Test
    void occupancyWindowIncludesSparseAircraftExcludesLaterNoise() {
        TargetView awacs = awacs("AW", 100.0, 20_000L, 40_000L);
        List<AwacsOccupancyWindow> windows = service.collectWindows(java.util.Collections.singletonList(awacs), 3.0, 0.01);
        assertEquals(1, windows.size());

        DetectionPoint aircraft = point("src.csv", 2, Instant.ofEpochMilli(30_000L), 100.0);
        DetectionPoint noise = point("src.csv", 3, Instant.ofEpochMilli(80_000L), 100.0);
        List<DetectionPoint> points = new ArrayList<DetectionPoint>();
        points.add(aircraft);
        points.add(noise);
        List<DetectionPoint> kept = service.filterPoints(points, windows);
        assertEquals(1, kept.size());
        assertEquals(2L, kept.get(0).getRowIndex());
    }

    @Test
    void hopSegmentsDoNotPullPointsAfterLeavingFreq() {
        TargetView awacs = new TargetView();
        awacs.setTargetId("HOP");
        awacs.setTargetType("AWACS");
        awacs.setPeriodMs(Double.valueOf(400.0));
        List<SeriesPoint> series = new ArrayList<SeriesPoint>();
        series.add(new SeriesPoint(10_000L, 200.0));
        series.add(new SeriesPoint(20_000L, 200.0));
        series.add(new SeriesPoint(30_000L, 400.0));
        series.add(new SeriesPoint(40_000L, 400.0));
        awacs.setFreqSeries(series);

        List<AwacsOccupancyWindow> windows = service.collectWindows(java.util.Collections.singletonList(awacs), 3.0, 0.01);
        assertEquals(2, windows.size());
        assertEquals(200.0, windows.get(0).getFreqCenterMhz(), 0.001);
        assertEquals(400.0, windows.get(1).getFreqCenterMhz(), 0.001);

        DetectionPoint leftoverOnA = point("src.csv", 9, Instant.ofEpochMilli(35_000L), 200.0);
        List<DetectionPoint> kept = service.filterPoints(java.util.Collections.singletonList(leftoverOnA), windows);
        assertTrue(kept.isEmpty(), "A 段离开后同频点不得进入 A 占用窗");
        assertTrue(windows.get(1).matches(400.0, 35_000L));
        assertFalse(windows.get(0).matches(200.0, 35_000L));
    }

    @Test
    void noAwacsSkipsWindows() {
        TargetView air = new TargetView();
        air.setTargetId("AIR");
        air.setTargetType("AIR");
        air.setPeriodMs(Double.valueOf(400.0));
        air.setFreqSeries(java.util.Collections.singletonList(new SeriesPoint(10_000L, 100.0)));
        List<AwacsOccupancyWindow> windows = service.collectWindows(java.util.Collections.singletonList(air), 3.0, 0.01);
        assertTrue(windows.isEmpty());
    }

    @Test
    void longTxIntervalCannotBeSeed() {
        TargetView slow = new TargetView();
        slow.setTargetId("SLOW");
        slow.setTargetType("AWACS");
        slow.setPeriodMs(Double.valueOf(3000.0));
        slow.setFreqSeries(java.util.Collections.singletonList(new SeriesPoint(10_000L, 100.0)));
        assertFalse(AwacsCommandNetService.isAwacsSeed(slow));
        List<AwacsOccupancyWindow> windows = service.collectWindows(java.util.Collections.singletonList(slow), 3.0, 0.01);
        assertTrue(windows.isEmpty());
    }

    @Test
    void clusterByFreqMergesSameBand() {
        List<AwacsOccupancyWindow> windows = new ArrayList<AwacsOccupancyWindow>();
        windows.add(new AwacsOccupancyWindow(100.0, 0.01, 1000L, 5000L, "A"));
        windows.add(new AwacsOccupancyWindow(100.005, 0.01, 8000L, 9000L, "A"));
        windows.add(new AwacsOccupancyWindow(300.0, 0.01, 1000L, 2000L, "B"));
        List<OccupancyCluster> clusters = service.clusterByFreq(windows, 0.01);
        assertEquals(2, clusters.size());
        assertEquals(1000L, clusters.get(0).getTStartMs());
        assertEquals(9000L, clusters.get(0).getTEndMs());
        assertTrue(clusters.get(0).matchesPoint(100.0, 2000L));
        assertFalse(clusters.get(0).matchesPoint(100.0, 6000L), "并集时间轴上的空洞不得用簇窗筛点");
    }

    @Test
    void firstPassSceneOverlapsOccupancyWindow() {
        SceneSummaryEntry scene = new SceneSummaryEntry(
                3,
                SceneType.TRACK_CONTINUOUS,
                Instant.ofEpochMilli(15_000L),
                Instant.ofEpochMilli(45_000L),
                99.99,
                100.01,
                2,
                0d,
                java.util.Collections.<Integer>emptyList(),
                "once");
        AwacsOccupancyWindow window = new AwacsOccupancyWindow(100.0, 0.01, 20_000L, 40_000L, "AW");
        assertTrue(AwacsCommandNetPassService.sceneOverlapsAnyWindow(
                scene, java.util.Collections.singletonList(window)));
        AwacsOccupancyWindow otherFreq = new AwacsOccupancyWindow(300.0, 0.01, 20_000L, 40_000L, "AW");
        assertFalse(AwacsCommandNetPassService.sceneOverlapsAnyWindow(
                scene, java.util.Collections.singletonList(otherFreq)));
    }

    @Test
    void collectWindowsFromDutyAwacsTracks() {
        BearingTrack track = new BearingTrack();
        track.setId(7);
        track.setSuggestedPlatformType("AWACS");
        track.getObservations().add(new TrackObservation(
                Instant.ofEpochMilli(10_000L), 180.0, 306.925, "a.csv", 1L));
        track.getObservations().add(new TrackObservation(
                Instant.ofEpochMilli(20_000L), 181.0, 306.925, "a.csv", 2L));
        List<AwacsOccupancyWindow> windows = service.collectWindowsFromTracks(
                java.util.Collections.singletonList(track), 3.0, 0.01);
        assertEquals(1, windows.size());
        assertEquals(306.925, windows.get(0).getFreqCenterMhz(), 0.001);
        assertTrue(windows.get(0).matches(306.925, 15_000L));
        assertTrue(windows.get(0).getTStartMs() <= 7_000L);
        assertTrue(windows.get(0).getTEndMs() >= 23_000L);
    }

    private static TargetView awacs(String id, double freqMhz, long t0, long t1) {
        TargetView t = new TargetView();
        t.setTargetId(id);
        t.setTargetType("AWACS");
        t.setPeriodMs(Double.valueOf(400.0));
        List<SeriesPoint> series = new ArrayList<SeriesPoint>();
        series.add(new SeriesPoint(t0, freqMhz));
        series.add(new SeriesPoint(t1, freqMhz));
        t.setFreqSeries(series);
        return t;
    }

    private static DetectionPoint point(String file, long row, Instant time, double freqMhz) {
        return new DetectionPoint(file, row, time, 10.0, freqMhz, "raw", 1.0);
    }
}
