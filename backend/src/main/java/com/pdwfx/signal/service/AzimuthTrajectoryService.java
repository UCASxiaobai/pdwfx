package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.SeriesPoint;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * 时间-方位轨迹：DBSCAN 分轨迹 → RANSAC 局部直线 → 样条曲线（弯曲段）。
 * <p>适配「多条轨迹 + 局部直线 + 少量弯曲」形态。
 */
@Service
public class AzimuthTrajectoryService {

    private static final int DBSCAN_MIN_PTS = 3;
    private static final double DBSCAN_EPS_AZ_DEG = 5.5;
    private static final double DBSCAN_EPS_TIME_SEC = 35.0;
    private static final double RANSAC_RESIDUAL_DEG = 4.0;
    private static final int RANSAC_ITERATIONS = 100;
    private static final double RANSAC_MIN_INLIER_RATIO = 0.68;
    /** 平行双轨最小方位差 (°)，如 230° 与 233°；低于 3° 易与噪声混淆 */
    private static final double PARALLEL_AZ_GAP_MIN = 3.0;
    /** 1D 双簇拆分时两簇中心最小间距 */
    private static final double PARALLEL_K2_MIN_GAP = 2.5;
    private static final long PARALLEL_TIME_WINDOW_MS = 30_000L;
    private static final double MIN_PARALLEL_TIME_OVERLAP = 0.2;
    /** 样条相对直线 RSS 改善比例下限 */
    private static final double SPLINE_VS_LINE_RSS_RATIO = 0.82;
    /** 超过此点数：先均匀采样做 DBSCAN，再把全量点归入最近轨迹（不删数据） */
    private static final int DBSCAN_SAMPLE_CAP = 10000;

    public List<List<DetectSignal>> clusterTrajectories(List<DetectSignal> signals) {
        if (signals.isEmpty()) {
            return new ArrayList<>();
        }
        if (signals.size() <= DBSCAN_SAMPLE_CAP) {
            return clusterTrajectoriesDbscan(signals);
        }
        List<DetectSignal> sample = downsampleUniform(signals, DBSCAN_SAMPLE_CAP);
        List<List<DetectSignal>> seedClusters = clusterTrajectoriesDbscan(sample);
        if (signals.size() > 25000) {
            return assignBySeedAnchors(signals, seedClusters);
        }
        return assignAllPointsToClusters(signals, seedClusters);
    }

