package com.scenefinder.model;

import java.util.List;

/**
 * 方位角（0°~360°）相关的数学工具：归一化、最短角差、展开以避免 0/360 跳变。
 */
public final class BearingMath {

    private BearingMath() {
    }

    /** 将任意角度归一化到 [0, 360)。 */
    public static double normalize360(double bearing) {
        double v = bearing % 360.0;
        if (v < 0) {
            v += 360.0;
        }
        return v;
    }

    /**
     * 从 fromDeg 到 toDeg 的最短有符号角差（度），范围 (-180, 180]。
     * 用于聚类间隙判断、关联代价、速度估计。
     */
    public static double shortestDelta(double fromDeg, double toDeg) {
        double delta = normalize360(toDeg) - normalize360(fromDeg);
        if (delta > 180.0) {
            delta -= 360.0;
        } else if (delta < -180.0) {
            delta += 360.0;
        }
        return delta;
    }

    /**
     * 将 bearing 展开到 reference 附近，使折线图在 0/360 边界处连续（可视化用）。
     */
    public static double unwrapToward(double reference, double bearing) {
        double base = normalize360(reference);
        double candidate = normalize360(bearing);
        double delta = candidate - base;
        if (delta > 180.0) {
            candidate -= 360.0;
        } else if (delta < -180.0) {
            candidate += 360.0;
        }
        return candidate;
    }

    /**
     * 按方位排序后，相邻方位差 ≤ {@code mergeGapDeg} 的合并为同一簇。
     * 用于轮询 burst：小间隙只合并重复测向，较大差则计为不同并发设备。
     */
    public static int countBearingClusters(List<Double> bearings, double mergeGapDeg) {
        if (bearings == null || bearings.isEmpty()) {
            return 0;
        }
        List<Double> sorted = bearings.stream()
                .map(BearingMath::normalize360)
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        int clusters = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (Math.abs(shortestDelta(sorted.get(i - 1), sorted.get(i))) > mergeGapDeg) {
                clusters++;
            }
        }
        return clusters;
    }

    /**
     * 按 {@link #countBearingClusters} 相同规则合并后，返回各簇的圆均值方位（升序）。
     */
    public static List<Double> clusterCenters(List<Double> bearings, double mergeGapDeg) {
        if (bearings == null || bearings.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<Double> sorted = bearings.stream()
                .map(BearingMath::normalize360)
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        List<Double> centers = new java.util.ArrayList<>();
        List<Double> current = new java.util.ArrayList<>();
        current.add(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            double prev = sorted.get(i - 1);
            double next = sorted.get(i);
            if (Math.abs(shortestDelta(prev, next)) <= mergeGapDeg) {
                current.add(next);
            } else {
                centers.add(circularMean(current));
                current = new java.util.ArrayList<>();
                current.add(next);
            }
        }
        centers.add(circularMean(current));
        centers.sort(Double::compareTo);
        return centers;
    }

    private static double circularMean(List<Double> bearings) {
        double sin = 0;
        double cos = 0;
        for (double b : bearings) {
            double rad = Math.toRadians(b);
            sin += Math.sin(rad);
            cos += Math.cos(rad);
        }
        return normalize360(Math.toDegrees(Math.atan2(sin, cos)));
    }

    /**
     * 最大簇占全部测向点数的比例（0~1）。与 {@link #countBearingClusters} 使用相同合并规则。
     */
    public static double largestClusterFraction(List<Double> bearings, double mergeGapDeg) {
        if (bearings == null || bearings.isEmpty()) {
            return 1.0;
        }
        List<Double> sorted = bearings.stream()
                .map(BearingMath::normalize360)
                .sorted()
                .collect(java.util.stream.Collectors.toList());
        int largest = 1;
        int current = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (Math.abs(shortestDelta(sorted.get(i - 1), sorted.get(i))) <= mergeGapDeg) {
                current++;
            } else {
                largest = Math.max(largest, current);
                current = 1;
            }
        }
        largest = Math.max(largest, current);
        return largest / (double) sorted.size();
    }
}
