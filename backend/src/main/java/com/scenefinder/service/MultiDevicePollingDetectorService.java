package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.FrequencyBandUtils;
import com.scenefinder.model.QualityScene;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 检测「多设备轮询通信」场景。
 * <p>
 * <b>当且仅当</b>：在观测窗内，至少两个方位点位在多个轮次中<b>按固定周期共现</b>（每轮 burst 内并发、
 * 跨轮方位槽位稳定），才判为轮询。两条连续轨迹偶然时间重叠、或单目标测向噪声拆成多簇，均不满足。
 * </p>
 */
@Service
public class MultiDevicePollingDetectorService {

    /**
     * 在检测点序列中发现轮询通信候选场景（未与轨迹场景融合、未最终排名）。
     */
    public List<QualityScene> findPollingScenes(List<DetectionPoint> points, SceneFinderProperties props) {
        if (points.isEmpty()) {
            return Collections.emptyList();
        }

        List<List<DetectionPoint>> bands = FrequencyBandUtils.partitionPoints(
                points, props.getFreqClusterGapMhz());

        List<ScoredPollingWindow> candidates = new ArrayList<>();
        for (List<DetectionPoint> band : bands) {
            if (band.size() < props.getPollingMinBurstsInWindow() * 2) {
                continue;
            }
            List<MultiBearingBurst> bursts = detectMultiBearingBursts(band, props);
            if (bursts.size() < props.getPollingMinBurstsInWindow()) {
                continue;
            }
            candidates.addAll(scoreSlidingWindows(bursts, band, props));
        }

        candidates.sort(Comparator.comparingDouble(ScoredPollingWindow::getScore).reversed());
        List<QualityScene> result = new ArrayList<>();
        int rank = 1;
        for (ScoredPollingWindow w : candidates) {
            result.add(QualityScene.pollingScene(
                    rank++,
                    w.getWindowStart(),
                    w.getWindowEnd(),
                    round(w.getFreqCenterMhz()),
                    round(w.getFreqMinMhz()),
                    round(w.getFreqMaxMhz()),
                    round(w.getScore()),
                    w.getBurstCount(),
                    round(w.getMedianBearingSpanDeg()),
                    round(w.getPeriodSec()),
                    round(w.getAvgBearingsPerBurst()),
                    w.getTypicalBearingsPerBurst(),
                    round(w.getPeriodicityScore())
            ));
        }
        return result;
    }

