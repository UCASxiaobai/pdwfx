package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.TargetView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 主站 (MASTER) / 从站 (SLAVE) 识别。
 *
 * <p>主判据（{@link #compositeScore}）：
 * <ul>
 *   <li><b>流量占比</b> ≈ 55% — 本网活跃发射量份额（优先 nSignalTime 驻留之和，否则节奏占空比×观测窗）</li>
 *   <li><b>占空比</b> ≈ 40% — {@link TargetView#getAvgDutyCycle()}（节奏分析，含 nSignalTime）</li>
 *   <li>其余时序/幅度仅作 tie-break ≤ 5%</li>
 * </ul>
 *
 * <p>跨目标时序信息仅写入 roleReason 供人工核对，不参与主从排序。
 */
@Component
public class MasterSlaveAnalysisService {

    /** 45s 无新点则结束当前发射簇（仅 roleReason 统计用） */
    private static final long BURST_GAP_MS = 45_000L;
    /** 主站发射后，从站在此窗口内跟随则计为响应（说明用） */
    private static final long RESPONSE_WINDOW_MS = 90_000L;
    private static final long LEAD_WINDOW_MS = 8_000L;
    /** 主从得分：流量 + 占空比为主 */
    private static final double WEIGHT_TRAFFIC = 0.55;
    private static final double WEIGHT_DUTY = 0.40;
    private static final double WEIGHT_TIEBREAK = 0.05;

    /**
     * 为同网全部目标分配 MASTER/SLAVE，并写入 {@link TargetView#setRoleReason}。
     */
    public void assignRoles(List<TargetContext> contexts, long networkStart, long networkEnd) {
        if (contexts.isEmpty()) return;
        long windowMs = Math.max(1, networkEnd - networkStart);
        for (TargetContext ctx : contexts) {
            ctx.features = extractFeatures(ctx.signals, networkStart, networkEnd);
        }
        enrichNetworkCoupling(contexts);
        computeNetworkTrafficShares(contexts, windowMs);

        double maxComposite = -1;
        TargetContext masterCtx = null;
        for (TargetContext ctx : contexts) {
            ctx.compositeScore = compositeScore(ctx);
            if (ctx.compositeScore > maxComposite) {
                maxComposite = ctx.compositeScore;
                masterCtx = ctx;
            }
        }

        double second = -1;
        for (TargetContext ctx : contexts) {
            if (ctx != masterCtx && ctx.compositeScore > second) {
                second = ctx.compositeScore;
            }
        }
        if (second < 0) second = maxComposite;

        for (TargetContext ctx : contexts) {
            TargetView t = ctx.target;
            boolean isMaster = ctx == masterCtx;
            t.setRole(isMaster ? "MASTER" : "SLAVE");
            TemporalFeatures f = ctx.features;
            if (isMaster) {
                t.setRoleReason(String.format(
                        "主站：流量占比%.1f%%(本网100%%)，占空比%.1f%%，活跃跨度%.1f%%，burst%d次；"
                                + "综合得分%.2f(流量×%.0f%%+占空比×%.0f%%，次高%.2f) → MASTER。"
                                + " 参考：先发%.0f%%，响应%.0f%%",
                        ctx.networkEmissionShareRatio * 100,
                        rhythmDutyPct(ctx.target),
                        f.onlineSpanRatio * 100, f.burstCount,
                        ctx.compositeScore, WEIGHT_TRAFFIC * 100, WEIGHT_DUTY * 100, second,
                        f.leadRatio * 100, f.responseRate * 100));
            } else {
                t.setRoleReason(String.format(
                        "从站：流量占比%.1f%%，占空比%.1f%%，活跃跨度%.1f%%，burst%d次；"
                                + "综合得分%.2f(主站%s %.2f) → SLAVE。"
                                + " 参考：滞后中位%.1fs",
                        ctx.networkEmissionShareRatio * 100,
                        rhythmDutyPct(ctx.target),
                        f.onlineSpanRatio * 100, f.burstCount,
                        ctx.compositeScore, masterCtx.target.getTargetId(), maxComposite,
                        f.medianLagAfterLeaderSec));
            }
        }
    }

    /** 跨目标：先发/响应/中心性 */
    public void enrichNetworkCoupling(List<TargetContext> contexts) {
        if (contexts.size() < 2) {
            for (TargetContext ctx : contexts) {
                if (ctx.features != null) {
                    ctx.features.leadRatio = 1.0;
                    ctx.features.responseRate = 0;
                    ctx.features.centrality = 0.5;
                }
            }
            return;
        }

        List<List<Long>> allBurstStarts = new ArrayList<>();
        for (TargetContext ctx : contexts) {
            allBurstStarts.add(burstStartTimes(ctx.signals));
        }

        for (int i = 0; i < contexts.size(); i++) {
            TargetContext ctx = contexts.get(i);
            TemporalFeatures f = ctx.features;
            List<Long> myStarts = allBurstStarts.get(i);
            if (myStarts.isEmpty()) continue;

            int leadWins = 0;
            int responded = 0;
            List<Long> lags = new ArrayList<>();

            for (Long start : myStarts) {
                boolean isEarliest = true;
                for (int j = 0; j < contexts.size(); j++) {
                    if (j == i) continue;
                    for (Long other : allBurstStarts.get(j)) {
                        if (Math.abs(other - start) <= LEAD_WINDOW_MS && other < start) {
                            isEarliest = false;
                            break;
                        }
                    }
                    if (!isEarliest) break;
                }
                if (isEarliest) leadWins++;

                int followers = 0;
                for (int j = 0; j < contexts.size(); j++) {
                    if (j == i) continue;
                    boolean hasFollow = false;
                    for (DetectSignal s : contexts.get(j).signals) {
                        long dt = s.getDetectTimesss() - start;
                        if (dt > 0 && dt <= RESPONSE_WINDOW_MS) {
                            hasFollow = true;
                            lags.add(dt);
                            break;
                        }
                    }
                    if (hasFollow) followers++;
                }
                if (followers > 0) responded++;
            }

            f.leadRatio = (double) leadWins / myStarts.size();
            f.responseRate = (double) responded / myStarts.size();
            f.centrality = f.responseRate * 0.6 + f.leadRatio * 0.4;

            if (!lags.isEmpty()) {
                lags.sort(Long::compareTo);
                f.medianLagAfterLeaderSec = lags.get(lags.size() / 2) / 1000.0;
            }

            f.temporalCoupling = computeCoupling(ctx, contexts, i);
        }
    }

    private double computeCoupling(TargetContext self, List<TargetContext> all, int idx) {
        if (self.signals.isEmpty()) return 0;
        int hits = 0;
        for (DetectSignal s : self.signals) {
            long t = s.getDetectTimesss();
            for (int j = 0; j < all.size(); j++) {
                if (j == idx) continue;
                for (DetectSignal o : all.get(j).signals) {
                    long dt = o.getDetectTimesss() - t;
                    if (dt >= 0 && dt <= RESPONSE_WINDOW_MS) {
                        hits++;
                        break;
                    }
                }
            }
        }
        return Math.min(1.0, (double) hits / self.signals.size());
    }

    private TemporalFeatures extractFeatures(List<DetectSignal> signals, long netStart, long netEnd) {
        TemporalFeatures f = new TemporalFeatures();
        if (signals.isEmpty()) return f;
        List<DetectSignal> sorted = new ArrayList<>(signals);
        sorted.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));

        long tMin = sorted.get(0).getDetectTimesss();
        long tMax = sorted.get(sorted.size() - 1).getDetectTimesss();
        f.onlineSpanRatio = (double) (tMax - tMin) / Math.max(1, netEnd - netStart);
        f.emissionCount = sorted.size();
        f.emissionRate = f.emissionCount / Math.max(1.0, (tMax - tMin) / 1000.0);

        List<long[]> bursts = clusterBursts(sorted);
        f.burstCount = bursts.size();
        long activeMs = 0;
        for (long[] b : bursts) {
            activeMs += Math.max(1, b[1] - b[0]);
        }
        long window = Math.max(1, netEnd - netStart);
        f.activeEmissionMs = activeMs;
        f.dutyCycle = Math.min(1.0, (double) activeMs / window);
        return f;
    }

    /**
     * 同网各目标<b>流量</b>占比归一化（合计 100%）。
     * 优先：Σ nSignalTime 驻留(ms)；否则：节奏占空比×观测窗；再否则：TOA burst 活跃时长或 PDW 数。
     */
    public void computeNetworkTrafficShares(List<TargetContext> contexts, long windowMs) {
        if (contexts.isEmpty()) return;
        double totalWeight = 0;
        double[] weights = new double[contexts.size()];
        for (int i = 0; i < contexts.size(); i++) {
            double w = trafficWeightMs(contexts.get(i), windowMs);
            weights[i] = w;
            totalWeight += w;
        }
        if (totalWeight <= 0) {
            double even = 1.0 / contexts.size();
            for (TargetContext ctx : contexts) {
                ctx.networkEmissionShareRatio = even;
            }
            return;
        }
        for (int i = 0; i < contexts.size(); i++) {
            contexts.get(i).networkEmissionShareRatio = weights[i] / totalWeight;
        }
    }

    /** @deprecated 使用 {@link #computeNetworkTrafficShares} */
    public void computeNetworkEmissionShares(List<TargetContext> contexts) {
        computeNetworkTrafficShares(contexts, 1);
    }

    private double trafficWeightMs(TargetContext ctx, long windowMs) {
        double dwellSum = 0;
        for (DetectSignal s : ctx.signals) {
            dwellSum += Math.max(0, s.getSignalDwellMs());
        }
        if (dwellSum > 0) {
            return dwellSum;
        }
        double dutyPct = rhythmDutyPct(ctx.target);
        if (dutyPct > 0 && windowMs > 0) {
            return dutyPct / 100.0 * windowMs;
        }
        TemporalFeatures f = ctx.features;
        if (f != null && f.activeEmissionMs > 0) {
            return f.activeEmissionMs;
        }
        return Math.max(1, ctx.signals.size());
    }

    private static double rhythmDutyPct(TargetView target) {
        return target.getAvgDutyCycle() > 0 ? target.getAvgDutyCycle() : 0;
    }

    /** 主站：流量占比 + 节奏占空比 */
    private double compositeScore(TargetContext ctx) {
        double trafficShare = ctx.networkEmissionShareRatio;
        double dutyNorm = Math.min(1.0, rhythmDutyPct(ctx.target) / 100.0);
        TemporalFeatures f = ctx.features;
        double tie = 0;
        if (f != null) {
            tie = Math.min(1.0, f.burstCount / 20.0) * 0.5 + f.leadRatio * 0.5;
        }
        return trafficShare * WEIGHT_TRAFFIC
                + dutyNorm * WEIGHT_DUTY
                + tie * WEIGHT_TIEBREAK;
    }

    private List<long[]> clusterBursts(List<DetectSignal> sorted) {
        List<long[]> bursts = new ArrayList<>();
        long start = sorted.get(0).getDetectTimesss();
        long end = start;
        for (int i = 1; i < sorted.size(); i++) {
            long t = sorted.get(i).getDetectTimesss();
            if (t - end > BURST_GAP_MS) {
                bursts.add(new long[]{start, end});
                start = t;
            }
            end = t;
        }
        bursts.add(new long[]{start, end});
        return bursts;
    }

    private List<Long> burstStartTimes(List<DetectSignal> signals) {
        List<long[]> bursts = clusterBursts(signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(Collectors.toList()));
        List<Long> starts = new ArrayList<>();
        for (long[] b : bursts) starts.add(b[0]);
        return starts;
    }

    public static class TargetContext {
        public final TargetView target;
        public final List<DetectSignal> signals;
        public TemporalFeatures features;
        /** 同网归一化发射时间占比，各目标之和为 1 */
        public double networkEmissionShareRatio;
        public double compositeScore;

        public TargetContext(TargetView target, List<DetectSignal> signals) {
            this.target = target;
            this.signals = signals;
        }
    }

    static class TemporalFeatures {
        int emissionCount;
        double emissionRate;
        int burstCount;
        long activeEmissionMs;
        double dutyCycle;
        double onlineSpanRatio;
        double leadRatio;
        double responseRate;
        double centrality;
        double medianLagAfterLeaderSec;
        double temporalCoupling;
    }
}
