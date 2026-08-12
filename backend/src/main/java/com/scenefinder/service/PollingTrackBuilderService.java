package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SourceRowRef;
import com.scenefinder.model.TrackObservation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 轮询场景专用建轨：先筛出参与轮询的 burst 点位，再估计并发目标数 N，
 * 按「轮次 k 槽位 i → 轮次 k+1 槽位 i」跨轮关联，避免近距并发被连续轨迹算法并成单目标。
 */
@Service
public class PollingTrackBuilderService {

    /**
     * 单次轮询场景建轨结果。
     */
    public static final class LaneBuildResult {
        private final List<BearingTrack> tracks;
        private final Map<SourceRowRef, Integer> rowToTrackId;
        private final int laneCount;
        private final int alignedRoundCount;

        LaneBuildResult(
                List<BearingTrack> tracks,
                Map<SourceRowRef, Integer> rowToTrackId,
                int laneCount,
                int alignedRoundCount
        ) {
            this.tracks = tracks == null ? Collections.emptyList() : tracks;
            this.rowToTrackId = rowToTrackId == null ? Collections.emptyMap() : rowToTrackId;
            this.laneCount = laneCount;
            this.alignedRoundCount = alignedRoundCount;
        }

        public static LaneBuildResult empty() {
            return new LaneBuildResult(Collections.emptyList(), Collections.emptyMap(), 0, 0);
        }

        public List<BearingTrack> getTracks() {
            return tracks;
        }

        public Map<SourceRowRef, Integer> getRowToTrackId() {
            return rowToTrackId;
        }

        public int getLaneCount() {
            return laneCount;
        }

        public int getAlignedRoundCount() {
            return alignedRoundCount;
        }
    }

    /**
     * 为轮询场景构建 N 条 lane 轨迹，仅连接相邻对齐轮次中同一槽位的测向点。
     */
    public LaneBuildResult buildLaneTracks(
            QualityScene scene,
            List<DetectionPoint> allPoints,
            SceneFinderProperties props,
            int startTrackId
    ) {
        if (scene == null || allPoints == null || allPoints.isEmpty() || scene.getPollingPeriodSec() <= 0) {
            return LaneBuildResult.empty();
        }

        List<DetectionPoint> inScene = filterScenePoints(scene, allPoints);
        if (inScene.size() < props.getPollingMinBearingsPerBurst() * 2) {
            return LaneBuildResult.empty();
        }

        long coalesceMillis = Math.max(1, Math.round(props.getPollingBurstCoalesceSec() * 1000.0));
        double mergeGapDeg = props.getPollingBurstBearingGapDeg();
        List<BurstSnapshot> bursts = detectValidBursts(inScene, coalesceMillis, mergeGapDeg, props);
        if (bursts.size() < 2) {
            return LaneBuildResult.empty();
        }

        List<BurstSnapshot> aligned = alignBurstsToSceneWindow(bursts, scene, scene.getPollingPeriodSec(), props);
        if (aligned.size() < 2) {
            return LaneBuildResult.empty();
        }

        int minRoundHits = PollingAlignmentUtil.minRoundHits(
                aligned.size(), props.getPollingMinSlotRoundCoverageRatio());
        List<Double> persistentSlots = identifyPersistentSlotCenters(aligned, minRoundHits, props);
        if (persistentSlots.size() < props.getPollingMinBearingsPerBurst()) {
            return LaneBuildResult.empty();
        }

        List<BearingTrack> laneTracks = new ArrayList<>(persistentSlots.size());
        for (int i = 0; i < persistentSlots.size(); i++) {
            BearingTrack track = new BearingTrack();
            track.setId(startTrackId + i);
            laneTracks.add(track);
        }

        Map<SourceRowRef, Integer> rowToTrackId = new LinkedHashMap<>();
        int[] slotRoundHits = new int[persistentSlots.size()];

        for (BurstSnapshot burst : aligned) {
            List<PollingBurstClustering.BearingCluster> clusters = burst.getClusters();
            if (clusters.isEmpty()) {
                continue;
            }
            int[] assignment = assignClustersToLanes(persistentSlots, clusters, persistentSlots.size());
            for (int slot = 0; slot < persistentSlots.size(); slot++) {
                int clusterIdx = assignment[slot];
                if (clusterIdx < 0 || clusterIdx >= clusters.size()) {
                    continue;
                }
                slotRoundHits[slot]++;
                BearingTrack track = laneTracks.get(slot);
                PollingBurstClustering.BearingCluster cluster = clusters.get(clusterIdx);
                for (DetectionPoint point : cluster.getPoints()) {
                    rowToTrackId.put(point.sourceRow(), track.getId());
                }
                track.getObservations().add(roundObservation(cluster, burst.getCenterTime()));
            }
        }

        List<BearingTrack> nonEmpty = new ArrayList<>();
        Map<SourceRowRef, Integer> filteredRows = new LinkedHashMap<>();
        for (int slot = 0; slot < persistentSlots.size(); slot++) {
            if (slotRoundHits[slot] < minRoundHits) {
                continue;
            }
            BearingTrack track = laneTracks.get(slot);
            if (track.getObservations().size() < 2) {
                continue;
            }
            track.getObservations().sort(Comparator.comparing(TrackObservation::getTime));
            nonEmpty.add(track);
            for (TrackObservation obs : track.getObservations()) {
                filteredRows.put(obs.sourceRow(), track.getId());
            }
        }
        if (nonEmpty.isEmpty()) {
            return LaneBuildResult.empty();
        }

        return new LaneBuildResult(nonEmpty, filteredRows, nonEmpty.size(), aligned.size());
    }

