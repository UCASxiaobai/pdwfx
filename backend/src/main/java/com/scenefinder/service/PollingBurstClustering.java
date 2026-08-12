package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.DetectionPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 轮询 burst 内并发目标划分：纵轴为方位，同时间窗内不同方位即不同目标；
 * 仅合并测向抖动（极小方位差重复测向），不因方位远近而合并。
 */
public final class PollingBurstClustering {

    /** 重复测向合并上限（度）；大于此差值的方位一律视为不同并发目标。 */
    public static final double MAX_DUPLICATE_MERGE_DEG = 0.12;

    private PollingBurstClustering() {
    }

    public static double duplicateMergeDeg(SceneFinderProperties props) {
        if (props == null) {
            return MAX_DUPLICATE_MERGE_DEG;
        }
        return Math.min(props.getPollingBurstBearingGapDeg(), MAX_DUPLICATE_MERGE_DEG);
    }

    public static List<BearingCluster> clusterConcurrentTargets(
            List<DetectionPoint> points,
            SceneFinderProperties props
    ) {
        return clusterConcurrentTargets(points, duplicateMergeDeg(props));
    }

    public static List<BearingCluster> clusterConcurrentTargets(
            List<DetectionPoint> points,
            double duplicateMergeDeg
    ) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }
        List<DetectionPoint> sorted = points.stream()
                .sorted(Comparator.comparingDouble(p -> BearingMath.normalize360(p.getBearingDeg())))
                .collect(Collectors.toList());

        List<BearingCluster> clusters = new ArrayList<>();
        List<DetectionPoint> current = new ArrayList<>();
        current.add(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            DetectionPoint prev = sorted.get(i - 1);
            DetectionPoint next = sorted.get(i);
            if (Math.abs(BearingMath.shortestDelta(prev.getBearingDeg(), next.getBearingDeg())) <= duplicateMergeDeg) {
                current.add(next);
            } else {
                clusters.add(toCluster(current));
                current = new ArrayList<>();
                current.add(next);
            }
        }
        clusters.add(toCluster(current));
        clusters.sort(Comparator.comparingDouble(BearingCluster::getCenterDeg));
        return clusters;
    }

    private static BearingCluster toCluster(List<DetectionPoint> points) {
        double sin = 0;
        double cos = 0;
        for (DetectionPoint p : points) {
            double rad = Math.toRadians(BearingMath.normalize360(p.getBearingDeg()));
            sin += Math.sin(rad);
            cos += Math.cos(rad);
        }
        double center = BearingMath.normalize360(Math.toDegrees(Math.atan2(sin, cos)));
        return new BearingCluster(center, new ArrayList<>(points));
    }

    public static final class BearingCluster {
        private final double centerDeg;
        private final List<DetectionPoint> points;

        BearingCluster(double centerDeg, List<DetectionPoint> points) {
            this.centerDeg = centerDeg;
            this.points = points;
        }

        public double getCenterDeg() {
            return centerDeg;
        }

        public List<DetectionPoint> getPoints() {
            return points;
        }
    }
}
