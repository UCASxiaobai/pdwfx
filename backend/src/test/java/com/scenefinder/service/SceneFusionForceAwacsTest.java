package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.AwacsOccupancyWindow;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SceneType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneFusionForceAwacsTest {

    private final SceneFusionService fusion = new SceneFusionService();

    @Test
    void forceKeepsLowScoreSceneOverlappingAwacsWindow() {
        SceneFinderProperties props = new SceneFinderProperties();
        props.setTopKTrackScenes(1);
        props.setTopKPollingScenes(0);
        props.setSceneFusionOverlapSuppressRatio(0.99);

        QualityScene high = QualityScene.trackScene(
                1,
                Instant.ofEpochMilli(0L),
                Instant.ofEpochMilli(60_000L),
                200.0,
                199.9,
                200.1,
                1,
                10.0,
                1,
                5.0,
                1.0,
                Collections.singletonList(Integer.valueOf(1))
        );
        QualityScene lowAwacsBand = QualityScene.trackScene(
                2,
                Instant.ofEpochMilli(10_000L),
                Instant.ofEpochMilli(50_000L),
                306.925,
                306.9,
                306.95,
                1,
                1.0,
                1,
                5.0,
                1.0,
                Collections.singletonList(Integer.valueOf(2))
        );

        List<AwacsOccupancyWindow> force = new ArrayList<AwacsOccupancyWindow>();
        force.add(new AwacsOccupancyWindow(306.925, 0.05, 5_000L, 55_000L, "AW1"));

        List<QualityScene> out = fusion.assembleDisjointResults(
                java.util.Arrays.asList(high, lowAwacsBand),
                Collections.<QualityScene>emptyList(),
                props,
                force
        );
        assertEquals(2, out.size());
        boolean keptAwacsFreq = false;
        for (int i = 0; i < out.size(); i++) {
            if (Math.abs(out.get(i).getFreqCenterMhz() - 306.925) < 0.01) {
                keptAwacsFreq = true;
            }
        }
        assertTrue(keptAwacsFreq, "与预警机占用窗重叠的低频分场景应被强制保留");
    }
}
