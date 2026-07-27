package com.pdwfx.signal.util;

import com.scenefinder.model.BearingMath;

/**
 * 由本机位置（zjwzjd/zjwzwd）与测向方位（xhfw）推算目标地理坐标。
 */
public final class BearingGeolocationUtil {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private BearingGeolocationUtil() {
    }

    /** 沿方位角前进 distanceKm 公里后的经纬度。 */
    public static double[] forwardPoint(double lonDeg, double latDeg, double bearingDeg, double distanceKm) {
        double brng = Math.toRadians(BearingMath.normalize360(bearingDeg));
        double lat1 = Math.toRadians(latDeg);
        double lon1 = Math.toRadians(lonDeg);
        double d = distanceKm / EARTH_RADIUS_KM;
        double lat2 = Math.asin(
                Math.sin(lat1) * Math.cos(d) + Math.cos(lat1) * Math.sin(d) * Math.cos(brng));
        double lon2 = lon1 + Math.atan2(
                Math.sin(brng) * Math.sin(d) * Math.cos(lat1),
                Math.cos(d) - Math.sin(lat1) * Math.sin(lat2));
        return new double[]{Math.toDegrees(lon2), Math.toDegrees(lat2)};
    }

    /**
     * 两条测向射线在局部切平面上的交点（度）。若近平行则返回 null。
     */
    public static double[] intersectRays(
            double lon1, double lat1, double bearing1,
            double lon2, double lat2, double bearing2
    ) {
        double refLat = (lat1 + lat2) / 2.0;
        double[] p1 = toLocalMeters(lon1, lat1, refLat);
        double[] p2 = toLocalMeters(lon2, lat2, refLat);
        double b1 = Math.toRadians(BearingMath.normalize360(bearing1));
        double b2 = Math.toRadians(BearingMath.normalize360(bearing2));
        double[] d1 = new double[]{Math.sin(b1), Math.cos(b1)};
        double[] d2 = new double[]{Math.sin(b2), Math.cos(b2)};

        double cross = d1[0] * d2[1] - d1[1] * d2[0];
        if (Math.abs(cross) < 1e-6) {
            return null;
        }
        double[] diff = new double[]{p2[0] - p1[0], p2[1] - p1[1]};
        double t = (diff[0] * d2[1] - diff[1] * d2[0]) / cross;
        double ix = p1[0] + t * d1[0];
        double iy = p1[1] + t * d1[1];
        return fromLocalMeters(ix, iy, refLat, (lon1 + lon2) / 2.0);
    }

    private static double[] toLocalMeters(double lon, double lat, double refLat) {
        double x = Math.toRadians(lon) * EARTH_RADIUS_KM * 1000.0 * Math.cos(Math.toRadians(refLat));
        double y = Math.toRadians(lat) * EARTH_RADIUS_KM * 1000.0;
        return new double[]{x, y};
    }

    private static double[] fromLocalMeters(double xM, double yM, double refLat, double refLon) {
        double lat = refLat + Math.toDegrees(yM / (EARTH_RADIUS_KM * 1000.0));
        double lon = refLon + Math.toDegrees(xM / (EARTH_RADIUS_KM * 1000.0 * Math.cos(Math.toRadians(refLat))));
        return new double[]{lon, lat};
    }

    public static double median(double[] values) {
        if (values.length == 0) return Double.NaN;
        double[] copy = values.clone();
        java.util.Arrays.sort(copy);
        int mid = copy.length / 2;
        if (copy.length % 2 == 0) {
            return (copy[mid - 1] + copy[mid]) / 2.0;
        }
        return copy[mid];
    }

    /** 两点间大圆距离（km）。 */
    public static double distanceKm(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double lat1r = Math.toRadians(lat1);
        double lat2r = Math.toRadians(lat2);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1r) * Math.cos(lat2r) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** 从 (lon1,lat1) 指向 (lon2,lat2) 的方位角（度，0~360）。 */
    public static double bearingTo(double lon1, double lat1, double lon2, double lat2) {
        double lat1r = Math.toRadians(lat1);
        double lat2r = Math.toRadians(lat2);
        double dLon = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dLon) * Math.cos(lat2r);
        double x = Math.cos(lat1r) * Math.sin(lat2r)
                - Math.sin(lat1r) * Math.cos(lat2r) * Math.cos(dLon);
        return BearingMath.normalize360(Math.toDegrees(Math.atan2(y, x)));
    }
}
