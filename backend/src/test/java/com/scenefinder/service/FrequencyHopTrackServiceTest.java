package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.HopBatch;
import com.scenefinder.model.HopBatchGrouping;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.TrackObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrequencyHopTrackServiceTest {

    @Test
    void unifiedViewLinksTracksAndSkipsNoise() {
        SceneFinderProperties props = new SceneFinderProperties();
        props.setEnableFreqHopAnalysis(true);
        props.setFrameSeconds(1.0);
        props.setAssociationGateDeg(8.0);
        props.setMaxMissedFrames(3);
        props.setMaxTrackBearingRateDegPerSec(30.0);
        props.setFreqClusterGapMhz(0.01);
        props.setHopMinPoints(3);
        props.setHopMinSeconds(1.0);

        Instant t0 = Instant.parse("2025-01-01T10:00:00Z");
        List<DetectionPoint> points = new ArrayList<>();

        BearingTrack trackA = new BearingTrack();
        trackA.setId(1);
        trackA.setHits(5);
        for (int i = 0; i < 5; i++) {
            DetectionPoint p = point("a.csv", i, t0.plusSeconds(i), 100.0 + i * 0.2, 287.625);
            points.add(p);
            trackA.getObservations().add(obs(p));
        }
        trackA.setBearingDeg(100.8);
        trackA.setLastTime(t0.plusSeconds(4));
        trackA.setSuggestedPlatformType("AWACS");

        BearingTrack trackB = new BearingTrack();
        trackB.setId(2);
        trackB.setHits(5);
        for (int i = 5; i < 10; i++) {
            DetectionPoint p = point("a.csv", i, t0.plusSeconds(i), 100.0 + i * 0.2, 300.125);
            points.add(p);
            trackB.getObservations().add(obs(p));
        }
        trackB.setBearingDeg(101.8);
        trackB.setLastTime(t0.plusSeconds(9));

        // short noise chain (alone, no link)
        BearingTrack noise = new BearingTrack();
        noise.setId(99);
        noise.setHits(1);
        DetectionPoint np = point("a.csv", 99, t0.plusSeconds(50), 200.0, 400.0);
        points.add(np);
        noise.getObservations().add(obs(np));
        noise.setBearingDeg(200.0);
        noise.setLastTime(t0.plusSeconds(50));

        QualityScene scene = QualityScene.trackScene(
                1,
                t0,
                t0.plusSeconds(60),
                287.625,
                287.6,
                287.65,
                1,
                10.0,
                1,
                5.0,
                0.1,
                Collections.singletonList(1));

        Map<Integer, BearingTrack> byId = new LinkedHashMap<>();
        byId.put(1, trackA);
        byId.put(2, trackB);
        byId.put(99, noise);

        FrequencyHopTrackService svc = new FrequencyHopTrackService();
        List<Map<String, Object>> views = svc.buildHoppingTrackViews(
                Collections.singletonList(scene), byId, points, props, ZoneId.of("UTC"));

        assertEquals(1, views.size());
        Map<String, Object> view = views.get(0);
        assertTrue(Boolean.TRUE.equals(view.get("unified")));
        assertEquals(1, ((Number) view.get("noiseSkippedCount")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tracks = (List<Map<String, Object>>) view.get("tracks");
        assertEquals(1, tracks.size());
        Map<String, Object> track = tracks.get(0);
        assertTrue(Boolean.TRUE.equals(track.get("hasFreqHop")));
        assertFalse(Boolean.TRUE.equals(track.get("noiseCandidate")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) track.get("hops");
        assertEquals(1, hops.size());
        assertEquals("AWACS", track.get("targetType"));
        assertEquals("预警机", track.get("targetTypeLabel"));
    }

    @Test
    void hopBatchesMergeLinkedTracksAndKeepNoiseSingletons() {
        SceneFinderProperties props = new SceneFinderProperties();
        props.setEnableFreqHopAnalysis(true);
        props.setAssociationGateDeg(8.0);
        props.setMaxTrackBearingRateDegPerSec(30.0);
        props.setFreqClusterGapMhz(0.01);
        props.setHopMinPoints(3);
        props.setHopMinSeconds(1.0);

        Instant t0 = Instant.parse("2025-01-01T10:00:00Z");
        List<DetectionPoint> points = new ArrayList<>();
        BearingTrack trackA = fillTrack(1, t0, 5, 100.0, 287.625, points);
        BearingTrack trackB = fillTrack(2, t0.plusSeconds(5), 5, 101.0, 300.125, points);
        BearingTrack noise = fillTrack(99, t0.plusSeconds(50), 1, 200.0, 400.0, points);

        QualityScene scene = QualityScene.trackScene(
                1, t0, t0.plusSeconds(60), 287.625, 287.6, 287.65,
                1, 10.0, 1, 5.0, 0.1, Collections.singletonList(1));
        Map<Integer, BearingTrack> byId = new LinkedHashMap<>();
        byId.put(1, trackA);
        byId.put(2, trackB);
        byId.put(99, noise);

        FrequencyHopTrackService svc = new FrequencyHopTrackService();
        HopBatchGrouping grouping = svc.buildHopBatches(
                Collections.singletonList(scene), byId, points, props);
        assertEquals(2, grouping.getBatches().size());
        assertEquals(1, grouping.getNoiseSkippedCount());

        HopBatch hop = null;
        HopBatch leftover = null;
        for (HopBatch b : grouping.getBatches()) {
            if ("hop:1".equals(b.getBatchId())) {
                hop = b;
            } else if ("track:99".equals(b.getBatchId())) {
                leftover = b;
            }
        }
        assertTrue(hop != null);
        assertTrue(leftover != null);
        assertEquals("目标1", hop.getLabel());
        assertTrue(hop.getLinkedTrackIds().contains(Integer.valueOf(1)));
        assertTrue(hop.getLinkedTrackIds().contains(Integer.valueOf(2)));
        assertEquals("单轨99", leftover.getLabel());
    }

    @Test
    void hopBatchesAreNotCappedAtDisplayLimit() {
        SceneFinderProperties props = new SceneFinderProperties();
        props.setEnableFreqHopAnalysis(true);
        props.setAssociationGateDeg(6.0);
        props.setMaxTrackBearingRateDegPerSec(30.0);
        props.setFreqClusterGapMhz(0.01);
        props.setHopMinPoints(3);
        props.setHopMinSeconds(1.0);
        props.setHopLinkMaxGapSec(1.0);

        Instant t0 = Instant.parse("2025-01-01T10:00:00Z");
        List<DetectionPoint> points = new ArrayList<>();
        Map<Integer, BearingTrack> byId = new LinkedHashMap<>();
        for (int i = 1; i <= 42; i++) {
            BearingTrack t = fillTrack(i, t0.plusSeconds(i * 30L), 5, 10.0 + i, 287.625 + i, points);
            byId.put(Integer.valueOf(i), t);
        }
        QualityScene scene = QualityScene.trackScene(
                1, t0, t0.plusSeconds(2000), 287.625, 200, 400,
                1, 10.0, 1, 5.0, 0.1, Collections.singletonList(1));
        FrequencyHopTrackService svc = new FrequencyHopTrackService();
        HopBatchGrouping grouping = svc.buildHopBatches(
                Collections.singletonList(scene), byId, points, props);
        assertEquals(42, grouping.getBatches().size());
        List<Map<String, Object>> views = svc.buildHoppingTrackViews(
                Collections.singletonList(scene), byId, points, props, ZoneId.of("UTC"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tracks = (List<Map<String, Object>>) views.get(0).get("tracks");
        assertEquals(40, tracks.size());
    }

    @Test
    void sameFreqResumesAcrossTwentySecondGapWithoutHop() {
        List<Map<String, Object>> tracks = linkTwoTracks(20, 150.0, 150.2, 287.625, 287.625);
        assertEquals(1, tracks.size());
        Map<String, Object> track = tracks.get(0);
        assertFalse(Boolean.TRUE.equals(track.get("hasFreqHop")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) track.get("hops");
        assertTrue(hops == null || hops.isEmpty());
        @SuppressWarnings("unchecked")
        List<Integer> linked = (List<Integer>) track.get("linkedTrackIds");
        assertEquals(2, linked.size());
    }

    @Test
    void differentFreqHopsAcrossTwentySecondGap() {
        List<Map<String, Object>> tracks = linkTwoTracks(20, 150.0, 150.2, 287.625, 417.3);
        assertEquals(1, tracks.size());
        Map<String, Object> track = tracks.get(0);
        assertTrue(Boolean.TRUE.equals(track.get("hasFreqHop")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hops = (List<Map<String, Object>>) track.get("hops");
        assertEquals(1, hops.size());
    }

    @Test
    void doesNotLinkWhenGapExceedsMax() {
        List<Map<String, Object>> tracks = linkTwoTracks(40, 150.0, 150.2, 287.625, 287.625);
        assertEquals(2, tracks.size());
    }

    @Test
    void doesNotLinkLongGapWhenBearingDiffersTooMuch() {
        List<Map<String, Object>> tracks = linkTwoTracks(20, 150.0, 165.0, 287.625, 417.3);
        assertEquals(2, tracks.size());
    }

    private static List<Map<String, Object>> linkTwoTracks(
            long gapSec, double bearingA, double bearingB, double freqA, double freqB) {
        SceneFinderProperties props = new SceneFinderProperties();
        props.setEnableFreqHopAnalysis(true);
        props.setAssociationGateDeg(6.0);
        props.setMaxTrackBearingRateDegPerSec(30.0);
        props.setFreqClusterGapMhz(0.01);
        props.setHopMinPoints(3);
        props.setHopMinSeconds(1.0);
        props.setHopLinkMaxGapSec(25.0);
        props.setHopLinkLongGapGateDeg(3.0);

        Instant t0 = Instant.parse("2025-01-01T10:00:00Z");
        List<DetectionPoint> points = new ArrayList<>();
        BearingTrack trackA = fillTrack(1, t0, 5, bearingA, freqA, points);
        Instant tB = t0.plusSeconds(4 + gapSec);
        BearingTrack trackB = fillTrack(2, tB, 5, bearingB, freqB, points);

        QualityScene scene = QualityScene.trackScene(
                1, t0, tB.plusSeconds(10), freqA, freqA, freqA,
                1, 10.0, 1, 5.0, 0.1, Collections.singletonList(1));
        Map<Integer, BearingTrack> byId = new LinkedHashMap<>();
        byId.put(1, trackA);
        byId.put(2, trackB);

        FrequencyHopTrackService svc = new FrequencyHopTrackService();
        List<Map<String, Object>> views = svc.buildHoppingTrackViews(
                Collections.singletonList(scene), byId, points, props, ZoneId.of("UTC"));
        assertEquals(1, views.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tracks = (List<Map<String, Object>>) views.get(0).get("tracks");
        return tracks;
    }

    private static BearingTrack fillTrack(
            int id, Instant start, int n, double bearing, double freq, List<DetectionPoint> points) {
        BearingTrack track = new BearingTrack();
        track.setId(id);
        for (int i = 0; i < n; i++) {
            DetectionPoint p = point("a.csv", id * 100L + i, start.plusSeconds(i), bearing, freq);
            points.add(p);
            track.getObservations().add(obs(p));
        }
        track.setHits(n);
        track.setBearingDeg(bearing);
        track.setLastTime(start.plusSeconds(n - 1));
        return track;
    }

    private static DetectionPoint point(
            String file, long row, Instant time, double bearing, double freq) {
        return new DetectionPoint(file, row, time, bearing, freq, "");
    }

    private static TrackObservation obs(DetectionPoint p) {
        return new TrackObservation(
                p.getTime(), p.getBearingDeg(), p.getFrequencyMhz(),
                p.getSourceFile(), p.getRowIndex());
    }
}
