package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SceneType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiDevicePollingDetectorServiceTest {

    private final MultiDevicePollingDetectorService detector = new MultiDevicePollingDetectorService();

    /**
     * 三测向点挤在 ~10° 内（100/105/110），每轮 3 设备、8s 周期——近距并发轮询。
     */
    @Test
    void detectsPollingWhenBearingsAreCloseButRepeatPerBurst() {
        SceneFinderProperties props = pollingProps();
        List<QualityScene> scenes = detector.findPollingScenes(
                buildPeriodicPoints(new double[] {100.0, 105.0, 110.0}, 8.0), props);

        assertFalse(scenes.isEmpty(), "expected polling scene for tight multi-device bursts");
        assertTrue(scenes.stream().anyMatch(s -> s.getSceneType() == SceneType.MULTI_DEVICE_POLLING));
    }

    /**
     * 更紧的三方位（100/101/102），旧版 4° 合并间隙会并成单簇；0.5° 间隙应识别为 3 设备。
     */
    @Test
    void detectsPollingWhenBearingsWithinFewDegrees() {
        SceneFinderProperties props = pollingProps();
        List<QualityScene> scenes = detector.findPollingScenes(
                buildPeriodicPoints(new double[] {100.0, 101.0, 102.0}, 8.0), props);

        assertFalse(scenes.isEmpty(), "100/101/102 should count as three concurrent devices per burst");
    }

    @Test
    void rejectsWhenEffectivelySingleDeviceInBurst() {
        SceneFinderProperties props = pollingProps();
        // 0.5° 内视为同一设备重复测向 → 仅 1 簇，不足 3 设备
        List<QualityScene> scenes = detector.findPollingScenes(
                buildPeriodicPoints(new double[] {100.0, 100.2, 100.4}, 8.0), props);

        assertTrue(scenes.isEmpty(), "duplicate bearings on one device should not qualify as polling");
    }

    @Test
    void rejectsTwoContinuousOverlappingTracks() {
        SceneFinderProperties props = pollingProps();
        List<DetectionPoint> points = buildOverlappingContinuousTracks();
        List<QualityScene> scenes = detector.findPollingScenes(points, props);
        assertTrue(scenes.isEmpty(), "two smooth overlapping tracks are not periodic multi-point polling");
    }

    private static List<DetectionPoint> buildOverlappingContinuousTracks() {
        List<DetectionPoint> points = new ArrayList<>();
        long baseMs = Instant.parse("2025-07-03T10:54:14Z").toEpochMilli();
        double freq = 307.183;
        long row = 0;
        for (int sec = 0; sec <= 51; sec += 2) {
            double bearing1 = 124.5 + sec * 0.08;
            points.add(new DetectionPoint("synthetic.csv", row++, Instant.ofEpochMilli(baseMs + sec * 1000L),
                    bearing1, freq, null));
        }
        for (int sec = 36; sec <= 97; sec += 2) {
            double bearing2 = 130.0 + (sec - 36) * 0.15;
            points.add(new DetectionPoint("synthetic.csv", row++, Instant.ofEpochMilli(baseMs + sec * 1000L),
                    bearing2, freq, null));
        }
        return points;
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
        return props;
    }

    private static List<DetectionPoint> buildPeriodicPoints(double[] bearings, double periodSec) {
        List<DetectionPoint> points = new ArrayList<>();
        long baseMs = Instant.parse("2025-07-03T10:00:00Z").toEpochMilli();
        for (int round = 0; round < 13; round++) {
            long burstStart = baseMs + Math.round(round * periodSec * 1000.0);
            for (int bi = 0; bi < bearings.length; bi++) {
                points.add(new DetectionPoint(
                        "synthetic.csv",
                        round * 10L + bi,
                        Instant.ofEpochMilli(burstStart + bi * 200L),
                        bearings[bi],
                        240.55,
                        null
                ));
            }
        }
        return points;
    }
}
