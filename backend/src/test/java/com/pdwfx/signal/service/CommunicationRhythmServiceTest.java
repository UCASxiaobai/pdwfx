package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.TargetView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationRhythmServiceTest {

    private static final long PRI_MS = 100L;
    private static final double FREQ_MHZ = 286.0;

    private final CommunicationRhythmService rhythm = new CommunicationRhythmService();

    @Test
    void complementaryTalkersFallBackToPri() {
        List<DetectSignal> aPulses = new ArrayList<DetectSignal>();
        aPulses.addAll(burstPulses(0L, 10, 104.0));
        aPulses.addAll(burstPulses(5000L, 10, 104.0));
        aPulses.addAll(burstPulses(10000L, 10, 104.0));
        List<DetectSignal> bPulses = new ArrayList<DetectSignal>();
        bPulses.addAll(burstPulses(1100L, 38, 111.0));
        bPulses.addAll(burstPulses(6100L, 38, 111.0));

        TargetView a = rhythmTarget("T1", aPulses);
        TargetView b = rhythmTarget("T2", bPulses);
        Double rawPeriod = a.getPeriodMs();
        assertTrue(rawPeriod != null && rawPeriod.doubleValue() > 2000.0);

        List<TargetView> targets = new ArrayList<TargetView>();
        targets.add(a);
        targets.add(b);
        rhythm.adjustPeriodForPeerOccupancy(targets);

        assertEquals(PRI_MS, a.getPeriodMs(), 1.0);
        assertFalse(CommunicationLinkAnalysisService.isLongTxIntervalAir(a));
        assertTrue(a.getPeriodAdjustNote().contains("回退 PRI"));
        assertTrue(a.getPeriodAdjustNote().contains("间隙被同频占用"));
    }

    @Test
    void sparseTwoTargetsKeepLongPeriod() {
        List<DetectSignal> aPulses = new ArrayList<DetectSignal>();
        aPulses.addAll(burstPulses(0L, 10, 104.0));
        aPulses.addAll(burstPulses(5000L, 10, 104.0));
        aPulses.addAll(burstPulses(10000L, 10, 104.0));
        List<DetectSignal> bPulses = new ArrayList<DetectSignal>();
        bPulses.addAll(burstPulses(20000L, 10, 111.0));
        bPulses.addAll(burstPulses(25000L, 10, 111.0));
        bPulses.addAll(burstPulses(30000L, 10, 111.0));

        TargetView a = rhythmTarget("T1", aPulses);
        TargetView b = rhythmTarget("T2", bPulses);
        double rawA = a.getPeriodMs().doubleValue();
        double rawB = b.getPeriodMs().doubleValue();
        assertTrue(rawA > 2000.0);
        assertTrue(rawB > 2000.0);

        List<TargetView> targets = new ArrayList<TargetView>();
        targets.add(a);
        targets.add(b);
        rhythm.adjustPeriodForPeerOccupancy(targets);

        assertEquals(rawA, a.getPeriodMs(), 1.0);
        assertEquals(rawB, b.getPeriodMs(), 1.0);
        assertNull(a.getPeriodAdjustNote());
        assertTrue(CommunicationLinkAnalysisService.isLongTxIntervalAir(a));
    }

    @Test
    void singleTargetPeriodUnchanged() {
        List<DetectSignal> pulses = new ArrayList<DetectSignal>();
        pulses.addAll(burstPulses(0L, 10, 104.0));
        pulses.addAll(burstPulses(5000L, 10, 104.0));
        pulses.addAll(burstPulses(10000L, 10, 104.0));
        TargetView a = rhythmTarget("T1", pulses);
        double raw = a.getPeriodMs().doubleValue();
        assertTrue(raw > 2000.0);

        rhythm.adjustPeriodForPeerOccupancy(Arrays.asList(new TargetView[]{a}));

        assertEquals(raw, a.getPeriodMs(), 1.0);
        assertNull(a.getPeriodAdjustNote());
        assertTrue(CommunicationLinkAnalysisService.isLongTxIntervalAir(a));
    }

    private TargetView rhythmTarget(String id, List<DetectSignal> pulses) {
        TargetView t = new TargetView();
        t.setTargetId(id);
        rhythm.applyRhythmMetrics(t, pulses);
        t.setClusteredSignals(pulses);
        return t;
    }

    private static List<DetectSignal> burstPulses(long startMs, int count, double azimuth) {
        List<DetectSignal> out = new ArrayList<DetectSignal>();
        for (int i = 0; i < count; i++) {
            DetectSignal s = new DetectSignal();
            s.setDetectTimesss(startMs + i * PRI_MS);
            s.setFreq(FREQ_MHZ);
            s.setAzimuth(azimuth);
            s.setSignalDwellMs((double) PRI_MS);
            s.setSignalLevel(80.0);
            out.add(s);
        }
        return out;
    }
}
