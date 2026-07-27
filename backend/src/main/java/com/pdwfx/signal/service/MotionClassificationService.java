package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 固定站 / 移动目标（飞机）识别。
 *
 * <p>输入：单目标信号列表中的 TARGET_LON / TARGET_LAT（表格式 DWJD/DWD）。
 *
 * <p>判定流程（{@link #assess}）：
 * <ol>
 *   <li>有效定位点 &lt; {@value #MIN_COORDS} → {@code NO_ELLIPSE}，判飞机</li>
 *   <li>估计 2×2 协方差 → 误差椭圆，各点 Mahalanobis d² 与 χ²(2,95%)={@value #CHI2_95_2D} 比较</li>
 *   <li>前/后半段离散度 → 椭圆是否收敛</li>
 *   <li>简化卡尔曼 → 航速、航向稳定度</li>
 *   <li>漂移在椭圆内且收敛 → FIXED；超出椭圆且连续航迹 → MOBILE</li>
 * </ol>
 *
 * <p>调用方：{@link SignalAnalysisService#classifyPlatformType}（辅证）；收敛字段仍写入 TargetView
 */
@Component
public class MotionClassificationService {

    /** 2 维 95% 置信 Mahalanobis 门限 */
    private static final double CHI2_95_2D = 5.991;
    private static final int MIN_COORDS = 6;
    private static final double COV_EPS = 1e-8;
    private static final double CONVERGENCE_RATIO = 0.75;
    private static final double CONVERGENCE_ABS_SPREAD = 0.01;

    /**
     * 对单目标全部侦获点做固定/移动判定。
     *
     * @return {@link MotionAssessment#state}：
     *         FIXED / MOBILE / NO_ELLIPSE / UNCERTAIN
     */
    public MotionAssessment assess(List<DetectSignal> signals) {
        List<DetectSignal> withCoords = signals.stream()
                .filter(s -> s.getTargetLon() != null && s.getTargetLat() != null)
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(Collectors.toList());
        int total = signals.size();
        int n = withCoords.size();
        MotionAssessment r = new MotionAssessment();
        if (n < MIN_COORDS) {
            r.state = "NO_ELLIPSE";
            r.convergenceState = "NOT_CONVERGING";
            r.ellipseConverging = false;
            r.detail = String.format(
                    "误差椭圆：无法形成（有效定位点 %d/%d，需≥%d）；按规则判为飞机",
                    n, total, MIN_COORDS);
            return r;
        }

        double[][] pts = new double[n][2];
        long[] times = new long[n];
        for (int i = 0; i < n; i++) {
            pts[i][0] = withCoords.get(i).getTargetLon();
            pts[i][1] = withCoords.get(i).getTargetLat();
            times[i] = withCoords.get(i).getDetectTimesss();
        }

        double[] centroid = mean2(pts);
        double[][] cov = cov2(pts, centroid);
        double[][] invCov = invert2x2(cov);

        int mid = Math.max(1, n / 2);
        double earlySpread = spread(sub(pts, 0, mid));
        double lateSpread = spread(sub(pts, mid, n));
        // 前后半段定位点(DWJD/DWD)离散度；与地图测向线几何汇聚无关
        boolean alreadyTight = earlySpread < CONVERGENCE_ABS_SPREAD && lateSpread < CONVERGENCE_ABS_SPREAD;
        boolean shrinking = lateSpread < earlySpread * CONVERGENCE_RATIO
                && lateSpread < CONVERGENCE_ABS_SPREAD;
        boolean ellipseConverging = alreadyTight || shrinking;
        double azSpreadDeg = azimuthSpreadDeg(signals);

        int inliers = 0;
        double maxMd2 = 0;
        for (double[] p : pts) {
            double md2 = mahalanobis2(p, centroid, invCov);
            maxMd2 = Math.max(maxMd2, md2);
            if (md2 <= CHI2_95_2D) inliers++;
        }
        r.inlierRatio = (double) inliers / n;
        r.maxMahalanobis2 = maxMd2;
        r.ellipseMajor = Math.sqrt(Math.max(cov[0][0], cov[1][1]));
        r.ellipseMinor = Math.sqrt(Math.min(cov[0][0], cov[1][1]));
        r.earlySpread = earlySpread;
        r.lateSpread = lateSpread;
        r.ellipseConverging = ellipseConverging;

        KalmanTrack kf = estimateKinematic(pts, times, invCov);
        r.smoothedSpeed = kf.speedDegPerSec;
        r.headingStability = kf.headingStability;
        r.innovationInlierRatio = kf.innovationInlierRatio;

        boolean driftInEllipse = r.inlierRatio >= 0.82 && maxMd2 <= CHI2_95_2D * 1.5;
        boolean stableCenter = lateSpread < 0.015 && ellipseConverging;
        boolean exceedsEllipse = r.inlierRatio < 0.55 || maxMd2 > CHI2_95_2D * 2.5;
        boolean continuousMotion = kf.speedDegPerSec >= 2e-5 && kf.headingStability >= 0.55;

        String ellipseTag = ellipseConverging ? "误差椭圆收敛" : "误差椭圆不收敛";
        String azNote = azimuthConvergenceNote(azSpreadDeg, ellipseConverging);
        if ((driftInEllipse && stableCenter) || (ellipseConverging && kf.speedDegPerSec < 1e-5)) {
            r.state = "FIXED";
            r.convergenceState = "CONVERGING";
            r.detail = String.format(
                    "%s；固定目标特征：Mahalanobis 内点率 %.0f%%（门限χ²=%.2f），最大d²=%.2f；"
                            + "离散度前%.4f°→后%.4f°；卡尔曼航速≈%.2e°/s，航向稳定度%.2f",
                    ellipseTag,
                    r.inlierRatio * 100, CHI2_95_2D, maxMd2, earlySpread, lateSpread,
                    kf.speedDegPerSec, kf.headingStability) + azNote;
        } else if (exceedsEllipse && continuousMotion) {
            r.state = "MOBILE";
            r.convergenceState = "NOT_CONVERGING";
            r.detail = String.format(
                    "%s；移动目标特征：偏移超出误差椭圆(内点率%.0f%%，最大d²=%.2f>门限)；"
                            + "轨迹连续航速≈%.2e°/s，航向稳定度%.2f；卡尔曼创新内点率%.0f%%",
                    ellipseTag,
                    r.inlierRatio * 100, maxMd2, kf.speedDegPerSec, kf.headingStability,
                    kf.innovationInlierRatio * 100) + azNote;
        } else if (ellipseConverging) {
            r.state = "FIXED";
            r.convergenceState = "CONVERGING";
            r.detail = String.format(
                    "%s；倾向固定：离散度前%.4f°→后%.4f°，Mahalanobis内点率%.0f%%，航速较低(%.2e°/s)",
                    ellipseTag,
                    earlySpread, lateSpread, r.inlierRatio * 100, kf.speedDegPerSec) + azNote;
        } else if (continuousMotion) {
            r.state = "MOBILE";
            r.convergenceState = "NOT_CONVERGING";
            r.detail = String.format(
                    "%s；倾向移动：连续位移(航速%.2e°/s，航向稳定%.2f)，Mahalanobis内点率%.0f%%",
                    ellipseTag,
                    kf.speedDegPerSec, kf.headingStability, r.inlierRatio * 100) + azNote;
        } else {
            r.state = "UNCERTAIN";
            r.convergenceState = ellipseConverging ? "CONVERGING" : "NOT_CONVERGING";
            r.detail = String.format(
                    "%s；定位噪声边界：内点率%.0f%%，d²=%.2f，离散度前%.4f°后%.4f°，航速%.2e°/s（需更多点或更好协方差）",
                    ellipseTag,
                    r.inlierRatio * 100, maxMd2, earlySpread, lateSpread, kf.speedDegPerSec) + azNote;
        }
        return r;
    }

    /** 全目标 PDW 方位标准差（度），用于与地图测向线目视对比 */
    private static double azimuthSpreadDeg(List<DetectSignal> signals) {
        if (signals == null || signals.isEmpty()) return 999;
        double sum = 0, sum2 = 0;
        int n = 0;
        for (DetectSignal s : signals) {
            sum += s.getAzimuth();
            sum2 += s.getAzimuth() * s.getAzimuth();
            n++;
        }
        if (n < 2) return 0;
        double mean = sum / n;
        double var = Math.max(0, sum2 / n - mean * mean);
        return Math.sqrt(var);
    }

    private static String azimuthConvergenceNote(double azSpreadDeg, boolean ellipseConverging) {
        if (azSpreadDeg >= 4.0) return "";
        if (ellipseConverging) {
            return String.format("；测向方位σ≈%.1f°（与目视测向线汇聚一致）", azSpreadDeg);
        }
        return String.format(
                "；测向方位σ≈%.1f°（目视测向线可汇聚），但定位点(DWJD/DWD)前后离散度未达椭圆收敛",
                azSpreadDeg);
    }

    private static double azimuthStepAvg(List<DetectSignal> signals) {
        List<DetectSignal> sorted = signals.stream()
                .sorted(Comparator.comparingLong(DetectSignal::getDetectTimesss))
                .collect(Collectors.toList());
        if (sorted.size() < 2) return 0;
        double sum = 0;
        for (int i = 1; i < sorted.size(); i++) {
            sum += Math.abs(sorted.get(i).getAzimuth() - sorted.get(i - 1).getAzimuth());
        }
        return sum / (sorted.size() - 1);
    }

    private static double[][] sub(double[][] pts, int from, int to) {
        double[][] s = new double[to - from][2];
        for (int i = from; i < to; i++) {
            s[i - from] = pts[i];
        }
        return s;
    }

    private static double spread(double[][] pts) {
        if (pts.length == 0) return 999;
        double[] m = mean2(pts);
        double[][] c = cov2(pts, m);
        return Math.sqrt(c[0][0] + c[1][1]);
    }

    private static double[] mean2(double[][] pts) {
        double sx = 0, sy = 0;
        for (double[] p : pts) {
            sx += p[0];
            sy += p[1];
        }
        return new double[]{sx / pts.length, sy / pts.length};
    }

    private static double[][] cov2(double[][] pts, double[] mean) {
        double sxx = 0, syy = 0, sxy = 0;
        int n = pts.length;
        for (double[] p : pts) {
            double dx = p[0] - mean[0];
            double dy = p[1] - mean[1];
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        double den = Math.max(1, n - 1);
        return new double[][]{
                {sxx / den + COV_EPS, sxy / den},
                {sxy / den, syy / den + COV_EPS}
        };
    }

    private static double[][] invert2x2(double[][] m) {
        double det = m[0][0] * m[1][1] - m[0][1] * m[1][0];
        if (Math.abs(det) < 1e-12) {
            return new double[][]{{1 / COV_EPS, 0}, {0, 1 / COV_EPS}};
        }
        return new double[][]{
                {m[1][1] / det, -m[0][1] / det},
                {-m[1][0] / det, m[0][0] / det}
        };
    }

    private static double mahalanobis2(double[] p, double[] mean, double[][] inv) {
        double dx = p[0] - mean[0];
        double dy = p[1] - mean[1];
        return dx * (inv[0][0] * dx + inv[0][1] * dy) + dy * (inv[1][0] * dx + inv[1][1] * dy);
    }

    /**
     * 常速卡尔曼：预测-更新后对观测创新做 Mahalanobis 门限，抑制测向噪声造成的假位移。
     */
    private static KalmanTrack estimateKinematic(double[][] pts, long[] times, double[][] invCov) {
        double[] x = {pts[0][0], pts[0][1], 0, 0};
        int innovIn = 0;
        List<Double> headings = new ArrayList<>();
        List<Double> speeds = new ArrayList<>();

        for (int i = 1; i < pts.length; i++) {
            double dt = Math.max(1.0, times[i] - times[i - 1]) / 1000.0;
            double predLon = x[0] + x[2] * dt;
            double predLat = x[1] + x[3] * dt;
            double[] innov = {pts[i][0] - predLon, pts[i][1] - predLat};
            double md2 = mahalanobis2(innov, new double[]{0, 0}, invCov);
            if (md2 <= CHI2_95_2D) {
                innovIn++;
                x[0] = predLon + 0.35 * innov[0];
                x[1] = predLat + 0.35 * innov[1];
            } else {
                x[0] = pts[i][0];
                x[1] = pts[i][1];
            }
            x[2] = innov[0] / dt * 0.5 + x[2] * 0.5;
            x[3] = innov[1] / dt * 0.5 + x[3] * 0.5;
            double speed = Math.hypot(x[2], x[3]);
            speeds.add(speed);
            if (speed > 1e-9) headings.add(Math.atan2(x[3], x[2]));
        }

        KalmanTrack t = new KalmanTrack();
        t.speedDegPerSec = speeds.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        t.innovationInlierRatio = pts.length <= 1 ? 1 : (double) innovIn / (pts.length - 1);
        if (headings.size() >= 2) {
            double avg = headings.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double var = 0;
            for (double h : headings) {
                double d = h - avg;
                var += d * d;
            }
            var /= headings.size();
            t.headingStability = Math.max(0, 1 - Math.min(1, Math.sqrt(var) / Math.PI));
        }
        return t;
    }

    private static class KalmanTrack {
        double speedDegPerSec;
        double headingStability;
        double innovationInlierRatio;
    }

    public static class MotionAssessment {
        public String state = "UNKNOWN";
        public String convergenceState = "UNKNOWN";
        public String detail = "";
        public double inlierRatio;
        public double maxMahalanobis2;
        public double ellipseMajor;
        public double ellipseMinor;
        public double earlySpread;
        public double lateSpread;
        public boolean ellipseConverging;
        public double smoothedSpeed;
        public double headingStability;
        public double innovationInlierRatio;
        public double fallbackAzimuthMove;
    }
}
