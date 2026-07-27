package com.scenefinder.service;

import com.scenefinder.model.BearingMath;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 测向与定位航迹关联。
 * <p>
 * 单帧：满足方位/距离门限；未开启 {@link MatchConfig#ignoreTimeDimension} 时，
 * 测向与定位时差还须 ≤ {@link MatchConfig#maxTimeDeltaMs}。
 * 批级：同一测向批内须存在连续 {@link MatchConfig#sustainDurationMs} 毫秒的时间窗，
 * 窗内每条测向均指向同一目标，方可锁定关联。
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
        /** 同一测向批内须持续指向同一目标的最短时间（毫秒） */
        public long sustainDurationMs = 10_000L;
        /** 三次方均值：锚点之后时间窗（秒） */
        public double meanSquareBeforeSec = 60;
        /** 三次方均值：锚点之前时间窗（秒） */
        public double meanSquareAfterSec = 15;
        public boolean requireTrackCoverage = true;
        public boolean allowTrackExtrapolation = false;
        public boolean retroactiveOnConfirm = true;
        public boolean enableBearingChangeFilter = false;
        public double bearingChangeToleranceDeg = 10.0;
    }

    private static final class PointAlignment {
        final double geomBearingDeg;
        final double angleErrorDeg;
        final double distanceM;

        PointAlignment(double geomBearingDeg, double angleErrorDeg, double distanceM) {
            this.geomBearingDeg = geomBearingDeg;
            this.angleErrorDeg = angleErrorDeg;
            this.distanceM = distanceM;
        }
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
        PointAlignment best = null;
        for (DeviceTrack p : traj.trackPoints) {
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

    static List<String> findFrameCandidates(
            Measurement m,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config,
            Measurement prev,
            Map<String, PointAlignment> prevAlignments
    ) {
        List<String> candidates = new ArrayList<>();
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
            candidates.add(traj.deviceId);
        }
        return candidates;
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

    /**
     * 逐条测向匹配结果（仅可信帧）。
     */
    public static Map<Measurement, String> match(
            List<Measurement> measurements,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        MatchConfig cfg = config != null ? config : new MatchConfig();
        Map<Measurement, String> result = new LinkedHashMap<>();
        Map<String, String> batchSummary = new LinkedHashMap<>();
        for (List<Measurement> batch : groupByBatch(measurements).values()) {
            processBatch(batch, trajectories, cfg, result, batchSummary);
        }
        return result;
    }

    /**
     * 批级锁定：batchId → deviceId。
     */
    public static Map<String, String> matchBatchSummary(
            List<Measurement> measurements,
            Map<String, DeviceTrajectory> trajectories,
            MatchConfig config
    ) {
        MatchConfig cfg = config != null ? config : new MatchConfig();
        Map<Measurement, String> ignored = new LinkedHashMap<>();
        Map<String, String> batchSummary = new LinkedHashMap<>();
        for (List<Measurement> batch : groupByBatch(measurements).values()) {
            processBatch(batch, trajectories, cfg, ignored, batchSummary);
        }
        return batchSummary;
    }
}
