package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.util.BearingGeolocationUtil;
import com.scenefinder.model.BearingMath;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据本机经纬度与测向方位，对编批目标做地理定位估计。
 * 定位结果距我方侦察平台不超过 {@link #MAX_PLATFORM_DISTANCE_KM} km。
 */
@Service
public class BearingGeolocationService {

    /** 定位点距我方平台最大允许距离（km）。 */
    public static final double MAX_PLATFORM_DISTANCE_KM = 400.0;
    /** 单条测向前推默认距离（km），用于无多射线交点时的回退。 */
    private static final double DEFAULT_FORWARD_KM = 50.0;
    /** 两射线夹角低于此值视为近平行，不参与交点。 */
    private static final double MIN_RAY_SEPARATION_DEG = 4.0;

    public static final class LocateResult {
        public final Double lon;
        public final Double lat;
        /** BEARING | CSV | MIXED | NONE */
        public final String method;
        public final long detectStartMs;
        public final long detectEndMs;
        public final int detectCount;

        public LocateResult(Double lon, Double lat, String method,
                            long detectStartMs, long detectEndMs, int detectCount) {
            this.lon = lon;
            this.lat = lat;
            this.method = method;
            this.detectStartMs = detectStartMs;
            this.detectEndMs = detectEndMs;
            this.detectCount = detectCount;
        }
    }

    public LocateResult estimate(List<DetectSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return new LocateResult(null, null, "NONE", 0L, 0L, 0);
        }
        long start = signals.stream().mapToLong(DetectSignal::getDetectTimesss).min().orElse(0L);
        long end = signals.stream().mapToLong(DetectSignal::getDetectTimesss).max().orElse(0L);
        int count = signals.size();

        List<double[]> csvPoints = new ArrayList<>();
        List<Observation> obs = new ArrayList<>();
        for (DetectSignal s : signals) {
            if (s.getTargetLon() != null && s.getTargetLat() != null
                    && isValidCoord(s.getTargetLon(), s.getTargetLat())) {
                csvPoints.add(new double[]{s.getTargetLon(), s.getTargetLat()});
            }
            if (s.getLongitude() != null && s.getLatitude() != null
                    && isValidCoord(s.getLongitude(), s.getLatitude())
                    && Double.isFinite(s.getAzimuth())) {
                obs.add(new Observation(s.getLongitude(), s.getLatitude(), s.getAzimuth()));
            }
        }

        csvPoints = filterCsvWithinRange(csvPoints, obs);

        double[] bearingFix = intersectFromObservations(obs);
        boolean hasBearing = bearingFix != null;
        boolean hasCsv = !csvPoints.isEmpty();

        Double lon = null;
        Double lat = null;
        String method = "NONE";

        if (hasBearing && hasCsv) {
            double[] csvMed = medianPoint(csvPoints);
            double[] blended = new double[]{
                    (bearingFix[0] + csvMed[0]) / 2.0,
                    (bearingFix[1] + csvMed[1]) / 2.0
            };
            double[] constrained = constrainToMaxRange(blended[0], blended[1], obs);
            if (constrained != null) {
                lon = constrained[0];
                lat = constrained[1];
                method = "MIXED";
            }
        } else if (hasBearing) {
            double[] constrained = constrainToMaxRange(bearingFix[0], bearingFix[1], obs);
            if (constrained != null) {
                lon = constrained[0];
                lat = constrained[1];
                method = "BEARING";
            }
        } else if (hasCsv) {
            double[] med = medianPoint(csvPoints);
            double[] constrained = constrainToMaxRange(med[0], med[1], obs);
            if (constrained != null) {
                lon = constrained[0];
                lat = constrained[1];
                method = "CSV";
            } else if (obs.isEmpty()) {
                lon = med[0];
                lat = med[1];
                method = "CSV";
            }
        }

        return new LocateResult(lon, lat, method, start, end, count);
    }

    private static boolean isValidCoord(double lon, double lat) {
        return lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90 && !(lon == 0 && lat == 0);
    }

    private static double[] medianPoint(List<double[]> points) {
        double[] lons = points.stream().mapToDouble(p -> p[0]).toArray();
        double[] lats = points.stream().mapToDouble(p -> p[1]).toArray();
        return new double[]{BearingGeolocationUtil.median(lons), BearingGeolocationUtil.median(lats)};
    }

    private static List<double[]> filterCsvWithinRange(List<double[]> csvPoints, List<Observation> obs) {
        if (csvPoints.isEmpty() || obs.isEmpty()) {
            return csvPoints;
        }
        List<double[]> kept = new ArrayList<>();
        for (double[] p : csvPoints) {
            if (withinMaxRange(p[0], p[1], obs)) {
                kept.add(p);
            }
        }
        return kept;
    }

    private static boolean withinMaxRange(double lon, double lat, List<Observation> obs) {
        if (obs.isEmpty()) {
            return true;
        }
        double minDist = Double.MAX_VALUE;
        for (Observation o : obs) {
            double d = BearingGeolocationUtil.distanceKm(o.lon, o.lat, lon, lat);
            if (d < minDist) {
                minDist = d;
            }
        }
        return minDist <= MAX_PLATFORM_DISTANCE_KM;
    }

    private static boolean withinMaxRangeFromBoth(double lon, double lat, Observation a, Observation b) {
        return BearingGeolocationUtil.distanceKm(a.lon, a.lat, lon, lat) <= MAX_PLATFORM_DISTANCE_KM
                && BearingGeolocationUtil.distanceKm(b.lon, b.lat, lon, lat) <= MAX_PLATFORM_DISTANCE_KM;
    }

    /**
     * 将定位点约束在距最近我方平台不超过 {@link #MAX_PLATFORM_DISTANCE_KM} km；
     * 若已超限则沿平台→目标方向截断到 400 km 处。
     */
    private static double[] constrainToMaxRange(double lon, double lat, List<Observation> obs) {
        if (obs.isEmpty()) {
            return new double[]{lon, lat};
        }
        Observation nearest = null;
        double minDist = Double.MAX_VALUE;
        for (Observation o : obs) {
            double d = BearingGeolocationUtil.distanceKm(o.lon, o.lat, lon, lat);
            if (d < minDist) {
                minDist = d;
                nearest = o;
            }
        }
        if (nearest == null) {
            return new double[]{lon, lat};
        }
        if (minDist <= MAX_PLATFORM_DISTANCE_KM) {
            return new double[]{lon, lat};
        }
        double bearing = BearingGeolocationUtil.bearingTo(nearest.lon, nearest.lat, lon, lat);
        return BearingGeolocationUtil.forwardPoint(
                nearest.lon, nearest.lat, bearing, MAX_PLATFORM_DISTANCE_KM);
    }

    private static double[] intersectFromObservations(List<Observation> obs) {
        if (obs.isEmpty()) return null;
        if (obs.size() == 1) {
            Observation o = obs.get(0);
            double forwardKm = Math.min(DEFAULT_FORWARD_KM, MAX_PLATFORM_DISTANCE_KM);
            return BearingGeolocationUtil.forwardPoint(o.lon, o.lat, o.bearing, forwardKm);
        }
        List<double[]> intersections = new ArrayList<>();
        for (int i = 0; i < obs.size(); i++) {
            for (int j = i + 1; j < obs.size(); j++) {
                Observation a = obs.get(i);
                Observation b = obs.get(j);
                double sep = Math.abs(BearingMath.shortestDelta(a.bearing, b.bearing));
                if (sep < MIN_RAY_SEPARATION_DEG) continue;
                double[] ix = BearingGeolocationUtil.intersectRays(
                        a.lon, a.lat, a.bearing, b.lon, b.lat, b.bearing);
                if (ix != null && isValidCoord(ix[0], ix[1])
                        && withinMaxRangeFromBoth(ix[0], ix[1], a, b)) {
                    intersections.add(ix);
                }
            }
        }
        if (!intersections.isEmpty()) {
            return medianPoint(intersections);
        }
        List<double[]> forwards = new ArrayList<>();
        double forwardKm = Math.min(DEFAULT_FORWARD_KM, MAX_PLATFORM_DISTANCE_KM);
        for (Observation o : obs) {
            forwards.add(BearingGeolocationUtil.forwardPoint(
                    o.lon, o.lat, o.bearing, forwardKm));
        }
        return medianPoint(forwards);
    }

    private static final class Observation {
        final double lon;
        final double lat;
        final double bearing;

        Observation(double lon, double lat, double bearing) {
            this.lon = lon;
            this.lat = lat;
            this.bearing = bearing;
        }
    }
}
