package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.DetectionBatchResponse;
import com.pdwfx.signal.model.DetectionBatchRow;
import com.pdwfx.signal.model.NetworkView;
import com.pdwfx.signal.model.TargetView;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionBatchAssemblerTest {

    @Test
    void sameTargetSharesBatchId() {
        DetectSignal a1 = signal("R1", 1000L, 90.0, 306.925);
        DetectSignal a2 = signal("R2", 1100L, 90.2, 306.925);
        DetectSignal b1 = signal("R3", 1200L, 180.0, 306.925);
        DetectSignal leftover = signal("R4", 1300L, 10.0, 306.925);

        TargetView t1 = target("T1", "AWACS", a1, a2);
        TargetView t2 = target("T2", "AIR", b1);
        NetworkView view = new NetworkView();
        view.setNetworkId(21);
        view.setFreq(306.925);
        view.setCommLinkChannel("D03");
        view.setCommLinkChannelLabel("D03 预警机态势广播");
        List<TargetView> targets = new ArrayList<TargetView>();
        targets.add(t1);
        targets.add(t2);
        view.setTargets(targets);

        List<DetectSignal> bucket = new ArrayList<DetectSignal>();
        bucket.add(a1);
        bucket.add(a2);
        bucket.add(b1);
        bucket.add(leftover);

        DetectionBatchAssembler assembler = new DetectionBatchAssembler();
        assembler.setAnalysisId("abc");
        assembler.addNetwork(view, bucket, true);
        DetectionBatchResponse response = assembler.build(1L);

        assertEquals(2, response.getBatchCount());
        assertEquals(4, response.getDetectionCount());
        assertEquals(1, response.getUnassignedCount());
        assertEquals(1, batchOf(response, "R1"));
        assertEquals(1, batchOf(response, "R2"));
        assertEquals(2, batchOf(response, "R3"));
        assertEquals(0, batchOf(response, "R4"));

        DetectionBatchRow r1 = rowOf(response, "R1");
        assertEquals("AWACS", r1.getTargetType());
        assertEquals("预警机", r1.getTargetTypeLabel());
        assertEquals(90.0, r1.getAzimuthDeg(), 1e-6);
        assertEquals(306.925, r1.getFreqMhz(), 1e-6);
        assertEquals("D03", r1.getChannel());
        assertEquals(1000L, r1.getDetectTimeMs());
        assertTrue(r1.getDetectTime() != null && r1.getDetectTime().length() > 0);
    }

    private static int batchOf(DetectionBatchResponse response, String signalId) {
        return rowOf(response, signalId).getBatchId();
    }

    private static DetectionBatchRow rowOf(DetectionBatchResponse response, String signalId) {
        for (DetectionBatchRow row : response.getDetections()) {
            if (signalId.equals(row.getSignalId())) {
                return row;
            }
        }
        throw new AssertionError("missing " + signalId);
    }

    private static TargetView target(String id, String type, DetectSignal... signals) {
        TargetView t = new TargetView();
        t.setTargetId(id);
        t.setTargetType(type);
        List<DetectSignal> list = new ArrayList<DetectSignal>();
        for (int i = 0; i < signals.length; i++) {
            list.add(signals[i]);
        }
        t.setClusteredSignals(list);
        return t;
    }

    private static DetectSignal signal(String id, long t, double az, double freq) {
        DetectSignal s = new DetectSignal();
        s.setId(id);
        s.setDetectTimesss(t);
        s.setAzimuth(az);
        s.setFreq(freq);
        return s;
    }
}
