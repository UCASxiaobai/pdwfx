package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.FrequencyBandUtils;
import com.scenefinder.model.TrackObservation;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 由检测点构建方位轨迹。
 * <p>
 * 流程（每个窄频段内独立执行）：
 * <ol>
 *   <li>按 {@link SceneFinderProperties#getFrameSeconds()} 时间分帧</li>
 *   <li>帧内按 {@link SceneFinderProperties#getBearingClusterGapDeg()} 方位聚类</li>
 *   <li>跨帧：预测方位 + {@link SceneFinderProperties#getAssociationGateDeg()} 方位门控
 *       + {@link SceneFinderProperties#getFreqClusterGapMhz()} 频率门控，贪心匹配</li>
 *   <li>丢帧超过 {@link SceneFinderProperties#getMaxMissedFrames()} 则结束轨迹</li>
 *   <li>过滤：hits &ge; minTrackPoints 且 duration &ge; minTrackSeconds</li>
 * </ol>
 * 宽频段输入会先按 freqClusterGapMhz 拆成多个频段，避免不同频率互相串轨。
 * </p>
 */
@Service
public class TrackBuilderService {

    /**
     * 对全部检测点建轨。先 {@link FrequencyBandUtils#partitionPoints} 分频段，再逐段建轨并合并 ID。
     * 若开启 {@link SceneFinderProperties#isDutyPriorityEnabled()}：同频粗方位估占空比后按
     * GROUND→AWACS→AIR 优先级建轨，低优先级不抢已占用点。
     */
    public List<BearingTrack> buildTracks(List<DetectionPoint> points, SceneFinderProperties props) {
        if (points.isEmpty()) {
            return Collections.emptyList();
        }
        if (props.isDutyPriorityEnabled()) {
            return buildTracksDutyPriority(points, props);
        }
        return buildTracksLegacy(points, props);
    }

    private List<BearingTrack> buildTracksLegacy(List<DetectionPoint> points, SceneFinderProperties props) {
        List<List<DetectionPoint>> freqBands = FrequencyBandUtils.partitionPoints(
                points, props.getFreqClusterGapMhz());
        List<BearingTrack> allTracks = new ArrayList<>();
        int nextTrackId = 1;
        for (List<DetectionPoint> bandPoints : freqBands) {
            nextTrackId = buildTracksForBand(bandPoints, props, nextTrackId, allTracks);
        }
        return allTracks.stream()
                .sorted(Comparator.comparing(BearingTrack::startTime))
                .collect(Collectors.toList());
    }

    private List<BearingTrack> buildTracksDutyPriority(List<DetectionPoint> points, SceneFinderProperties props) {
        List<List<DetectionPoint>> freqBands = FrequencyBandUtils.partitionPoints(
                points, props.getFreqClusterGapMhz());
        List<BearingTrack> allTracks = new ArrayList<>();
        int nextTrackId = 1;
        Set<String> claimed = new HashSet<>();
        for (List<DetectionPoint> bandPoints : freqBands) {
            List<DutyBucket> buckets = coarseDutyBuckets(bandPoints, props);
            buckets.sort((a, b) -> {
                int pa = priorityRank(a.suggestedType);
                int pb = priorityRank(b.suggestedType);
                if (pa != pb) {
                    return Integer.compare(pa, pb);
                }
                return Double.compare(b.dutyPct, a.dutyPct);
            });
            for (DutyBucket bucket : buckets) {
                List<DetectionPoint> available = new ArrayList<>();
                for (DetectionPoint p : bucket.points) {
                    if (!claimed.contains(p.pointKey())) {
                        available.add(p);
                    }
                }
                if (available.isEmpty()) {
                    continue;
                }
                SceneFinderProperties gated = cloneWithAssociationGate(props, gateForType(bucket.suggestedType, props));
                List<BearingTrack> built = new ArrayList<>();
                nextTrackId = buildTracksForBand(available, gated, nextTrackId, built);
                for (BearingTrack t : built) {
                    t.setSuggestedPlatformType(bucket.suggestedType);
                    t.setCoarseDutyPct(bucket.dutyPct);
                    for (TrackObservation o : t.getObservations()) {
                        claimed.add(o.getSourceFile() + "|" + o.getRowIndex());
                    }
                    allTracks.add(t);
                }
            }
        }
        return allTracks.stream()
                .sorted(Comparator.comparing(BearingTrack::startTime))
                .collect(Collectors.toList());
    }

    private static int priorityRank(String type) {
        if ("GROUND".equals(type)) return 0;
        if ("AWACS".equals(type)) return 1;
        return 2;
    }

    private static double gateForType(String type, SceneFinderProperties props) {
        if ("GROUND".equals(type)) {
            return props.getDutyGroundAssociationGateDeg();
        }
        if ("AWACS".equals(type)) {
            return props.getDutyAwacsAssociationGateDeg();
        }
        return props.getDutyAirAssociationGateDeg() > 0
                ? props.getDutyAirAssociationGateDeg()
                : props.getAssociationGateDeg();
    }

    private static SceneFinderProperties cloneWithAssociationGate(SceneFinderProperties src, double gate) {
        SceneFinderProperties p = new SceneFinderProperties();
        p.copyFrom(src);
        p.setAssociationGateDeg(gate);
        p.setBearingClusterGapDeg(Math.min(src.getBearingClusterGapDeg(), Math.max(1.0, gate)));
        return p;
    }

    private List<DutyBucket> coarseDutyBuckets(List<DetectionPoint> bandPoints, SceneFinderProperties props) {
        List<DetectionPoint> sorted = new ArrayList<>(bandPoints);
        sorted.sort(Comparator.comparing(DetectionPoint::getTime)
                .thenComparingDouble(DetectionPoint::getBearingDeg));
        double gap = Math.max(0.5, props.getDutyCoarseBearingGapDeg());
        List<List<DetectionPoint>> clusters = new ArrayList<>();
        for (DetectionPoint p : sorted) {
            boolean placed = false;
            for (List<DetectionPoint> c : clusters) {
                double ref = c.stream().mapToDouble(DetectionPoint::getBearingDeg).average().orElse(p.getBearingDeg());
                if (Math.abs(BearingMath.shortestDelta(ref, p.getBearingDeg())) <= gap) {
                    c.add(p);
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                List<DetectionPoint> c = new ArrayList<>();
                c.add(p);
                clusters.add(c);
            }
        }
        List<DutyBucket> out = new ArrayList<>();
        for (List<DetectionPoint> c : clusters) {
            double duty = estimateDutyPct(c);
            String type;
            if (duty >= props.getDutyGroundMinPct()) {
                type = "GROUND";
            } else if (duty >= props.getDutyAwacsMinPct()) {
                type = "AWACS";
            } else {
                type = "AIR";
            }
            out.add(new DutyBucket(c, duty, type));
        }
        return out;
    }

    /** 活跃驻留累计 / 观测窗 → 占空比 % */
    static double estimateDutyPct(List<DetectionPoint> pts) {
        if (pts == null || pts.size() < 2) {
            return 0d;
        }
        long t0 = Long.MAX_VALUE;
        long t1 = Long.MIN_VALUE;
        double active = 0d;
        for (DetectionPoint p : pts) {
            long ms = p.getTime().toEpochMilli();
            t0 = Math.min(t0, ms);
            t1 = Math.max(t1, ms);
            active += Math.max(0d, p.getSignalDwellMs());
        }
        long span = t1 - t0;
        if (span <= 0L) {
            return 0d;
        }
        if (active <= 0d) {
            // 无驻留列时用点数密度近似：假设每点有效 50ms
            active = pts.size() * 50.0;
        }
        return Math.min(100.0, active * 100.0 / span);
    }

    private static final class DutyBucket {
        final List<DetectionPoint> points;
        final double dutyPct;
        final String suggestedType;

        DutyBucket(List<DetectionPoint> points, double dutyPct, String suggestedType) {
            this.points = points;
            this.dutyPct = dutyPct;
            this.suggestedType = suggestedType;
        }
    }

    /**
     * 单一频段内的建轨主循环。
     *
     * @param nextTrackId 下一条新轨迹的起始 ID
     * @param sink        完成的轨迹写入此列表
     * @return 下一段频段应使用的起始 trackId
     */
    private int buildTracksForBand(
            List<DetectionPoint> points,
            SceneFinderProperties props,
            int nextTrackId,
            List<BearingTrack> sink
    ) {
        long frameMillis = Math.max(1, Math.round(props.getFrameSeconds() * 1000.0));
        Map<Long, List<DetectionPoint>> frames = new HashMap<>();
        for (DetectionPoint point : points) {
            long frameKey = point.getTime().toEpochMilli() / frameMillis;
            frames.computeIfAbsent(frameKey, ignored -> new ArrayList<>()).add(point);
        }

        List<Long> frameKeys = new ArrayList<>(frames.keySet());
        frameKeys.sort(Long::compareTo);

        List<BearingTrack> active = new ArrayList<>();
        List<BearingTrack> finished = new ArrayList<>();

        for (Long frameKey : frameKeys) {
            List<FrameCluster> clusters = clusterFrame(frames.get(frameKey), props.getBearingClusterGapDeg());
            Instant frameTime = Instant.ofEpochMilli(frameKey * frameMillis);
            nextTrackId = associateFrame(active, clusters, frameTime, props, nextTrackId);

            List<BearingTrack> stillActive = new ArrayList<>();
            for (BearingTrack track : active) {
                if (track.getMissedFrames() > props.getMaxMissedFrames()) {
                    finished.add(track);
                } else {
                    stillActive.add(track);
                }
            }
            active = stillActive;
        }

        finished.addAll(active);
        for (BearingTrack track : finished) {
            List<BearingTrack> segments = TrackQualityUtil.splitAndFilter(track, props, nextTrackId);
            for (BearingTrack seg : segments) {
                sink.add(seg);
                nextTrackId = Math.max(nextTrackId, seg.getId() + 1);
            }
        }
        return nextTrackId;
    }

    /**
     * 当前帧：已有 active 轨迹与 frame 簇做最优一对一匹配（按方位代价升序贪心），
     * 未匹配簇开新轨迹，未匹配轨迹增加 missedFrames。
     */
    private int associateFrame(
            List<BearingTrack> active,
            List<FrameCluster> clusters,
            Instant frameTime,
            SceneFinderProperties props,
            int nextTrackId
    ) {
        Set<Integer> usedClusters = new HashSet<>();
        Set<BearingTrack> matchedTracks = new HashSet<>();

        List<MatchCandidate> candidates = new ArrayList<>();
        for (int ti = 0; ti < active.size(); ti++) {
            BearingTrack track = active.get(ti);
            double predicted = predictBearing(track, frameTime);
            double trackFreq = FrequencyBandUtils.dominantFrequencyMhz(track);
            for (int ci = 0; ci < clusters.size(); ci++) {
                FrameCluster cluster = clusters.get(ci);
                double delta = Math.abs(BearingMath.shortestDelta(predicted, cluster.getBearingDeg()));
                if (delta > props.getAssociationGateDeg()) {
                    continue;
                }
                double dt = Math.max(0.001, (frameTime.toEpochMilli() - track.getLastTime().toEpochMilli()) / 1000.0);
                if (delta / dt > props.getMaxTrackBearingRateDegPerSec()) {
                    continue;
                }
                // 频率门控：避免宽频段残留的不同频率点并入同一条轨迹
                if (trackFreq > 0 && cluster.getFrequencyMhz() > 0
                        && Math.abs(trackFreq - cluster.getFrequencyMhz()) > props.getFreqClusterGapMhz()) {
                    continue;
                }
                candidates.add(new MatchCandidate(ti, ci, delta));
            }
        }

        candidates.sort(Comparator.comparingDouble(MatchCandidate::getCost));
        for (MatchCandidate candidate : candidates) {
            if (matchedTracks.contains(active.get(candidate.getTrackIndex()))
                    || usedClusters.contains(candidate.getClusterIndex())) {
                continue;
            }
            BearingTrack track = active.get(candidate.getTrackIndex());
            FrameCluster cluster = clusters.get(candidate.getClusterIndex());
            updateTrack(track, cluster, frameTime);
            matchedTracks.add(track);
            usedClusters.add(candidate.getClusterIndex());
        }

        for (BearingTrack track : active) {
            if (!matchedTracks.contains(track)) {
                track.setMissedFrames(track.getMissedFrames() + 1);
            }
        }

        for (int ci = 0; ci < clusters.size(); ci++) {
            if (usedClusters.contains(ci)) {
                continue;
            }
            FrameCluster cluster = clusters.get(ci);
            BearingTrack track = newTrack(cluster, frameTime, nextTrackId);
            nextTrackId++;
            active.add(track);
        }
        return nextTrackId;
    }

    private BearingTrack newTrack(FrameCluster cluster, Instant frameTime, int trackId) {
        BearingTrack track = new BearingTrack();
        track.setId(trackId);
        track.setBearingDeg(cluster.getBearingDeg());
        track.setVelocityDegPerSec(0);
        track.setLastTime(frameTime);
        track.setHits(1);
        track.setMissedFrames(0);
        for (DetectionPoint point : cluster.getPoints()) {
            track.getObservations().add(new TrackObservation(
                    point.getTime(), point.getBearingDeg(), point.getFrequencyMhz(),
                    point.getSourceFile(), point.getRowIndex()));
        }
        return track;
    }

    /** 更新方位与指数平滑角速度（0.7 旧 + 0.3 新），并追加观测。 */
    private void updateTrack(BearingTrack track, FrameCluster cluster, Instant frameTime) {
        double dt = Math.max(0.001, (frameTime.toEpochMilli() - track.getLastTime().toEpochMilli()) / 1000.0);
        double newBearing = cluster.getBearingDeg();
        double velocity = BearingMath.shortestDelta(track.getBearingDeg(), newBearing) / dt;

        track.setVelocityDegPerSec(0.7 * track.getVelocityDegPerSec() + 0.3 * velocity);
        track.setBearingDeg(newBearing);
        track.setLastTime(frameTime);
        track.setHits(track.getHits() + 1);
        track.setMissedFrames(0);

        for (DetectionPoint point : cluster.getPoints()) {
            track.getObservations().add(new TrackObservation(
                    point.getTime(), point.getBearingDeg(), point.getFrequencyMhz(),
                    point.getSourceFile(), point.getRowIndex()));
        }
    }

    /** 匀速模型预测当前帧方位。 */
    private double predictBearing(BearingTrack track, Instant frameTime) {
        double dt = Math.max(0, (frameTime.toEpochMilli() - track.getLastTime().toEpochMilli()) / 1000.0);
        return track.getBearingDeg() + track.getVelocityDegPerSec() * dt;
    }

    /**
     * 单帧内按方位排序后，相邻点间隙 &gt; gapDeg 则分段；每段取方位均值作为一个簇。
     */
    private List<FrameCluster> clusterFrame(List<DetectionPoint> points, double gapDeg) {
        if (points == null || points.isEmpty()) {
            return Collections.emptyList();
        }

        List<DetectionPoint> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingDouble(p -> BearingMath.normalize360(p.getBearingDeg())));

        List<List<DetectionPoint>> groups = new ArrayList<>();
        List<DetectionPoint> current = new ArrayList<>();
        current.add(sorted.get(0));

        for (int i = 1; i < sorted.size(); i++) {
            DetectionPoint prev = sorted.get(i - 1);
            DetectionPoint now = sorted.get(i);
            double gap = Math.abs(BearingMath.shortestDelta(prev.getBearingDeg(), now.getBearingDeg()));
            if (gap > gapDeg) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(now);
        }
        groups.add(current);

        List<FrameCluster> clusters = new ArrayList<>();
        for (List<DetectionPoint> group : groups) {
            double sum = 0;
            for (DetectionPoint point : group) {
                sum += BearingMath.normalize360(point.getBearingDeg());
            }
            clusters.add(new FrameCluster(sum / group.size(), group));
        }
        return clusters;
    }

    private static final class FrameCluster {
        private final double bearingDeg;
        private final double frequencyMhz;
        private final List<DetectionPoint> points;

        FrameCluster(double bearingDeg, List<DetectionPoint> points) {
            this(bearingDeg, FrequencyBandUtils.clusterFrequencyMhz(points), points);
        }

        FrameCluster(double bearingDeg, double frequencyMhz, List<DetectionPoint> points) {
            this.bearingDeg = bearingDeg;
            this.frequencyMhz = frequencyMhz;
            this.points = points;
        }

        double getBearingDeg() {
            return bearingDeg;
        }

        double getFrequencyMhz() {
            return frequencyMhz;
        }

        List<DetectionPoint> getPoints() {
            return points;
        }
    }

    private static final class MatchCandidate {
        private final int trackIndex;
        private final int clusterIndex;
        private final double cost;

        MatchCandidate(int trackIndex, int clusterIndex, double cost) {
            this.trackIndex = trackIndex;
            this.clusterIndex = clusterIndex;
            this.cost = cost;
        }

        int getTrackIndex() {
            return trackIndex;
        }

        int getClusterIndex() {
            return clusterIndex;
        }

        double getCost() {
            return cost;
        }
    }
}
