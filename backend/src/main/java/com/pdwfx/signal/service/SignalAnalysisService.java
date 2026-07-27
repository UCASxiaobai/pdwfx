package com.pdwfx.signal.service;

import com.pdwfx.signal.model.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 通信侦获信号分析 — 算法编排入口。
 *
 * <p>处理流水线（按调用顺序）：
 * <pre>
 * analyze()
 *   ├─ splitByFreq()              同频编批
 *   ├─ mergeMultiFreqNetworks()   异频网络合并
 *   └─ buildNetworkView()         单网分析
 *         ├─ detectCommMode()           同频/异频/跳频
 *         ├─ {@link AzimuthTrajectoryService}  DBSCAN 分轨迹 + RANSAC 直线 + 样条曲线
 *         ├─ clusterBySignalLevel()       幅度并行双轨拆分（见 splitParallelAmplitudeTracks）
 *         ├─ MotionClassificationService    固定/移动（误差椭圆+Mahalanobis）
 *         ├─ MasterSlaveAnalysisService     主从（发射时间占比为主）
 *         ├─ classifyPlatformType()         平台类型（发射占空比主判据，椭圆辅证）
 *         └─ buildSummary()                 可读分析结论
 * </pre>
 *
 * <p>相关类：
 * <ul>
 *   <li>{@link MotionClassificationService} — 固定站/飞机判定</li>
 *   <li>{@link MasterSlaveAnalysisService} — MASTER/SLAVE 判定</li>
 *   <li>{@link ExcelImportService} — CSV/Excel 字段映射</li>
 * </ul>
 *
 * <p>设计文档：{@code docs/algorithm-design.md}
 */
@Service
public class SignalAnalysisService {

    private final MotionClassificationService motionClassificationService;
    private final MasterSlaveAnalysisService masterSlaveAnalysisService;
    private final AzimuthTrajectoryService azimuthTrajectoryService;
    private final CommunicationRhythmService communicationRhythmService;
    private final CommunicationLinkAnalysisService communicationLinkAnalysisService;
    private final BearingGeolocationService bearingGeolocationService;
    private final BearingTrackMatchService bearingTrackMatchService;

    private static final int MAX_ANALYSIS_POINTS = 12000;
    private static final int MAX_CHART_POINTS = 800;
    private static final int MAX_MERGE_GROUPS = 200;
    /** 摘要阶段通信模式估计的最大采样条数 */
    private static final int COMM_MODE_SAMPLE_CAP = 4000;
    /** 合并后方位残差标准差超过此值，认为可能错合了两个批 */
    private static final double MAX_MERGED_TRACK_RESIDUAL_STD = 4.5d;
    /** 幅度整体差值低于此值不拆分目标 */
    private static final double MIN_SIGNAL_LEVEL_SPLIT_RANGE = 18d;
    /** 幅度聚类间隔 (dB) */
    private static final double SIGNAL_LEVEL_GAP = 6d;
    /** 测向误差门限（度），用于平台类型分类时的方位稳定判据 */
    private static final double AZIMUTH_DF_ERROR_DEG = 1.0;
    /** 发射占空比 ≥ 此值 → 地面站（平台类型主判据） */
    private static final double DUTY_CYCLE_GROUND_PCT = 25.0;
    /** 发射占空比 ≥ 此值且 < 地面门限 → 预警机（平台类型主判据，todo：>4%） */
    private static final double DUTY_CYCLE_AWACS_PCT = CommunicationLinkAnalysisService.DUTY_AWACS_MIN_PCT;
    /** 占空比近地面门限时，误差椭圆辅证采用的带宽（百分点） */
    private static final double DUTY_CYCLE_GROUND_GRAY_PCT = 3.0;
    /** 样本数不足时不做幅度拆分 */
    private static final int MIN_SIGNAL_SAMPLES_FOR_SPLIT = 120;
    /** 幅度子簇内标准差上限，超过则视为单条变化曲线不拆分 */
    private static final double MAX_LEVEL_STD_FOR_STABLE_TRACK = 10d;
    /** 两幅度档时间重叠比例下限，无重叠说明是时序变化而非并行双轨 */
    private static final double MIN_AMP_TRACK_TIME_OVERLAP = 0.25;
    /** 幅度子簇最小点数，过小则并入邻近簇 */
    private static final int MIN_SIGNAL_CLUSTER_SIZE = 15;
    /** 位置匹配编批：全量 PDW 同池分析（不按频率拆网） */
    private static final boolean POSITION_FIRST_NETWORK_POOL = true;

    public SignalAnalysisService(MotionClassificationService motionClassificationService,
                                 MasterSlaveAnalysisService masterSlaveAnalysisService,
                                 AzimuthTrajectoryService azimuthTrajectoryService,
                                 CommunicationRhythmService communicationRhythmService,
                                 CommunicationLinkAnalysisService communicationLinkAnalysisService,
                                 BearingGeolocationService bearingGeolocationService,
                                 BearingTrackMatchService bearingTrackMatchService) {
        this.motionClassificationService = motionClassificationService;
        this.masterSlaveAnalysisService = masterSlaveAnalysisService;
        this.azimuthTrajectoryService = azimuthTrajectoryService;
        this.communicationRhythmService = communicationRhythmService;
        this.communicationLinkAnalysisService = communicationLinkAnalysisService;
        this.bearingGeolocationService = bearingGeolocationService;
        this.bearingTrackMatchService = bearingTrackMatchService;
    }

    /** 入口：分网并返回摘要；单网详情由 {@link AnalysisSessionService} 按需构建 */
    public List<NetworkBucket> partitionNetworks(List<DetectSignal> signals, double freqTolerance) {
        return partitionNetworks(signals, freqTolerance, true);
    }

    public List<NetworkBucket> partitionNetworks(
            List<DetectSignal> signals,
            double freqTolerance,
            boolean mergeMultiFreq
    ) {
        List<List<DetectSignal>> networkBuckets;
        if (mergeMultiFreq && POSITION_FIRST_NETWORK_POOL) {
            networkBuckets = new ArrayList<>();
            networkBuckets.add(new ArrayList<>(signals));
        } else {
            networkBuckets = splitByFreq(signals, freqTolerance);
            if (mergeMultiFreq) {
                networkBuckets = mergeMultiFreqNetworks(networkBuckets, freqTolerance);
            }
        }
        List<NetworkBucket> buckets = new ArrayList<>();
        int networkId = 1;
        for (List<DetectSignal> bucket : networkBuckets) {
            int id = networkId++;
            buckets.add(new NetworkBucket(id, bucket, buildQuickSummary(id, bucket, freqTolerance)));
        }
        return buckets;
    }

    /** @deprecated 全量构建所有网络，大文件极慢；请用 {@link #partitionNetworks} */
    public NetworkAnalysisResponse analyze(List<DetectSignal> signals, double freqTolerance) {
        List<NetworkBucket> buckets = partitionNetworks(signals, freqTolerance);
        NetworkAnalysisResponse response = new NetworkAnalysisResponse();
        List<NetworkView> views = new ArrayList<>();
        for (NetworkBucket b : buckets) {
            views.add(buildNetworkView(b.networkId, b.signals, freqTolerance));
        }
        response.setNetworks(views);
        response.setNetworkCount(views.size());
        return response;
    }

