package com.scenefinder.service;

import com.scenefinder.model.DetectionPoint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DutyPriorityEstimateTest {

    @Test
    void estimateDutyUsesDwellOverSpan() {
        Instant t0 = Instant.parse("2025-01-01T00:00:00Z");
        List<DetectionPoint> pts = Arrays.asList(
                new DetectionPoint("f", 1, t0, 10, 400, "a", 500),
                new DetectionPoint("f", 2, t0.plusSeconds(1), 10.2, 400, "b", 500),
                new DetectionPoint("f", 3, t0.plusSeconds(2), 10.1, 400, "c", 500)
        );
        // active 1500ms / span 2000ms = 75%
        double duty = TrackBuilderService.estimateDutyPct(pts);
        assertTrue(duty > 70 && duty <= 100, "duty=" + duty);
    }
}
