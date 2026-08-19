package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.NetworkView;
import com.pdwfx.signal.model.TargetView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommunicationLinkAnalysisServiceTest {

    private final CommunicationLinkAnalysisService service = new CommunicationLinkAnalysisService();

    @Test
    void longTxIntervalForcesAircraft() {
        assertTrue(CommunicationLinkAnalysisService.isLongTxIntervalAir(Double.valueOf(3000.0), Double.valueOf(500.0)));
        assertFalse(CommunicationLinkAnalysisService.isLongTxIntervalAir(Double.valueOf(1500.0), Double.valueOf(500.0)));
        assertTrue(CommunicationLinkAnalysisService.isLongTxIntervalAir(null, Double.valueOf(2500.0)));
        assertFalse(CommunicationLinkAnalysisService.isLongTxIntervalAir(Double.valueOf(2000.0), Double.valueOf(2000.0)));
    }

    @Test
    void longPeriodKeepsAirEvenOnD03() {
        TargetView t = singleTarget("T-slow", "AWACS", "MASTER", 6.0, 26.0, 500.0, 100.0);
        t.setPeriodMs(Double.valueOf(3000.0));
        NetworkView view = network(t);
        service.analyze(view, emptySignals());
        assertEquals("AIR", view.getTargets().get(0).getTargetType());
    }

    @Test
    void platformTypeByDutyUsesExperimentalBands() {
        assertEquals("AIR", CommunicationLinkAnalysisService.platformTypeByDuty(0.5));
        assertEquals("AIR", CommunicationLinkAnalysisService.platformTypeByDuty(3.19));
        assertEquals("AWACS", CommunicationLinkAnalysisService.platformTypeByDuty(3.2));
        assertEquals("AWACS", CommunicationLinkAnalysisService.platformTypeByDuty(6.16));
        assertEquals("AWACS", CommunicationLinkAnalysisService.platformTypeByDuty(10.8));
        assertEquals("GROUND", CommunicationLinkAnalysisService.platformTypeByDuty(11.0));
        assertEquals("GROUND", CommunicationLinkAnalysisService.platformTypeByDuty(25.0));
    }

    @Test
    void formerHighDutyAwacsFromBatchBecomeGround() {
        // batch_20250703_105233_1786935716466 中 14%+ 原预警机
        double[] duties = {24.53, 22.92, 22.07, 18.32, 14.49};
        for (int i = 0; i < duties.length; i++) {
            assertEquals("GROUND", CommunicationLinkAnalysisService.platformTypeByDuty(duties[i]),
                    "duty=" + duties[i]);
        }
    }

    @Test
    void scoreD03RejectsHighDutySingleTransmit() {
        NetworkView view = network(singleTarget("T-high", "AWACS", "MASTER", 22.0, 26.0, 500.0, 100.0));
        service.analyze(view, emptySignals());
        assertNotEquals(CommunicationLinkAnalysisService.CHANNEL_D03, view.getCommLinkChannel());
    }

    @Test
    void scoreD03AcceptsTypicalAwacsSingleTransmit() {
        NetworkView view = network(singleTarget("T-ok", "AWACS", "MASTER", 6.0, 26.0, 500.0, 100.0));
        service.analyze(view, emptySignals());
        assertEquals(CommunicationLinkAnalysisService.CHANNEL_D03, view.getCommLinkChannel());
    }

    @Test
    void scoreD05AcceptsAwacsAndFighterMix() {
        List<TargetView> targets = new ArrayList<TargetView>();
        targets.add(singleTarget("AW", "AWACS", "MASTER", 5.5, 25.0, 500.0, 70.0));
        targets.add(singleTarget("F1", "AIR", "SLAVE", 0.5, 20.0, 3000.0, 15.0));
        targets.add(singleTarget("F2", "AIR", "SLAVE", 0.5, 20.0, 2500.0, 15.0));
        NetworkView view = network(targets);
        view.setNetworkDutyCycle(7.5);
        service.analyze(view, emptySignals());
        assertEquals(CommunicationLinkAnalysisService.CHANNEL_D05, view.getCommLinkChannel());
    }

    @Test
    void scoreD04AcceptsGroundAwacsAlternate() {
        List<TargetView> targets = new ArrayList<TargetView>();
        targets.add(singleTarget("AW", "AWACS", "MASTER", 7.35, 18.5, 250.0, 30.0));
        targets.add(singleTarget("G", "GROUND", "SLAVE", 25.0, 63.4, 250.0, 70.0));
        NetworkView view = network(targets);
        view.setNetworkDutyCycle(32.5);
        service.analyze(view, emptySignals());
        assertEquals(CommunicationLinkAnalysisService.CHANNEL_D04, view.getCommLinkChannel());
    }

    @Test
    void d04DoesNotRewriteTypeByDwell() {
        List<TargetView> targets = new ArrayList<TargetView>();
        targets.add(singleTarget("AW", "AWACS", "MASTER", 7.35, 18.5, 250.0, 30.0));
        targets.add(singleTarget("G", "GROUND", "SLAVE", 25.0, 63.4, 250.0, 65.0));
        targets.add(singleTarget("T3", "AIR", "SLAVE", 2.01, 27.45, 250.0, 5.0));
        NetworkView view = network(targets);
        view.setNetworkDutyCycle(32.5);
        service.analyze(view, emptySignals());
        assertEquals(CommunicationLinkAnalysisService.CHANNEL_D04, view.getCommLinkChannel());
        assertEquals("AIR", view.getTargets().get(2).getTargetType());
        assertEquals("AWACS", view.getTargets().get(0).getTargetType());
        assertEquals("GROUND", view.getTargets().get(1).getTargetType());
    }

    private static NetworkView network(TargetView one) {
        List<TargetView> targets = new ArrayList<TargetView>();
        targets.add(one);
        return network(targets);
    }

    private static NetworkView network(List<TargetView> targets) {
        NetworkView view = new NetworkView();
        view.setCommMode("SAME_FREQ");
        view.setTargets(targets);
        view.setTargetCount(targets.size());
        return view;
    }

    private static TargetView singleTarget(String id, String type, String role,
                                           double duty, double dwellMs, double priMs, double sharePct) {
        TargetView t = new TargetView();
        t.setTargetId(id);
        t.setTargetType(type);
        t.setRole(role);
        t.setAvgDutyCycle(duty);
        t.setBurstDurationMeanMs(dwellMs);
        t.setEstimatedPriMs(Double.valueOf(priMs));
        t.setEmissionSharePct(sharePct);
        return t;
    }

    private static List<DetectSignal> emptySignals() {
        return new ArrayList<DetectSignal>();
    }
}
