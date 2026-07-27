package com.scenefinder.service;

import com.scenefinder.model.SceneOverlapGroup;
import com.scenefinder.model.SceneSummaryEntry;
import com.scenefinder.model.SceneType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneOverlapGrouperTest {

  @Test
  void chainOverlap_mergesTransitiveEvenWhenEndsDoNotMeet() {
    // A∩B, B∩C, but A∩C empty → still one group {1,2,3}
    List<SceneSummaryEntry> entries = Arrays.asList(
        entry(1, "2025-01-01T12:00:00Z", "2025-01-01T12:03:00Z"),
        entry(2, "2025-01-01T12:02:00Z", "2025-01-01T12:05:00Z"),
        entry(3, "2025-01-01T12:04:00Z", "2025-01-01T12:08:00Z")
    );
    List<SceneOverlapGroup> groups = SceneOverlapGrouper.groupEntries(entries);
    assertEquals(1, groups.size());
    assertEquals(Arrays.asList(1, 2, 3), groups.get(0).getRanks());
    assertEquals(Instant.parse("2025-01-01T12:00:00Z"), groups.get(0).getSpanStart());
    assertEquals(Instant.parse("2025-01-01T12:08:00Z"), groups.get(0).getSpanEnd());
  }

  @Test
  void noOverlap_eachSceneAlone() {
    List<SceneSummaryEntry> entries = Arrays.asList(
        entry(1, "2025-01-01T12:00:00Z", "2025-01-01T12:01:00Z"),
        entry(2, "2025-01-01T12:10:00Z", "2025-01-01T12:11:00Z")
    );
    List<SceneOverlapGroup> groups = SceneOverlapGrouper.groupEntries(entries);
    assertEquals(2, groups.size());
    List<List<Integer>> rankSets = groups.stream()
        .map(SceneOverlapGroup::getRanks)
        .collect(Collectors.toList());
    assertTrue(rankSets.contains(Collections.singletonList(1)));
    assertTrue(rankSets.contains(Collections.singletonList(2)));
  }

  private static SceneSummaryEntry entry(int rank, String start, String end) {
    return new SceneSummaryEntry(
        rank,
        SceneType.TRACK_CONTINUOUS,
        Instant.parse(start),
        Instant.parse(end),
        200,
        300,
        0,
        0,
        Collections.emptyList(),
        null
    );
  }
}