    /** 每轮每槽仅保留一个代表点（簇心方位 + 轮次中心时刻），避免同轮多点被连成折线。 */
    private static TrackObservation roundObservation(PollingBurstClustering.BearingCluster cluster, Instant roundCenterTime) {
        DetectionPoint rep = cluster.getPoints().get(0);
        return new TrackObservation(
                roundCenterTime,
                BearingMath.normalize360(cluster.getCenterDeg()),
                rep.getFrequencyMhz(),
                rep.getSourceFile(),
                rep.getRowIndex()
        );
    }

    private static List<DetectionPoint> filterScenePoints(QualityScene scene, List<DetectionPoint> allPoints) {
        List<DetectionPoint> inScene = new ArrayList<>();
        for (DetectionPoint p : allPoints) {
            if (p.getTime().isBefore(scene.getWindowStart()) || p.getTime().isAfter(scene.getWindowEnd())) {
                continue;
            }
            if (p.getFrequencyMhz() < scene.getFreqMinMhz() - 0.01
                    || p.getFrequencyMhz() > scene.getFreqMaxMhz() + 0.01) {
                continue;
            }
            inScene.add(p);
        }
        inScene.sort(Comparator.comparing(DetectionPoint::getTime));
        return inScene;
    }

    /**
     * 按场景评分时间窗与周期网格对齐 burst，要求覆盖足够多的期望轮次（整窗重复）。
     */
    private List<BurstSnapshot> alignBurstsToSceneWindow(
            List<BurstSnapshot> bursts,
            QualityScene scene,
            double periodSec,
            SceneFinderProperties props
    ) {
        if (bursts.isEmpty() || periodSec <= 0) {
            return Collections.emptyList();
        }
        long periodMs = Math.max(1, Math.round(periodSec * 1000.0));
        double tolMs = periodSec * props.getPollingPeriodToleranceRatio() * 1000.0;
        long startMs = scene.getWindowStart().toEpochMilli();
        long endMs = scene.getWindowEnd().toEpochMilli();

        List<BurstSnapshot> sorted = bursts.stream()
                .sorted(Comparator.comparing(BurstSnapshot::getCenterTime))
                .collect(Collectors.toList());

        List<BurstSnapshot> aligned = new ArrayList<>();
        java.util.Set<BurstSnapshot> used = new java.util.HashSet<>();
        for (long targetMs = startMs; targetMs <= endMs + tolMs; targetMs += periodMs) {
            BurstSnapshot hit = findBurstNear(sorted, targetMs, tolMs, used);
            if (hit != null) {
                aligned.add(hit);
                used.add(hit);
            }
        }

        return aligned;
    }

