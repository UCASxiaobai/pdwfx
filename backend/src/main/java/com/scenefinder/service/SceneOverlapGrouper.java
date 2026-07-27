package com.scenefinder.service;

import com.scenefinder.model.SceneOverlapGroup;
import com.scenefinder.model.SceneSummaryEntry;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 场景时间窗重合分组：任意两窗有时间交集则连边，经传递闭包合并（并查集）。
 * <ul>
 *   <li>A∩B、B∩C 但 A 与 C 不直接重合 → A、B、C 仍同组</li>
 *   <li>与其他场景均无交集 → 单独成组，不合并</li>
 * </ul>
 * 组内导出/绘图时段取成员时间窗并集。
 */
@Service
public class SceneOverlapGrouper {

    private final SceneSummaryReader sceneSummaryReader;

    public SceneOverlapGrouper(SceneSummaryReader sceneSummaryReader) {
        this.sceneSummaryReader = sceneSummaryReader;
    }

    public List<SceneOverlapGroup> group(Path outputDir, List<Integer> selectedRanks) throws IOException {
        if (selectedRanks == null || selectedRanks.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Integer> wanted = new HashSet<>(selectedRanks);
        List<SceneSummaryEntry> entries = sceneSummaryReader.load(outputDir).stream()
                .filter(e -> wanted.contains(e.getRank()))
                .sorted(Comparator.comparingInt(SceneSummaryEntry::getRank))
                .collect(Collectors.toList());
        return groupEntries(entries);
    }

    /**
     * 对给定场景条目做传递闭包分组（供测试与 {@link #group} 共用）。
     */
    static List<SceneOverlapGroup> groupEntries(List<SceneSummaryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new ArrayList<>();
        }

        int n = entries.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (windowsOverlap(entries.get(i), entries.get(j))) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<SceneSummaryEntry>> components = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            components.computeIfAbsent(root, ignored -> new ArrayList<>()).add(entries.get(i));
        }

        List<SceneOverlapGroup> groups = new ArrayList<>();
        for (List<SceneSummaryEntry> members : components.values()) {
            members.sort(Comparator.comparingInt(SceneSummaryEntry::getRank));
            List<Integer> ranks = members.stream().map(SceneSummaryEntry::getRank).collect(Collectors.toList());
            Instant spanStart = members.stream()
                    .map(SceneSummaryEntry::getWindowStart)
                    .min(Instant::compareTo)
                    .orElse(Instant.EPOCH);
            Instant spanEnd = members.stream()
                    .map(SceneSummaryEntry::getWindowEnd)
                    .max(Instant::compareTo)
                    .orElse(Instant.EPOCH);
            groups.add(new SceneOverlapGroup(ranks, spanStart, spanEnd, members));
        }
        groups.sort(Comparator.comparingInt(SceneOverlapGroup::getPrimaryRank));
        return groups;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int i, int j) {
        int ri = find(parent, i);
        int rj = find(parent, j);
        if (ri != rj) {
            parent[rj] = ri;
        }
    }

    /** 闭区间 [start,end] 是否有交集（端点相接也算重合） */
    static boolean windowsOverlap(SceneSummaryEntry a, SceneSummaryEntry b) {
        return !a.getWindowStart().isAfter(b.getWindowEnd())
                && !b.getWindowStart().isAfter(a.getWindowEnd());
    }
}
