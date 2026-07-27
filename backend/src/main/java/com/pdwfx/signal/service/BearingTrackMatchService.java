package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 时间对齐方位匹配（与前端 {@code bearingMatch.js} 一致）：忽略频率差异，
 * 在重合时段内比较方位序列，用于异频目标簇合并。
 */
@Service
public class BearingTrackMatchService {

    public static final class MatchOptions {
        private double gateDeg = 0.1;
        private long timeToleranceMs = 6000L;
        private int minMatchPoints = 10;
        private long minOverlapMs = 300L;
        private double maxRateDiffDegPerSec = 2.5;
        private boolean requireMotionConsistent = false;

        public double getGateDeg() { return gateDeg; }
        public void setGateDeg(double gateDeg) { this.gateDeg = gateDeg; }
        public long getTimeToleranceMs() { return timeToleranceMs; }
        public void setTimeToleranceMs(long timeToleranceMs) { this.timeToleranceMs = timeToleranceMs; }
        public int getMinMatchPoints() { return minMatchPoints; }
        public void setMinMatchPoints(int minMatchPoints) { this.minMatchPoints = minMatchPoints; }
        public long getMinOverlapMs() { return minOverlapMs; }
        public void setMinOverlapMs(long minOverlapMs) { this.minOverlapMs = minOverlapMs; }
        public double getMaxRateDiffDegPerSec() { return maxRateDiffDegPerSec; }
        public void setMaxRateDiffDegPerSec(double maxRateDiffDegPerSec) {
            this.maxRateDiffDegPerSec = maxRateDiffDegPerSec;
        }
        public boolean isRequireMotionConsistent() { return requireMotionConsistent; }
        public void setRequireMotionConsistent(boolean requireMotionConsistent) {
            this.requireMotionConsistent = requireMotionConsistent;
        }
    }

    public static final class MatchStats {
        public final boolean agree;
        public final int matchPoints;
        public final Double medianDeltaDeg;
        public final long overlapMs;

        MatchStats(boolean agree, int matchPoints, Double medianDeltaDeg, long overlapMs) {
            this.agree = agree;
            this.matchPoints = matchPoints;
            this.medianDeltaDeg = medianDeltaDeg;
            this.overlapMs = overlapMs;
        }
    }