    /**
     * 将时间相近的检测点合并为 burst，筛出「多方位、非单簇主导」的轮次。
     */
    private List<MultiBearingBurst> detectMultiBearingBursts(
            List<DetectionPoint> sortedBand,
            SceneFinderProperties props
    ) {
        List<DetectionPoint> sorted = sortedBand.stream()
                .sorted(Comparator.comparing(DetectionPoint::getTime))
                .collect(Collectors.toList());

        long coalesceMillis = Math.max(1, Math.round(props.getPollingBurstCoalesceSec() * 1000.0));
        List<MultiBearingBurst> bursts = new ArrayList<>();

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

            BurstMetrics metrics = analyzeBurst(group, props);
            if (metrics != null) {
                Instant center = Instant.ofEpochMilli(
                        group.stream().mapToLong(p -> p.getTime().toEpochMilli()).sum() / group.size());
                bursts.add(new MultiBearingBurst(center, metrics));
            }
        }
        return bursts;
    }

    /**
     * 单轮 burst 指标；不满足「多设备并发 + 周期重复」前置条件时返回 null。
     * <p>
     * burst 内每个测向点（PDW）在 {@link SceneFinderProperties#getPollingBurstCoalesceSec()} 窗内共现；
     * 方位差 &gt; {@link SceneFinderProperties#getPollingBurstBearingGapDeg()} 即计为不同并发设备。
     * </p>
     */
    private BurstMetrics analyzeBurst(List<DetectionPoint> group, SceneFinderProperties props) {
        List<Double> bearings = group.stream()
                .map(p -> BearingMath.normalize360(p.getBearingDeg()))
                .collect(Collectors.toList());

        double duplicateMergeDeg = PollingBurstClustering.duplicateMergeDeg(props);
        int concurrentDevices = BearingMath.countBearingClusters(bearings, duplicateMergeDeg);
        if (concurrentDevices < props.getPollingMinBearingsPerBurst()) {
            return null;
        }

        double span = bearingSpanDeg(bearings, duplicateMergeDeg);
        double minSpanDeg = props.getPollingMinBurstBearingSpanDeg();
        if (minSpanDeg > 0 && span < minSpanDeg) {
            return null;
        }

        double dominantFraction = BearingMath.largestClusterFraction(bearings, duplicateMergeDeg);
        if (dominantFraction > props.getPollingMaxDominantClusterFraction()) {
            return null;
        }

        List<Double> clusterCenters = BearingMath.clusterCenters(bearings, duplicateMergeDeg);
        if (!passesInterClusterSeparation(clusterCenters, props.getPollingMinInterClusterSeparationDeg())) {
            return null;
        }

        double freqCenter = group.stream().mapToDouble(DetectionPoint::getFrequencyMhz).average().orElse(0);
        double freqMin = group.stream().mapToDouble(DetectionPoint::getFrequencyMhz).min().orElse(0);
        double freqMax = group.stream().mapToDouble(DetectionPoint::getFrequencyMhz).max().orElse(0);

        return new BurstMetrics(concurrentDevices, span, freqCenter, freqMin, freqMax, clusterCenters);
    }

    /** 簇心最小间距或总跨度须达到阈值，避免测向噪声被计为多设备。 */
    private static boolean passesInterClusterSeparation(List<Double> centers, double minSepDeg) {
        if (centers == null || centers.size() < 2) {
            return false;
        }
        if (minSepDeg <= 0) {
            return true;
        }
        double minPair = Double.POSITIVE_INFINITY;
        double minC = Double.POSITIVE_INFINITY;
        double maxC = Double.NEGATIVE_INFINITY;
        for (double c : centers) {
            minC = Math.min(minC, c);
            maxC = Math.max(maxC, c);
        }
        double span = Math.min(Math.abs(maxC - minC), 360 - Math.abs(maxC - minC));
        for (int i = 0; i < centers.size(); i++) {
            for (int j = i + 1; j < centers.size(); j++) {
                minPair = Math.min(minPair, Math.abs(BearingMath.shortestDelta(centers.get(i), centers.get(j))));
            }
        }
        return minPair >= minSepDeg || span >= minSepDeg * 1.5;
    }

    /** 周期多点位共现：整窗网格对齐轮次 + 稳定方位槽位均达标。 */
    private boolean validatePeriodicMultiPointPattern(
            List<MultiBearingBurst> alignedRun,
            SceneFinderProperties props
    ) {
        if (alignedRun.size() < props.getPollingMinAlignedMultiBursts()) {
            return false;
        }
        return countStableBearingSlots(alignedRun, props) >= props.getPollingMinStableBearingSlots();
    }

    /** 按评分时间窗起止与周期，将 burst 对齐到整窗网格。 */
    private List<MultiBearingBurst> alignMultiBurstsToWindow(
            List<MultiBearingBurst> bursts,
            Instant windowStart,
            Instant windowEnd,
            double periodSec,
            SceneFinderProperties props
    ) {
        if (bursts.isEmpty() || periodSec <= 0) {
            return Collections.emptyList();
        }
        long periodMs = Math.max(1, Math.round(periodSec * 1000.0));
        double tolMs = periodSec * props.getPollingPeriodToleranceRatio() * 1000.0;
        long startMs = windowStart.toEpochMilli();
        long endMs = windowEnd.toEpochMilli();

        List<MultiBearingBurst> sorted = bursts.stream()
                .sorted(Comparator.comparing(MultiBearingBurst::getCenterTime))
                .collect(Collectors.toList());

        List<MultiBearingBurst> aligned = new ArrayList<>();
        Set<MultiBearingBurst> used = new HashSet<>();
        for (long targetMs = startMs; targetMs <= endMs + tolMs; targetMs += periodMs) {
            MultiBearingBurst hit = findBurstNear(sorted, targetMs, tolMs, used);
            if (hit != null) {
                aligned.add(hit);
                used.add(hit);
            }
        }

        return aligned;
    }

    private List<MultiBearingBurst> pickBestAlignedRun(
            List<MultiBearingBurst> bursts,
            double periodSec,
            SceneFinderProperties props
    ) {
        long periodMs = Math.max(1, Math.round(periodSec * 1000.0));
        double tolMs = periodSec * props.getPollingPeriodToleranceRatio() * 1000.0;
        List<MultiBearingBurst> sorted = bursts.stream()
                .sorted(Comparator.comparing(MultiBearingBurst::getCenterTime))
                .collect(Collectors.toList());

        List<MultiBearingBurst> best = Collections.emptyList();
        for (MultiBearingBurst anchor : sorted) {
            List<MultiBearingBurst> run = collectAlignedRun(anchor, sorted, periodMs, tolMs);
            if (run.size() > best.size()) {
                best = run;
            }
        }
        return best;
    }

    private static List<MultiBearingBurst> collectAlignedRun(
            MultiBearingBurst anchor,
            List<MultiBearingBurst> sorted,
            long periodMs,
            double tolMs
    ) {
        long anchorMs = anchor.getCenterTime().toEpochMilli();
        Set<MultiBearingBurst> used = new HashSet<>();
        List<MultiBearingBurst> run = new ArrayList<>();
        run.add(anchor);
        used.add(anchor);

        for (int k = 1; k < 64; k++) {
            MultiBearingBurst hit = findBurstNear(sorted, anchorMs + k * periodMs, tolMs, used);
            if (hit == null) {
                break;
            }
            run.add(hit);
            used.add(hit);
        }
        for (int k = 1; k < 64; k++) {
            MultiBearingBurst hit = findBurstNear(sorted, anchorMs - k * periodMs, tolMs, used);
            if (hit == null) {
                break;
            }
            run.add(0, hit);
            used.add(hit);
        }
        return run;
    }

    private static MultiBearingBurst findBurstNear(
            List<MultiBearingBurst> bursts,
            long targetMs,
            double tolMs,
            Set<MultiBearingBurst> exclude
    ) {
        MultiBearingBurst best = null;
        double bestGap = Double.POSITIVE_INFINITY;
        for (MultiBearingBurst burst : bursts) {
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

    private int countStableBearingSlots(List<MultiBearingBurst> alignedRun, SceneFinderProperties props) {
        if (alignedRun.isEmpty()) {
            return 0;
        }
        double matchDeg = Math.max(2.0, props.getPollingMinInterClusterSeparationDeg());
        double maxStd = props.getPollingMaxSlotBearingStdDeg();
        int minHits = PollingAlignmentUtil.minRoundHits(
                alignedRun.size(), props.getPollingMinSlotRoundCoverageRatio());

        List<Double> seedSlots = new ArrayList<>(alignedRun.get(0).getMetrics().getClusterCenters());
        seedSlots.sort(Double::compareTo);
        if (seedSlots.size() < 2) {
            return 0;
        }

        int stable = 0;
        for (double slot : seedSlots) {
            List<Double> hits = new ArrayList<>();
            for (MultiBearingBurst burst : alignedRun) {
                Double matched = nearestCenterWithin(slot, burst.getMetrics().getClusterCenters(), matchDeg);
                if (matched != null) {
                    hits.add(matched);
                }
            }
            if (hits.size() >= minHits && stdDev(hits) <= maxStd) {
                stable++;
            }
        }
        return stable;
    }

    private static Double nearestCenterWithin(double reference, List<Double> centers, double maxDeltaDeg) {
        Double best = null;
        double bestGap = Double.POSITIVE_INFINITY;
        for (double center : centers) {
            double gap = Math.abs(BearingMath.shortestDelta(reference, center));
            if (gap <= maxDeltaDeg && gap < bestGap) {
                bestGap = gap;
                best = center;
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

    /** burst 内各并发设备簇心的最大相邻方位差（度），用于评分与展示。 */
    private static double bearingSpanDeg(List<Double> bearings, double mergeGapDeg) {
        if (bearings.size() < 2) {
            return 0;
        }
        List<Double> sorted = bearings.stream().map(BearingMath::normalize360).sorted().collect(Collectors.toList());
        List<Double> centers = new ArrayList<>();
        List<Double> current = new ArrayList<>();
        current.add(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            double prev = sorted.get(i - 1);
            double next = sorted.get(i);
            if (Math.abs(BearingMath.shortestDelta(prev, next)) <= mergeGapDeg) {
                current.add(next);
            } else {
                centers.add(clusterCenter(current));
                current = new ArrayList<>();
                current.add(next);
            }
        }
        centers.add(clusterCenter(current));
        if (centers.size() < 2) {
            return 0;
        }
        centers.sort(Double::compareTo);
        double maxGap = 0;
        for (int i = 1; i < centers.size(); i++) {
            maxGap = Math.max(maxGap, Math.abs(BearingMath.shortestDelta(centers.get(i - 1), centers.get(i))));
        }
        return maxGap;
    }

    private static double clusterCenter(List<Double> bearings) {
        double sin = 0;
        double cos = 0;
        for (double b : bearings) {
            double rad = Math.toRadians(b);
            sin += Math.sin(rad);
            cos += Math.cos(rad);
        }
        return BearingMath.normalize360(Math.toDegrees(Math.atan2(sin, cos)));
    }

    private List<ScoredPollingWindow> scoreSlidingWindows(
            List<MultiBearingBurst> bursts,
            List<DetectionPoint> band,
            SceneFinderProperties props
    ) {
        long windowMillis = Math.round(props.getPollingWindowSeconds() * 1000.0);
        long stepMillis = Math.max(1, Math.round(props.getPollingWindowStepSeconds() * 1000.0));

        Instant minTime = bursts.get(0).getCenterTime();
        Instant maxTime = bursts.get(bursts.size() - 1).getCenterTime();

        List<ScoredPollingWindow> scored = new ArrayList<>();
        for (long startMillis = minTime.toEpochMilli();
             startMillis + windowMillis <= maxTime.toEpochMilli() + stepMillis;
             startMillis += stepMillis) {
            Instant windowStart = Instant.ofEpochMilli(startMillis);
            Instant windowEnd = Instant.ofEpochMilli(startMillis + windowMillis);

            List<MultiBearingBurst> inWindow = bursts.stream()
                    .filter(b -> !b.getCenterTime().isBefore(windowStart) && !b.getCenterTime().isAfter(windowEnd))
                    .collect(Collectors.toList());
            if (inWindow.size() < props.getPollingMinBurstsInWindow()) {
                continue;
            }

            PeriodEstimate period = estimatePeriod(inWindow, props);
            if (period == null) {
                continue;
            }
            List<MultiBearingBurst> gridAligned = alignMultiBurstsToWindow(
                    inWindow, windowStart, windowEnd, period.getPeriodSec(), props);
            if (!validatePeriodicMultiPointPattern(gridAligned, props)) {
                continue;
            }

            List<Double> spans = gridAligned.stream().map(b -> b.getMetrics().getBearingSpanDeg()).sorted().collect(Collectors.toList());
            double medianSpan = spans.get(spans.size() / 2);
            double avgBearings = gridAligned.stream().mapToInt(b -> b.getMetrics().getBearingClusterCount()).average().orElse(0);
            int typicalBearings = (int) Math.round(avgBearings);

            double burstBonus = Math.min(gridAligned.size() / (double) props.getPollingMinBurstsInWindow(), 2.5);
            double periodBonus = period.getPeriodicityScore() * 2.0;
            double spanBonus = Math.min(medianSpan / 30.0, 1.5);
            double bearingBonus = Math.min(typicalBearings / 4.0, 1.5);
            double score = 2.0 * burstBonus + periodBonus + spanBonus + bearingBonus;

            double freqCenter = gridAligned.stream().mapToDouble(b -> b.getMetrics().getFreqCenterMhz()).average().orElse(0);
            double freqMin = gridAligned.stream().mapToDouble(b -> b.getMetrics().getFreqMinMhz()).min().orElse(0);
            double freqMax = gridAligned.stream().mapToDouble(b -> b.getMetrics().getFreqMaxMhz()).max().orElse(0);

            scored.add(new ScoredPollingWindow(
                    windowStart,
                    windowEnd,
                    score,
                    gridAligned.size(),
                    medianSpan,
                    period.getPeriodSec(),
                    avgBearings,
                    typicalBearings,
                    period.getPeriodicityScore(),
                    freqCenter,
                    freqMin,
                    freqMax,
                    gridAligned
            ));
        }
        return scored;
    }

    private PeriodEstimate estimatePeriod(List<MultiBearingBurst> bursts, SceneFinderProperties props) {
        if (bursts.size() < 3) {
            return null;
        }
        List<MultiBearingBurst> sorted = bursts.stream()
                .sorted(Comparator.comparing(MultiBearingBurst::getCenterTime))
                .collect(Collectors.toList());
        List<Double> intervals = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            double sec = (sorted.get(i).getCenterTime().toEpochMilli()
                    - sorted.get(i - 1).getCenterTime().toEpochMilli()) / 1000.0;
            if (sec >= props.getPollingPeriodMinSec() && sec <= props.getPollingPeriodMaxSec()) {
                intervals.add(sec);
            }
        }
        if (intervals.size() < 2) {
            return null;
        }
        intervals.sort(Double::compareTo);
        double median = intervals.get(intervals.size() / 2);
        double tolerance = median * props.getPollingPeriodToleranceRatio();
        long matching = intervals.stream()
                .filter(iv -> Math.abs(iv - median) <= tolerance)
                .count();
        double periodicityScore = matching / (double) intervals.size();
        if (periodicityScore < props.getPollingMinPeriodicityScore() || matching < 2) {
            return null;
        }
        return new PeriodEstimate(median, periodicityScore);
    }

    private List<ScoredPollingWindow> mergeAdjacentWindows(
            List<ScoredPollingWindow> windows,
            SceneFinderProperties props
    ) {
        if (windows.isEmpty()) {
            return Collections.emptyList();
        }
        long mergeGapMillis = Math.round(props.getMergeMaxGapSeconds() * 1000.0);
        List<ScoredPollingWindow> sorted = windows.stream()
                .sorted(Comparator.comparingLong(w -> w.getWindowStart().toEpochMilli()))
                .collect(Collectors.toList());

        List<ScoredPollingWindow> merged = new ArrayList<>();
        ScoredPollingWindow current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            ScoredPollingWindow next = sorted.get(i);
            long gap = next.getWindowStart().toEpochMilli() - current.getWindowEnd().toEpochMilli();
            if (gap <= mergeGapMillis) {
                current = mergeWindows(current, next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private ScoredPollingWindow mergeWindows(ScoredPollingWindow a, ScoredPollingWindow b) {
        Instant start = a.getWindowStart().isBefore(b.getWindowStart()) ? a.getWindowStart() : b.getWindowStart();
        Instant end = a.getWindowEnd().isAfter(b.getWindowEnd()) ? a.getWindowEnd() : b.getWindowEnd();
        List<MultiBearingBurst> bursts = new ArrayList<>(a.getBursts());
        for (MultiBearingBurst burst : b.getBursts()) {
            boolean dup = bursts.stream()
                    .anyMatch(existing -> Math.abs(existing.getCenterTime().toEpochMilli()
                            - burst.getCenterTime().toEpochMilli()) < 500);
            if (!dup) {
                bursts.add(burst);
            }
        }
        bursts.sort(Comparator.comparing(MultiBearingBurst::getCenterTime));
        return new ScoredPollingWindow(
                start,
                end,
                Math.max(a.getScore(), b.getScore()),
                bursts.size(),
                (a.getMedianBearingSpanDeg() + b.getMedianBearingSpanDeg()) / 2.0,
                (a.getPeriodSec() + b.getPeriodSec()) / 2.0,
                (a.getAvgBearingsPerBurst() + b.getAvgBearingsPerBurst()) / 2.0,
                Math.max(a.getTypicalBearingsPerBurst(), b.getTypicalBearingsPerBurst()),
                Math.max(a.getPeriodicityScore(), b.getPeriodicityScore()),
                (a.getFreqCenterMhz() + b.getFreqCenterMhz()) / 2.0,
                Math.min(a.getFreqMinMhz(), b.getFreqMinMhz()),
                Math.max(a.getFreqMaxMhz(), b.getFreqMaxMhz()),
                bursts
        );
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static final class BurstMetrics {
        private final int bearingClusterCount;
        private final double bearingSpanDeg;
        private final double freqCenterMhz;
        private final double freqMinMhz;
        private final double freqMaxMhz;
        private final List<Double> clusterCenters;

        BurstMetrics(int bearingClusterCount, double bearingSpanDeg, double freqCenterMhz,
                     double freqMinMhz, double freqMaxMhz, List<Double> clusterCenters) {
            this.bearingClusterCount = bearingClusterCount;
            this.bearingSpanDeg = bearingSpanDeg;
            this.freqCenterMhz = freqCenterMhz;
            this.freqMinMhz = freqMinMhz;
            this.freqMaxMhz = freqMaxMhz;
            this.clusterCenters = clusterCenters == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(clusterCenters));
        }

        int getBearingClusterCount() {
            return bearingClusterCount;
        }

        double getBearingSpanDeg() {
            return bearingSpanDeg;
        }

        double getFreqCenterMhz() {
            return freqCenterMhz;
        }

        double getFreqMinMhz() {
            return freqMinMhz;
        }

        double getFreqMaxMhz() {
            return freqMaxMhz;
        }

        List<Double> getClusterCenters() {
            return clusterCenters;
        }
    }

    private static final class MultiBearingBurst {
        private final Instant centerTime;
        private final BurstMetrics metrics;

        MultiBearingBurst(Instant centerTime, BurstMetrics metrics) {
            this.centerTime = centerTime;
            this.metrics = metrics;
        }

        Instant getCenterTime() {
            return centerTime;
        }

        BurstMetrics getMetrics() {
            return metrics;
        }
    }

    private static final class PeriodEstimate {
        private final double periodSec;
        private final double periodicityScore;

        PeriodEstimate(double periodSec, double periodicityScore) {
            this.periodSec = periodSec;
            this.periodicityScore = periodicityScore;
        }

        double getPeriodSec() {
            return periodSec;
        }

        double getPeriodicityScore() {
            return periodicityScore;
        }
    }

    private static final class ScoredPollingWindow {
        private final Instant windowStart;
        private final Instant windowEnd;
        private final double score;
        private final int burstCount;
        private final double medianBearingSpanDeg;
        private final double periodSec;
        private final double avgBearingsPerBurst;
        private final int typicalBearingsPerBurst;
        private final double periodicityScore;
        private final double freqCenterMhz;
        private final double freqMinMhz;
        private final double freqMaxMhz;
        private final List<MultiBearingBurst> bursts;

        ScoredPollingWindow(Instant windowStart, Instant windowEnd, double score, int burstCount,
                            double medianBearingSpanDeg, double periodSec, double avgBearingsPerBurst,
                            int typicalBearingsPerBurst, double periodicityScore, double freqCenterMhz,
                            double freqMinMhz, double freqMaxMhz, List<MultiBearingBurst> bursts) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.score = score;
            this.burstCount = burstCount;
            this.medianBearingSpanDeg = medianBearingSpanDeg;
            this.periodSec = periodSec;
            this.avgBearingsPerBurst = avgBearingsPerBurst;
            this.typicalBearingsPerBurst = typicalBearingsPerBurst;
            this.periodicityScore = periodicityScore;
            this.freqCenterMhz = freqCenterMhz;
            this.freqMinMhz = freqMinMhz;
            this.freqMaxMhz = freqMaxMhz;
            this.bursts = bursts;
        }

        Instant getWindowStart() {
            return windowStart;
        }

        Instant getWindowEnd() {
            return windowEnd;
        }

        double getScore() {
            return score;
        }

        int getBurstCount() {
            return burstCount;
        }

        double getMedianBearingSpanDeg() {
            return medianBearingSpanDeg;
        }

        double getPeriodSec() {
            return periodSec;
        }

        double getAvgBearingsPerBurst() {
            return avgBearingsPerBurst;
        }

        int getTypicalBearingsPerBurst() {
            return typicalBearingsPerBurst;
        }

        double getPeriodicityScore() {
            return periodicityScore;
        }

        double getFreqCenterMhz() {
            return freqCenterMhz;
        }

        double getFreqMinMhz() {
            return freqMinMhz;
        }

        double getFreqMaxMhz() {
            return freqMaxMhz;
        }

        List<MultiBearingBurst> getBursts() {
            return bursts;
        }
    }
}
