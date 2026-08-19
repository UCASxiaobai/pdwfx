package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SceneTrackPartitionTest {

    @Test
    void partitionBySceneTrackIdKeepsLaneBoundaries() throws Exception {
        SignalAnalysisService svc = new SignalAnalysisService(
                null, null, null, null, null, null, null);
        List<DetectSignal> signals = new ArrayList<DetectSignal>();
        signals.addAll(cluster(1, 100.0, 8));
        signals.addAll(cluster(1, 100.2, 8));
        signals.addAll(cluster(2, 101.0, 8));
        signals.addAll(cluster(3, 102.0, 8));
        Method m = SignalAnalysisService.class.getDeclaredMethod("partitionBySceneTrackId", List.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<List<DetectSignal>> groups = (List<List<DetectSignal>>) m.invoke(svc, signals);
        assertNotNull(groups);
        assertEquals(3, groups.size());
    }

    private static List<DetectSignal> cluster(int trackId, double az, int n) {
        List<DetectSignal> list = new ArrayList<DetectSignal>();
        for (int i = 0; i < n; i++) {
            DetectSignal s = new DetectSignal();
            s.setSceneTrackId(Integer.valueOf(trackId));
            s.setAzimuth(az);
            s.setFreq(240.55);
            s.setDetectTimesss(1_000_000L + i * 8000L);
            list.add(s);
        }
        return list;
    }
}