    public static final class NetworkBucket {
        public final int networkId;
        public final List<DetectSignal> signals;
        public final NetworkSummary summary;

        public NetworkBucket(int networkId, List<DetectSignal> signals, NetworkSummary summary) {
            this.networkId = networkId;
            this.signals = signals;
            this.summary = summary;
        }
    }

    private NetworkSummary buildQuickSummary(int networkId, List<DetectSignal> networkSignals, double freqTolerance) {
        NetworkSummary summary = new NetworkSummary();
        double center = 0, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (DetectSignal s : networkSignals) {
            double f = s.getFreq();
            center += f;
            min = Math.min(min, f);
            max = Math.max(max, f);
        }
        int n = networkSignals.size();
        center = n > 0 ? center / n : 0;
        if (min == Double.MAX_VALUE) min = center;
        if (max == -Double.MAX_VALUE) max = center;
        summary.setNetworkId(networkId);
        summary.setFreq(center);
        summary.setNetworkType("未知");
        summary.setNetworkConfidence(round2(Math.min(0.85, 0.45 + Math.min(0.40, n / 500.0))));
        summary.setFreqBand(freqBand(center));
        summary.setFreqRange(formatFreqRange(min, max));
        summary.setFreqStability(freqStability(center, min, max));
        summary.setCommMode(detectCommMode(sampleForSummary(networkSignals), freqTolerance));
        summary.setStationType("MIXED");
        summary.setSignalCount(n);
        summary.setTargetCount(0);
        return summary;
    }

    private List<DetectSignal> sampleForSummary(List<DetectSignal> signals) {
        if (signals.size() <= COMM_MODE_SAMPLE_CAP) return signals;
        return downsampleUniform(signals, COMM_MODE_SAMPLE_CAP);
    }

    /** 编批：方位轨迹 → 并行方位/幅度轨 → 目标簇列表（每个元素对应一个目标） */
    private List<List<DetectSignal>> partitionIntoTargetClusters(List<DetectSignal> networkSignals) {
        long netStart = networkSignals.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        long netEnd = networkSignals.stream().mapToLong(DetectSignal::getDetectTimesss).max().orElse(0L);

        List<DetectSignal> analysisSignals = new ArrayList<>(networkSignals);
        List<List<DetectSignal>> azimuthClusters = azimuthTrajectoryService.clusterTrajectories(analysisSignals);
        azimuthClusters = refineParallelTracks(azimuthClusters, 3.0d, 2);
        azimuthClusters = azimuthTrajectoryService.mergeTrajectoryFragments(azimuthClusters, 4d, 0.04d);
        azimuthClusters = BearingFragmentFilter.dropEphemeralClusters(azimuthClusters, netStart, netEnd);
        List<List<DetectSignal>> afterAzSplit = new ArrayList<>();
        for (List<DetectSignal> c : azimuthClusters) {
            afterAzSplit.addAll(azimuthTrajectoryService.splitParallelBandsIfConcurrent(c));
        }
        afterAzSplit = BearingFragmentFilter.dropEphemeralClusters(afterAzSplit, netStart, netEnd);
        List<List<DetectSignal>> targets = new ArrayList<>();
        for (List<DetectSignal> aCluster : afterAzSplit) {
            for (List<DetectSignal> sub : clusterBySignalLevel(aCluster, SIGNAL_LEVEL_GAP)) {
                if (!BearingFragmentFilter.isEphemeralFragment(sub, netStart, netEnd)) {
                    targets.add(sub);
                }
            }
        }
        return bearingTrackMatchService.mergeMatchingClusters(targets, new BearingTrackMatchService.MatchOptions());
    }

    // ==================== 编批-1：同频网络划分 ====================

