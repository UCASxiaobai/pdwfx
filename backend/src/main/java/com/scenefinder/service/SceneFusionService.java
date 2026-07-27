package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SceneType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 融合两类场景：轨迹与轮询各自按分项 Top-K 选取，再合并时间重合的同类型场景，最后统一排名输出。
 */
@Service
public class SceneFusionService {

    public List<QualityScene> fuse(
            List<QualityScene> trackScenes,
            List<QualityScene> pollingScenes,
            SceneFinderProperties props
    ) {
        double overlapThreshold = props.getSceneFusionOverlapSuppressRatio();

        List<QualityScene> selectedTracks = mergeOverlappingScenes(
                selectTopKPerType(
                        trackScenes,
                        Math.max(0, props.getTopKTrackScenes()),
                        SceneType.TRACK_CONTINUOUS,
                        overlapThreshold
                )
        );
        List<QualityScene> selectedPolling = mergeOverlappingScenes(
                selectTopKPerType(
                        pollingScenes,
                        Math.max(0, props.getTopKPollingScenes()),
                        SceneType.MULTI_DEVICE_POLLING,
                        overlapThreshold
                )
        );

        List<QualityScene> combined = new ArrayList<>(selectedTracks.size() + selectedPolling.size());
        combined.addAll(selectedTracks);
        combined.addAll(selectedPolling);
        combined.sort(Comparator.comparingDouble(QualityScene::getScore).reversed());

        List<QualityScene> ranked = new ArrayList<>();
        int rank = 1;
        for (QualityScene scene : combined) {
            ranked.add(replaceRank(scene, rank++));
        }
        return ranked;
    }

