package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.FrequencyBandUtils;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.TrackObservation;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 对确认轨迹进行场景评分，输出 Top-K {@link QualityScene}。
 * <p>
 * <b>核心思路</b>：按 {@link SceneFinderProperties#getSceneFreqBandGapMhz()} 将轨迹分成若干同频块，
 * 每块内独立做滑动时间窗评分，再全局按 score 排序取 Top-K（与建轨/信号分析使用同一频率容差）。
 * </p>
 * <p>
 * <b>得分公式</b>（{@link #buildScoredWindow}，越高越好）：
 * <pre>
 *   score = 2.5×trackBonus + 2.5×separationBonus + 1.5×densityBonus + 0.75×deviceBonus − smoothPenalty
 *   若 medianSeparation &lt; minSeparationDeg 则再 −0.35
 * </pre>
 * 其中 trackBonus=min(轨迹数,8)/8，separationBonus=min(分离度,10)/10，
 * densityBonus=min(轨迹数/minTracksInScene, 2)，deviceBonus 按 distinctDevices 计，
 * smoothPenalty=min(平均平滑度,5)/5。
 * </p>
 */
@Service
public class SceneScorerService {

    /**
     * 发现 Top-K 优质场景。
     *
     * @param tracks 已确认轨迹列表
     * @param props  含窗口长度、步进、Top-K、频段分组等参数
     */
    public List<QualityScene> findTopScenes(List<BearingTrack> tracks, SceneFinderProperties props) {
        if (tracks.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Integer, Double> dominantFreqByTrack = new HashMap<>();
        for (BearingTrack track : tracks) {
            dominantFreqByTrack.put(track.getId(), FrequencyBandUtils.dominantFrequencyMhz(track));
        }

        Map<Integer, Integer> trackToBand = FrequencyBandUtils.assignTracksToBands(
                dominantFreqByTrack, props.getSceneFreqBandGapMhz());

        Map<Integer, List<BearingTrack>> tracksByBand = tracks.stream()
                .filter(t -> trackToBand.containsKey(t.getId()))
                .collect(Collectors.groupingBy(t -> trackToBand.get(t.getId())));

        List<ScoredWindow> allWindows = new ArrayList<>();
        for (Map.Entry<Integer, List<BearingTrack>> entry : tracksByBand.entrySet()) {
            List<BearingTrack> bandTracks = entry.getValue();
            if (bandTracks.size() < props.getMinTracksInScene()) {
                continue;
            }
            int bandId = entry.getKey();
            List<ScoredWindow> bandWindows = scoreSlidingWindows(
                    bandTracks, dominantFreqByTrack, props, bandId);
            // 不在此阶段合并/截断：滑动窗步进小于窗长时相邻窗大量重叠，合并或过早 Top-K 会导致最终只剩极少数场景。
            for (ScoredWindow window : bandWindows) {
                if (window.getTrackIds().size() >= props.getMinTracksInScene()) {
                    allWindows.add(window);
                }
            }
        }

        allWindows.sort(Comparator.comparingDouble(ScoredWindow::getScore).reversed());

        List<QualityScene> result = new ArrayList<>();
        int rank = 1;
        for (ScoredWindow window : allWindows) {
            result.add(QualityScene.trackScene(
                    rank++,
                    window.getWindowStart(),
                    window.getWindowEnd(),
                    round(window.getFreqCenterMhz()),
                    round(window.getFreqMinMhz()),
                    round(window.getFreqMaxMhz()),
                    window.getDistinctFreqCount(),
                    round(window.getScore()),
                    window.getTrackIds().size(),
                    round(window.getSeparation()),
                    round(window.getSmoothness()),
                    window.getTrackIds()
            ));
        }
        return result;
    }

    /** 在单一频段块内生成候选时间窗（滑动或全段单窗）。 */
    private List<ScoredWindow> scoreSlidingWindows(
            List<BearingTrack> tracks,
            Map<Integer, Double> dominantFreqByTrack,
            SceneFinderProperties props,
            int freqBandId
    ) {
        Instant minTime = tracks.stream().map(BearingTrack::startTime).min(Instant::compareTo)
                .orElseThrow(() -> new IllegalStateException("tracks must not be empty"));
        Instant maxTime = tracks.stream().map(BearingTrack::endTime).max(Instant::compareTo)
                .orElseThrow(() -> new IllegalStateException("tracks must not be empty"));

        long spanMillis = maxTime.toEpochMilli() - minTime.toEpochMilli();
        long minSpanMillis = Math.max(1000L, Math.round(props.getMinTrackSeconds() * 1000.0));
        if (spanMillis < minSpanMillis) {
            return Collections.emptyList();
        }

        // 流式全段窗：每频段只评 [min,max] 一次
        if (props.isFullSpanWindow()) {
            List<BearingTrack> inWindow = qualifyingTracks(tracks, minTime, maxTime, props);
            if (inWindow.size() < props.getMinTracksInScene()) {
                return Collections.emptyList();
            }
            return Collections.singletonList(
                    buildScoredWindow(minTime, maxTime, inWindow, dominantFreqByTrack, props, freqBandId));
        }

        long windowMillis = Math.round(props.getWindowSeconds() * 1000.0);
        long stepMillis = Math.max(1, Math.round(props.getWindowStepSeconds() * 1000.0));

        List<ScoredWindow> scored = new ArrayList<>();
        for (long startMillis = minTime.toEpochMilli();
             startMillis + windowMillis <= maxTime.toEpochMilli() + stepMillis;
             startMillis += stepMillis) {
            Instant windowStart = Instant.ofEpochMilli(startMillis);
            Instant windowEnd = Instant.ofEpochMilli(startMillis + windowMillis);

            List<BearingTrack> inWindow = qualifyingTracks(tracks, windowStart, windowEnd, props);
            if (inWindow.size() < props.getMinTracksInScene()) {
                continue;
            }

            scored.add(buildScoredWindow(windowStart, windowEnd, inWindow, dominantFreqByTrack, props, freqBandId));
        }
        return scored;
    }

    /** 按时间排序后合并重叠/相邻且轨迹相似的窗口（仅同一 freqBandId）。 */
    private List<ScoredWindow> mergeOverlappingWindows(List<ScoredWindow> windows, SceneFinderProperties props) {
        if (windows.isEmpty()) {
            return Collections.emptyList();
        }

        long mergeGapMillis = Math.round(props.getMergeMaxGapSeconds() * 1000.0);
        List<ScoredWindow> sorted = windows.stream()
                .sorted(Comparator.comparingLong(w -> w.getWindowStart().toEpochMilli()))
                .collect(Collectors.toList());

        List<ScoredWindow> merged = new ArrayList<>();
        ScoredWindow current = sorted.get(0);

        for (int i = 1; i < sorted.size(); i++) {
            ScoredWindow next = sorted.get(i);
            if (shouldMerge(current, next, mergeGapMillis)) {
                current = unionWindows(current, next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /**
     * 是否合并两窗口：必须同一频段；无重叠时空隙 ≤ mergeMaxGapSeconds；
     * 有重叠时轨迹 ID 集合 Jaccard ≥ 0.35。
     */
    private boolean shouldMerge(ScoredWindow a, ScoredWindow b, long mergeGapMillis) {
        if (a.getFreqBandId() != b.getFreqBandId()) {
            return false;
        }

        long gap = b.getWindowStart().toEpochMilli() - a.getWindowEnd().toEpochMilli();
        long overlapStart = Math.max(a.getWindowStart().toEpochMilli(), b.getWindowStart().toEpochMilli());
        long overlapEnd = Math.min(a.getWindowEnd().toEpochMilli(), b.getWindowEnd().toEpochMilli());
        long overlap = Math.max(0, overlapEnd - overlapStart);

        if (overlap <= 0 && gap > mergeGapMillis) {
            return false;
        }
        if (overlap <= 0) {
            return true;
        }

        Set<Integer> union = new TreeSet<>();
        union.addAll(a.getTrackIds());
        union.addAll(b.getTrackIds());
        long shared = a.getTrackIds().stream().filter(b.getTrackIds()::contains).count();
        double jaccard = union.isEmpty() ? 0 : shared / (double) union.size();
        return jaccard >= 0.35;
    }

    /** 合并两窗口时间范围与轨迹 ID 并集；score 取较大者，指标稍后由 recomputeWindow 重算。 */
    private ScoredWindow unionWindows(ScoredWindow a, ScoredWindow b) {
        Instant start = a.getWindowStart().isBefore(b.getWindowStart()) ? a.getWindowStart() : b.getWindowStart();
        Instant end = a.getWindowEnd().isAfter(b.getWindowEnd()) ? a.getWindowEnd() : b.getWindowEnd();

        Set<Integer> trackIds = new TreeSet<>();
        trackIds.addAll(a.getTrackIds());
        trackIds.addAll(b.getTrackIds());

        return new ScoredWindow(
                start,
                end,
                Math.max(a.getScore(), b.getScore()),
                trackIds.size(),
                0,
                0,
                new ArrayList<>(trackIds),
                0,
                0,
                0,
                a.getDistinctFreqCount() + b.getDistinctFreqCount(),
                a.getFreqBandId()
        );
    }

    /** 合并后按最终时间窗与轨迹集合重新计算得分与频段统计。 */
    private ScoredWindow recomputeWindow(
            ScoredWindow merged,
            List<BearingTrack> bandTracks,
            Map<Integer, Double> dominantFreqByTrack,
            SceneFinderProperties props,
            int freqBandId
    ) {
        List<BearingTrack> inWindow = qualifyingTracks(bandTracks, merged.getWindowStart(), merged.getWindowEnd(), props);
        if (inWindow.isEmpty()) {
            return merged;
        }
        return buildScoredWindow(
                merged.getWindowStart(), merged.getWindowEnd(), inWindow, dominantFreqByTrack, props, freqBandId);
    }

    /** 计算单个时间窗的综合得分与输出字段。 */
    private ScoredWindow buildScoredWindow(
            Instant windowStart,
            Instant windowEnd,
            List<BearingTrack> inWindow,
            Map<Integer, Double> dominantFreqByTrack,
            SceneFinderProperties props,
            int freqBandId
    ) {
        double separation = medianSeparation(inWindow, windowStart, windowEnd, props.getFrameSeconds());
        double smoothness = inWindow.stream().mapToDouble(BearingTrack::smoothnessScore).average().orElse(999);

        List<Double> freqs = inWindow.stream()
                .map(t -> dominantFreqByTrack.getOrDefault(t.getId(), 0.0))
                .filter(f -> f > 0)
                .sorted()
                .collect(Collectors.toList());
        double freqMin = freqs.isEmpty() ? 0 : freqs.get(0);
        double freqMax = freqs.isEmpty() ? 0 : freqs.get(freqs.size() - 1);
        double freqCenter = freqs.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        int distinctDevices = countFrequencyDevices(freqs, props.getFreqClusterGapMhz());

        double trackBonus = Math.min(inWindow.size(), 8) / 8.0;
        double separationBonus = Math.min(separation / 10.0, 1.0);
        double smoothPenalty = Math.min(smoothness / 5.0, 1.0);
        double densityBonus = Math.min(inWindow.size() / (double) props.getMinTracksInScene(), 2.0);
        double deviceBonus = Math.min(distinctDevices / (double) props.getMinTracksInScene(), 1.5);

        double score = 2.5 * trackBonus
                + 2.5 * separationBonus
                + 1.5 * densityBonus
                + 0.75 * deviceBonus
                - 1.0 * smoothPenalty;

        if (separation < props.getMinSeparationDeg()) {
            score -= 0.35;
        }

        List<Integer> trackIds = inWindow.stream().map(BearingTrack::getId).sorted().collect(Collectors.toList());

        return new ScoredWindow(
                windowStart,
                windowEnd,
                score,
                inWindow.size(),
                separation,
                smoothness,
                trackIds,
                freqCenter,
                freqMin,
                freqMax,
                distinctDevices,
                freqBandId
        );
    }

    /**
     * 按主频间隙统计“设备数”：已排序的主频列表，相邻差 &gt; gapMhz 则计为新设备。
     */
    private int countFrequencyDevices(List<Double> sortedFreqs, double gapMhz) {
        if (sortedFreqs.isEmpty()) {
            return 0;
        }
        int devices = 1;
        double last = sortedFreqs.get(0);
        for (int i = 1; i < sortedFreqs.size(); i++) {
            if (sortedFreqs.get(i) - last > gapMhz) {
                devices++;
                last = sortedFreqs.get(i);
            }
        }
        return devices;
    }

    /**
     * 时间窗内“有效”轨迹：与窗有交集，且重叠时长 ≥ minTrackSeconds。
     */
    private List<BearingTrack> qualifyingTracks(
            List<BearingTrack> tracks,
            Instant windowStart,
            Instant windowEnd,
            SceneFinderProperties props
    ) {
        long minOverlapMillis = Math.round(props.getMinTrackSeconds() * 1000.0);

        return tracks.stream()
                .filter(track -> overlaps(track, windowStart, windowEnd))
                .filter(track -> overlapMillis(track, windowStart, windowEnd) >= minOverlapMillis)
                .collect(Collectors.toList());
    }

    private long overlapMillis(BearingTrack track, Instant windowStart, Instant windowEnd) {
        Instant trackStart = track.startTime();
        Instant trackEnd = track.endTime();
        if (trackStart == null || trackEnd == null) {
            return 0;
        }
        long overlapStart = Math.max(trackStart.toEpochMilli(), windowStart.toEpochMilli());
        long overlapEnd = Math.min(trackEnd.toEpochMilli(), windowEnd.toEpochMilli());
        return Math.max(0, overlapEnd - overlapStart);
    }

    private boolean overlaps(BearingTrack track, Instant windowStart, Instant windowEnd) {
        return !track.endTime().isBefore(windowStart) && !track.startTime().isAfter(windowEnd);
    }

    /**
     * 中位方位分离度：窗内每帧收集各轨迹方位，算相邻方位最小间隔，再对所有帧取中位数。
     * 反映“同一时刻多目标是否拉开”。
     */
    private double medianSeparation(List<BearingTrack> tracks, Instant windowStart, Instant windowEnd, double frameSeconds) {
        long frameMillis = Math.max(1, Math.round(frameSeconds * 1000.0));
        Map<Long, List<Double>> frameBearings = new HashMap<>();

        for (BearingTrack track : tracks) {
            for (TrackObservation obs : track.getObservations()) {
                if (obs.getTime().isBefore(windowStart) || obs.getTime().isAfter(windowEnd)) {
                    continue;
                }
                long frameKey = obs.getTime().toEpochMilli() / frameMillis;
                frameBearings.computeIfAbsent(frameKey, ignored -> new ArrayList<>())
                        .add(BearingMath.normalize360(obs.getBearingDeg()));
            }
        }

        List<Double> separations = new ArrayList<>();
        for (List<Double> bearings : frameBearings.values()) {
            if (bearings.size() < 2) {
                continue;
            }
            bearings.sort(Double::compareTo);
            double minGap = Double.MAX_VALUE;
            for (int i = 1; i < bearings.size(); i++) {
                minGap = Math.min(minGap, Math.abs(BearingMath.shortestDelta(bearings.get(i - 1), bearings.get(i))));
            }
            if (minGap < Double.MAX_VALUE) {
                separations.add(minGap);
            }
        }

        if (separations.isEmpty()) {
            return 0;
        }
        separations.sort(Double::compareTo);
        return separations.get(separations.size() / 2);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static final class ScoredWindow {
        private final Instant windowStart;
        private final Instant windowEnd;
        private final double score;
        private final int trackCount;
        private final double separation;
        private final double smoothness;
        private final List<Integer> trackIds;
        private final double freqCenterMhz;
        private final double freqMinMhz;
        private final double freqMaxMhz;
        private final int distinctFreqCount;
        private final int freqBandId;

        ScoredWindow(Instant windowStart, Instant windowEnd, double score, int trackCount,
                     double separation, double smoothness, List<Integer> trackIds,
                     double freqCenterMhz, double freqMinMhz, double freqMaxMhz,
                     int distinctFreqCount, int freqBandId) {
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.score = score;
            this.trackCount = trackCount;
            this.separation = separation;
            this.smoothness = smoothness;
            this.trackIds = trackIds;
            this.freqCenterMhz = freqCenterMhz;
            this.freqMinMhz = freqMinMhz;
            this.freqMaxMhz = freqMaxMhz;
            this.distinctFreqCount = distinctFreqCount;
            this.freqBandId = freqBandId;
        }

        Instant getWindowStart() { return windowStart; }
        Instant getWindowEnd() { return windowEnd; }
        double getScore() { return score; }
        int getTrackCount() { return trackCount; }
        double getSeparation() { return separation; }
        double getSmoothness() { return smoothness; }
        List<Integer> getTrackIds() { return trackIds; }
        double getFreqCenterMhz() { return freqCenterMhz; }
        double getFreqMinMhz() { return freqMinMhz; }
        double getFreqMaxMhz() { return freqMaxMhz; }
        int getDistinctFreqCount() { return distinctFreqCount; }
        int getFreqBandId() { return freqBandId; }
    }
}