    /** 按 FREQ 排序，容差 freqTolerance(MHz) 内归为一网 */
    private List<List<DetectSignal>> splitByFreq(List<DetectSignal> signals, double tol) {
        List<DetectSignal> sorted = new ArrayList<>(signals);
        sorted.sort(Comparator.comparingDouble(DetectSignal::getFreq));
        List<List<DetectSignal>> groups = new ArrayList<>();
        List<DetectSignal> current = new ArrayList<>();
        double anchor = Double.NaN;
        for (DetectSignal s : sorted) {
            if (current.isEmpty()) {
                current.add(s);
                anchor = s.getFreq();
                continue;
            }
            if (Math.abs(s.getFreq() - anchor) <= tol) {
                current.add(s);
            } else {
                groups.add(current);
                current = new ArrayList<>();
                current.add(s);
                anchor = s.getFreq();
            }
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    /** 单网完整分析（编批 + 识别 + 图表序列） */
    public NetworkView buildNetworkView(int id, List<DetectSignal> networkSignals, double freqTolerance) {
        NetworkView view = new NetworkView();
        view.setNetworkId(id);
        view.setFreq(networkSignals.stream().mapToDouble(DetectSignal::getFreq).average().orElse(0d));
        view.setCommMode(detectCommMode(networkSignals, freqTolerance));
        view.setSignalCount(networkSignals.size());
        view.setRawAzimuthSeries(toDownsampledSeries(networkSignals, true, MAX_CHART_POINTS));
        view.setRawSignalSeries(toDownsampledSeries(networkSignals, false, MAX_CHART_POINTS));
        view.setTargetClusteringMethod("POSITION_MATCH");

        List<TargetView> targets = new ArrayList<>();
        List<MasterSlaveAnalysisService.TargetContext> roleContexts = new ArrayList<>();
        Map<TargetView, MotionClassificationService.MotionAssessment> motionByTarget = new HashMap<>();
        int tid = 1;
        for (List<DetectSignal> targetSignals : partitionIntoTargetClusters(networkSignals)) {
                TargetView target = new TargetView();
                target.setTargetId("T" + tid++);
                // --- 识别-1：固定/移动（委托 MotionClassificationService）---
                MotionClassificationService.MotionAssessment motion =
                        motionClassificationService.assess(targetSignals);
                target.setConvergence(motion.convergenceState);
                target.setConvergenceDetail(motion.detail);
                if ("NO_ELLIPSE".equals(motion.state)) {
                    target.setEllipseStatus("NO_ELLIPSE");
                    target.setEllipseConverging(null);
                } else {
                    target.setEllipseStatus("HAS_ELLIPSE");
                    target.setEllipseConverging(motion.ellipseConverging);
                    target.setEarlySpread(motion.earlySpread);
                    target.setLateSpread(motion.lateSpread);
                }
                target.setAvgSignalLevel(round2(targetSignals.stream().mapToDouble(DetectSignal::getSignalLevel).average().orElse(0d)));
                target.setAvgSnr(round2(targetSignals.stream().mapToDouble(DetectSignal::getSnr).average().orElse(0d)));
                motionByTarget.put(target, motion);
                target.setConfidence(computeConfidence(targetSignals));
                target.setScore(computeRoleScore(targetSignals));
                target.setRawAzimuthSeries(toDownsampledSeries(targetSignals, true, MAX_CHART_POINTS));
                target.setRawSignalSeries(toDownsampledSeries(targetSignals, false, MAX_CHART_POINTS));
                target.setAzimuthSeries(azimuthTrajectoryService.toChartSeries(targetSignals, MAX_CHART_POINTS));
                target.setSignalSeries(toDownsampledSeries(targetSignals, false, MAX_CHART_POINTS));
                target.setFreqSeries(toDownsampledSeries(targetSignals, MAX_CHART_POINTS));
                target.setTrackPoints(toDownsampledTrackPoints(targetSignals, MAX_CHART_POINTS));
                target.setCommFreqMhzList(distinctCommFreqs(targetSignals));
                BearingGeolocationService.LocateResult locate =
                        bearingGeolocationService.estimate(targetSignals);
                target.setLocateLon(locate.lon);
                target.setLocateLat(locate.lat);
                target.setLocateMethod(locate.method);
                target.setDetectStartMs(locate.detectStartMs);
                target.setDetectEndMs(locate.detectEndMs);
                target.setDetectCount(locate.detectCount);
                communicationRhythmService.applyRhythmMetrics(target, targetSignals);
                targets.add(target);
                roleContexts.add(new MasterSlaveAnalysisService.TargetContext(target, targetSignals));
        }

        // --- 识别-2：主从（委托 MasterSlaveAnalysisService，发射时间占比权重最高）---
        long netStart = networkSignals.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        long netEnd = networkSignals.stream().mapToLong(DetectSignal::getDetectTimesss).max().orElse(0L);
        masterSlaveAnalysisService.assignRoles(roleContexts, netStart, netEnd);
        // --- 识别-3：平台类型（发射占空比主判据，测向1°门限，误差椭圆辅证）---
        for (MasterSlaveAnalysisService.TargetContext ctx : roleContexts) {
            classifyPlatformType(ctx, motionByTarget.get(ctx.target));
        }
        view.setTargets(targets);
        view.setStationType(resolveStationType(targets));
        populateNetworkOutput(view, networkSignals);
        communicationLinkAnalysisService.analyze(view, networkSignals);
        view.setAnalysisSummary(buildSummary(view));
        return view;
    }

    // ==================== 编批-1 扩展：异频/跳频 ====================

    /** 时间重叠 + 方位相近 + 调制一致 → 合并为 MULTI_FREQ 网络 */
    private List<List<DetectSignal>> mergeMultiFreqNetworks(List<List<DetectSignal>> groups, double tol) {
        if (groups.size() <= 1 || groups.size() > MAX_MERGE_GROUPS) return groups;
        List<MergeGroup> current = new ArrayList<>();
        for (List<DetectSignal> group : groups) {
            current.add(new MergeGroup(group));
        }
        boolean changed = true;
        int pass = 0;
        while (changed && pass++ < 20) {
            changed = false;
            List<MergeGroup> next = new ArrayList<>();
            for (MergeGroup group : current) {
                boolean merged = false;
                for (MergeGroup exist : next) {
                    if (exist.canMergeWith(group, tol)) {
                        exist.absorb(group);
                        merged = true;
                        changed = true;
                        break;
                    }
                }
                if (!merged) {
                    next.add(group);
                }
            }
            current = next;
        }
        List<List<DetectSignal>> out = new ArrayList<>();
        for (MergeGroup g : current) {
            out.add(g.signals);
        }
        return out;
    }

    private static final class MergeGroup {
        final List<DetectSignal> signals;
        double freqMean;
        long tStart;
        long tEnd;
        String dominantMod;
        double azMean;

        MergeGroup(List<DetectSignal> signals) {
            this.signals = signals;
            recomputeMeta();
        }

        void absorb(MergeGroup other) {
            signals.addAll(other.signals);
            recomputeMeta();
        }

        void recomputeMeta() {
            if (signals.isEmpty()) return;
            double sum = 0;
            double azSum = 0;
            tStart = Long.MAX_VALUE;
            tEnd = Long.MIN_VALUE;
            Map<String, Long> modCounts = new HashMap<>();
            for (DetectSignal s : signals) {
                sum += s.getFreq();
                azSum += s.getAzimuth();
                tStart = Math.min(tStart, s.getDetectTimesss());
                tEnd = Math.max(tEnd, s.getDetectTimesss());
                String m = s.getModulateStyle();
                if (m != null && !m.trim().isEmpty()) {
                    modCounts.merge(m.trim(), 1L, Long::sum);
                }
            }
            freqMean = sum / signals.size();
            azMean = azSum / signals.size();
            dominantMod = modCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);
        }

        boolean canMergeWith(MergeGroup other, double tol) {
            if (Math.abs(freqMean - other.freqMean) <= tol * 2) return false;
            long overlapStart = Math.max(tStart, other.tStart);
            long overlapEnd = Math.min(tEnd, other.tEnd);
            if (overlapStart > overlapEnd) return false;
            long overlap = overlapEnd - overlapStart;
            long minSpan = Math.min(tEnd - tStart, other.tEnd - other.tStart);
            if (minSpan <= 0 || overlap < minSpan * 0.3) return false;
            if (dominantMod != null && other.dominantMod != null && !dominantMod.equals(other.dominantMod)) {
                return false;
            }
            double azA = azimuthForOverlap(overlapStart, overlapEnd);
            double azB = other.azimuthForOverlap(overlapStart, overlapEnd);
            return Math.abs(azA - azB) <= 20d;
        }

        private double azimuthForOverlap(long overlapStart, long overlapEnd) {
            long span = Math.max(1, tEnd - tStart);
            long overlap = overlapEnd - overlapStart;
            if (overlap >= span * 0.55) {
                return azMean;
            }
            return SignalAnalysisService.meanAzimuthInRangeSampled(signals, overlapStart, overlapEnd, 800);
        }
    }

    private static double meanAzimuthInRangeSampled(List<DetectSignal> signals, long start, long end, int maxSamples) {
        if (signals.isEmpty()) return 0;
        if (signals.size() <= maxSamples) {
            return meanAzimuthInRange(signals, start, end);
        }
        double sum = 0;
        int count = 0;
        double step = (double) signals.size() / maxSamples;
        for (int i = 0; i < maxSamples; i++) {
            DetectSignal s = signals.get(Math.min(signals.size() - 1, (int) (i * step)));
            if (s.getDetectTimesss() >= start && s.getDetectTimesss() <= end) {
                sum += s.getAzimuth();
                count++;
            }
        }
        return count > 0 ? sum / count : azMeanFallback(signals);
    }

    private static double azMeanFallback(List<DetectSignal> signals) {
        double sum = 0;
        for (DetectSignal s : signals) sum += s.getAzimuth();
        return sum / signals.size();
    }

    @SuppressWarnings("unused")
    private boolean canMergeAsMultiFreq(List<DetectSignal> a, List<DetectSignal> b, double tol) {
        double freqA = a.stream().mapToDouble(DetectSignal::getFreq).average().orElse(0d);
        double freqB = b.stream().mapToDouble(DetectSignal::getFreq).average().orElse(0d);
        if (Math.abs(freqA - freqB) <= tol * 2) return false;

        long aStart = a.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        long aEnd = a.stream().mapToLong(DetectSignal::getDetectTimesss).max().orElse(0L);
        long bStart = b.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        long bEnd = b.stream().mapToLong(DetectSignal::getDetectTimesss).max().orElse(0L);
        long overlapStart = Math.max(aStart, bStart);
        long overlapEnd = Math.min(aEnd, bEnd);
        if (overlapStart > overlapEnd) return false;
        long overlap = overlapEnd - overlapStart;
        long minSpan = Math.min(aEnd - aStart, bEnd - bStart);
        if (minSpan <= 0 || overlap < minSpan * 0.3) return false;

        double azA = meanAzimuthInRange(a, overlapStart, overlapEnd);
        double azB = meanAzimuthInRange(b, overlapStart, overlapEnd);
        if (Math.abs(azA - azB) > 20d) return false;

        String modA = dominantModulateStyle(a);
        String modB = dominantModulateStyle(b);
        if (modA != null && modB != null && !modA.equals(modB)) return false;
        return true;
    }

    private static double meanAzimuthInRange(List<DetectSignal> signals, long start, long end) {
        return signals.stream()
                .filter(s -> s.getDetectTimesss() >= start && s.getDetectTimesss() <= end)
                .mapToDouble(DetectSignal::getAzimuth)
                .average()
                .orElse(0d);
    }

    private String dominantModulateStyle(List<DetectSignal> signals) {
        Map<String, Long> counts = signals.stream()
                .map(DetectSignal::getModulateStyle)
                .filter(Objects::nonNull)
                .filter(s -> s != null && !s.trim().isEmpty())
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        return counts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private String detectCommMode(List<DetectSignal> signals, double tol) {
        int distinctBands = countDistinctFreqBands(signals, tol);
        if (distinctBands <= 1) return "SAME_FREQ";
        double avgFreqDelta = avgDelta(toFreqSeries(signals));
        if (avgFreqDelta > 0.8 && distinctBands > signals.size() * 0.1) {
            return "HOPPING";
        }
        return "MULTI_FREQ";
    }

    private int countDistinctFreqBands(List<DetectSignal> signals, double tol) {
        List<Double> freqs = signals.stream().map(DetectSignal::getFreq).sorted().collect(Collectors.toList());
        if (freqs.isEmpty()) return 0;
        int bands = 1;
        double anchor = freqs.get(0);
        for (double f : freqs) {
            if (Math.abs(f - anchor) > tol) {
                bands++;
                anchor = f;
            }
        }
        return bands;
    }

    private List<SeriesPoint> toDownsampledSeries(List<DetectSignal> signals, boolean azimuth, int maxPoints) {
        return toSeries(downsampleUniform(signals, maxPoints), azimuth);
    }

    private List<SeriesPoint> toDownsampledSeries(List<DetectSignal> signals, int maxPoints) {
        return toFreqSeries(downsampleUniform(signals, maxPoints));
    }

    private List<TrackPoint> toDownsampledTrackPoints(List<DetectSignal> signals, int maxPoints) {
        return toTrackPoints(downsampleUniform(signals, maxPoints));
    }

    private List<DetectSignal> downsampleUniform(List<DetectSignal> signals, int maxPoints) {
        if (signals.size() <= maxPoints) return signals;
        List<DetectSignal> sorted = new ArrayList<>(signals);
        sorted.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));
        List<DetectSignal> result = new ArrayList<>(maxPoints);
        double step = (double) sorted.size() / maxPoints;
        for (int i = 0; i < maxPoints; i++) {
            result.add(sorted.get(Math.min(sorted.size() - 1, (int) (i * step))));
        }
        return result;
    }