    private List<BurstSnapshot> detectValidBursts(
            List<DetectionPoint> sorted,
            long coalesceMillis,
            double mergeGapDeg,
            SceneFinderProperties props
    ) {
        List<BurstSnapshot> bursts = new ArrayList<>();
        int i = 0;
        while (i < sorted.size()) {
            Instant burstStart = sorted.get(i).getTime();
            List<DetectionPoint> group = new ArrayList<>();
            group.add(sorted.get(i));
            int j = i + 1;
            while (j < sorted.size()
                    && sorted.get(j).getTime().toEpochMilli() - burstStart.toEpochMilli() <= coalesceMillis) {
                group.add(sorted.get(j));
                j++;
            }
            i = j;

            List<PollingBurstClustering.BearingCluster> clusters = PollingBurstClustering.clusterConcurrentTargets(
                    group, props);
            if (clusters.size() < props.getPollingMinBearingsPerBurst()) {
                continue;
            }
            long centerMs = group.stream().mapToLong(p -> p.getTime().toEpochMilli()).sum() / group.size();
            bursts.add(new BurstSnapshot(Instant.ofEpochMilli(centerMs), clusters));
        }
        return bursts;
    }

    private List<BurstSnapshot> pickAlignedRun(
            List<BurstSnapshot> bursts,
            double periodSec,
            SceneFinderProperties props
    ) {
        long periodMs = Math.max(1, Math.round(periodSec * 1000.0));
        double tolMs = periodSec * props.getPollingPeriodToleranceRatio() * 1000.0;

        List<BurstSnapshot> sorted = bursts.stream()
                .sorted(Comparator.comparing(BurstSnapshot::getCenterTime))
                .collect(Collectors.toList());

        List<BurstSnapshot> best = Collections.emptyList();
        for (BurstSnapshot anchor : sorted) {
            List<BurstSnapshot> run = collectAlignedRun(anchor, sorted, periodMs, tolMs);
            if (run.size() > best.size()) {
                best = run;
            }
        }
        return best;
    }

    private static List<BurstSnapshot> collectAlignedRun(
            BurstSnapshot anchor,
            List<BurstSnapshot> sorted,
            long periodMs,
            double tolMs
    ) {
        long anchorMs = anchor.getCenterTime().toEpochMilli();
        List<BurstSnapshot> run = new ArrayList<>();
        run.add(anchor);
        java.util.Set<BurstSnapshot> used = new java.util.HashSet<>();
        used.add(anchor);

        for (int k = 1; k < 64; k++) {
            BurstSnapshot hit = findBurstNear(sorted, anchorMs + k * periodMs, tolMs, used);
            if (hit == null) {
                break;
            }
            run.add(hit);
            used.add(hit);
        }
        for (int k = 1; k < 64; k++) {
            BurstSnapshot hit = findBurstNear(sorted, anchorMs - k * periodMs, tolMs, used);
            if (hit == null) {
                break;
            }
            run.add(0, hit);
            used.add(hit);
        }
        return run;
    }

    private static BurstSnapshot findBurstNear(
            List<BurstSnapshot> bursts,
            long targetMs,
            double tolMs,
            java.util.Set<BurstSnapshot> exclude
    ) {
        BurstSnapshot best = null;
        double bestGap = Double.POSITIVE_INFINITY;
        for (BurstSnapshot burst : bursts) {
            if (exclude != null && exclude.contains(burst)) {
                continue;
            }
            double gap = Math.abs(burst.getCenterTime().toEpochMilli() - targetMs);
            if (gap <= tolMs && gap < bestGap) {
                bestGap = gap;
                best = burst;
            }
        }
        return best;
    }

    /** 在整段对齐轮次中，识别出现次数 ≥ minRoundHits 的稳定方位槽位。 */
    private static List<Double> identifyPersistentSlotCenters(
            List<BurstSnapshot> aligned,
            int minRoundHits,
            SceneFinderProperties props
    ) {
        double matchDeg = Math.max(2.0, props.getPollingMinInterClusterSeparationDeg());
        double maxStd = props.getPollingMaxSlotBearingStdDeg();

        BurstSnapshot seedBurst = aligned.stream()
                .max(Comparator.comparingInt(b -> b.getClusters().size()))
                .orElse(aligned.get(0));

        List<Double> seedSlots = seedBurst.getClusters().stream()
                .map(PollingBurstClustering.BearingCluster::getCenterDeg)
                .sorted()
                .collect(Collectors.toList());

        List<Double> persistent = new ArrayList<>();
        for (double slot : seedSlots) {
            List<Double> hits = new ArrayList<>();
            for (BurstSnapshot burst : aligned) {
                Double matched = nearestCenterWithin(slot, burst.getClusters(), matchDeg);
                if (matched != null) {
                    hits.add(matched);
                }
            }
            if (hits.size() >= minRoundHits && stdDev(hits) <= maxStd) {
                persistent.add(circularMeanDeg(hits));
            }
        }
        persistent.sort(Double::compareTo);
        return persistent;
    }

