package com.pdwfx.signal.service;



import com.pdwfx.signal.model.DetectSignal;



import java.util.ArrayList;

import java.util.Comparator;

import java.util.List;



/**

 * 方位轨迹片段质量判定：识别短时、低窗口占比、高抖动的噪声片段，避免误删长时稳定目标。

 */

public final class BearingFragmentFilter {



    /** 覆盖观测窗比例高时视为稳定目标 */

    private static final double STRONG_SPAN_RATIO = 0.55;

    /** 中等覆盖 + 一定点数 */

    private static final double MEDIUM_SPAN_RATIO = 0.38;

    private static final int MEDIUM_POINT_COUNT = 12;



    private BearingFragmentFilter() {

    }



    public static List<List<DetectSignal>> dropEphemeralClusters(

            List<List<DetectSignal>> clusters,

            long netStartMs,

            long netEndMs

    ) {

        if (clusters == null || clusters.isEmpty()) {

            return clusters;

        }

        List<List<DetectSignal>> kept = new ArrayList<>();

        for (List<DetectSignal> cluster : clusters) {

            if (!isEphemeralFragment(cluster, netStartMs, netEndMs)) {

                kept.add(cluster);

            }

        }

        return kept;

    }



    /**

     * @return true 表示应丢弃的噪声片段

     */

    public static boolean isEphemeralFragment(List<DetectSignal> cluster, long netStartMs, long netEndMs) {

        if (cluster == null || cluster.isEmpty()) {

            return true;

        }

        int n = cluster.size();

        double spanSec = spanSeconds(cluster);

        double netSpanSec = Math.max(1.0, (netEndMs - netStartMs) / 1000.0);

        double spanRatio = spanSec / netSpanSec;



        if (spanRatio >= STRONG_SPAN_RATIO) {

            return false;

        }

        if (spanRatio >= MEDIUM_SPAN_RATIO && n >= MEDIUM_POINT_COUNT) {

            return false;

        }



        double maxStep = maxStepRateDegPerSec(cluster);

        double meanStep = meanStepRateDegPerSec(cluster);

        double bearingSpan = bearingSpanDeg(cluster);



        // 观测窗占比极低：轮询场景短时脉冲簇（图中 T4–T6），不再因点数多而豁免

        if (spanRatio < 0.25) {

            return true;

        }

        if (spanRatio < 0.34 && (maxStep > 6.0 || meanStep > 2.2)) {

            return true;

        }

        if (spanRatio < 0.34 && bearingSpan > 8 && meanStep > 1.8 && n < 80) {

            return true;

        }



        if (spanSec < 18 && n < 8) {

            return true;

        }

        if (maxStep > 11.0 && spanRatio < 0.40) {

            return true;

        }

        if (maxStep > 14.0 && spanRatio < 0.48 && n < 20) {

            return true;

        }

        if (bearingSpan > 35 && spanSec < 35 && n < 15) {

            return true;

        }

        return false;

    }



    public static double spanSeconds(List<DetectSignal> cluster) {

        if (cluster == null || cluster.size() < 2) {

            return 0;

        }

        List<DetectSignal> sorted = sorted(cluster);

        long t0 = sorted.get(0).getDetectTimesss();

        long t1 = sorted.get(sorted.size() - 1).getDetectTimesss();

        return Math.max(0, t1 - t0) / 1000.0;

    }



    public static double maxStepRateDegPerSec(List<DetectSignal> cluster) {

        if (cluster == null || cluster.size() < 2) {

            return 0;

        }

        List<DetectSignal> sorted = sorted(cluster);

        double max = 0;

        for (int i = 1; i < sorted.size(); i++) {

            double dt = (sorted.get(i).getDetectTimesss() - sorted.get(i - 1).getDetectTimesss()) / 1000.0;

            if (dt <= 0.001) {

                continue;

            }

            double da = Math.abs(shortestDelta(

                    sorted.get(i - 1).getAzimuth(), sorted.get(i).getAzimuth()));

            max = Math.max(max, da / dt);

        }

        return max;

    }



    public static double meanStepRateDegPerSec(List<DetectSignal> cluster) {

        if (cluster == null || cluster.size() < 2) {

            return 0;

        }

        List<DetectSignal> sorted = sorted(cluster);

        double sum = 0;

        int count = 0;

        for (int i = 1; i < sorted.size(); i++) {

            double dt = (sorted.get(i).getDetectTimesss() - sorted.get(i - 1).getDetectTimesss()) / 1000.0;

            if (dt <= 0.001) {

                continue;

            }

            double da = Math.abs(shortestDelta(

                    sorted.get(i - 1).getAzimuth(), sorted.get(i).getAzimuth()));

            sum += da / dt;

            count++;

        }

        return count == 0 ? 0 : sum / count;

    }



    public static double bearingSpanDeg(List<DetectSignal> cluster) {

        if (cluster == null || cluster.isEmpty()) {

            return 0;

        }

        double min = Double.POSITIVE_INFINITY;

        double max = Double.NEGATIVE_INFINITY;

        for (DetectSignal s : cluster) {

            double az = normalize360(s.getAzimuth());

            min = Math.min(min, az);

            max = Math.max(max, az);

        }

        return Math.min(Math.abs(max - min), 360 - Math.abs(max - min));

    }



    private static List<DetectSignal> sorted(List<DetectSignal> cluster) {

        return cluster.stream()

                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))

                .collect(java.util.stream.Collectors.toList());

    }



    private static double normalize360(double bearing) {

        double v = bearing % 360;

        if (v < 0) {

            v += 360;

        }

        return v;

    }



    private static double shortestDelta(double fromDeg, double toDeg) {

        double delta = normalize360(toDeg) - normalize360(fromDeg);

        if (delta > 180) {

            delta -= 360;

        }

        if (delta < -180) {

            delta += 360;

        }

        return delta;

    }

}