    private List<SeriesPoint> toSeries(List<DetectSignal> signals, boolean azimuth) {
        return signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .map(s -> new SeriesPoint(s.getDetectTimesss(), azimuth ? s.getAzimuth() : s.getSignalLevel()))
                .collect(Collectors.toList());
    }

    private List<SeriesPoint> toFreqSeries(List<DetectSignal> signals) {
        return signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .map(s -> new SeriesPoint(s.getDetectTimesss(), s.getFreq()))
                .collect(Collectors.toList());
    }

    private List<TrackPoint> toTrackPoints(List<DetectSignal> signals) {
        return signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .map(s -> {
                    TrackPoint p = new TrackPoint();
                    p.setT(s.getDetectTimesss());
                    p.setPlatformLon(s.getLongitude());
                    p.setPlatformLat(s.getLatitude());
                    p.setTargetLon(s.getTargetLon());
                    p.setTargetLat(s.getTargetLat());
                    p.setAzimuth(s.getAzimuth());
                    p.setBatchId(DirectionFindingMatchBridge.resolveBatchId(s));
                    p.setFreq(s.getFreq());
                    p.setSignalLevel(s.getSignalLevel());
                    p.setSignalDwellMs(s.getSignalDwellMs());
                    return p;
                })
                .collect(Collectors.toList());
    }

    // ==================== 编批-2：方位轨迹（委托 AzimuthTrajectoryService）====================

    private List<List<DetectSignal>> refineParallelTracks(List<List<DetectSignal>> clusters,
                                                          double minSeparation, int maxPasses) {
        List<List<DetectSignal>> current = clusters;
        for (int pass = 0; pass < maxPasses; pass++) {
            List<List<DetectSignal>> next = new ArrayList<>();
            for (List<DetectSignal> cluster : current) {
                next.addAll(azimuthTrajectoryService.splitParallelIfNeeded(cluster, minSeparation));
            }
            if (next.size() == current.size()) break;
            current = next;
        }
        return splitJitteryMergedTracks(current);
    }

    /**
     * 合并后质量检查：若一个合并簇相对拟合线抖动过大，说明可能把两条批误合。
     */
    private List<List<DetectSignal>> splitJitteryMergedTracks(List<List<DetectSignal>> clusters) {
        List<List<DetectSignal>> checked = new ArrayList<>();
        for (List<DetectSignal> cluster : clusters) {
            if (cluster.size() < 24
                    || azimuthTrajectoryService.azimuthResidualStd(cluster) <= MAX_MERGED_TRACK_RESIDUAL_STD) {
                checked.add(cluster);
                continue;
            }
            List<List<DetectSignal>> split = azimuthTrajectoryService.splitParallelIfNeeded(cluster, 3d);
            if (split.size() > 1) {
                checked.addAll(split);
            } else {
                checked.add(cluster);
            }
        }
        return checked;
    }

    // ==================== 编批-3：幅度二次拆分 ====================

