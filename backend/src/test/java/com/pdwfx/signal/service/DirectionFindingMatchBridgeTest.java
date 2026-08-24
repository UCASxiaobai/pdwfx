package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.scenefinder.model.HopBatch;
import com.scenefinder.model.HopBatchGrouping;
import com.scenefinder.model.TrackObservation;
import com.scenefinder.service.DirectionFindingMatcher;
import com.scenefinder.service.HopBatchIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectionFindingMatchBridgeTest {

    @TempDir
    Path tempDir;

    @Test
    void hopIndexOverridesFreqBatchId() throws Exception {
        long t0 = Instant.parse("2025-01-01T10:00:00Z").toEpochMilli();
        HopBatchGrouping grouping = new HopBatchGrouping();
        HopBatch batch = new HopBatch();
        batch.setBatchId("hop:1");
        batch.setLabel("目标1");
        batch.setSeedTrackId(1);
        List<TrackObservation> obs = new ArrayList<>();
        obs.add(new TrackObservation(Instant.ofEpochMilli(t0), 58.0, 286.0, "a.csv", 2L));
        obs.add(new TrackObservation(Instant.ofEpochMilli(t0 + 1000), 58.1, 300.0, "a.csv", 3L));
        batch.setObservations(obs);
        grouping.add(batch);
        HopBatchIndex.writeCsv(tempDir, grouping);
        HopBatchIndex index = HopBatchIndex.loadQuietly(tempDir.toString());

        DetectSignal s1 = signal("1139", 286.0, 58.0, t0, 2L);
        DetectSignal s2 = signal("1139", 300.0, 58.1, t0 + 1000, 3L);

        assertEquals("1139@286.0000", DirectionFindingMatchBridge.resolveBatchId(s1));
        assertEquals("1139@300.0000", DirectionFindingMatchBridge.resolveBatchId(s2));

        List<DetectSignal> signals = new ArrayList<>();
        signals.add(s1);
        signals.add(s2);
        List<DirectionFindingMatcher.Measurement> withHop =
                DirectionFindingMatchBridge.buildMeasurements(signals, index);
        assertEquals(2, withHop.size());
        assertEquals("hop:1", withHop.get(0).resolvedBatchId());
        assertEquals("hop:1", withHop.get(1).resolvedBatchId());

        List<DirectionFindingMatcher.Measurement> noHop =
                DirectionFindingMatchBridge.buildMeasurements(signals, null);
        assertEquals("1139@286.0000", noHop.get(0).resolvedBatchId());
        assertEquals("1139@300.0000", noHop.get(1).resolvedBatchId());
        assertNotEquals(noHop.get(0).resolvedBatchId(), noHop.get(1).resolvedBatchId());
    }

    @Test
    void unnamedLocatePointsAreSkippedAsDevices() {
        ExternalTargetFix named = new ExternalTargetFix();
        named.setTargetName("歼-16");
        named.setTargetId("MB_1");
        named.setRowNum(9);
        named.setDetectTimeMs(1_700_000_000_000L);
        named.setLongitude(107.7);
        named.setLatitude(22.2);

        ExternalTargetFix unnamed = new ExternalTargetFix();
        unnamed.setRowNum(4);
        unnamed.setDetectTimeMs(1_700_000_000_000L);
        unnamed.setLongitude(107.8);
        unnamed.setLatitude(22.3);

        List<ExternalTargetFix> fixes = new ArrayList<ExternalTargetFix>();
        fixes.add(named);
        fixes.add(unnamed);

        assertEquals("歼-16", DirectionFindingMatchBridge.resolveDeviceId(named));
        assertNull(DirectionFindingMatchBridge.resolveDeviceId(unnamed));
        assertEquals(1, DirectionFindingMatchBridge.buildTrajectories(fixes).size());
        assertTrue(DirectionFindingMatchBridge.buildTrajectories(fixes).containsKey("歼-16"));
    }

    @Test
    void missingOutputDirFallsBackToFreq() {
        DetectSignal s = signal("1139", 286.0, 58.0, 1_700_000_000_000L, 2L);
        HopBatchIndex empty = HopBatchIndex.loadQuietly(null);
        String id = DirectionFindingMatchBridge.resolveBatchId(s, empty, s.getDetectTimesss());
        assertEquals("1139@286.0000", id);
    }

    private static DetectSignal signal(String plat, double freq, double az, long timeMs, long row) {
        DetectSignal s = new DetectSignal();
        s.setLocPlatId(plat);
        s.setFreq(freq);
        s.setAzimuth(az);
        s.setDetectTimesss(timeMs);
        s.setLongitude(Double.valueOf(116.0));
        s.setLatitude(Double.valueOf(40.0));
        s.setSourceRowIndex(Long.valueOf(row));
        return s;
    }
}
