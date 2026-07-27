package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.BurstWindow;
import com.pdwfx.signal.model.SeriesPoint;
import com.pdwfx.signal.model.TargetView;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 目标级通信节奏分析：周期、burst、占空比及可视化序列。
 *
 * <p>用于平台类型辅助判断与对外输出，<b>不参与目标编批拆分</b>。
 * {@link #splitByRhythm} 已保留但默认不在流水线中调用。
 */
@Component
public class CommunicationRhythmService {

    /** 样本不足不做节奏拆分（仅输出节奏指标） */
    private static final int MIN_SAMPLES_FOR_SPLIT = 40;
    /** 拆分后单轨最少点数，避免碎目标 */
    private static final int MIN_TRACK_SIZE = 20;
    private static final double MIN_ASSIGN_RATIO = 0.75;
    private static final int MAX_RHYTHM_TRACKS = 4;
    /** 多轨 PRI 差异低于此比例视为同一节奏，不拆分 */
    private static final double MIN_PRI_RATIO_FOR_SPLIT = 1.25;
    private static final double PRI_MATCH_TOLERANCE_RATIO = 0.15;
    /** 与方位并行轨一致：同时段内方位差≥此值才认为可能多目标 */
    private static final double CONCURRENT_AZ_GAP_DEG = 3.0;
    private static final long CONCURRENT_AZ_WINDOW_MS = 30_000L;
    private static final long MIN_PRI_MS = 80;
    private static final long MAX_PRI_MS = 600_000L;
    private static final double BURST_GAP_PRI_FACTOR = 3.0;
    private static final int MAX_CHART_POINTS = 800;
    private static final int PRI_HIST_BINS = 40;

    /**
     * 按 TOA/PRI 多轨关联拆分；无法拆分或样本不足时返回原簇。
     */
    public List<List<DetectSignal>> splitByRhythm(List<DetectSignal> signals) {
        if (signals == null || signals.size() < MIN_SAMPLES_FOR_SPLIT) {
            return singleton(signals);
        }
        List<DetectSignal> sorted = sortedByTime(signals);
        long[] times = sorted.stream().mapToLong(DetectSignal::getDetectTimesss).toArray();
        long[] intervals = intervalsMs(times);
        if (intervals.length < 5) {
            return singleton(signals);
        }

        // 无「同时段不同方位」时不做节奏拆分，避免单目标抖动被拆成多条 SLAVE
        if (!hasConcurrentAzimuthSeparation(sorted)) {
            return singleton(signals);
        }

        List<PriTrack> tracks = associateTracks(sorted, intervals);
        tracks = mergeSimilarPriTracks(tracks);
        if (!shouldAcceptRhythmSplit(tracks, sorted.size())) {
            return singleton(signals);
        }

        List<List<DetectSignal>> out = new ArrayList<>();
        int minSize = Math.max(MIN_TRACK_SIZE, (int) Math.ceil(sorted.size() * 0.08));
        for (PriTrack tr : tracks) {
            if (tr.signals.size() >= minSize) {
                out.add(tr.signals);
            }
        }
        if (out.size() <= 1) {
            return singleton(signals);
        }
        return out;
    }

    /**
     * 是否接受节奏拆分：轨数合理、PRI 明显不同、分配率足够高。
     */
    private boolean shouldAcceptRhythmSplit(List<PriTrack> tracks, int totalSignals) {
        if (tracks.size() <= 1 || tracks.size() > MAX_RHYTHM_TRACKS) {
            return false;
        }
        int minSize = Math.max(MIN_TRACK_SIZE, (int) Math.ceil(totalSignals * 0.08));
        List<PriTrack> significant = tracks.stream()
                .filter(t -> t.signals.size() >= minSize)
                .collect(Collectors.toList());
        if (significant.size() < 2) {
            return false;
        }
        long minPri = significant.stream().mapToLong(t -> t.priMs).min().orElse(1L);
        long maxPri = significant.stream().mapToLong(t -> t.priMs).max().orElse(1L);
        if (minPri <= 0 || (double) maxPri / minPri < MIN_PRI_RATIO_FOR_SPLIT) {
            return false;
        }
        long assigned = significant.stream().mapToLong(t -> t.signals.size()).sum();
        return assigned >= totalSignals * MIN_ASSIGN_RATIO;
    }

    private boolean hasConcurrentAzimuthSeparation(List<DetectSignal> sorted) {
        if (sorted.size() < 8) return false;
        for (int i = 0; i < sorted.size(); i++) {
            DetectSignal a = sorted.get(i);
            for (int j = i + 1; j < sorted.size(); j++) {
                long dt = sorted.get(j).getDetectTimesss() - a.getDetectTimesss();
                if (dt > CONCURRENT_AZ_WINDOW_MS) break;
                if (Math.abs(sorted.get(j).getAzimuth() - a.getAzimuth()) >= CONCURRENT_AZ_GAP_DEG) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 写入节奏可视化序列与估计 PRI */
    public void applyRhythmMetrics(TargetView target, List<DetectSignal> signals) {
        RhythmMetrics m = computeMetrics(signals);
        target.setEstimatedPriMs(m.estimatedPriMs);
        target.setPriJitterPct(m.priJitterPct);
        target.setPeriodMs(m.periodMs);
        target.setPeriodStability(m.periodStability);
        target.setPeriodConfidence(m.periodConfidence);
        target.setBurstDurationMeanMs(m.burstDurationMeanMs);
        target.setBurstDurationStdMs(m.burstDurationStdMs);
        target.setBurstCount(m.bursts.size());
        target.setPulseCountMean(m.pulseCountMean);
        target.setMeanPriMs(m.meanPriMs);
        target.setPriStdMs(m.priStdMs);
        target.setAvgDutyCycle(m.avgDutyCycle);
        target.setMaxDutyCycle(m.maxDutyCycle);
        target.setBurstConfidence(m.burstConfidence);
        target.setFreqDrift(m.freqDrift);
        target.setDoaDrift(m.doaDrift);
        target.setAmplitudeMean(m.amplitudeMean);
        target.setAmplitudeStd(m.amplitudeStd);
        target.setSignalStability(m.signalStability);
        target.setEvidenceSignalCount(signals == null ? 0 : signals.size());
        target.setToaIntervalSeries(downsample(m.toaIntervalSeries));
        target.setPriHistogram(m.priHistogram);
        target.setJitterSeries(downsample(m.jitterSeries));
        target.setPeriodErrorSeries(downsample(m.periodErrorSeries));
        target.setDutyCycleTrend(downsample(m.dutyCycleTrend));
        target.setBurstTimelineSeries(downsample(m.burstTimelineSeries));
        target.setBursts(m.bursts);
    }

    // ==================== 多 PRI 轨关联 ====================

    private List<PriTrack> associateTracks(List<DetectSignal> sorted, long[] intervals) {
        long[] candidatePris = estimateCandidatePris(intervals);
        List<PriTrack> tracks = new ArrayList<>();

        for (DetectSignal s : sorted) {
            long t = s.getDetectTimesss();
            PriTrack best = null;
            double bestErr = Double.MAX_VALUE;
            for (PriTrack tr : tracks) {
                if (tr.lastToa < 0) continue;
                double err = matchError(t, tr);
                if (err < bestErr) {
                    bestErr = err;
                    best = tr;
                }
            }
            if (best != null && bestErr <= toleranceMs(best.priMs)) {
                best.add(s);
            } else {
                PriTrack nt = new PriTrack();
                nt.priMs = candidatePris.length > 0 ? candidatePris[0] : medianInterval(intervals);
                nt.add(s);
                tracks.add(nt);
            }
        }

        for (PriTrack tr : tracks) {
            tr.reestimatePri();
        }
        return mergeSimilarPriTracks(tracks);
    }

    private double matchError(long t, PriTrack tr) {
        double err = Math.abs(t - (tr.lastToa + tr.priMs));
        if (tr.priMs > MIN_PRI_MS * 2) {
            err = Math.min(err, Math.abs(t - (tr.lastToa + tr.priMs / 2)));
            err = Math.min(err, Math.abs(t - (tr.lastToa + tr.priMs * 2)));
        }
        return err;
    }

    private long toleranceMs(long pri) {
        return Math.max(150, Math.round(pri * PRI_MATCH_TOLERANCE_RATIO));
    }

    private List<PriTrack> mergeSimilarPriTracks(List<PriTrack> tracks) {
        if (tracks.size() <= 1) return tracks;
        boolean merged = true;
        while (merged) {
            merged = false;
            List<PriTrack> next = new ArrayList<>();
            for (PriTrack tr : tracks) {
                boolean absorbed = false;
                for (PriTrack exist : next) {
                    if (priSimilar(exist.priMs, tr.priMs)) {
                        exist.signals.addAll(tr.signals);
                        exist.signals.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));
                        exist.reestimatePri();
                        absorbed = true;
                        merged = true;
                        break;
                    }
                }
                if (!absorbed) next.add(tr);
            }
            tracks = next;
        }
        return tracks;
    }

    private boolean priSimilar(long a, long b) {
        if (a <= 0 || b <= 0) return false;
        double ratio = (double) Math.max(a, b) / Math.min(a, b);
        return ratio < 1.12;
    }

    private long[] estimateCandidatePris(long[] intervals) {
        long[] filtered = Arrays.stream(intervals)
                .filter(v -> v >= MIN_PRI_MS && v <= MAX_PRI_MS)
                .toArray();
        if (filtered.length < 3) {
            return new long[]{medianInterval(intervals)};
        }
        long min = Arrays.stream(filtered).min().orElse(MIN_PRI_MS);
        long max = Arrays.stream(filtered).max().orElse(MAX_PRI_MS);
        int bins = Math.min(PRI_HIST_BINS, Math.max(12, (int) ((max - min) / 500) + 1));
        double binW = Math.max(1.0, (max - min) / (double) bins);
        int[] hist = new int[bins];
        for (long v : filtered) {
            int idx = (int) Math.min(bins - 1, (v - min) / binW);
            hist[idx]++;
        }
        int maxCount = 0;
        for (int c : hist) maxCount = Math.max(maxCount, c);
        int threshold = Math.max(2, (int) (maxCount * 0.25));
        List<Long> peaks = new ArrayList<>();
        for (int i = 0; i < bins; i++) {
            if (hist[i] >= threshold) {
                long center = min + Math.round((i + 0.5) * binW);
                peaks.add(center);
            }
        }
        if (peaks.isEmpty()) {
            return new long[]{medianInterval(filtered)};
        }
        return peaks.stream().limit(4).mapToLong(Long::longValue).toArray();
    }

    // ==================== 节奏指标与图表序列 ====================

    private RhythmMetrics computeMetrics(List<DetectSignal> signals) {
        RhythmMetrics m = new RhythmMetrics();
        if (signals == null || signals.size() < 2) return m;

        List<DetectSignal> sorted = sortedByTime(signals);
        long[] times = sorted.stream().mapToLong(DetectSignal::getDetectTimesss).toArray();
        long[] intervals = intervalsMs(times);
        if (intervals.length == 0) return m;

        long pri = medianInterval(intervals);
        m.estimatedPriMs = (double) pri;
        m.meanPriMs = round2(mean(intervals));
        m.priStdMs = round2(std(intervals));
        m.priJitterPct = jitterPercent(intervals, pri);
        m.periodMs = (double) pri;

        for (int i = 0; i < intervals.length; i++) {
            long t = times[i + 1];
            double dtSec = intervals[i] / 1000.0;
            m.toaIntervalSeries.add(new SeriesPoint(t, dtSec));
            if (pri > 0) {
                double jitterPct = Math.abs(intervals[i] - pri) * 100.0 / pri;
                m.jitterSeries.add(new SeriesPoint(t, round2(jitterPct)));
            }
        }

        long predicted = times[0];
        m.periodErrorSeries.add(new SeriesPoint(times[0], 0));
        for (int i = 1; i < times.length; i++) {
            predicted += pri;
            m.periodErrorSeries.add(new SeriesPoint(times[i], times[i] - predicted));
        }

        buildPriHistogram(intervals, m);
        m.bursts.addAll(extractBursts(sorted, pri));
        long windowMs = Math.max(1, times[times.length - 1] - times[0]);
        computeBurstAndDutyMetrics(m, pri, times, windowMs, sorted);
        computeSignalStability(sorted, m);
        return m;
    }

    private List<BurstWindow> extractBursts(List<DetectSignal> sorted, long pri) {
        List<BurstWindow> bursts = new ArrayList<>();
        if (sorted.isEmpty()) return bursts;
        long gapThreshold = Math.max(300L, Math.round(pri * BURST_GAP_PRI_FACTOR));
        int start = 0;
        for (int i = 1; i < sorted.size(); i++) {
            long gap = sorted.get(i).getDetectTimesss() - sorted.get(i - 1).getDetectTimesss();
            if (gap > gapThreshold) {
                addBurst(sorted, start, i - 1, bursts);
                start = i;
            }
        }
        addBurst(sorted, start, sorted.size() - 1, bursts);
        return bursts;
    }

    private void addBurst(List<DetectSignal> sorted, int start, int end, List<BurstWindow> bursts) {
        if (start > end) return;
        DetectSignal first = sorted.get(start);
        DetectSignal last = sorted.get(end);
        long t0 = first.getDetectTimesss();
        long t1 = last.getDetectTimesss();
        int count = end - start + 1;
        double duration = burstActiveDurationMs(sorted, start, end, t0, t1);
        double pri = count <= 1 ? 0 : duration / (count - 1);
        double meanFreq = 0;
        for (int i = start; i <= end; i++) {
            meanFreq += sorted.get(i).getFreq();
        }
        meanFreq /= count;
        bursts.add(new BurstWindow(t0, t1, round2(duration), count, round2(pri), round2(meanFreq)));
    }

    /** burst 活跃时长：有 nSignalTime 时累加脉级驻留，否则用 TOA 跨度 */
    private double burstActiveDurationMs(List<DetectSignal> sorted, int start, int end, long t0, long t1) {
        double dwellSum = 0;
        for (int i = start; i <= end; i++) {
            dwellSum += sorted.get(i).getSignalDwellMs();
        }
        if (dwellSum > 0) {
            return Math.max(1, dwellSum);
        }
        return Math.max(1, t1 - t0);
    }

    private List<Double> perPulseDwellMs(List<DetectSignal> sorted) {
        List<Double> dwells = new ArrayList<>();
        for (DetectSignal s : sorted) {
            if (s.getSignalDwellMs() > 0) {
                dwells.add(s.getSignalDwellMs());
            }
        }
        return dwells;
    }

    /**
     * 占空比 = 观测窗内活跃时长 / 观测窗；主周期 = burst 间隔(多 burst) 或脉冲 PRI(单 burst)。
     */
    private void computeBurstAndDutyMetrics(RhythmMetrics m, long pulsePri, long[] times, long windowMs,
                                            List<DetectSignal> sorted) {
        if (m.bursts.isEmpty()) return;
        List<Double> pulseCounts = new ArrayList<>();
        long[] starts = new long[m.bursts.size()];
        long activeMs = 0;
        for (int i = 0; i < m.bursts.size(); i++) {
            BurstWindow b = m.bursts.get(i);
            pulseCounts.add((double) b.getPulseCount());
            starts[i] = b.getStart();
            activeMs += Math.max(1, Math.round(b.getDurationMs()));
            m.burstTimelineSeries.add(new SeriesPoint(b.getStart(), b.getMeanFreq()));
        }

        long burstPeriod = starts.length >= 2 ? medianInterval(intervalsMs(starts)) : pulsePri;
        m.periodMs = (double) burstPeriod;
        m.estimatedPriMs = (double) pulsePri;
        List<Double> pulseDwells = perPulseDwellMs(sorted);
        if (!pulseDwells.isEmpty()) {
            m.burstDurationMeanMs = round2(mean(pulseDwells));
            m.burstDurationStdMs = round2(std(pulseDwells));
        } else {
            List<Double> burstDurations = new ArrayList<>();
            for (BurstWindow b : m.bursts) {
                burstDurations.add(b.getDurationMs());
            }
            m.burstDurationMeanMs = round2(mean(burstDurations));
            m.burstDurationStdMs = round2(std(burstDurations));
        }
        m.pulseCountMean = round2(mean(pulseCounts));

        m.avgDutyCycle = round2(Math.min(100.0, activeMs * 100.0 / windowMs));

        double maxDuty = 0;
        for (int i = 0; i < m.bursts.size(); i++) {
            BurstWindow b = m.bursts.get(i);
            long slotPeriod = windowMs;
            if (i < m.bursts.size() - 1) {
                slotPeriod = Math.max(1, starts[i + 1] - starts[i]);
            } else if (m.bursts.size() >= 2) {
                slotPeriod = Math.max(1, burstPeriod);
            }
            double duty = Math.min(100.0, b.getDurationMs() * 100.0 / slotPeriod);
            maxDuty = Math.max(maxDuty, duty);
            m.dutyCycleTrend.add(new SeriesPoint(b.getStart(), round2(duty)));
        }
        m.maxDutyCycle = round2(maxDuty);
        m.burstConfidence = round2(Math.min(1.0,
                Math.min(1.0, m.bursts.size() / 6.0) * 0.45
                        + Math.max(0, 1.0 - Math.min(1.0, m.burstDurationStdMs / Math.max(1.0, m.burstDurationMeanMs))) * 0.35
                        + Math.min(1.0, m.pulseCountMean / 10.0) * 0.20));

        if (starts.length >= 2) {
            long[] burstIntervals = intervalsMs(starts);
            double meanPeriod = mean(burstIntervals);
            double stdPeriod = std(burstIntervals);
            m.periodStability = round2(Math.max(0, 1.0 - Math.min(1.0, stdPeriod / Math.max(1.0, meanPeriod))));
            m.periodConfidence = round2(Math.min(1.0, m.periodStability * 0.7 + Math.min(1.0, starts.length / 8.0) * 0.3));
        } else {
            m.periodStability = round2(Math.max(0, 1.0 - Math.min(1.0, m.priJitterPct / 100.0)));
            m.periodConfidence = round2(Math.min(0.5, m.periodStability * 0.5));
        }
    }

    private void computeSignalStability(List<DetectSignal> sorted, RhythmMetrics m) {
        List<Double> freqs = sorted.stream().map(DetectSignal::getFreq).collect(Collectors.toList());
        List<Double> azimuths = sorted.stream().map(DetectSignal::getAzimuth).collect(Collectors.toList());
        List<Double> amps = sorted.stream().map(DetectSignal::getSignalLevel).collect(Collectors.toList());
        m.freqDrift = round2(range(freqs));
        m.doaDrift = round2(range(azimuths));
        m.amplitudeMean = round2(mean(amps));
        m.amplitudeStd = round2(std(amps));
        double ampScore = Math.max(0, 1.0 - Math.min(1.0, m.amplitudeStd / Math.max(1.0, Math.abs(m.amplitudeMean))));
        double freqScore = Math.max(0, 1.0 - Math.min(1.0, m.freqDrift / 5.0));
        double doaScore = Math.max(0, 1.0 - Math.min(1.0, m.doaDrift / 30.0));
        m.signalStability = round2(ampScore * 0.35 + freqScore * 0.35 + doaScore * 0.30);
    }

    private void buildPriHistogram(long[] intervals, RhythmMetrics m) {
        long[] filtered = Arrays.stream(intervals)
                .filter(v -> v >= MIN_PRI_MS && v <= MAX_PRI_MS)
                .toArray();
        if (filtered.length == 0) return;
        long min = Arrays.stream(filtered).min().orElse(MIN_PRI_MS);
        long max = Arrays.stream(filtered).max().orElse(MAX_PRI_MS);
        int bins = Math.min(30, Math.max(8, (int) ((max - min) / 1000) + 1));
        double binW = Math.max(1.0, (max - min) / (double) bins);
        int[] hist = new int[bins];
        for (long v : filtered) {
            int idx = (int) Math.min(bins - 1, (v - min) / binW);
            hist[idx]++;
        }
        for (int i = 0; i < bins; i++) {
            if (hist[i] == 0) continue;
            long center = min + Math.round((i + 0.5) * binW);
            m.priHistogram.add(new SeriesPoint(center, hist[i]));
        }
    }

    private double jitterPercent(long[] intervals, long pri) {
        if (pri <= 0 || intervals.length == 0) return 0;
        double sum = 0;
        int n = 0;
        for (long d : intervals) {
            if (d < MIN_PRI_MS || d > MAX_PRI_MS) continue;
            sum += Math.abs(d - pri) * 100.0 / pri;
            n++;
        }
        return n == 0 ? 0 : round2(sum / n);
    }

    private static double mean(long[] values) {
        if (values.length == 0) return 0;
        double sum = 0;
        for (long v : values) sum += v;
        return sum / values.length;
    }

    private static double std(long[] values) {
        if (values.length == 0) return 0;
        double avg = mean(values);
        double sum = 0;
        for (long v : values) {
            double d = v - avg;
            sum += d * d;
        }
        return Math.sqrt(sum / values.length);
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) return 0;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    private static double std(List<Double> values) {
        if (values.isEmpty()) return 0;
        double avg = mean(values);
        double sum = 0;
        for (double v : values) {
            double d = v - avg;
            sum += d * d;
        }
        return Math.sqrt(sum / values.size());
    }

    private static double range(List<Double> values) {
        if (values.isEmpty()) return 0;
        double min = values.get(0);
        double max = values.get(0);
        for (double v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return max - min;
    }

    private static long medianInterval(long[] intervals) {
        long[] copy = Arrays.stream(intervals)
                .filter(v -> v >= MIN_PRI_MS && v <= MAX_PRI_MS)
                .sorted()
                .toArray();
        if (copy.length == 0) {
            copy = Arrays.stream(intervals).sorted().toArray();
        }
        if (copy.length == 0) return 1000;
        return copy[copy.length / 2];
    }

    private static long[] intervalsMs(long[] times) {
        long[] out = new long[times.length - 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.max(0, times[i + 1] - times[i]);
        }
        return out;
    }

    private List<DetectSignal> sortedByTime(List<DetectSignal> signals) {
        return signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(Collectors.toList());
    }

    private List<List<DetectSignal>> singleton(List<DetectSignal> signals) {
        List<List<DetectSignal>> one = new ArrayList<>();
        one.add(signals == null ? new ArrayList<>() : signals);
        return one;
    }

    private List<SeriesPoint> downsample(List<SeriesPoint> series) {
        if (series.size() <= MAX_CHART_POINTS) return series;
        int step = (int) Math.ceil(series.size() / (double) MAX_CHART_POINTS);
        List<SeriesPoint> out = new ArrayList<>();
        for (int i = 0; i < series.size(); i += step) {
            out.add(series.get(i));
        }
        return out;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static class PriTrack {
        final List<DetectSignal> signals = new ArrayList<>();
        long lastToa = -1;
        long priMs = 1000;

        void add(DetectSignal s) {
            signals.add(s);
            lastToa = s.getDetectTimesss();
        }

        void reestimatePri() {
            if (signals.size() < 2) return;
            signals.sort(Comparator.comparingLong(DetectSignal::getDetectTimesss));
            lastToa = signals.get(signals.size() - 1).getDetectTimesss();
            long[] times = signals.stream().mapToLong(DetectSignal::getDetectTimesss).toArray();
            priMs = medianInterval(intervalsMs(times));
        }
    }

    private static class RhythmMetrics {
        Double estimatedPriMs;
        double priJitterPct;
        Double periodMs;
        double periodStability;
        double periodConfidence;
        double burstDurationMeanMs;
        double burstDurationStdMs;
        double pulseCountMean;
        double meanPriMs;
        double priStdMs;
        double avgDutyCycle;
        double maxDutyCycle;
        double burstConfidence;
        double freqDrift;
        double doaDrift;
        double amplitudeMean;
        double amplitudeStd;
        double signalStability;
        final List<SeriesPoint> toaIntervalSeries = new ArrayList<>();
        final List<SeriesPoint> priHistogram = new ArrayList<>();
        final List<SeriesPoint> jitterSeries = new ArrayList<>();
        final List<SeriesPoint> periodErrorSeries = new ArrayList<>();
        final List<SeriesPoint> dutyCycleTrend = new ArrayList<>();
        final List<SeriesPoint> burstTimelineSeries = new ArrayList<>();
        final List<BurstWindow> bursts = new ArrayList<>();
    }
}