    /**
     * 同方位簇内的幅度细分入口。
     * 仅当 {@link #splitParallelAmplitudeTracks} 检测到两条时间重叠、各自稳定的并行幅度轨时才拆目标。
     */
    private List<List<DetectSignal>> clusterBySignalLevel(List<DetectSignal> signals, double levelGap) {
        if (signals.size() < MIN_SIGNAL_SAMPLES_FOR_SPLIT) {
            return singletonCluster(signals);
        }
        double min = signals.stream().mapToDouble(DetectSignal::getSignalLevel).min().orElse(0d);
        double max = signals.stream().mapToDouble(DetectSignal::getSignalLevel).max().orElse(0d);
        if (Math.abs(max - min) < MIN_SIGNAL_LEVEL_SPLIT_RANGE) {
            return singletonCluster(signals);
        }
        return mergeTinySignalClusters(splitParallelAmplitudeTracks(signals, levelGap), MIN_SIGNAL_CLUSTER_SIZE);
    }

    /**
     * 仅当同方位簇内存在两条时间重叠、各自幅度稳定的并行曲线时才拆分。
     * 不再按幅度值排序聚类，避免单条时变幅度曲线被误拆成两档。
     */
    private List<List<DetectSignal>> splitParallelAmplitudeTracks(List<DetectSignal> signals, double minSeparation) {
        if (signals.size() < MIN_SIGNAL_SAMPLES_FOR_SPLIT) {
            return singletonCluster(signals);
        }
        AmplitudeTrendModel trend = fitAmplitudeTrend(signals);
        List<DetectSignal> sorted = signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(Collectors.toList());

        double lowSum = 0, highSum = 0;
        int lowCount = 0, highCount = 0;
        double mid = sorted.stream()
                .mapToDouble(s -> s.getSignalLevel() - trend.predict(s.getDetectTimesss()))
                .average().orElse(0d);
        for (DetectSignal s : sorted) {
            double residual = s.getSignalLevel() - trend.predict(s.getDetectTimesss());
            if (residual < mid) {
                lowSum += residual;
                lowCount++;
            } else {
                highSum += residual;
                highCount++;
            }
        }
        if (lowCount < MIN_SIGNAL_CLUSTER_SIZE || highCount < MIN_SIGNAL_CLUSTER_SIZE) {
            return singletonCluster(signals);
        }
        double lowMean = lowSum / lowCount;
        double highMean = highSum / highCount;
        if (Math.abs(highMean - lowMean) < minSeparation) {
            return singletonCluster(signals);
        }

        double splitAt = (lowMean + highMean) / 2.0;
        List<DetectSignal> lower = new ArrayList<>();
        List<DetectSignal> upper = new ArrayList<>();
        for (DetectSignal s : sorted) {
            double residual = s.getSignalLevel() - trend.predict(s.getDetectTimesss());
            if (residual < splitAt) lower.add(s);
            else upper.add(s);
        }

        if (temporalOverlapRatio(lower, upper) < MIN_AMP_TRACK_TIME_OVERLAP) {
            return singletonCluster(signals);
        }
        if (levelStd(lower) > MAX_LEVEL_STD_FOR_STABLE_TRACK
                || levelStd(upper) > MAX_LEVEL_STD_FOR_STABLE_TRACK) {
            return singletonCluster(signals);
        }
        if (Math.abs(meanLevel(lower) - meanLevel(upper)) < MIN_SIGNAL_LEVEL_SPLIT_RANGE) {
            return singletonCluster(signals);
        }

        List<List<DetectSignal>> split = new ArrayList<>();
        split.add(lower);
        split.add(upper);
        return split;
    }

