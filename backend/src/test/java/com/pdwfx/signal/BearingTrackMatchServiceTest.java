package com.pdwfx.signal;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.service.BearingTrackMatchService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BearingTrackMatchServiceTest {

    private final BearingTrackMatchService service = new BearingTrackMatchService();

    @Test
    void mergesCrossFreqClustersWithAlignedBearing() {
        List<DetectSignal> trackA = buildTrack(500.0, 120.0, 0);
        List<DetectSignal> trackB = buildTrack(520.0, 120.05, 50);
        List<List<DetectSignal>> clusters = new ArrayList<>();
        clusters.add(trackA);
        clusters.add(trackB);

        List<List<DetectSignal>> merged = service.mergeMatchingClusters(clusters, new BearingTrackMatchService.MatchOptions());
        assertEquals(1, merged.size());
        assertEquals(trackA.size() + trackB.size(), merged.get(0).size());
    }

    private static List<DetectSignal> buildTrack(double freq, double azimuth, long timeOffsetMs) {
        List<DetectSignal> out = new ArrayList<>();
        long base = 1_700_000_000_000L + timeOffsetMs;
        for (int i = 0; i < 20; i++) {
            DetectSignal s = new DetectSignal();
            s.setFreq(freq);
            s.setAzimuth(azimuth + i * 0.01);
            s.setSignalLevel(-40);
            s.setDetectTimesss(base + i * 2000L);
            out.add(s);
        }
        return out;
    }
}