    private static double circularMeanDeg(List<Double> bearings) {
        double sin = 0;
        double cos = 0;
        for (double b : bearings) {
            double rad = Math.toRadians(b);
            sin += Math.sin(rad);
            cos += Math.cos(rad);
        }
        return BearingMath.normalize360(Math.toDegrees(Math.atan2(sin, cos)));
    }

    private static Double nearestCenterWithin(
            double reference,
            List<PollingBurstClustering.BearingCluster> clusters,
            double maxDeltaDeg
    ) {
        Double best = null;
        double bestGap = Double.POSITIVE_INFINITY;
        for (PollingBurstClustering.BearingCluster cluster : clusters) {
            double gap = Math.abs(BearingMath.shortestDelta(reference, cluster.getCenterDeg()));
            if (gap <= maxDeltaDeg && gap < bestGap) {
                bestGap = gap;
                best = cluster.getCenterDeg();
            }
        }
        return best;
    }

    private static double stdDev(List<Double> values) {
        if (values.size() < 2) {
            return 0;
        }
        double mean = values.stream().mapToDouble(v -> v).average().orElse(0);
        double var = 0;
        for (double v : values) {
            double d = v - mean;
            var += d * d;
        }
        return Math.sqrt(var / values.size());
    }

    /**
     * 跨轮槽位匹配：每轮将 burst 内各方位簇匹配到固定槽位，仅相邻轮次连线。
     */
    private static int[] assignClustersToLanes(
            List<Double> slotCenters,
            List<PollingBurstClustering.BearingCluster> clusters,
            int laneCount
    ) {
        int[] assignment = new int[laneCount];
        for (int i = 0; i < laneCount; i++) {
            assignment[i] = -1;
        }
        if (clusters.isEmpty()) {
            return assignment;
        }

        int size = Math.max(laneCount, clusters.size());
        double highCost = 1_000.0;
        double[][] cost = new double[size][size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (i >= laneCount || j >= clusters.size()) {
                    cost[i][j] = highCost;
                } else {
                    cost[i][j] = Math.abs(BearingMath.shortestDelta(
                            slotCenters.get(i), clusters.get(j).getCenterDeg()));
                }
            }
        }

        int[] match = hungarianMinCost(cost);
        for (int lane = 0; lane < laneCount; lane++) {
            int clusterIdx = match[lane];
            if (clusterIdx >= 0 && clusterIdx < clusters.size() && cost[lane][clusterIdx] < highCost / 2) {
                assignment[lane] = clusterIdx;
            }
        }
        return assignment;
    }

    /** 方阵匈牙利算法；返回 row→col 匹配。 */
    private static int[] hungarianMinCost(double[][] costMatrix) {
        int n = costMatrix.length;
        int[] bestColForRow = new int[n];
        for (int i = 0; i < n; i++) {
            bestColForRow[i] = -1;
        }
        double[] bestCost = {Double.POSITIVE_INFINITY};
        int[] current = new int[n];
        boolean[] usedCol = new boolean[n];
        assignRec(costMatrix, 0, 0, current, usedCol, bestColForRow, bestCost);
        return bestColForRow;
    }

    private static void assignRec(
            double[][] cost,
            int row,
            double sum,
            int[] current,
            boolean[] usedCol,
            int[] bestColForRow,
            double[] bestCost
    ) {
        int n = cost.length;
        if (row == n) {
            if (sum < bestCost[0]) {
                bestCost[0] = sum;
                System.arraycopy(current, 0, bestColForRow, 0, n);
            }
            return;
        }
        for (int col = 0; col < n; col++) {
            if (usedCol[col]) {
                continue;
            }
            usedCol[col] = true;
            current[row] = col;
            assignRec(cost, row + 1, sum + cost[row][col], current, usedCol, bestColForRow, bestCost);
            usedCol[col] = false;
        }
    }

    private static final class BurstSnapshot {
        private final Instant centerTime;
        private final List<PollingBurstClustering.BearingCluster> clusters;

        BurstSnapshot(Instant centerTime, List<PollingBurstClustering.BearingCluster> clusters) {
            this.centerTime = centerTime;
            this.clusters = clusters;
        }

        Instant getCenterTime() {
            return centerTime;
        }

        List<PollingBurstClustering.BearingCluster> getClusters() {
            return clusters;
        }
    }
}