    /** 大网快速归簇：按种子簇时空中心距离分配，避免对全量点反复拟合 */
    private List<List<DetectSignal>> assignBySeedAnchors(List<DetectSignal> all,
                                                         List<List<DetectSignal>> seedClusters) {
        if (seedClusters.isEmpty()) {
            return singletonList(all);
        }
        long t0 = all.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        double epsTime = DBSCAN_EPS_TIME_SEC;
        double[][] centers = new double[seedClusters.size()][2];
        for (int i = 0; i < seedClusters.size(); i++) {
            List<DetectSignal> seed = seedClusters.get(i);
            centers[i][0] = seed.stream().mapToLong(DetectSignal::getDetectTimesss).average().orElse(0L);
            centers[i][1] = seed.stream().mapToDouble(DetectSignal::getAzimuth).average().orElse(0d);
        }
        List<List<DetectSignal>> clusters = new ArrayList<>();
        for (int i = 0; i < seedClusters.size(); i++) {
            clusters.add(new ArrayList<>());
        }
        for (DetectSignal s : all) {
            double pt = s.getDetectTimesss();
            double pa = s.getAzimuth();
            int best = 0;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < centers.length; i++) {
                double dt = (pt - centers[i][0]) / 1000.0 / epsTime;
                double da = (pa - centers[i][1]) / DBSCAN_EPS_AZ_DEG;
                double dist = dt * dt + da * da;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
            clusters.get(best).add(s);
        }
        for (List<DetectSignal> c : clusters) {
            c.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));
        }
        return clusters;
    }

    private List<List<DetectSignal>> singletonList(List<DetectSignal> signals) {
        List<List<DetectSignal>> one = new ArrayList<>();
        one.add(new ArrayList<>(signals));
        return one;
    }

    private List<DetectSignal> downsampleUniform(List<DetectSignal> signals, int maxPoints) {
        if (signals.size() <= maxPoints) {
            return new ArrayList<>(signals);
        }
        List<DetectSignal> sorted = signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(java.util.stream.Collectors.toList());
        List<DetectSignal> result = new ArrayList<>(maxPoints);
        double step = (double) sorted.size() / maxPoints;
        for (int i = 0; i < maxPoints; i++) {
            result.add(sorted.get(Math.min(sorted.size() - 1, (int) (i * step))));
        }
        return result;
    }

    private List<List<DetectSignal>> assignAllPointsToClusters(List<DetectSignal> all,
                                                                 List<List<DetectSignal>> seedClusters) {
        if (seedClusters.isEmpty()) {
            List<List<DetectSignal>> one = new ArrayList<>();
            one.add(new ArrayList<>(all));
            return one;
        }
        List<List<DetectSignal>> clusters = new ArrayList<>();
        for (int i = 0; i < seedClusters.size(); i++) {
            clusters.add(new ArrayList<>());
        }
        for (DetectSignal s : all) {
            int bestIdx = -1;
            double bestResidual = Double.MAX_VALUE;
            for (int i = 0; i < seedClusters.size(); i++) {
                List<DetectSignal> trial = new ArrayList<>(clusters.get(i));
                trial.add(s);
                double residual = Math.abs(s.getAzimuth() - fitTrajectory(trial).predict(s.getDetectTimesss()));
                if (residual < bestResidual) {
                    bestResidual = residual;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0) {
                clusters.add(new ArrayList<>());
                clusters.get(clusters.size() - 1).add(s);
            } else {
                clusters.get(bestIdx).add(s);
            }
        }
        for (List<DetectSignal> c : clusters) {
            c.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));
        }
        clusters.removeIf(List::isEmpty);
        return clusters;
    }

    /**
     * DBSCAN 在 (时间, 方位) 空间分簇，得到多条轨迹；噪声点归入最近簇。
     */
    private List<List<DetectSignal>> clusterTrajectoriesDbscan(List<DetectSignal> signals) {
        if (signals.isEmpty()) {
            return new ArrayList<>();
        }
        if (signals.size() < DBSCAN_MIN_PTS) {
            List<List<DetectSignal>> one = new ArrayList<>();
            one.add(new ArrayList<>(signals));
            return one;
        }
        List<DetectSignal> sorted = signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(java.util.stream.Collectors.toList());
        long t0 = sorted.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        double epsTime = adaptiveTimeEps(sorted, t0);

        int n = sorted.size();
        int[] labels = new int[n];
        Arrays.fill(labels, -1);
        int clusterId = 0;

        for (int i = 0; i < n; i++) {
            if (labels[i] != -1) continue;
            List<Integer> neighbors = regionQuery(sorted, t0, i, epsTime);
            if (neighbors.size() < DBSCAN_MIN_PTS) {
                labels[i] = -2;
                continue;
            }
            labels[i] = clusterId;
            List<Integer> seeds = new ArrayList<>(neighbors);
            seeds.remove(Integer.valueOf(i));
            java.util.HashSet<Integer> seedSet = new java.util.HashSet<>(seeds);
            int idx = 0;
            while (idx < seeds.size()) {
                int j = seeds.get(idx++);
                if (labels[j] == -2) {
                    labels[j] = clusterId;
                }
                if (labels[j] != -1) continue;
                labels[j] = clusterId;
                List<Integer> jNeighbors = regionQuery(sorted, t0, j, epsTime);
                if (jNeighbors.size() >= DBSCAN_MIN_PTS) {
                    for (int k : jNeighbors) {
                        if (seedSet.add(k)) seeds.add(k);
                    }
                }
            }
            clusterId++;
        }

        List<List<DetectSignal>> clusters = new ArrayList<>();
        for (int c = 0; c < clusterId; c++) {
            clusters.add(new ArrayList<>());
        }
        List<DetectSignal> noise = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (labels[i] >= 0) {
                clusters.get(labels[i]).add(sorted.get(i));
            } else {
                noise.add(sorted.get(i));
            }
        }
        for (DetectSignal s : noise) {
            assignNoiseToNearestCluster(s, clusters, t0, epsTime);
        }
        clusters.removeIf(c -> c.size() < DBSCAN_MIN_PTS);
        clusters.removeIf(List::isEmpty);
        for (List<DetectSignal> c : clusters) {
            c.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));
        }
        return clusters;
    }

    private double adaptiveTimeEps(List<DetectSignal> sorted, long t0) {
        if (sorted.size() < 2) return DBSCAN_EPS_TIME_SEC;
        double[] gaps = new double[sorted.size() - 1];
        for (int i = 0; i < gaps.length; i++) {
            gaps[i] = (sorted.get(i + 1).getDetectTimesss() - sorted.get(i).getDetectTimesss()) / 1000.0;
        }
        Arrays.sort(gaps);
        double median = gaps[gaps.length / 2];
        return Math.max(DBSCAN_EPS_TIME_SEC, Math.min(120.0, median * 4.0));
    }

    private List<Integer> regionQuery(List<DetectSignal> sorted, long t0, int index, double epsTime) {
        List<Integer> neighbors = new ArrayList<>();
        DetectSignal p = sorted.get(index);
        double pt = (p.getDetectTimesss() - t0) / 1000.0;
        double pa = p.getAzimuth();
        long windowMs = (long) (epsTime * 1000.0);
        long tMin = p.getDetectTimesss() - windowMs;
        long tMax = p.getDetectTimesss() + windowMs;
        int from = lowerBoundByTime(sorted, tMin);
        int to = upperBoundByTime(sorted, tMax);
        for (int j = from; j <= to; j++) {
            if (j == index) continue;
            DetectSignal q = sorted.get(j);
            double dt = (q.getDetectTimesss() - t0) / 1000.0 - pt;
            double da = q.getAzimuth() - pa;
            double dist = Math.sqrt((dt / epsTime) * (dt / epsTime) + (da / DBSCAN_EPS_AZ_DEG) * (da / DBSCAN_EPS_AZ_DEG));
            if (dist <= 1.0) neighbors.add(j);
        }
        return neighbors;
    }

    private int lowerBoundByTime(List<DetectSignal> sorted, long tMin) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid).getDetectTimesss() < tMin) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private int upperBoundByTime(List<DetectSignal> sorted, long tMax) {
        int lo = 0;
        int hi = sorted.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted.get(mid).getDetectTimesss() <= tMax) lo = mid + 1;
            else hi = mid;
        }
        return lo - 1;
    }

    private void assignNoiseToNearestCluster(DetectSignal s, List<List<DetectSignal>> clusters, long t0, double epsTime) {
        if (clusters.isEmpty()) {
            return;
        }
        double pt = (s.getDetectTimesss() - t0) / 1000.0;
        double pa = s.getAzimuth();
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < clusters.size(); i++) {
            for (DetectSignal q : clusters.get(i)) {
                double dt = (q.getDetectTimesss() - t0) / 1000.0 - pt;
                double da = q.getAzimuth() - pa;
                double dist = Math.sqrt((dt / epsTime) * (dt / epsTime) + (da / DBSCAN_EPS_AZ_DEG) * (da / DBSCAN_EPS_AZ_DEG));
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
        }
        if (bestDist > 1.25) {
            return;
        }
        clusters.get(best).add(s);
    }

    // ==================== 合并：基于拟合模型 ====================

    public List<List<DetectSignal>> mergeTrajectoryFragments(List<List<DetectSignal>> clusters,
                                                             double azimuthGap,
                                                             double slopeGap) {
        if (clusters.size() <= 1) return clusters;
        List<List<DetectSignal>> merged = new ArrayList<>();
        for (List<DetectSignal> c : clusters) {
            List<DetectSignal> candidate = new ArrayList<>(c);
            candidate.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));
            boolean combined = false;
            for (List<DetectSignal> exist : merged) {
                if (canMergeFragments(exist, candidate, azimuthGap, slopeGap)) {
                    exist.addAll(candidate);
                    exist.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));
                    combined = true;
                    break;
                }
            }
            if (!combined) {
                merged.add(candidate);
            }
        }
        return merged;
    }

    private boolean canMergeFragments(List<DetectSignal> a, List<DetectSignal> b,
                                      double azimuthGap, double slopeGap) {
        if (a.isEmpty() || b.isEmpty()) return false;
        TrajectoryModel ma = fitTrajectory(a);
        TrajectoryModel mb = fitTrajectory(b);
        long taStart = a.get(0).getDetectTimesss();
        long taEnd = a.get(a.size() - 1).getDetectTimesss();
        long tbStart = b.get(0).getDetectTimesss();
        long tbEnd = b.get(b.size() - 1).getDetectTimesss();
        long overlapStart = Math.max(taStart, tbStart);
        long overlapEnd = Math.min(taEnd, tbEnd);
        long slopeCompareT = overlapStart <= overlapEnd
                ? overlapStart + (overlapEnd - overlapStart) / 2
                : (taEnd <= tbStart ? tbStart : taStart);
        if (Math.abs(ma.slopeAt(slopeCompareT) - mb.slopeAt(slopeCompareT)) > slopeGap) {
            return false;
        }
        if (overlapStart <= overlapEnd) {
            if (hasMultipleAzimuthBands(a, PARALLEL_AZ_GAP_MIN) || hasMultipleAzimuthBands(b, PARALLEL_AZ_GAP_MIN)) {
                return false;
            }
            long[] sampleTimes = {
                    overlapStart,
                    overlapStart + (overlapEnd - overlapStart) / 4,
                    overlapStart + (overlapEnd - overlapStart) / 2,
                    overlapStart + (overlapEnd - overlapStart) * 3 / 4,
                    overlapEnd
            };
            double maxGap = 0;
            for (long t : sampleTimes) {
                maxGap = Math.max(maxGap, Math.abs(ma.predict(t) - mb.predict(t)));
            }
            return maxGap <= azimuthGap;
        }
        if (taEnd <= tbStart) {
            return Math.abs(ma.predict(tbStart) - b.get(0).getAzimuth()) <= azimuthGap;
        }
        if (tbEnd <= taStart) {
            return Math.abs(mb.predict(taStart) - a.get(0).getAzimuth()) <= azimuthGap;
        }
        return false;
    }

    public double azimuthResidualStd(List<DetectSignal> signals) {
        TrajectoryModel model = fitTrajectory(signals);
        List<Double> residuals = new ArrayList<>(signals.size());
        for (DetectSignal s : signals) {
            residuals.add(s.getAzimuth() - model.predict(s.getDetectTimesss()));
        }
        return std(residuals);
    }

    /**
     * 仅当同一时刻附近存在两条明显不同方位（平行双轨）时才拆分，避免把单调爬升曲线拆成多个目标。
     */
    public List<List<DetectSignal>> splitParallelBandsIfConcurrent(List<DetectSignal> cluster) {
        if (!hasConcurrentAzimuthSeparation(cluster, PARALLEL_AZ_GAP_MIN, PARALLEL_TIME_WINDOW_MS)) {
            return singleton(cluster);
        }
        // 1) 方位最大空隙（两带之间无中间点时有效）
        List<List<DetectSignal>> byGap = splitByAzimuthGap(cluster, PARALLEL_AZ_GAP_MIN);
        if (byGap.size() > 1 && parallelBandsOverlapInTime(byGap.get(0), byGap.get(1))) {
            return byGap;
        }
        // 2) 1D 双簇（K-means k=2 方位），适用于红线 ~195° 与 ~202° 同时存在、中间有插值点的情况
        List<List<DetectSignal>> byK2 = splitByTwoMeansAzimuth(cluster, PARALLEL_K2_MIN_GAP);
        if (byK2.size() > 1 && parallelBandsOverlapInTime(byK2.get(0), byK2.get(1))) {
            return byK2;
        }
        return splitParallelIfNeeded(cluster, PARALLEL_AZ_GAP_MIN);
    }

    /** @deprecated 迭代方位拆分易过碎，请用 {@link #splitParallelBandsIfConcurrent} */
    public List<List<DetectSignal>> splitAllAzimuthBands(List<DetectSignal> cluster) {
        return splitParallelBandsIfConcurrent(cluster);
    }

    /**
     * 在 windowMs 内是否存在方位差 ≥ minSep 的两点（平行轨特征，非时间单调爬升）。
     */
    private boolean hasConcurrentAzimuthSeparation(List<DetectSignal> cluster, double minSep, long windowMs) {
        if (cluster.size() < 8) return false;
        List<DetectSignal> sorted = cluster.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(java.util.stream.Collectors.toList());
        for (int i = 0; i < sorted.size(); i++) {
            DetectSignal a = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                long dt = sorted.get(j).getDetectTimesss() - a.getDetectTimesss();
                if (dt > windowMs) break;
                if (Math.abs(sorted.get(j).getAzimuth() - a.getAzimuth()) >= minSep) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean parallelBandsOverlapInTime(List<DetectSignal> a, List<DetectSignal> b) {
        long a0 = a.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        long a1 = a.stream().mapToLong(DetectSignal::getDetectTimesss).max().orElse(0L);
        long b0 = b.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        long b1 = b.stream().mapToLong(DetectSignal::getDetectTimesss).max().orElse(0L);
        long overlapStart = Math.max(a0, b0);
        long overlapEnd = Math.min(a1, b1);
        if (overlapStart > overlapEnd) return false;
        long overlap = overlapEnd - overlapStart;
        long minSpan = Math.min(a1 - a0, b1 - b0);
        return minSpan > 0 && overlap >= minSpan * MIN_PARALLEL_TIME_OVERLAP;
    }

    /** 平行轨拆分：先按方位最大空隙分带，再按拟合残差双峰分 */
    public List<List<DetectSignal>> splitParallelIfNeeded(List<DetectSignal> cluster, double minSeparation) {
        if (!hasConcurrentAzimuthSeparation(cluster, PARALLEL_AZ_GAP_MIN, PARALLEL_TIME_WINDOW_MS)) {
            return singleton(cluster);
        }
        if (cluster.size() < 8) {
            return singleton(cluster);
        }
        double gapThresh = Math.min(minSeparation, PARALLEL_AZ_GAP_MIN);
        List<List<DetectSignal>> byAzGap = splitByAzimuthGap(cluster, gapThresh);
        if (byAzGap.size() > 1 && parallelBandsOverlapInTime(byAzGap.get(0), byAzGap.get(1))) {
            return byAzGap;
        }
        if (cluster.size() < 16) {
            return singleton(cluster);
        }
        TrajectoryModel ref = fitTrajectory(cluster);
        List<DetectSignal> sorted = cluster.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(java.util.stream.Collectors.toList());
        if (ref.kind == TrajectoryKind.SPLINE && isBidirectional(sorted) && !hasMultipleAzimuthBands(cluster, gapThresh)) {
            return singleton(cluster);
        }
        double lowMean = 0, highMean = 0;
        int lowCount = 0, highCount = 0;
        List<Double> residuals = new ArrayList<>(cluster.size());
        for (DetectSignal s : cluster) {
            residuals.add(s.getAzimuth() - ref.predict(s.getDetectTimesss()));
        }
        double mid = residuals.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
        for (double r : residuals) {
            if (r < mid) {
                lowMean += r;
                lowCount++;
            } else {
                highMean += r;
                highCount++;
            }
        }
        if (lowCount == 0 || highCount == 0) return singleton(cluster);
        lowMean /= lowCount;
        highMean /= highCount;
        if (Math.abs(highMean - lowMean) < minSeparation || lowCount < 8 || highCount < 8) {
            return singleton(cluster);
        }
        double splitAt = (lowMean + highMean) / 2.0;
        List<DetectSignal> lower = new ArrayList<>();
        List<DetectSignal> upper = new ArrayList<>();
        for (DetectSignal s : cluster) {
            double r = s.getAzimuth() - ref.predict(s.getDetectTimesss());
            if (r < splitAt) lower.add(s);
            else upper.add(s);
        }
        List<List<DetectSignal>> split = new ArrayList<>();
        split.add(lower);
        split.add(upper);
        return split;
    }

    /**
     * 按方位角排序后的最大空隙拆分（适用于同一目标两条平行方位带，如 173° 与 179°）。
     */
    private List<List<DetectSignal>> splitByAzimuthGap(List<DetectSignal> cluster, double minGap) {
        if (cluster.size() < 8) {
            return singleton(cluster);
        }
        double azMin = cluster.stream().mapToDouble(DetectSignal::getAzimuth).min().orElse(0d);
        double azMax = cluster.stream().mapToDouble(DetectSignal::getAzimuth).max().orElse(0d);
        if (azMax - azMin < minGap) {
            return singleton(cluster);
        }
        List<DetectSignal> byAz = cluster.stream()
                .sorted(Comparator.comparingDouble(DetectSignal::getAzimuth))
                .collect(java.util.stream.Collectors.toList());
        double maxGap = 0;
        int splitAfter = -1;
        for (int i = 0; i < byAz.size() - 1; i++) {
            double gap = byAz.get(i + 1).getAzimuth() - byAz.get(i).getAzimuth();
            if (gap > maxGap) {
                maxGap = gap;
                splitAfter = i;
            }
        }
        if (maxGap < minGap || splitAfter < 0) {
            return singleton(cluster);
        }
        int lowCount = splitAfter + 1;
        int highCount = byAz.size() - splitAfter - 1;
        if (lowCount < 4 || highCount < 4) {
            return singleton(cluster);
        }
        double splitAz = (byAz.get(splitAfter).getAzimuth() + byAz.get(splitAfter + 1).getAzimuth()) / 2.0;
        List<DetectSignal> lower = new ArrayList<>();
        List<DetectSignal> upper = new ArrayList<>();
        for (DetectSignal s : cluster) {
            if (s.getAzimuth() <= splitAz) lower.add(s);
            else upper.add(s);
        }
        if (lower.isEmpty() || upper.isEmpty()) {
            return singleton(cluster);
        }
        List<List<DetectSignal>> split = new ArrayList<>();
        split.add(lower);
        split.add(upper);
        return split;
    }

    /** 方位 1D 双簇（兜底）：最大空隙法失败时，用两均值分带 */
    private List<List<DetectSignal>> splitByTwoMeansAzimuth(List<DetectSignal> cluster, double minGap) {
        if (cluster.size() < 8) {
            return singleton(cluster);
        }
        double azMin = cluster.stream().mapToDouble(DetectSignal::getAzimuth).min().orElse(0d);
        double azMax = cluster.stream().mapToDouble(DetectSignal::getAzimuth).max().orElse(0d);
        if (azMax - azMin < minGap) {
            return singleton(cluster);
        }
        double c1 = azMin;
        double c2 = azMax;
        List<Double> sortedAz = cluster.stream().mapToDouble(DetectSignal::getAzimuth).sorted().boxed()
                .collect(java.util.stream.Collectors.toList());
        if (sortedAz.size() >= 4) {
            c1 = sortedAz.get(sortedAz.size() / 4);
            c2 = sortedAz.get(sortedAz.size() * 3 / 4);
        }
        for (int iter = 0; iter < 8; iter++) {
            double s1 = 0, s2 = 0;
            int n1 = 0, n2 = 0;
            for (DetectSignal s : cluster) {
                if (Math.abs(s.getAzimuth() - c1) <= Math.abs(s.getAzimuth() - c2)) {
                    s1 += s.getAzimuth();
                    n1++;
                } else {
                    s2 += s.getAzimuth();
                    n2++;
                }
            }
            if (n1 == 0 || n2 == 0) return singleton(cluster);
            c1 = s1 / n1;
            c2 = s2 / n2;
        }
        if (Math.abs(c1 - c2) < minGap) {
            return singleton(cluster);
        }
        List<DetectSignal> lower = new ArrayList<>();
        List<DetectSignal> upper = new ArrayList<>();
        double mid = (c1 + c2) / 2.0;
        for (DetectSignal s : cluster) {
            if (s.getAzimuth() <= mid) lower.add(s);
            else upper.add(s);
        }
        if (lower.size() < 4 || upper.size() < 4) {
            return singleton(cluster);
        }
        List<List<DetectSignal>> split = new ArrayList<>();
        split.add(lower);
        split.add(upper);
        return split;
    }

    private boolean hasMultipleAzimuthBands(List<DetectSignal> cluster, double minGap) {
        if (!hasConcurrentAzimuthSeparation(cluster, minGap, PARALLEL_TIME_WINDOW_MS)) {
            return false;
        }
        List<List<DetectSignal>> split = splitByAzimuthGap(cluster, minGap);
        return split.size() > 1 && parallelBandsOverlapInTime(split.get(0), split.get(1));
    }

    // ==================== 拟合：RANSAC 直线 + 样条 ====================

    public TrajectoryModel fitTrajectory(List<DetectSignal> signals) {
        if (signals.isEmpty()) {
            return TrajectoryModel.flat(0d, 0L);
        }
        if (signals.size() == 1) {
            DetectSignal s = signals.get(0);
            return TrajectoryModel.flat(s.getAzimuth(), s.getDetectTimesss());
        }
        List<DetectSignal> sorted = signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(java.util.stream.Collectors.toList());
        long t0 = sorted.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);

        RansacLine ransac = ransacLineFit(sorted, t0);
        SplineCurve spline = SplineCurve.fit(sorted, t0);

        if (ransac != null && ransac.inlierRatio >= RANSAC_MIN_INLIER_RATIO) {
            double lineRss = ransac.refitRss(sorted, t0);
            double splineRss = spline.rss(sorted);
            if (!hasMultipleAzimuthBands(sorted, PARALLEL_AZ_GAP_MIN)
                    && splineRss <= lineRss * SPLINE_VS_LINE_RSS_RATIO
                    && sorted.size() >= 5) {
                return TrajectoryModel.spline(spline);
            }
            return TrajectoryModel.line(ransac.slope, ransac.intercept, t0);
        }
        if (sorted.size() >= 3 && !hasMultipleAzimuthBands(sorted, PARALLEL_AZ_GAP_MIN)) {
            return TrajectoryModel.spline(spline);
        }
        return TrajectoryModel.line(ransac != null ? ransac.slope : 0d,
                ransac != null ? ransac.intercept : sorted.get(0).getAzimuth(), t0);
    }

    /** 由本簇 PDW 内容派生种子，保证摘要统计与详情编批结果一致（不依赖全局 RNG 调用次数） */
    private static long trajectorySeed(List<DetectSignal> sorted) {
        long h = 42L;
        int step = Math.max(1, sorted.size() / 32);
        for (int i = 0; i < sorted.size(); i += step) {
            DetectSignal s = sorted.get(i);
            h = h * 31 + s.getDetectTimesss();
            h = h * 31 + Double.hashCode(s.getAzimuth());
        }
        return h * 31 + sorted.size();
    }

    private RansacLine ransacLineFit(List<DetectSignal> sorted, long t0) {
        int n = sorted.size();
        if (n < 2) return null;
        Random rng = new Random(trajectorySeed(sorted));
        RansacLine best = null;
        int bestInliers = 0;
        for (int iter = 0; iter < RANSAC_ITERATIONS; iter++) {
            int i = rng.nextInt(n);
            int j = rng.nextInt(n);
            while (j == i) j = rng.nextInt(n);
            double t1 = (sorted.get(i).getDetectTimesss() - t0) / 1000.0;
            double a1 = sorted.get(i).getAzimuth();
            double t2 = (sorted.get(j).getDetectTimesss() - t0) / 1000.0;
            double a2 = sorted.get(j).getAzimuth();
            if (Math.abs(t2 - t1) < 1e-6) continue;
            double slope = (a2 - a1) / (t2 - t1);
            double intercept = a1 - slope * t1;
            int inliers = 0;
            for (DetectSignal s : sorted) {
                double t = (s.getDetectTimesss() - t0) / 1000.0;
                double err = Math.abs(s.getAzimuth() - (intercept + slope * t));
                if (err <= RANSAC_RESIDUAL_DEG) inliers++;
            }
            if (inliers > bestInliers) {
                bestInliers = inliers;
                best = new RansacLine(slope, intercept, t0, inliers / (double) n);
            }
        }
        if (best == null) return null;
        return best.refitLeastSquares(sorted, t0);
    }

    /**
     * 折线图序列：用真实方位降采样，避免样条在双轨数据上外推爆炸（出现上亿度）。
     * 聚类/合并仍用 RANSAC+样条；仅展示层走实测点。
     */
    public List<SeriesPoint> toChartSeries(List<DetectSignal> signals, int maxPoints) {
        if (signals.isEmpty()) {
            return new ArrayList<>();
        }
        List<DetectSignal> sorted = signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(java.util.stream.Collectors.toList());
        if (sorted.size() <= maxPoints) {
            List<SeriesPoint> series = new ArrayList<>(sorted.size());
            for (DetectSignal s : sorted) {
                series.add(new SeriesPoint(s.getDetectTimesss(), round2(clampAzimuth(s.getAzimuth()))));
            }
            return series;
        }
        List<DetectSignal> sample = downsampleUniform(sorted, maxPoints);
        List<SeriesPoint> series = new ArrayList<>(sample.size());
        for (DetectSignal s : sample) {
            series.add(new SeriesPoint(s.getDetectTimesss(), round2(clampAzimuth(s.getAzimuth()))));
        }
        return series;
    }

    private static double clampAzimuth(double az) {
        if (!Double.isFinite(az)) return 0d;
        if (az < -360 || az > 720) return 0d;
        return az;
    }

    private boolean isBidirectional(List<DetectSignal> signals) {
        if (signals.size() < 4) return false;
        boolean inc = false, dec = false;
        for (int i = 1; i < signals.size(); i++) {
            double d = signals.get(i).getAzimuth() - signals.get(i - 1).getAzimuth();
            if (d > 0.4) inc = true;
            if (d < -0.4) dec = true;
        }
        return inc && dec;
    }

    private static List<List<DetectSignal>> singleton(List<DetectSignal> cluster) {
        List<List<DetectSignal>> one = new ArrayList<>();
        one.add(cluster);
        return one;
    }

    private static double std(List<Double> values) {
        if (values.isEmpty()) return 999;
        double avg = values.stream().mapToDouble(Double::doubleValue).average().orElse(0d);
        double variance = values.stream().mapToDouble(v -> (v - avg) * (v - avg)).average().orElse(0d);
        return Math.sqrt(variance);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ==================== 模型类型 ====================

    public enum TrajectoryKind { LINE, SPLINE }

    public static final class TrajectoryModel {
        public final TrajectoryKind kind;
        private final double slope;
        private final double intercept;
        private final long t0;
        private final SplineCurve spline;

        private TrajectoryModel(TrajectoryKind kind, double slope, double intercept, long t0, SplineCurve spline) {
            this.kind = kind;
            this.slope = slope;
            this.intercept = intercept;
            this.t0 = t0;
            this.spline = spline;
        }

        static TrajectoryModel flat(double y, long t0) {
            return new TrajectoryModel(TrajectoryKind.LINE, 0d, y, t0, null);
        }

        static TrajectoryModel line(double slope, double intercept, long t0) {
            return new TrajectoryModel(TrajectoryKind.LINE, slope, intercept, t0, null);
        }

        static TrajectoryModel spline(SplineCurve spline) {
            return new TrajectoryModel(TrajectoryKind.SPLINE, 0d, 0d, spline.t0, spline);
        }

        public double predict(long tMs) {
            if (kind == TrajectoryKind.SPLINE && spline != null) {
                return spline.predict(tMs);
            }
            double x = (tMs - t0) / 1000.0;
            return intercept + slope * x;
        }

        public double slopeAt(long tMs) {
            if (kind == TrajectoryKind.SPLINE && spline != null) {
                return spline.slopeAt(tMs);
            }
            return slope;
        }
    }

    private static final class RansacLine {
        double slope;
        double intercept;
        long t0;
        double inlierRatio;

        RansacLine(double slope, double intercept, long t0, double inlierRatio) {
            this.slope = slope;
            this.intercept = intercept;
            this.t0 = t0;
            this.inlierRatio = inlierRatio;
        }

        RansacLine refitLeastSquares(List<DetectSignal> sorted, long t0) {
            double sx = 0, sy = 0, sxx = 0, sxy = 0;
            int n = 0;
            for (DetectSignal s : sorted) {
                double x = (s.getDetectTimesss() - t0) / 1000.0;
                double y = s.getAzimuth();
                if (Math.abs(y - (intercept + slope * x)) > RANSAC_RESIDUAL_DEG * 1.5) continue;
                sx += x;
                sy += y;
                sxx += x * x;
                sxy += x * y;
                n++;
            }
            if (n < 2) return this;
            double den = n * sxx - sx * sx;
            double sl = Math.abs(den) < 1e-9 ? slope : (n * sxy - sx * sy) / den;
            double ic = (sy - sl * sx) / n;
            int inliers = 0;
            for (DetectSignal s : sorted) {
                double x = (s.getDetectTimesss() - t0) / 1000.0;
                if (Math.abs(s.getAzimuth() - (ic + sl * x)) <= RANSAC_RESIDUAL_DEG) inliers++;
            }
            return new RansacLine(sl, ic, t0, inliers / (double) sorted.size());
        }

        double refitRss(List<DetectSignal> sorted, long t0) {
            RansacLine ref = refitLeastSquares(sorted, t0);
            double rss = 0;
            for (DetectSignal s : sorted) {
                double x = (s.getDetectTimesss() - t0) / 1000.0;
                double e = s.getAzimuth() - (ref.intercept + ref.slope * x);
                rss += e * e;
            }
            return rss;
        }
    }

    /** 自然三次样条（时间-方位） */
    private static final class SplineCurve {
        final long t0;
        final double[] t;
        final double[] y;
        final double[] m;

        private SplineCurve(long t0, double[] t, double[] y, double[] m) {
            this.t0 = t0;
            this.t = t;
            this.y = y;
            this.m = m;
        }

        static SplineCurve fit(List<DetectSignal> sorted, long t0) {
            List<TimeAz> deduped = dedupeByTime(sorted, t0);
            int n = deduped.size();
            double[] t = new double[n];
            double[] y = new double[n];
            for (int i = 0; i < n; i++) {
                t[i] = deduped.get(i).tSec;
                y[i] = deduped.get(i).azimuth;
            }
            double[] m = computeNaturalSplineM(t, y);
            return new SplineCurve(t0, t, y, m);
        }

        private static List<TimeAz> dedupeByTime(List<DetectSignal> sorted, long t0) {
            List<TimeAz> rows = new ArrayList<>();
            long lastT = Long.MIN_VALUE;
            double sumAz = 0;
            int cnt = 0;
            for (DetectSignal s : sorted) {
                long tMs = s.getDetectTimesss();
                if (tMs != lastT && cnt > 0) {
                    rows.add(new TimeAz((lastT - t0) / 1000.0, sumAz / cnt));
                    sumAz = 0;
                    cnt = 0;
                }
                lastT = tMs;
                sumAz += s.getAzimuth();
                cnt++;
            }
            if (cnt > 0) {
                rows.add(new TimeAz((lastT - t0) / 1000.0, sumAz / cnt));
            }
            return rows;
        }

        private static final class TimeAz {
            final double tSec;
            final double azimuth;

            TimeAz(double tSec, double azimuth) {
                this.tSec = tSec;
                this.azimuth = azimuth;
            }
        }

        double predict(long tMs) {
            double x = (tMs - t0) / 1000.0;
            if (x <= t[0]) return clampAz(y[0]);
            if (x >= t[t.length - 1]) return clampAz(y[y.length - 1]);
            int i = 0;
            while (i < t.length - 2 && x > t[i + 1]) i++;
            double h = t[i + 1] - t[i];
            if (h < 1e-9) return clampAz(y[i]);
            double a = (t[i + 1] - x) / h;
            double b = (x - t[i]) / h;
            double val = a * y[i] + b * y[i + 1]
                    + ((a * a * a - a) * m[i] + (b * b * b - b) * m[i + 1]) * h * h / 6.0;
            return clampAz(val);
        }

        private static double clampAz(double az) {
            if (!Double.isFinite(az)) return 0d;
            return Math.max(-360, Math.min(720, az));
        }

        double slopeAt(long tMs) {
            double h = 0.05;
            return (predict(tMs + (long) (h * 1000)) - predict(tMs - (long) (h * 1000))) / (2 * h);
        }

        double rss(List<DetectSignal> sorted) {
            double rss = 0;
            for (DetectSignal s : sorted) {
                double e = s.getAzimuth() - predict(s.getDetectTimesss());
                rss += e * e;
            }
            return rss;
        }

        private static double[] computeNaturalSplineM(double[] t, double[] y) {
            int n = t.length;
            if (n < 3) {
                double[] m = new double[n];
                return m;
            }
            double[] h = new double[n - 1];
            double[] alpha = new double[n - 1];
            for (int i = 0; i < n - 1; i++) {
                h[i] = t[i + 1] - t[i];
                if (h[i] < 1e-9) h[i] = 1e-9;
            }
            for (int i = 1; i < n - 1; i++) {
                alpha[i] = 3.0 / h[i] * (y[i + 1] - y[i]) - 3.0 / h[i - 1] * (y[i] - y[i - 1]);
            }
            double[] l = new double[n];
            double[] mu = new double[n];
            double[] z = new double[n];
            l[0] = 1;
            mu[0] = 0;
            z[0] = 0;
            for (int i = 1; i < n - 1; i++) {
                l[i] = 2.0 * (t[i + 1] - t[i - 1]) - h[i - 1] * mu[i - 1];
                mu[i] = h[i] / l[i];
                z[i] = (alpha[i] - h[i - 1] * z[i - 1]) / l[i];
            }
            l[n - 1] = 1;
            z[n - 1] = 0;
            double[] m = new double[n];
            m[n - 1] = 0;
            for (int j = n - 2; j >= 0; j--) {
                m[j] = z[j] - mu[j] * m[j + 1];
            }
            return m;
        }
    }
}