    /**
     * 将时间重合、同类型且频段相近的多个场景合并为一个（时间取并集，指标取较优/合并值）。
     */
    List<QualityScene> mergeOverlappingScenes(List<QualityScene> scenes) {
        if (scenes.size() <= 1) {
            return new ArrayList<>(scenes);
        }

        int n = scenes.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (shouldMergeOutput(scenes.get(i), scenes.get(j))) {
                    union(parent, i, j);
                }
            }
        }

        Map<Integer, List<QualityScene>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            groups.computeIfAbsent(root, k -> new ArrayList<>()).add(scenes.get(i));
        }

        List<QualityScene> merged = new ArrayList<>();
        for (List<QualityScene> group : groups.values()) {
            merged.add(group.size() == 1 ? group.get(0) : unionScenes(group));
        }
        merged.sort(Comparator.comparing(QualityScene::getWindowStart));
        return merged;
    }

    private boolean shouldMergeOutput(QualityScene a, QualityScene b) {
        if (a.getSceneType() != b.getSceneType()) {
            return false;
        }
        if (timeOverlapMillis(a, b) <= 0) {
            return false;
        }
        if (!freqBandsOverlap(a, b)) {
            return false;
        }
        if (a.getSceneType() == SceneType.TRACK_CONTINUOUS) {
            return trackIdJaccard(a.getTrackIds(), b.getTrackIds()) >= 0.35;
        }
        double periodDiff = Math.abs(a.getPollingPeriodSec() - b.getPollingPeriodSec());
        double periodTol = Math.max(1.0, a.getPollingPeriodSec() * 0.12);
        return periodDiff <= periodTol;
    }

    private long timeOverlapMillis(QualityScene a, QualityScene b) {
        long start = Math.max(a.getWindowStart().toEpochMilli(), b.getWindowStart().toEpochMilli());
        long end = Math.min(a.getWindowEnd().toEpochMilli(), b.getWindowEnd().toEpochMilli());
        return Math.max(0, end - start);
    }

    private QualityScene unionScenes(List<QualityScene> group) {
        QualityScene anchor = group.stream()
                .max(Comparator.comparingDouble(QualityScene::getScore))
                .orElse(group.get(0));

        Instant start = group.stream()
                .map(QualityScene::getWindowStart)
                .min(Instant::compareTo)
                .orElse(anchor.getWindowStart());
        Instant end = group.stream()
                .map(QualityScene::getWindowEnd)
                .max(Instant::compareTo)
                .orElse(anchor.getWindowEnd());

        double freqMin = group.stream().mapToDouble(QualityScene::getFreqMinMhz).min().orElse(anchor.getFreqMinMhz());
        double freqMax = group.stream().mapToDouble(QualityScene::getFreqMaxMhz).max().orElse(anchor.getFreqMaxMhz());
        double freqCenter = (freqMin + freqMax) / 2.0;
        double maxScore = group.stream().mapToDouble(QualityScene::getScore).max().orElse(anchor.getScore());

        if (anchor.getSceneType() == SceneType.TRACK_CONTINUOUS) {
            Set<Integer> trackIds = new TreeSet<>();
            for (QualityScene scene : group) {
                trackIds.addAll(scene.getTrackIds());
            }
            return QualityScene.trackScene(
                    anchor.getRank(),
                    start,
                    end,
                    freqCenter,
                    freqMin,
                    freqMax,
                    anchor.getDistinctDeviceCount(),
                    maxScore,
                    trackIds.size(),
                    anchor.getMedianSeparationDeg(),
                    anchor.getAverageSmoothness(),
                    new ArrayList<>(trackIds)
            );
        }

        int burstSum = group.stream().mapToInt(QualityScene::getPeriodicBurstCount).sum();
        double weightedPeriod = 0;
        double weightedBearings = 0;
        int weight = 0;
        for (QualityScene scene : group) {
            int bursts = Math.max(1, scene.getPeriodicBurstCount());
            weightedPeriod += scene.getPollingPeriodSec() * bursts;
            weightedBearings += scene.getAvgBearingsPerBurst() * bursts;
            weight += bursts;
        }
        double period = weight > 0 ? weightedPeriod / weight : anchor.getPollingPeriodSec();
        double avgBearings = weight > 0 ? weightedBearings / weight : anchor.getAvgBearingsPerBurst();
        int typicalBearings = (int) Math.round(avgBearings);

        Set<Integer> trackIds = new TreeSet<>();
        for (QualityScene scene : group) {
            trackIds.addAll(scene.getTrackIds());
        }

        QualityScene merged = QualityScene.pollingScene(
                anchor.getRank(),
                start,
                end,
                freqCenter,
                freqMin,
                freqMax,
                maxScore,
                burstSum,
                anchor.getMedianSeparationDeg(),
                period,
                avgBearings,
                typicalBearings,
                anchor.getPeriodicityScore()
        );
        return trackIds.isEmpty() ? merged : merged.withTrackIds(new ArrayList<>(trackIds));
    }

    private int find(int[] parent, int i) {
        if (parent[i] != i) {
            parent[i] = find(parent, parent[i]);
        }
        return parent[i];
    }

    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    private List<QualityScene> selectTopKPerType(
            List<QualityScene> candidates,
            int targetK,
            SceneType expectedType,
            double overlapThreshold
    ) {
        if (targetK <= 0 || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<QualityScene> sorted = candidates.stream()
                .filter(s -> s.getSceneType() == expectedType)
                .sorted(Comparator.comparingDouble(QualityScene::getScore).reversed())
                .collect(Collectors.toList());

        List<QualityScene> selected = new ArrayList<>();
        for (QualityScene candidate : sorted) {
            if (selected.size() >= targetK) {
                break;
            }
            if (shouldSuppress(candidate, selected, overlapThreshold)) {
                continue;
            }
            selected.add(candidate);
        }
        return selected;
    }

    private boolean shouldSuppress(
            QualityScene candidate,
            List<QualityScene> selected,
            double overlapThreshold
    ) {
        for (QualityScene existing : selected) {
            double overlapRatio = overlapRatio(candidate, existing);
            if (overlapRatio < overlapThreshold) {
                continue;
            }
            if (existing.getScore() < candidate.getScore()) {
                continue;
            }
            if (contentSimilarity(candidate, existing) >= 0.45) {
                return true;
            }
        }
        return false;
    }

    private double contentSimilarity(QualityScene a, QualityScene b) {
        if (a.getSceneType() == SceneType.MULTI_DEVICE_POLLING) {
            double periodDiff = Math.abs(a.getPollingPeriodSec() - b.getPollingPeriodSec());
            double periodTol = Math.max(1.0, a.getPollingPeriodSec() * 0.12);
            boolean samePeriod = periodDiff <= periodTol;
            boolean sameBand = freqBandsOverlap(a, b);
            return (samePeriod && sameBand) ? 1.0 : 0.3;
        }
        return trackIdJaccard(a.getTrackIds(), b.getTrackIds());
    }

    private boolean freqBandsOverlap(QualityScene a, QualityScene b) {
        return a.getFreqMaxMhz() >= b.getFreqMinMhz() - 1.0 && b.getFreqMaxMhz() >= a.getFreqMinMhz() - 1.0;
    }

    private double trackIdJaccard(List<Integer> aIds, List<Integer> bIds) {
        if (aIds.isEmpty() || bIds.isEmpty()) {
            return 0;
        }
        Set<Integer> union = new TreeSet<>();
        union.addAll(aIds);
        union.addAll(bIds);
        Set<Integer> aSet = new HashSet<>(aIds);
        long shared = bIds.stream().filter(aSet::contains).count();
        return union.isEmpty() ? 0 : shared / (double) union.size();
    }

    private double overlapRatio(QualityScene a, QualityScene b) {
        long overlap = timeOverlapMillis(a, b);
        long lenA = a.getWindowEnd().toEpochMilli() - a.getWindowStart().toEpochMilli();
        long lenB = b.getWindowEnd().toEpochMilli() - b.getWindowStart().toEpochMilli();
        long shorter = Math.max(1, Math.min(lenA, lenB));
        return overlap / (double) shorter;
    }

    private QualityScene replaceRank(QualityScene scene, int rank) {
        return new QualityScene(
                rank,
                scene.getSceneType(),
                scene.getWindowStart(),
                scene.getWindowEnd(),
                scene.getFreqCenterMhz(),
                scene.getFreqMinMhz(),
                scene.getFreqMaxMhz(),
                scene.getDistinctDeviceCount(),
                scene.getScore(),
                scene.getTrackCount(),
                scene.getMedianSeparationDeg(),
                scene.getAverageSmoothness(),
                scene.getTrackIds(),
                scene.getPollingPeriodSec(),
                scene.getPeriodicBurstCount(),
                scene.getAvgBearingsPerBurst(),
                scene.getPeriodicityScore(),
                scene.getAnnotation()
        );
    }
}