    /**
     * 并查集合并方位一致的轨迹簇（频率无关）。
     */
    public List<List<DetectSignal>> mergeMatchingClusters(
            List<List<DetectSignal>> clusters,
            MatchOptions options
    ) {
        if (clusters == null || clusters.size() <= 1) {
            return clusters != null ? clusters : new ArrayList<>();
        }
        MatchOptions opts = options != null ? options : new MatchOptions();
        int n = clusters.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (clustersAgree(clusters.get(i), clusters.get(j), opts).agree) {
                    union(parent, i, j);
                }
            }
        }
        java.util.Map<Integer, List<DetectSignal>> merged = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            merged.computeIfAbsent(root, ignored -> new ArrayList<>()).addAll(clusters.get(i));
        }
        List<List<DetectSignal>> out = new ArrayList<>();
        for (List<DetectSignal> signals : merged.values()) {
            signals.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));
            out.add(signals);
        }
        return out;
    }

    MatchStats clustersAgree(List<DetectSignal> a, List<DetectSignal> b, MatchOptions opts) {
        TrackSamples trackA = toTrackSamples(a);
        TrackSamples trackB = toTrackSamples(b);
        return bearingTracksAgree(trackA, trackB, opts);
    }

    private MatchStats bearingTracksAgree(TrackSamples trackA, TrackSamples trackB, MatchOptions opts) {
        OverlapWindow win = overlapWindow(trackA, trackB);
        if (win == null || win.overlapMs < opts.minOverlapMs) {
            return new MatchStats(false, 0, null, win != null ? win.overlapMs : 0);
        }
        List<Double> deltas = collectMatchDeltas(
                trackA, trackB, win.start, win.end, opts.timeToleranceMs, opts.gateDeg);
        if (deltas.size() < opts.minMatchPoints) {
            return new MatchStats(false, deltas.size(), null, win.overlapMs);
        }
        double med = median(deltas);
        if (med > opts.gateDeg) {
            return new MatchStats(false, deltas.size(), med, win.overlapMs);
        }
        if (opts.requireMotionConsistent) {
            Double rateA = bearingRateDegPerSec(trackA, win.start, win.end);
            Double rateB = bearingRateDegPerSec(trackB, win.start, win.end);
            if (rateA != null && rateB != null
                    && Math.abs(rateA - rateB) > opts.maxRateDiffDegPerSec) {
                return new MatchStats(false, deltas.size(), med, win.overlapMs);
            }
        }
        return new MatchStats(true, deltas.size(), med, win.overlapMs);
    }

    private static TrackSamples toTrackSamples(List<DetectSignal> signals) {
        List<long[]> points = new ArrayList<>();
        for (DetectSignal s : signals) {
            if (s.getDetectTimesss() <= 0) {
                continue;
            }
            points.add(new long[] { s.getDetectTimesss(), Double.doubleToLongBits(s.getAzimuth()) });
        }
        points.sort(Comparator.comparingLong(p -> p[0]));
        return new TrackSamples(points);
    }

    private static final class TrackSamples {
        private final List<long[]> points;

        private TrackSamples(List<long[]> points) {
            this.points = points;
        }

        private double azimuthAt(int idx) {
            return Double.longBitsToDouble(points.get(idx)[1]);
        }
    }

    private static final class OverlapWindow {
        private final long start;
        private final long end;
        private final long overlapMs;

        private OverlapWindow(long start, long end) {
            this.start = start;
            this.end = end;
            this.overlapMs = end - start;
        }
    }

    private OverlapWindow overlapWindow(TrackSamples a, TrackSamples b) {
        if (a.points.isEmpty() || b.points.isEmpty()) {
            return null;
        }
        long startA = a.points.get(0)[0];
        long endA = a.points.get(a.points.size() - 1)[0];
        long startB = b.points.get(0)[0];
        long endB = b.points.get(b.points.size() - 1)[0];
        long start = Math.max(startA, startB);
        long end = Math.min(endA, endB);
        if (start >= end) {
            return null;
        }
        return new OverlapWindow(start, end);
    }

    private List<Double> collectMatchDeltas(
            TrackSamples from,
            TrackSamples to,
            long start,
            long end,
            long timeToleranceMs,
            double gateDeg
    ) {
        List<Double> deltas = new ArrayList<>();
        sampleDeltas(from, to, start, end, timeToleranceMs, gateDeg, deltas);
        if (deltas.size() < 4) {
            sampleDeltas(to, from, start, end, timeToleranceMs, gateDeg, deltas);
        }
        return deltas;
    }

    private void sampleDeltas(
            TrackSamples from,
            TrackSamples to,
            long start,
            long end,
            long timeToleranceMs,
            double gateDeg,
            List<Double> deltas
    ) {
        for (long[] pt : from.points) {
            long t = pt[0];
            if (t < start || t > end) {
                continue;
            }
            Double otherAz = nearestAzimuth(to, t, timeToleranceMs);
            if (otherAz == null) {
                continue;
            }
            double az = Double.longBitsToDouble(pt[1]);
            double d = Math.abs(shortestDelta(az, otherAz));
            if (d <= gateDeg) {
                deltas.add(d);
            }
        }
    }

    private Double nearestAzimuth(TrackSamples track, long t, long maxGapMs) {
        Double best = null;
        long bestGap = Long.MAX_VALUE;
        for (long[] pt : track.points) {
            long gap = Math.abs(pt[0] - t);
            if (gap <= maxGapMs && gap < bestGap) {
                bestGap = gap;
                best = Double.longBitsToDouble(pt[1]);
            }
        }
        return best;
    }

    private Double bearingRateDegPerSec(TrackSamples track, long start, long end) {
        List<long[]> pts = new ArrayList<>();
        for (long[] p : track.points) {
            if (p[0] >= start && p[0] <= end) {
                pts.add(p);
            }
        }
        if (pts.size() < 3) {
            return null;
        }
        double dtSec = (pts.get(pts.size() - 1)[0] - pts.get(0)[0]) / 1000.0;
        if (dtSec < 0.5) {
            return null;
        }
        double az0 = Double.longBitsToDouble(pts.get(0)[1]);
        double az1 = Double.longBitsToDouble(pts.get(pts.size() - 1)[1]);
        return shortestDelta(az0, az1) / dtSec;
    }

    private static double shortestDelta(double fromDeg, double toDeg) {
        double delta = normalize360(toDeg) - normalize360(fromDeg);
        if (delta > 180.0) {
            delta -= 360.0;
        } else if (delta < -180.0) {
            delta += 360.0;
        }
        return delta;
    }

    private static double normalize360(double bearing) {
        double v = bearing % 360.0;
        if (v < 0) {
            v += 360.0;
        }
        return v;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        double[] arr = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(arr);
        int m = arr.length / 2;
        return arr.length % 2 == 1 ? arr[m] : (arr[m - 1] + arr[m]) / 2.0;
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
}
