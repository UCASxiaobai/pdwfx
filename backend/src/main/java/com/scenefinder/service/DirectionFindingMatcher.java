package com.scenefinder.service;

import com.scenefinder.model.BearingMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 测向与定位航迹关联。
 * <p>
 * 单帧：满足方位/距离门限；未开启 {@link MatchConfig#ignoreTimeDimension} 时，
 * 测向与定位时差还须 ≤ {@link MatchConfig#maxTimeDeltaMs}。
 * 精细批级：同一测向批内须存在连续 {@link MatchConfig#sustainDurationMs} 毫秒的时间窗，
 * 窗内每条测向均指向同一目标，方可锁定关联。
 * 粗匹配批级：不要求连续时间窗，按整批计票，命中帧数 ≥ {@link MatchConfig#minHits} 即锁定。
 * 默认 {@link MatchConfig#exclusiveAssign}：跨批按命中次数、平均垂直距贪心一对一，避免多批锁同一目标。
 */
public class DirectionFindingMatcher {

    // ---------- 数据模型 ----------

    public static class Measurement {
        public long time;
        public double rxLon;
        public double rxLat;
        public double bearing;
        /** 频率 Hz */
        public int freq;
        /** 测向批号；无则按 freq 虚拟分组 */
        public String batchId;
        public String id;

        public Measurement(long time, double rxLon, double rxLat, double bearing, int freq) {
            this(time, rxLon, rxLat, bearing, freq, null);
        }

        public Measurement(long time, double rxLon, double rxLat, double bearing, int freq, String batchId) {
            this.time = time;
            this.rxLon = rxLon;
            this.rxLat = rxLat;
            this.bearing = bearing;
            this.freq = freq;
            this.batchId = batchId;
        }

        public String resolvedBatchId() {
            if (batchId != null && !batchId.trim().isEmpty()) {
                return batchId.trim();
            }
            return "freq:" + freq;
        }
    }

    public static class DeviceTrack {
        public long time;
        public double lon;
        public double lat;
        public String deviceId;

        public DeviceTrack(String deviceId, long time, double lon, double lat) {
            this.deviceId = deviceId;
            this.time = time;
            this.lon = lon;
            this.lat = lat;
        }
    }

    public static class DeviceTrajectory {
        public String deviceId;
        public final List<DeviceTrack> trackPoints = new ArrayList<>();
        private boolean sorted;

        public DeviceTrajectory(String deviceId) {
            this.deviceId = deviceId;
        }

        public void addPoint(DeviceTrack point) {
            trackPoints.add(point);
            sorted = false;
        }

        /** 同一时刻仅保留最后一个点（避免 burst 重复时刻导致插值偏差）。 */
        public void upsertPoint(DeviceTrack point) {
            ensureSorted();
            for (int i = trackPoints.size() - 1; i >= 0; i--) {
                if (trackPoints.get(i).time == point.time) {
                    trackPoints.set(i, point);
                    return;
                }
            }
            trackPoints.add(point);
        }

        private void ensureSorted() {
            if (!sorted) {
                trackPoints.sort((a, b) -> Long.compare(a.time, b.time));
                sorted = true;
            }
        }

        /** 第一个 time ≥ t 的下标；若无则返回 size。 */
        int lowerBound(long t) {
            ensureSorted();
            int lo = 0;
            int hi = trackPoints.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (trackPoints.get(mid).time < t) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /** 第一个 time > t 的下标；若无则返回 size。 */
        int upperBound(long t) {
            ensureSorted();
            int lo = 0;
            int hi = trackPoints.size();
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (trackPoints.get(mid).time <= t) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /**
         * 线性插值该时刻设备位置。
         *
         * @param allowExtrapolation false 且 requireCoverage 时，超出首尾返回 null
         */
        public double[] getPositionAtTime(long time, boolean requireCoverage, boolean allowExtrapolation) {
            ensureSorted();
            if (trackPoints.isEmpty()) {
                return null;
            }
            long t0 = trackPoints.get(0).time;
            long t1 = trackPoints.get(trackPoints.size() - 1).time;
            if (requireCoverage && !allowExtrapolation && (time < t0 || time > t1)) {
                return null;
            }
            if (time <= t0) {
                DeviceTrack p = trackPoints.get(0);
                return new double[]{p.lon, p.lat};
            }
            if (time >= t1) {
                DeviceTrack p = trackPoints.get(trackPoints.size() - 1);
                return new double[]{p.lon, p.lat};
            }
            for (int i = 0; i < trackPoints.size() - 1; i++) {
                DeviceTrack p1 = trackPoints.get(i);
                DeviceTrack p2 = trackPoints.get(i + 1);
                if (time >= p1.time && time <= p2.time) {
                    double ratio = (double) (time - p1.time) / (p2.time - p1.time);
                    double lon = p1.lon + (p2.lon - p1.lon) * ratio;
                    double lat = p1.lat + (p2.lat - p1.lat) * ratio;
                    return new double[]{lon, lat};
                }
            }
            return null;
        }

        /** 与 queryTime 最近的航迹点时间差（毫秒）；无点则 {@link Long#MAX_VALUE}。 */
        public long nearestTimeDeltaMs(long queryTime) {
            ensureSorted();
            if (trackPoints.isEmpty()) {
                return Long.MAX_VALUE;
            }
            long best = Long.MAX_VALUE;
            for (DeviceTrack p : trackPoints) {
                best = Math.min(best, Math.abs(p.time - queryTime));
            }
            return best;
        }

        /** 取时间上最接近 queryTime 的航迹点坐标。 */
        public double[] getPositionAtNearestTime(long queryTime) {
            ensureSorted();
            if (trackPoints.isEmpty()) {
                return null;
            }
            DeviceTrack best = trackPoints.get(0);
            long bestDelta = Math.abs(best.time - queryTime);
            for (DeviceTrack p : trackPoints) {
                long delta = Math.abs(p.time - queryTime);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = p;
                }
            }
            return new double[]{best.lon, best.lat};
        }
    }

    public static class MatchConfig {
        /** @deprecated 保留 API 兼容；匹配以 {@link #sustainDurationMs} 为准 */
        @Deprecated
        public int minFrames = 3;
        /** @deprecated 保留 API 兼容 */
        @Deprecated
        public int maxHistoryFrames = 20;
        /** @deprecated 保留 API 兼容 */
        @Deprecated
        public double correctRate = 0.6;
        public double angleThresholdDeg = 0.5;
        public double distanceThresholdM = 400_000;
        /** 测向时刻与最近定位点时刻允许的最大时差（毫秒） */
        public long maxTimeDeltaMs = 2000L;
        /** 为 true 时单帧匹配不校验测向–定位时差，仅按方位/距离门限关联 */
        public boolean ignoreTimeDimension = false;
        /** 同一测向批内须持续指向同一目标的最短时间（毫秒）；仅精细模式生效 */
        public long sustainDurationMs = 10_000L;
        /** 为 true 时走粗匹配：宽时差下整批计票，不要求连续持续窗 */
        public boolean coarseMatch = false;
        /** 粗匹配锁定所需「指向同一目标」的帧数；1 表示只要指向即匹配 */
        public int minHits = 1;
        /** 三次方均值：锚点之后时间窗（秒） */
        public double meanSquareBeforeSec = 60;
        /** 三次方均值：锚点之前时间窗（秒） */
        public double meanSquareAfterSec = 15;
        public boolean requireTrackCoverage = true;
        public boolean allowTrackExtrapolation = false;
        public boolean retroactiveOnConfirm = true;
        public boolean enableBearingChangeFilter = false;
        public double bearingChangeToleranceDeg = 10.0;
        /**
         * 跨批一对一：每个测向批最多一个定位目标，每个定位目标最多一个测向批。
         * 按命中次数、平均垂直距、方位误差三次方均值贪心分配。
         */
        public boolean exclusiveAssign = true;
    }

    private static final class PointAlignment {
        final double geomBearingDeg;
        final double angleErrorDeg;
        final double distanceM;
        final double crossTrackM;

        PointAlignment(double geomBearingDeg, double angleErrorDeg, double distanceM) {
            this.geomBearingDeg = geomBearingDeg;
            this.angleErrorDeg = angleErrorDeg;
            this.distanceM = distanceM;
            this.crossTrackM = crossTrackM(distanceM, angleErrorDeg);
        }
    }

    /** 批–目标候选对，供互斥分配。 */
    static final class BatchTargetCandidate {
        final String batchId;
        final String deviceId;
        final int hits;
        final double avgCrossTrackM;
        final double cubicMeanAngle;

        BatchTargetCandidate(
                String batchId,
                String deviceId,
                int hits,
                double avgCrossTrackM,
                double cubicMeanAngle
        ) {
            this.batchId = batchId;
            this.deviceId = deviceId;
            this.hits = hits;
            this.avgCrossTrackM = avgCrossTrackM;
            this.cubicMeanAngle = cubicMeanAngle;
        }
    }

    static double crossTrackM(double slantRangeM, double azimuthErrorDeg) {
        return slantRangeM * Math.sin(Math.toRadians(azimuthErrorDeg));
    }

    // ---------- 地理工具 ----------

    static double distanceM(double lon1, double lat1, double lon2, double lat2) {
        final double R = 6371000;
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    static double bearingDeg(double lon1, double lat1, double lon2, double lat2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dLambda = Math.toRadians(lon2 - lon1);
        double x = Math.sin(dLambda) * Math.cos(phi2);
        double y = Math.cos(phi1) * Math.sin(phi2) - Math.sin(phi1) * Math.cos(phi2) * Math.cos(dLambda);
        return BearingMath.normalize360(Math.toDegrees(Math.atan2(x, y)));
    }

    // ---------- 核心算法 ----------

    static PointAlignment evaluateAlignment(Measurement m, DeviceTrajectory traj, MatchConfig config) {
        traj.ensureSorted();
        if (traj.trackPoints.isEmpty()) {
            return null;
        }
        int from = 0;
        int to = traj.trackPoints.size();
        if (!config.ignoreTimeDimension) {
            long t0 = m.time - config.maxTimeDeltaMs;
            long t1 = m.time + config.maxTimeDeltaMs;
            if (traj.trackPoints.get(0).time > t1 || traj.trackPoints.get(to - 1).time < t0) {
                return null;
            }
            from = traj.lowerBound(t0);
            to = traj.upperBound(t1);
        }
        return bestAlignmentInRange(m, traj, config, from, to);
    }

    static java.util.Map<String, Object> diagnoseUnusedDevice(
            String deviceId,
            DeviceTrajectory traj,
            List<Measurement> measurements,
            MatchConfig config,
            long dfT0,
            long dfT1
    ) {
        java.util.Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("deviceId", deviceId);
        if (traj == null || traj.trackPoints.isEmpty()) {
            data.put("points", Integer.valueOf(0));
            data.put("reason", "empty");
            return data;
        }
        traj.ensureSorted();
        long t0 = traj.trackPoints.get(0).time;
        long t1 = traj.trackPoints.get(traj.trackPoints.size() - 1).time;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < traj.trackPoints.size(); i++) {
            DeviceTrack p = traj.trackPoints.get(i);
            minLon = Math.min(minLon, p.lon);
            maxLon = Math.max(maxLon, p.lon);
            minLat = Math.min(minLat, p.lat);
            maxLat = Math.max(maxLat, p.lat);
        }
        data.put("points", Integer.valueOf(traj.trackPoints.size()));
        data.put("trajT0", Long.valueOf(t0));
        data.put("trajT1", Long.valueOf(t1));
        data.put("overlapMs", Long.valueOf(Math.min(t1, dfT1) - Math.max(t0, dfT0)));
        data.put("bbox", minLon + "," + minLat + ".." + maxLon + "," + maxLat);
        int timeFail = 0;
        int distFail = 0;
        int angleFail = 0;
        int pass = 0;
        int sampled = 0;
        double minDist = Double.POSITIVE_INFINITY;
        double minAngle = Double.POSITIVE_INFINITY;
        long minTimeDelta = Long.MAX_VALUE;
        int step = Math.max(1, measurements.size() / 200);
        for (int i = 0; i < measurements.size(); i += step) {
            Measurement m = measurements.get(i);
            sampled++;
            long nearest = traj.nearestTimeDeltaMs(m.time);
            if (nearest < minTimeDelta) {
                minTimeDelta = nearest;
            }
            if (!config.ignoreTimeDimension && nearest > config.maxTimeDeltaMs) {
                timeFail++;
                continue;
            }
            long win0 = m.time - config.maxTimeDeltaMs;
            long win1 = m.time + config.maxTimeDeltaMs;
            int from = config.ignoreTimeDimension ? 0 : traj.lowerBound(win0);
            int to = config.ignoreTimeDimension ? traj.trackPoints.size() : traj.upperBound(win1);
            double bestDist = Double.POSITIVE_INFINITY;
            double bestAngle = Double.POSITIVE_INFINITY;
            for (int j = from; j < to; j++) {
                DeviceTrack p = traj.trackPoints.get(j);
                if (!config.ignoreTimeDimension && Math.abs(p.time - m.time) > config.maxTimeDeltaMs) {
                    continue;
                }
                double dist = distanceM(m.rxLon, m.rxLat, p.lon, p.lat);
                double geom = bearingDeg(m.rxLon, m.rxLat, p.lon, p.lat);
                double angle = Math.abs(BearingMath.shortestDelta(geom, m.bearing));
                if (dist < bestDist) {
                    bestDist = dist;
                }
                if (angle < bestAngle) {
                    bestAngle = angle;
                }
            }
            if (bestDist == Double.POSITIVE_INFINITY) {
                timeFail++;
                continue;
            }
            if (bestDist < minDist) {
                minDist = bestDist;
            }
            if (bestAngle < minAngle) {
                minAngle = bestAngle;
            }
            if (bestDist > config.distanceThresholdM) {
                distFail++;
            } else if (bestAngle > config.angleThresholdDeg) {
                angleFail++;
            } else {
                pass++;
            }
        }
        data.put("sampled", Integer.valueOf(sampled));
        data.put("timeFail", Integer.valueOf(timeFail));
        data.put("distFail", Integer.valueOf(distFail));
        data.put("angleFail", Integer.valueOf(angleFail));
        data.put("pass", Integer.valueOf(pass));
        data.put("minTimeDeltaMs", Long.valueOf(minTimeDelta));
        data.put("minDistM", Double.valueOf(minDist));
        data.put("minAngleDeg", Double.valueOf(minAngle));
        return data;
    }

    static boolean sameAlignmentAsLinear(Measurement m, DeviceTrajectory traj, MatchConfig config) {
        PointAlignment indexed = evaluateAlignment(m, traj, config);
        PointAlignment linear = evaluateAlignmentLinear(m, traj, config);
        if (indexed == null && linear == null) {
            return true;
        }
        if (indexed == null || linear == null) {
            return false;
        }
        return Math.abs(indexed.angleErrorDeg - linear.angleErrorDeg) < 1e-12
                && Math.abs(indexed.geomBearingDeg - linear.geomBearingDeg) < 1e-12;
    }

    /** 全量线性扫描，仅测试用，须与 {@link #evaluateAlignment} 结果一致。 */
    static PointAlignment evaluateAlignmentLinear(Measurement m, DeviceTrajectory traj, MatchConfig config) {
        traj.ensureSorted();
        if (traj.trackPoints.isEmpty()) {
            return null;
        }
        return bestAlignmentInRange(m, traj, config, 0, traj.trackPoints.size());
    }

    private static PointAlignment bestAlignmentInRange(
            Measurement m,
            DeviceTrajectory traj,
            MatchConfig config,
            int from,
            int to
    ) {
        PointAlignment best = null;
        for (int i = from; i < to; i++) {
            DeviceTrack p = traj.trackPoints.get(i);
            if (!config.ignoreTimeDimension && Math.abs(p.time - m.time) > config.maxTimeDeltaMs) {
                continue;
            }
            PointAlignment align = evaluateAt(m, p.lon, p.lat, config);
            if (align != null && (best == null || align.angleErrorDeg < best.angleErrorDeg)) {
                best = align;
            }
        }
        return best;
    }

    private static PointAlignment evaluateAt(Measurement m, double txLon, double txLat, MatchConfig config) {
        double dist = distanceM(m.rxLon, m.rxLat, txLon, txLat);
        if (dist > config.distanceThresholdM) {
            return null;
        }
        double geom = bearingDeg(m.rxLon, m.rxLat, txLon, txLat);
        double angleError = Math.abs(BearingMath.shortestDelta(geom, m.bearing));
        if (angleError > config.angleThresholdDeg) {
            return null;
        }
        return new PointAlignment(geom, angleError, dist);
    }

    static Map<String, PointAlignment> findFrameAlignments(
            Measurement m,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config,
            Measurement prev,
            Map<String, PointAlignment> prevAlignments
    ) {
        Map<String, PointAlignment> alignments = new LinkedHashMap<>();
        for (DeviceTrajectory traj : trajectories.values()) {
            PointAlignment align = evaluateAlignment(m, traj, config);
            if (align == null) {
                continue;
            }
            if (config.enableBearingChangeFilter && prev != null && prevAlignments != null) {
                PointAlignment prevAlign = prevAlignments.get(traj.deviceId);
                if (prevAlign != null) {
                    double measuredDelta = BearingMath.shortestDelta(prev.bearing, m.bearing);
                    double geomDelta = BearingMath.shortestDelta(prevAlign.geomBearingDeg, align.geomBearingDeg);
                    if (Math.abs(measuredDelta - geomDelta) > config.bearingChangeToleranceDeg) {
                        continue;
                    }
                }
            }
            alignments.put(traj.deviceId, align);
        }
        return alignments;
    }

    static List<String> findFrameCandidates(
            Measurement m,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config,
            Measurement prev,
            Map<String, PointAlignment> prevAlignments
    ) {
        return new ArrayList<String>(findFrameAlignments(m, trajectories, config, prev, prevAlignments).keySet());
    }

    /** 区间 [start,end] 内公共目标；candidatesPerIndex 为预计算的每帧候选。 */
    static String findCommonAlignedDevice(
            List<Measurement> batch,
            int start,
            int end,
            List<List<String>> candidatesPerIndex,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        List<String> common = null;
        for (int k = start; k <= end; k++) {
            List<String> aligned = candidatesPerIndex.get(k);
            if (aligned.isEmpty()) {
                return null;
            }
            if (common == null) {
                common = new ArrayList<>(aligned);
            } else {
                common.retainAll(aligned);
                if (common.isEmpty()) {
                    return null;
                }
            }
        }
        if (common == null || common.isEmpty()) {
            return null;
        }
        if (common.size() == 1) {
            return common.get(0);
        }
        return selectByCubicMeanInRange(batch, start, end, common, trajectories, config);
    }

    static String selectByCubicMeanInRange(
            List<Measurement> batch,
            int start,
            int end,
            List<String> votePassed,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        String best = null;
        double bestCubic = Double.MAX_VALUE;
        for (String deviceId : votePassed) {
            DeviceTrajectory traj = trajectories.get(deviceId);
            if (traj == null) {
                continue;
            }
            double sumCube = 0;
            int count = 0;
            for (int k = start; k <= end; k++) {
                PointAlignment align = evaluateAlignment(batch.get(k), traj, config);
                if (align != null) {
                    sumCube += Math.pow(align.angleErrorDeg, 3);
                    count++;
                }
            }
            if (count == 0) {
                continue;
            }
            double cubicMean = Math.cbrt(sumCube / count);
            if (cubicMean < bestCubic) {
                bestCubic = cubicMean;
                best = deviceId;
            }
        }
        return best;
    }

    static int findSustainLockEnd(
            List<Measurement> batch,
            int start,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config,
            String deviceId
    ) {
        DeviceTrajectory traj = trajectories.get(deviceId);
        if (traj == null) {
            return start;
        }
        int end = start;
        for (int k = start; k < batch.size(); k++) {
            if (evaluateAlignment(batch.get(k), traj, config) == null) {
                break;
            }
            end = k;
        }
        return end;
    }

    static Map<String, List<Measurement>> groupByBatch(List<Measurement> measurements) {
        return measurements.stream()
                .collect(Collectors.groupingBy(Measurement::resolvedBatchId, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 粗匹配：整批计票，不要求连续持续窗。票数优先，相同则三次方均值更小者胜。
     */
    static void processBatchCoarse(
            List<Measurement> batch,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config,
            Map<Measurement, String> measurementResults,
            Map<String, String> batchSummary
    ) {
        batch.sort((a, b) -> Long.compare(a.time, b.time));
        String batchId = batch.get(0).resolvedBatchId();
        int minHits = config.minHits < 1 ? 1 : config.minHits;

        Map<String, Integer> hitCounts = new LinkedHashMap<>();
        Map<String, Double> cubeSums = new HashMap<>();
        Map<String, Integer> cubeCounts = new HashMap<>();
        List<Map<String, PointAlignment>> alignmentsPerFrame = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            Measurement measurement = batch.get(i);
            Map<String, PointAlignment> alignments =
                    findFrameAlignments(measurement, trajectories, config, null, null);
            alignmentsPerFrame.add(alignments);
            for (Map.Entry<String, PointAlignment> entry : alignments.entrySet()) {
                String deviceId = entry.getKey();
                PointAlignment align = entry.getValue();
                Integer prevHits = hitCounts.get(deviceId);
                hitCounts.put(deviceId, prevHits == null ? 1 : prevHits + 1);
                Double prevCube = cubeSums.get(deviceId);
                cubeSums.put(deviceId, (prevCube == null ? 0.0 : prevCube) + Math.pow(align.angleErrorDeg, 3));
                Integer prevCubeCount = cubeCounts.get(deviceId);
                cubeCounts.put(deviceId, prevCubeCount == null ? 1 : prevCubeCount + 1);
            }
        }

        String lockedDevice = null;
        int bestHits = 0;
        double bestCubic = Double.MAX_VALUE;
        for (Map.Entry<String, Integer> entry : hitCounts.entrySet()) {
            int hits = entry.getValue();
            if (hits < minHits) {
                continue;
            }
            Integer cubeCount = cubeCounts.get(entry.getKey());
            Double cubeSum = cubeSums.get(entry.getKey());
            if (cubeCount == null || cubeCount == 0 || cubeSum == null) {
                continue;
            }
            double cubicMean = Math.cbrt(cubeSum / cubeCount);
            if (hits > bestHits || (hits == bestHits && cubicMean < bestCubic)) {
                bestHits = hits;
                bestCubic = cubicMean;
                lockedDevice = entry.getKey();
            }
        }
        if (lockedDevice == null) {
            return;
        }
        if (trajectories.get(lockedDevice) == null) {
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            Map<String, PointAlignment> alignments = alignmentsPerFrame.get(i);
            if (alignments.containsKey(lockedDevice)) {
                measurementResults.put(batch.get(i), lockedDevice);
            }
        }
        batchSummary.put(batchId, lockedDevice);
    }

    static void processBatch(
            List<Measurement> batch,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config,
            Map<Measurement, String> measurementResults,
            Map<String, String> batchSummary
    ) {
        if (batch.isEmpty()) {
            return;
        }
        if (config.coarseMatch) {
            processBatchCoarse(batch, trajectories, config, measurementResults, batchSummary);
            return;
        }
        batch.sort((a, b) -> Long.compare(a.time, b.time));
        String batchId = batch.get(0).resolvedBatchId();
        int n = batch.size();

        List<List<String>> candidatesPerIndex = new ArrayList<>(n);
        for (Measurement m : batch) {
            candidatesPerIndex.add(findFrameCandidates(m, trajectories, config, null, null));
        }

        String lockedDevice = null;
        int lockWindowStart = -1;
        int lockWindowEnd = -1;

        outer:
        for (int start = 0; start < n; start++) {
            List<String> common = null;
            for (int end = start; end < n; end++) {
                List<String> aligned = candidatesPerIndex.get(end);
                if (aligned.isEmpty()) {
                    break;
                }
                if (common == null) {
                    common = new ArrayList<>(aligned);
                } else {
                    common.retainAll(aligned);
                    if (common.isEmpty()) {
                        break;
                    }
                }
                if (batch.get(end).time - batch.get(start).time < config.sustainDurationMs) {
                    continue;
                }
                lockedDevice = common.size() == 1
                        ? common.get(0)
                        : selectByCubicMeanInRange(batch, start, end, common, trajectories, config);
                if (lockedDevice != null) {
                    lockWindowStart = start;
                    lockWindowEnd = end;
                    break outer;
                }
            }
        }

        if (lockedDevice == null) {
            return;
        }

        DeviceTrajectory traj = trajectories.get(lockedDevice);
        if (traj == null) {
            return;
        }

        if (config.retroactiveOnConfirm) {
            for (int k = lockWindowStart; k <= lockWindowEnd; k++) {
                Measurement m = batch.get(k);
                if (evaluateAlignment(m, traj, config) != null) {
                    measurementResults.put(m, lockedDevice);
                }
            }
        }

        int extendStart = lockWindowEnd + 1;
        if (extendStart < batch.size()) {
            int extendEnd = findSustainLockEnd(batch, extendStart, trajectories, config, lockedDevice);
            for (int k = extendStart; k <= extendEnd; k++) {
                measurementResults.put(batch.get(k), lockedDevice);
            }
        }

        batchSummary.put(batchId, lockedDevice);
    }

    /** 点匹配与批摘要一次算出。 */
    public static final class MatchAllResult {
        public final Map<Measurement, String> pointMatches;
        public final Map<String, String> batchSummary;

        MatchAllResult(Map<Measurement, String> pointMatches, Map<String, String> batchSummary) {
            this.pointMatches = pointMatches;
            this.batchSummary = batchSummary;
        }
    }

    /**
     * 一次批处理同时得到逐条匹配与批级锁定。
     */
    public static MatchAllResult matchAll(
            List<Measurement> measurements,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        MatchConfig cfg = config != null ? config : new MatchConfig();
        Map<Measurement, String> result = new LinkedHashMap<Measurement, String>();
        Map<String, String> batchSummary = new LinkedHashMap<String, String>();
        if (measurements == null || measurements.isEmpty()) {
            return new MatchAllResult(result, batchSummary);
        }
        Map<String, List<Measurement>> grouped = groupByBatch(measurements);
        if (cfg.exclusiveAssign) {
            List<BatchTargetCandidate> candidates = new ArrayList<BatchTargetCandidate>();
            for (List<Measurement> batch : grouped.values()) {
                if (batch == null || batch.isEmpty()) {
                    continue;
                }
                candidates.addAll(collectBatchCandidates(batch, trajectories, cfg));
            }
            Map<String, String> assigned = assignExclusively(candidates);
            // #region agent log
            try {
                java.util.Map<String, Integer> maxHits = new java.util.LinkedHashMap<String, Integer>();
                java.util.Map<String, Integer> pairCount = new java.util.LinkedHashMap<String, Integer>();
                java.util.List<String> topPairs = new java.util.ArrayList<String>();
                for (BatchTargetCandidate c : candidates) {
                    if (c == null || c.deviceId == null) {
                        continue;
                    }
                    Integer prev = maxHits.get(c.deviceId);
                    if (prev == null || c.hits > prev.intValue()) {
                        maxHits.put(c.deviceId, Integer.valueOf(c.hits));
                    }
                    Integer pc = pairCount.get(c.deviceId);
                    pairCount.put(c.deviceId, Integer.valueOf(pc == null ? 1 : pc.intValue() + 1));
                    if (topPairs.size() < 15) {
                        topPairs.add(c.batchId + "->" + c.deviceId + ":hits=" + c.hits);
                    }
                }
                java.util.List<String> unused = new java.util.ArrayList<String>();
                java.util.Set<String> assignedDevices = new java.util.HashSet<String>(assigned.values());
                for (String deviceId : trajectories.keySet()) {
                    Integer hits = maxHits.get(deviceId);
                    boolean locked = assignedDevices.contains(deviceId);
                    if (!locked) {
                        unused.add(deviceId + "|maxHits=" + (hits == null ? 0 : hits)
                                + "|pairs=" + (pairCount.get(deviceId) == null ? 0 : pairCount.get(deviceId)));
                    }
                }
                java.util.Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
                data.put("dfBatchCount", Integer.valueOf(grouped.size()));
                data.put("candidatePairs", Integer.valueOf(candidates.size()));
                data.put("assignedCount", Integer.valueOf(assigned.size()));
                data.put("angleDeg", Double.valueOf(cfg.angleThresholdDeg));
                data.put("distM", Double.valueOf(cfg.distanceThresholdM));
                data.put("timeMs", Long.valueOf(cfg.maxTimeDeltaMs));
                data.put("minHits", Integer.valueOf(cfg.minHits));
                data.put("maxHitsByDevice", maxHits);
                data.put("unusedDevices", unused);
                data.put("assigned", assigned);
                data.put("topPairs", topPairs);
                java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("sessionId", "b9c0b8");
                payload.put("runId", "pre-fix");
                payload.put("hypothesisId", "G,H,I");
                payload.put("location", "DirectionFindingMatcher.java:matchAll");
                payload.put("message", "exclusive candidate vs unused devices");
                payload.put("timestamp", Long.valueOf(System.currentTimeMillis()));
                payload.put("data", data);
                String line = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload) + "\n";
                java.nio.file.Files.write(
                        java.nio.file.Paths.get("D:/Documents/Code/Java/pdwfx/debug-b9c0b8.log"),
                        line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                java.util.List<java.util.Map<String, Object>> unusedDiag = new java.util.ArrayList<java.util.Map<String, Object>>();
                long dfT0 = Long.MAX_VALUE;
                long dfT1 = Long.MIN_VALUE;
                for (int i = 0; i < measurements.size(); i++) {
                    long t = measurements.get(i).time;
                    if (t < dfT0) {
                        dfT0 = t;
                    }
                    if (t > dfT1) {
                        dfT1 = t;
                    }
                }
                for (String deviceId : trajectories.keySet()) {
                    if (assignedDevices.contains(deviceId)) {
                        continue;
                    }
                    unusedDiag.add(diagnoseUnusedDevice(
                            deviceId, trajectories.get(deviceId), measurements, cfg, dfT0, dfT1));
                }
                java.util.Map<String, Object> diag = new java.util.LinkedHashMap<String, Object>();
                diag.put("dfT0", Long.valueOf(dfT0));
                diag.put("dfT1", Long.valueOf(dfT1));
                diag.put("unusedDiag", unusedDiag);
                java.util.Map<String, Object> payload2 = new java.util.LinkedHashMap<String, Object>();
                payload2.put("sessionId", "b9c0b8");
                payload2.put("runId", "pre-fix");
                payload2.put("hypothesisId", "J");
                payload2.put("location", "DirectionFindingMatcher.java:matchAll");
                payload2.put("message", "unused device reject reasons");
                payload2.put("timestamp", Long.valueOf(System.currentTimeMillis()));
                payload2.put("data", diag);
                String line2 = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload2) + "\n";
                java.nio.file.Files.write(
                        java.nio.file.Paths.get("D:/Documents/Code/Java/pdwfx/debug-b9c0b8.log"),
                        line2.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {
                // debug only
            }
            // #endregion
            for (List<Measurement> batch : grouped.values()) {
                if (batch == null || batch.isEmpty()) {
                    continue;
                }
                String batchId = batch.get(0).resolvedBatchId();
                String deviceId = assigned.get(batchId);
                if (deviceId == null) {
                    continue;
                }
                lockBatchToDevice(batch, deviceId, trajectories, cfg, result, batchSummary);
            }
        } else {
            for (List<Measurement> batch : grouped.values()) {
                processBatch(batch, trajectories, cfg, result, batchSummary);
            }
        }
        return new MatchAllResult(result, batchSummary);
    }

    static List<BatchTargetCandidate> collectBatchCandidates(
            List<Measurement> batch,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        if (batch == null || batch.isEmpty()) {
            return Collections.emptyList();
        }
        if (config.coarseMatch) {
            return collectCoarseCandidates(batch, trajectories, config);
        }
        return collectFineCandidates(batch, trajectories, config);
    }

    static List<BatchTargetCandidate> collectCoarseCandidates(
            List<Measurement> batch,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        batch.sort(new Comparator<Measurement>() {
            @Override
            public int compare(Measurement a, Measurement b) {
                return Long.compare(a.time, b.time);
            }
        });
        String batchId = batch.get(0).resolvedBatchId();
        int minHits = config.minHits < 1 ? 1 : config.minHits;
        Map<String, Integer> hitCounts = new LinkedHashMap<String, Integer>();
        Map<String, Double> cubeSums = new HashMap<String, Double>();
        Map<String, Integer> cubeCounts = new HashMap<String, Integer>();
        Map<String, Double> crossSums = new HashMap<String, Double>();
        for (int i = 0; i < batch.size(); i++) {
            Map<String, PointAlignment> alignments =
                    findFrameAlignments(batch.get(i), trajectories, config, null, null);
            for (Map.Entry<String, PointAlignment> entry : alignments.entrySet()) {
                String deviceId = entry.getKey();
                PointAlignment align = entry.getValue();
                Integer prevHits = hitCounts.get(deviceId);
                hitCounts.put(deviceId, prevHits == null ? 1 : prevHits + 1);
                Double prevCube = cubeSums.get(deviceId);
                cubeSums.put(deviceId, Double.valueOf((prevCube == null ? 0.0 : prevCube.doubleValue())
                        + Math.pow(align.angleErrorDeg, 3)));
                Integer prevCubeCount = cubeCounts.get(deviceId);
                cubeCounts.put(deviceId, prevCubeCount == null ? 1 : prevCubeCount + 1);
                Double prevCross = crossSums.get(deviceId);
                crossSums.put(deviceId, Double.valueOf((prevCross == null ? 0.0 : prevCross.doubleValue())
                        + align.crossTrackM));
            }
        }
        List<BatchTargetCandidate> out = new ArrayList<BatchTargetCandidate>();
        for (Map.Entry<String, Integer> entry : hitCounts.entrySet()) {
            int hits = entry.getValue().intValue();
            if (hits < minHits) {
                continue;
            }
            String deviceId = entry.getKey();
            Integer cubeCount = cubeCounts.get(deviceId);
            Double cubeSum = cubeSums.get(deviceId);
            Double crossSum = crossSums.get(deviceId);
            if (cubeCount == null || cubeCount.intValue() == 0 || cubeSum == null) {
                continue;
            }
            double cubicMean = Math.cbrt(cubeSum.doubleValue() / cubeCount.intValue());
            double avgCross = crossSum == null ? 0.0 : crossSum.doubleValue() / hits;
            out.add(new BatchTargetCandidate(batchId, deviceId, hits, avgCross, cubicMean));
        }
        return out;
    }

    static List<BatchTargetCandidate> collectFineCandidates(
            List<Measurement> batch,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        batch.sort(new Comparator<Measurement>() {
            @Override
            public int compare(Measurement a, Measurement b) {
                return Long.compare(a.time, b.time);
            }
        });
        String batchId = batch.get(0).resolvedBatchId();
        int n = batch.size();
        List<List<String>> candidatesPerIndex = new ArrayList<List<String>>(n);
        Map<String, Integer> hitCounts = new LinkedHashMap<String, Integer>();
        Map<String, Double> cubeSums = new HashMap<String, Double>();
        Map<String, Integer> cubeCounts = new HashMap<String, Integer>();
        Map<String, Double> crossSums = new HashMap<String, Double>();
        for (int i = 0; i < n; i++) {
            Map<String, PointAlignment> alignments =
                    findFrameAlignments(batch.get(i), trajectories, config, null, null);
            candidatesPerIndex.add(new ArrayList<String>(alignments.keySet()));
            for (Map.Entry<String, PointAlignment> entry : alignments.entrySet()) {
                String deviceId = entry.getKey();
                PointAlignment align = entry.getValue();
                Integer prevHits = hitCounts.get(deviceId);
                hitCounts.put(deviceId, prevHits == null ? 1 : prevHits + 1);
                Double prevCube = cubeSums.get(deviceId);
                cubeSums.put(deviceId, Double.valueOf((prevCube == null ? 0.0 : prevCube.doubleValue())
                        + Math.pow(align.angleErrorDeg, 3)));
                Integer prevCubeCount = cubeCounts.get(deviceId);
                cubeCounts.put(deviceId, prevCubeCount == null ? 1 : prevCubeCount + 1);
                Double prevCross = crossSums.get(deviceId);
                crossSums.put(deviceId, Double.valueOf((prevCross == null ? 0.0 : prevCross.doubleValue())
                        + align.crossTrackM));
            }
        }
        Set<String> sustainDevices = findSustainDevices(batch, candidatesPerIndex, config);
        List<BatchTargetCandidate> out = new ArrayList<BatchTargetCandidate>();
        for (String deviceId : sustainDevices) {
            Integer hitsObj = hitCounts.get(deviceId);
            if (hitsObj == null || hitsObj.intValue() < 1) {
                continue;
            }
            int hits = hitsObj.intValue();
            Integer cubeCount = cubeCounts.get(deviceId);
            Double cubeSum = cubeSums.get(deviceId);
            Double crossSum = crossSums.get(deviceId);
            if (cubeCount == null || cubeCount.intValue() == 0 || cubeSum == null) {
                continue;
            }
            double cubicMean = Math.cbrt(cubeSum.doubleValue() / cubeCount.intValue());
            double avgCross = crossSum == null ? 0.0 : crossSum.doubleValue() / hits;
            out.add(new BatchTargetCandidate(batchId, deviceId, hits, avgCross, cubicMean));
        }
        return out;
    }

    static Set<String> findSustainDevices(
            List<Measurement> batch,
            List<List<String>> candidatesPerIndex,
            MatchConfig config
    ) {
        Set<String> devices = new HashSet<String>();
        int n = batch.size();
        for (int start = 0; start < n; start++) {
            List<String> common = null;
            for (int end = start; end < n; end++) {
                List<String> aligned = candidatesPerIndex.get(end);
                if (aligned.isEmpty()) {
                    break;
                }
                if (common == null) {
                    common = new ArrayList<String>(aligned);
                } else {
                    common.retainAll(aligned);
                    if (common.isEmpty()) {
                        break;
                    }
                }
                if (batch.get(end).time - batch.get(start).time < config.sustainDurationMs) {
                    continue;
                }
                devices.addAll(common);
            }
        }
        return devices;
    }

    /**
     * 按 (hits DESC, avgCrossTrackM ASC, cubicMeanAngle ASC) 贪心一对一。
     */
    static Map<String, String> assignExclusively(List<BatchTargetCandidate> candidates) {
        Map<String, String> assigned = new LinkedHashMap<String, String>();
        if (candidates == null || candidates.isEmpty()) {
            return assigned;
        }
        List<BatchTargetCandidate> sorted = new ArrayList<BatchTargetCandidate>(candidates);
        Collections.sort(sorted, new Comparator<BatchTargetCandidate>() {
            @Override
            public int compare(BatchTargetCandidate a, BatchTargetCandidate b) {
                int hitCmp = Integer.compare(b.hits, a.hits);
                if (hitCmp != 0) {
                    return hitCmp;
                }
                int crossCmp = Double.compare(a.avgCrossTrackM, b.avgCrossTrackM);
                if (crossCmp != 0) {
                    return crossCmp;
                }
                int cubicCmp = Double.compare(a.cubicMeanAngle, b.cubicMeanAngle);
                if (cubicCmp != 0) {
                    return cubicCmp;
                }
                int batchCmp = a.batchId.compareTo(b.batchId);
                if (batchCmp != 0) {
                    return batchCmp;
                }
                return a.deviceId.compareTo(b.deviceId);
            }
        });
        Set<String> usedBatches = new HashSet<String>();
        Set<String> usedDevices = new HashSet<String>();
        for (BatchTargetCandidate c : sorted) {
            if (c == null || c.batchId == null || c.deviceId == null) {
                continue;
            }
            if (usedBatches.contains(c.batchId) || usedDevices.contains(c.deviceId)) {
                continue;
            }
            usedBatches.add(c.batchId);
            usedDevices.add(c.deviceId);
            assigned.put(c.batchId, c.deviceId);
        }
        return assigned;
    }

    static void lockBatchToDevice(
            List<Measurement> batch,
            String deviceId,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config,
            Map<Measurement, String> measurementResults,
            Map<String, String> batchSummary
    ) {
        if (batch == null || batch.isEmpty() || deviceId == null) {
            return;
        }
        batch.sort(new Comparator<Measurement>() {
            @Override
            public int compare(Measurement a, Measurement b) {
                return Long.compare(a.time, b.time);
            }
        });
        String batchId = batch.get(0).resolvedBatchId();
        DeviceTrajectory traj = trajectories.get(deviceId);
        if (traj == null) {
            return;
        }
        if (config.coarseMatch) {
            for (int i = 0; i < batch.size(); i++) {
                Measurement m = batch.get(i);
                if (evaluateAlignment(m, traj, config) != null) {
                    measurementResults.put(m, deviceId);
                }
            }
            batchSummary.put(batchId, deviceId);
            return;
        }
        int n = batch.size();
        List<List<String>> candidatesPerIndex = new ArrayList<List<String>>(n);
        for (int i = 0; i < n; i++) {
            candidatesPerIndex.add(findFrameCandidates(batch.get(i), trajectories, config, null, null));
        }
        int[] window = findFirstSustainWindowForDevice(batch, deviceId, candidatesPerIndex, config);
        if (window == null) {
            for (int i = 0; i < n; i++) {
                Measurement m = batch.get(i);
                if (evaluateAlignment(m, traj, config) != null) {
                    measurementResults.put(m, deviceId);
                }
            }
            batchSummary.put(batchId, deviceId);
            return;
        }
        int lockWindowStart = window[0];
        int lockWindowEnd = window[1];
        if (config.retroactiveOnConfirm) {
            for (int k = lockWindowStart; k <= lockWindowEnd; k++) {
                Measurement m = batch.get(k);
                if (evaluateAlignment(m, traj, config) != null) {
                    measurementResults.put(m, deviceId);
                }
            }
        }
        int extendStart = lockWindowEnd + 1;
        if (extendStart < batch.size()) {
            int extendEnd = findSustainLockEnd(batch, extendStart, trajectories, config, deviceId);
            for (int k = extendStart; k <= extendEnd; k++) {
                measurementResults.put(batch.get(k), deviceId);
            }
        }
        batchSummary.put(batchId, deviceId);
    }

    static int[] findFirstSustainWindowForDevice(
            List<Measurement> batch,
            String deviceId,
            List<List<String>> candidatesPerIndex,
            MatchConfig config
    ) {
        int n = batch.size();
        for (int start = 0; start < n; start++) {
            List<String> common = null;
            for (int end = start; end < n; end++) {
                List<String> aligned = candidatesPerIndex.get(end);
                if (aligned.isEmpty() || !aligned.contains(deviceId)) {
                    break;
                }
                if (common == null) {
                    common = new ArrayList<String>(aligned);
                } else {
                    common.retainAll(aligned);
                    if (common.isEmpty() || !common.contains(deviceId)) {
                        break;
                    }
                }
                if (batch.get(end).time - batch.get(start).time < config.sustainDurationMs) {
                    continue;
                }
                return new int[]{start, end};
            }
        }
        return null;
    }

    /**
     * 逐条测向匹配结果（仅可信帧）。
     */
    public static Map<Measurement, String> match(
            List<Measurement> measurements,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        return matchAll(measurements, trajectories, config).pointMatches;
    }

    /**
     * 批级锁定：batchId → deviceId。
     */
    public static Map<String, String> matchBatchSummary(
            List<Measurement> measurements,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        return matchAll(measurements, trajectories, config).batchSummary;
    }
}