    private AmplitudeTrendModel fitAmplitudeTrend(List<DetectSignal> signals) {
        List<DetectSignal> sorted = signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(Collectors.toList());
        if (sorted.size() < 2) {
            double y = sorted.isEmpty() ? 0d : sorted.get(0).getSignalLevel();
            return AmplitudeTrendModel.flat(y, sorted.isEmpty() ? 0L : sorted.get(0).getDetectTimesss());
        }
        long t0 = sorted.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        int n = sorted.size();
        for (DetectSignal s : sorted) {
            double x = (s.getDetectTimesss() - t0) / 1000.0;
            double y = s.getSignalLevel();
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double den = n * sxx - sx * sx;
        double slope = Math.abs(den) < 1e-9 ? 0d : (n * sxy - sx * sy) / den;
        double intercept = (sy - slope * sx) / n;
        return new AmplitudeTrendModel(slope, intercept, t0);
    }

    private static double temporalOverlapRatio(List<DetectSignal> a, List<DetectSignal> b) {
        long a0 = a.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        long a1 = a.stream().mapToLong(DetectSignal::getDetectTimesss).max().orElse(0L);
        long b0 = b.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        long b1 = b.stream().mapToLong(DetectSignal::getDetectTimesss).max().orElse(0L);
        long overlapStart = Math.max(a0, b0);
        long overlapEnd = Math.min(a1, b1);
        if (overlapEnd <= overlapStart) return 0d;
        long overlap = overlapEnd - overlapStart;
        long minSpan = Math.min(a1 - a0, b1 - b0);
        return minSpan <= 0 ? 0d : (double) overlap / minSpan;
    }

    private static double levelStd(List<DetectSignal> signals) {
        if (signals.isEmpty()) return 999d;
        double avg = signals.stream().mapToDouble(DetectSignal::getSignalLevel).average().orElse(0d);
        double variance = signals.stream()
                .mapToDouble(s -> {
                    double d = s.getSignalLevel() - avg;
                    return d * d;
                })
                .average().orElse(0d);
        return Math.sqrt(variance);
    }

    private static double meanLevel(List<DetectSignal> signals) {
        return signals.stream().mapToDouble(DetectSignal::getSignalLevel).average().orElse(0d);
    }

    private static List<List<DetectSignal>> singletonCluster(List<DetectSignal> signals) {
        List<List<DetectSignal>> one = new ArrayList<>();
        one.add(new ArrayList<>(signals));
        return one;
    }

    private List<List<DetectSignal>> mergeTinySignalClusters(List<List<DetectSignal>> clusters, int minSize) {
        if (clusters.size() <= 1) return clusters;
        List<List<DetectSignal>> large = new ArrayList<>();
        List<List<DetectSignal>> tiny = new ArrayList<>();
        for (List<DetectSignal> c : clusters) {
            if (c.size() < minSize) tiny.add(c);
            else large.add(c);
        }
        if (large.isEmpty()) return clusters;
        for (List<DetectSignal> c : tiny) {
            double mean = c.stream().mapToDouble(DetectSignal::getSignalLevel).average().orElse(0d);
            List<DetectSignal> best = large.get(0);
            double bestGap = Math.abs(mean - best.stream().mapToDouble(DetectSignal::getSignalLevel).average().orElse(0d));
            for (int i = 1; i < large.size(); i++) {
                List<DetectSignal> candidate = large.get(i);
                double gap = Math.abs(mean - candidate.stream().mapToDouble(DetectSignal::getSignalLevel).average().orElse(0d));
                if (gap < bestGap) {
                    best = candidate;
                    bestGap = gap;
                }
            }
            best.addAll(c);
        }
        return large;
    }

    // ==================== 识别：平台类型（发射占空比主，椭圆辅） ====================

    /**
     * 平台类型主判据：目标自身发射占空比（burstDuration / period）。
     * <ul>
     *   <li>≥ {@value #DUTY_CYCLE_GROUND_PCT}% → 地面站 GROUND</li>
     *   <li>≥ {@value #DUTY_CYCLE_AWACS_PCT}% 且 &lt; 地面门限 → 预警机 AWACS</li>
     *   <li>否则 → 飞机 AIR</li>
     * </ul>
     * 测向：逐步进方位均差与 {@value #AZIMUTH_DF_ERROR_DEG}° 比较（测向误差门限）。
     * 误差椭圆 / 固定移动仅作低优先级辅证，不覆盖主判据的地面站结论。
     */
    private void classifyPlatformType(MasterSlaveAnalysisService.TargetContext ctx,
                                    MotionClassificationService.MotionAssessment motion) {
        TargetView target = ctx.target;
        List<DetectSignal> signals = ctx.signals;
        double sharePct = round2(ctx.networkEmissionShareRatio * 100.0);
        target.setEmissionSharePct(sharePct);
        double dutyPct = target.getAvgDutyCycle();
        double azStep = azimuthDiffAvg(signals);

        String primary;
        String primaryReason;
        if (dutyPct >= DUTY_CYCLE_GROUND_PCT) {
            primary = "GROUND";
            primaryReason = String.format(
                    "主判据：发射占空比 %.1f%% ≥ %.0f%% → 地面站（同网发射占比 %.1f%%）",
                    dutyPct, DUTY_CYCLE_GROUND_PCT, sharePct);
        } else if (dutyPct >= DUTY_CYCLE_AWACS_PCT) {
            primary = "AWACS";
            primaryReason = String.format(
                    "主判据：发射占空比 %.1f%% ∈ [%.0f%%, %.0f%%) → 预警机（同网发射占比 %.1f%%）",
                    dutyPct, DUTY_CYCLE_AWACS_PCT, DUTY_CYCLE_GROUND_PCT, sharePct);
        } else {
            primary = "AIR";
            primaryReason = String.format(
                    "主判据：发射占空比 %.1f%% < %.0f%% → 飞机（同网发射占比 %.1f%%）",
                    dutyPct, DUTY_CYCLE_AWACS_PCT, sharePct);
        }

        String finalType = primary;
        if ("AIR".equals(primary) && communicationLinkAnalysisService.qualifiesSecondaryAwacs(target)) {
            finalType = "AWACS";
            primaryReason = primaryReason + "；" + communicationLinkAnalysisService.secondaryAwacsNote(target);
        }

        String azNote = azStep <= AZIMUTH_DF_ERROR_DEG
                ? String.format("测向稳定(逐步进均差 %.2f° ≤ %.1f°误差门限)", azStep, AZIMUTH_DF_ERROR_DEG)
                : String.format("测向变化 %.2f°(门限 %.1f°)", azStep, AZIMUTH_DF_ERROR_DEG);

        StringBuilder aux = new StringBuilder();

        if (motion != null) {
            aux.append(String.format("；【辅】%s", motion.detail));
            if ("GROUND".equals(primary)) {
                // 主判据地面站不被椭圆推翻
            } else if ("AIR".equals(finalType)
                    && azStep <= AZIMUTH_DF_ERROR_DEG
                    && ("FIXED".equals(motion.state)
                    || (motion.ellipseConverging && "CONVERGING".equals(motion.convergenceState)))) {
                finalType = "GROUND";
                aux.append(" → 辅证(椭圆固定/收敛+测向≤1°)上调为地面站");
            } else if ("AWACS".equals(finalType)
                    && "MOBILE".equals(motion.state)
                    && !motion.ellipseConverging
                    && azStep > AZIMUTH_DF_ERROR_DEG * 5) {
                finalType = "AIR";
                aux.append(" → 辅证(椭圆移动且不收敛+测向漂移)下调为飞机");
            } else if (dutyPct >= DUTY_CYCLE_GROUND_PCT - DUTY_CYCLE_GROUND_GRAY_PCT
                    && dutyPct < DUTY_CYCLE_GROUND_PCT
                    && ("FIXED".equals(motion.state) || motion.ellipseConverging)) {
                finalType = "GROUND";
                aux.append(String.format(
                        " → 辅证(占空比近%.0f%%且椭圆倾向固定)上调为地面站", DUTY_CYCLE_GROUND_PCT));
            }
        }

        if ("AWACS".equals(finalType) && azStep <= AZIMUTH_DF_ERROR_DEG) {
            aux.append("；测向过稳(≤1°)，更接近固定站测向特征");
        }
        if ("GROUND".equals(finalType) && azStep > AZIMUTH_DF_ERROR_DEG) {
            aux.append("；测向抖动超1°门限，仍按发射占空比判地面站");
        }
        aux.append(String.format(
                "；【通信行为】主周期%.0fms(PRI%.0fms)，burst数%d，平均驻留%.1fms，占空比%.1f%%(活跃时长/观测窗)，峰值%.1f%%",
                target.getPeriodMs() == null ? 0d : target.getPeriodMs(),
                target.getEstimatedPriMs() == null ? 0d : target.getEstimatedPriMs(),
                target.getBurstCount(),
                target.getBurstDurationMeanMs(),
                target.getAvgDutyCycle(),
                target.getMaxDutyCycle()));

        target.setTargetType(finalType);
        double targetConfidence = computeTargetConfidence(target, dutyPct, azStep, finalType);
        target.setConfidence(targetConfidence);
        target.setLowConfidence(targetConfidence < 0.60
                || target.getPeriodConfidence() < 0.50
                || target.getBurstConfidence() < 0.50);
        target.setTargetTypeReason(primaryReason + "；" + azNote + aux);
    }

    private double computeTargetConfidence(TargetView target, double dutyPct, double azStep, String finalType) {
        double dutyScore;
        if ("GROUND".equals(finalType)) {
            dutyScore = Math.min(1.0, dutyPct / DUTY_CYCLE_GROUND_PCT);
        } else if ("AWACS".equals(finalType)) {
            dutyScore = 1.0 - Math.min(1.0,
                    Math.abs(dutyPct - (DUTY_CYCLE_AWACS_PCT + DUTY_CYCLE_GROUND_PCT) / 2.0)
                            / Math.max(1.0, (DUTY_CYCLE_GROUND_PCT - DUTY_CYCLE_AWACS_PCT) / 2.0));
        } else {
            dutyScore = 1.0 - Math.min(1.0, dutyPct / DUTY_CYCLE_AWACS_PCT);
        }
        double periodScore = target.getPeriodConfidence();
        double burstScore = target.getBurstConfidence();
        double signalScore = target.getSignalStability();
        double doaScore = Math.max(0, 1.0 - Math.min(1.0, azStep / 30.0));
        double sampleScore = Math.min(1.0, target.getEvidenceSignalCount() / 60.0);
        return round2(dutyScore * 0.30
                + periodScore * 0.20
                + burstScore * 0.15
                + signalScore * 0.15
                + doaScore * 0.10
                + sampleScore * 0.10);
    }

    private double computeRoleScore(List<DetectSignal> signals) {
        double avgSignal = signals.stream().mapToDouble(DetectSignal::getSignalLevel).average().orElse(0d);
        double avgSnr = signals.stream().mapToDouble(DetectSignal::getSnr).average().orElse(0d);
        return Math.round((avgSignal * 0.6 + avgSnr * 0.4) * 100.0) / 100.0;
    }

    private String resolveStationType(List<TargetView> targets) {
        boolean hasMaster = targets.stream().anyMatch(t -> "MASTER".equals(t.getRole()));
        boolean hasSlave = targets.stream().anyMatch(t -> "SLAVE".equals(t.getRole()));
        if (hasMaster && hasSlave) return "MIXED";
        if (hasMaster) return "MASTER";
        return "SLAVE";
    }

    private void populateNetworkOutput(NetworkView view, List<DetectSignal> networkSignals) {
        List<TargetView> targets = view.getTargets();
        double center = networkSignals.stream().mapToDouble(DetectSignal::getFreq).average().orElse(0d);
        double min = networkSignals.stream().mapToDouble(DetectSignal::getFreq).min().orElse(center);
        double max = networkSignals.stream().mapToDouble(DetectSignal::getFreq).max().orElse(center);
        int masters = (int) targets.stream().filter(t -> "MASTER".equals(t.getRole())).count();
        int slaves = (int) targets.stream().filter(t -> "SLAVE".equals(t.getRole())).count();
        int active = (int) targets.stream().filter(t -> t.getEvidenceSignalCount() > 0).count();

        view.setCenterFreq(round2(center));
        view.setFreqMin(round2(min));
        view.setFreqMax(round2(max));
        view.setFreqBand(freqBand(center));
        view.setFreqRange(formatFreqRange(min, max));
        view.setFreqStability(freqStability(center, min, max));
        view.setTargetCount(targets.size());
        view.setActiveTargetCount(active);
        view.setMainStationCount(masters);
        view.setSubStationCount(slaves);
        view.setNetworkPeriod(round2Nullable(medianTargetPeriod(targets)));
        view.setNetworkDutyCycle(round2(targets.stream().mapToDouble(TargetView::getAvgDutyCycle).average().orElse(0d)));
        view.setNetworkLoadLevel(loadLevel(view.getNetworkDutyCycle()));
        view.setNetworkType(inferNetworkType(view));
        view.setNetworkConfidence(computeNetworkConfidence(view));
        view.setLowConfidence(view.getNetworkConfidence() < 0.60 || targets.stream().anyMatch(TargetView::isLowConfidence));
        view.setUpdatedAt(System.currentTimeMillis());
        view.setUpdateMode("FULL");
    }

    private String inferNetworkType(NetworkView view) {
        List<TargetView> targets = view.getTargets();
        boolean hasGround = targets.stream().anyMatch(t -> "GROUND".equals(t.getTargetType()));
        boolean hasAir = targets.stream().anyMatch(t -> "AIR".equals(t.getTargetType()) || "AWACS".equals(t.getTargetType()));
        if ("HOPPING".equals(view.getCommMode()) || "MULTI_FREQ".equals(view.getCommMode())) return "数据链";
        if (hasGround && hasAir) return "空地通信";
        if (hasAir) return "空空通信";
        if (hasGround && view.getMainStationCount() > 0 && view.getSubStationCount() > 0) return "中继网";
        return "未知";
    }

    private double computeNetworkConfidence(NetworkView view) {
        List<TargetView> targets = view.getTargets();
        if (targets.isEmpty()) return 0;
        double targetScore = targets.stream().mapToDouble(TargetView::getConfidence).average().orElse(0d);
        double periodScore = targets.stream().mapToDouble(TargetView::getPeriodConfidence).average().orElse(0d);
        double roleScore = view.getMainStationCount() > 0 ? 0.85 : 0.45;
        return round2(targetScore * 0.45
                + periodScore * 0.25
                + view.getFreqStability() * 0.15
                + roleScore * 0.15);
    }

    private Double medianTargetPeriod(List<TargetView> targets) {
        List<Double> periods = targets.stream()
                .map(TargetView::getPeriodMs)
                .filter(Objects::nonNull)
                .filter(v -> v > 0)
                .sorted()
                .collect(Collectors.toList());
        if (periods.isEmpty()) return null;
        return periods.get(periods.size() / 2);
    }

    private String loadLevel(double duty) {
        if (duty < 5) return "低";
        if (duty < 25) return "中";
        if (duty < 50) return "高";
        return "拥塞";
    }

    private String freqBand(double centerFreq) {
        if (centerFreq >= 225 && centerFreq <= 400) return "225-400MHz";
        if (centerFreq >= 1000 && centerFreq < 2000) return "L波段";
        if (centerFreq >= 2000 && centerFreq < 4000) return "S波段";
        if (centerFreq > 0) return "未知频段";
        return "未知";
    }

    private String formatFreqRange(double min, double max) {
        return String.format("%.3f~%.3fMHz", min, max);
    }

    private double freqStability(double center, double min, double max) {
        double range = Math.max(0, max - min);
        if (center <= 0) return 0;
        return round2(Math.max(0, 1.0 - Math.min(1.0, range / Math.max(0.1, center * 0.01))));
    }

    private Double round2Nullable(Double v) {
        return v == null ? null : round2(v);
    }

    private double computeConfidence(List<DetectSignal> signals) {
        double n = Math.min(1.0, signals.size() / 60.0);
        double smooth = 1.0 - Math.min(1.0, azimuthDiffAvg(signals) / 40.0);
        return Math.round((0.6 * n + 0.4 * smooth) * 100.0) / 100.0;
    }

    private double azimuthDiffAvg(List<DetectSignal> signals) {
        List<DetectSignal> sorted = signals.stream().sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss)).collect(Collectors.toList());
        if (sorted.size() < 2) return 0;
        double sum = 0;
        for (int i = 1; i < sorted.size(); i++) {
            sum += Math.abs(sorted.get(i).getAzimuth() - sorted.get(i - 1).getAzimuth());
        }
        return sum / (sorted.size() - 1);
    }

    /** 幅度时间趋势（线性），仅用于幅度并行轨拆分 */
    private static class AmplitudeTrendModel {
        private final double slope;
        private final double intercept;
        private final long t0;

        private AmplitudeTrendModel(double slope, double intercept, long t0) {
            this.slope = slope;
            this.intercept = intercept;
            this.t0 = t0;
        }

        private static AmplitudeTrendModel flat(double y, long t0) {
            return new AmplitudeTrendModel(0d, y, t0);
        }

        private double predict(long t) {
            double x = (t - t0) / 1000.0;
            return intercept + slope * x;
        }
    }

    private String commModeLabel(String mode) {
        if ("MULTI_FREQ".equals(mode)) return "异频";
        if ("HOPPING".equals(mode)) return "定跳频";
        return "同频";
    }

    private String targetTypeLabel(String type) {
        if ("GROUND".equals(type)) return "固定站";
        if ("AWACS".equals(type)) return "预警机";
        if ("AIR".equals(type)) return "飞机";
        return type;
    }

    // ==================== 输出：分析结论文案 ====================

    /** 生成 networkConclusions / targetConclusions / anomalies，供前端展示 */
    private AnalysisSummary buildSummary(NetworkView view) {
        AnalysisSummary summary = new AnalysisSummary();
        List<TargetView> targets = view.getTargets();
        long masters = targets.stream().filter(t -> "MASTER".equals(t.getRole())).count();
        long slaves = targets.stream().filter(t -> "SLAVE".equals(t.getRole())).count();
        summary.getNetworkConclusions().add("该网络共识别 " + targets.size() + " 个目标");
        summary.getNetworkConclusions().add(String.format(
                "网络类型：%s，频段：%s，频率范围：%s，网络置信度 %.2f%s",
                view.getNetworkType(), view.getFreqBand(), view.getFreqRange(), view.getNetworkConfidence(),
                view.isLowConfidence() ? "（低置信度，需复核）" : ""));
        summary.getNetworkConclusions().add(String.format(
                "网络行为：主周期%s，平均占空比 %.1f%%，负载等级：%s，活跃目标 %d/%d",
                view.getNetworkPeriod() == null ? "未知" : String.format("%.0fms", view.getNetworkPeriod()),
                view.getNetworkDutyCycle(), view.getNetworkLoadLevel(),
                view.getActiveTargetCount(), view.getTargetCount()));
        summary.getNetworkConclusions().add(
                "编批：方位轨迹 → 并行方位/幅度轨；通信节奏(周期/占空比/burst)仅用于分析与平台类型，不参与目标拆分");
        summary.getNetworkConclusions().add("通信模式：" + commModeLabel(view.getCommMode()));
        if (view.getCommLinkChannelLabel() != null) {
            summary.getNetworkConclusions().add(
                    "通信链属性：" + view.getCommLinkChannelLabel() + " — " + view.getCommLinkReason());
        }
        summary.getNetworkConclusions().add(
                "通信链规则：D01地空引导(地+小飞机)/D02异频(仅小飞机)/D03预警机单发/D04预警+地面/D05预警+战机/D06跨区协同(地+小飞机)；不符→不明");
        summary.getNetworkConclusions().add(
                "平台类型：占空比≥25%→地面站，≥4%且<25%→预警机(PRI<2s可次级上调)，<4%→飞机；测向1°；波道驻留辅证");
        summary.getNetworkConclusions().add(
                "主从：流量占比(55%)+占空比(40%)为主；同网流量合计100%（优先 nSignalTime 驻留）");
        if (view.getSignalCount() > MAX_ANALYSIS_POINTS) {
            summary.getNetworkConclusions().add("原始信号 " + view.getSignalCount() + " 条，分析采样 " + MAX_ANALYSIS_POINTS + " 条");
        }
        summary.getNetworkConclusions().add("其中 MASTER: " + masters + " 个, SLAVE: " + slaves + " 个");
        long ellipseConv = targets.stream().filter(t -> Boolean.TRUE.equals(t.getEllipseConverging())).count();
        long ellipseNot = targets.stream().filter(t -> Boolean.FALSE.equals(t.getEllipseConverging())).count();
        long ellipseNone = targets.stream().filter(t -> "NO_ELLIPSE".equals(t.getEllipseStatus())).count();
        summary.getNetworkConclusions().add(String.format(
                "误差椭圆：可形成 %d 个目标，其中椭圆收敛 %d、椭圆不收敛 %d；定位点不足无法成椭圆 %d",
                ellipseConv + ellipseNot, ellipseConv, ellipseNot, ellipseNone));

        for (TargetView t : targets) {
            summary.getTargetConclusions().add(buildTargetConclusion(t));
        }

        if (targets.size() >= 2) {
            summary.getAnomalies().add("识别到 " + targets.size() + " 条方位/幅度轨迹，疑似多个目标共频通信");
        }
        long lowTargets = targets.stream().filter(TargetView::isLowConfidence).count();
        if (lowTargets > 0) {
            summary.getAnomalies().add("存在 " + lowTargets + " 个低置信度目标，建议查看原始PDW和周期/burst依据");
        }
        if ("HOPPING".equals(view.getCommMode())) {
            summary.getAnomalies().add("发现定跳频通信模式");
        } else if ("MULTI_FREQ".equals(view.getCommMode())) {
            summary.getAnomalies().add("发现异频通信模式");
        }
        if (summary.getAnomalies().isEmpty()) {
            summary.getAnomalies().add("未发现明显异常");
        }
        return summary;
    }

    private double avgDelta(List<SeriesPoint> series) {
        if (series == null || series.size() < 2) return 0d;
        double sum = 0;
        for (int i = 1; i < series.size(); i++) {
            sum += Math.abs(series.get(i).getV() - series.get(i - 1).getV());
        }
        return sum / (series.size() - 1);
    }

    private String buildTargetConclusion(TargetView t) {
        double azMove = avgDelta(t.getAzimuthSeries());
        double sigMove = avgDelta(t.getSignalSeries());
        String azDesc = azMove < 5
                ? String.format("方位曲线稳定(逐步进均差 %.2f°<5°)", azMove)
                : String.format("方位有明显漂移(逐步进均差 %.2f°≥5°)", azMove);
        String sigDesc = sigMove < 5
                ? String.format("幅度曲线稳定(逐步进均差 %.2f dB<5)", sigMove)
                : String.format("幅度波动较大(逐步进均差 %.2f dB≥5)", sigMove);
        return "Target " + t.getTargetId() + "："
                + String.format("【置信度】目标%.2f，周期%.2f，burst%.2f%s；",
                t.getConfidence(), t.getPeriodConfidence(), t.getBurstConfidence(),
                t.isLowConfidence() ? "（低置信度）" : "")
                + "【定位】" + nullToEmpty(t.getConvergenceDetail()) + "；"
                + "【平台】" + targetTypeLabel(t.getTargetType()) + " — " + nullToEmpty(t.getTargetTypeReason()) + "；"
                + "【主从】" + t.getRole() + " — " + nullToEmpty(t.getRoleReason()) + "；"
                + String.format("【通信行为】主周期%.0fms，PRI%.0fms，burst%d个，平均驻留%.1fms，占空比%.1f%%(活跃/观测窗)，峰值%.1f%%；",
                t.getPeriodMs() == null ? 0d : t.getPeriodMs(),
                t.getEstimatedPriMs() == null ? 0d : t.getEstimatedPriMs(),
                t.getBurstCount(),
                t.getBurstDurationMeanMs(),
                t.getAvgDutyCycle(),
                t.getMaxDutyCycle())
                + "【轨迹】" + azDesc + "，" + sigDesc;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static List<Double> distinctCommFreqs(List<DetectSignal> signals) {
        return signals.stream()
                .mapToDouble(DetectSignal::getFreq)
                .filter(f -> f > 0 && Double.isFinite(f))
                .boxed()
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

}
