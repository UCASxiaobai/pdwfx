package com.scenefinder.service;

import com.pdwfx.signal.model.SeriesPoint;
import com.pdwfx.signal.model.TargetView;
import com.pdwfx.signal.service.CommunicationLinkAnalysisService;
import com.scenefinder.model.AwacsOccupancyWindow;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.FrequencyBandUtils;
import com.scenefinder.model.OccupancyCluster;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SceneType;
import com.scenefinder.model.TrackObservation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从一次分析的预警机目标收集按频占用窗，并聚成指挥网频簇。
 */
@Service
public class AwacsCommandNetService {

    public static final String COMMAND_NET_ANNOTATION = "指挥网二次：预警机占用窗内同频全量点";

    public List<AwacsOccupancyWindow> collectWindows(List<TargetView> targets, double padSec, double freqTolMhz) {
        List<AwacsOccupancyWindow> out = new ArrayList<AwacsOccupancyWindow>();
        if (targets == null || targets.isEmpty()) {
            return out;
        }
        long padMs = Math.max(0L, Math.round(padSec * 1000.0));
        double tol = freqTolMhz > 0 ? freqTolMhz : 0.01;
        for (TargetView target : targets) {
            if (!isAwacsSeed(target)) {
                continue;
            }
            out.addAll(windowsFromTarget(target, padMs, tol));
        }
        return out;
    }

    /**
     * 场景分选阶段：从建轨占空比标成预警机的轨收集占用窗，用于强制保留同频同时段场景。
     */
    public List<AwacsOccupancyWindow> collectWindowsFromTracks(
            List<BearingTrack> tracks,
            double padSec,
            double freqTolMhz
    ) {
        List<AwacsOccupancyWindow> out = new ArrayList<AwacsOccupancyWindow>();
        if (tracks == null || tracks.isEmpty()) {
            return out;
        }
        long padMs = Math.max(0L, Math.round(padSec * 1000.0));
        double tol = freqTolMhz > 0 ? freqTolMhz : 0.01;
        for (int i = 0; i < tracks.size(); i++) {
            BearingTrack track = tracks.get(i);
            if (track == null || !"AWACS".equals(track.getSuggestedPlatformType())) {
                continue;
            }
            List<TrackObservation> obs = track.getObservations();
            if (obs == null || obs.isEmpty()) {
                continue;
            }
            List<SeriesPoint> series = new ArrayList<SeriesPoint>();
            for (int j = 0; j < obs.size(); j++) {
                TrackObservation o = obs.get(j);
                if (o == null || o.getTime() == null) {
                    continue;
                }
                series.add(new SeriesPoint(o.getTime().toEpochMilli(), o.getFrequencyMhz()));
            }
            if (series.isEmpty()) {
                continue;
            }
            out.addAll(windowsFromFreqSeries(series, padMs, tol, "track-" + track.getId()));
        }
        return out;
    }

    public static boolean sceneOverlapsAnyWindow(QualityScene scene, List<AwacsOccupancyWindow> windows) {
        if (scene == null || windows == null || windows.isEmpty()) {
            return false;
        }
        return sceneOverlapsAnyWindow(
                scene.getWindowStart(),
                scene.getWindowEnd(),
                scene.getFreqMinMhz(),
                scene.getFreqMaxMhz(),
                windows
        );
    }

    public static boolean sceneOverlapsAnyWindow(
            java.time.Instant windowStart,
            java.time.Instant windowEnd,
            double freqMinMhz,
            double freqMaxMhz,
            List<AwacsOccupancyWindow> windows
    ) {
        if (windowStart == null || windowEnd == null || windows == null) {
            return false;
        }
        long s0 = windowStart.toEpochMilli();
        long s1 = windowEnd.toEpochMilli();
        for (int i = 0; i < windows.size(); i++) {
            AwacsOccupancyWindow window = windows.get(i);
            if (s1 < window.getTStartMs() || s0 > window.getTEndMs()) {
                continue;
            }
            double lo = window.getFreqCenterMhz() - window.getFreqToleranceMhz();
            double hi = window.getFreqCenterMhz() + window.getFreqToleranceMhz();
            if (freqMaxMhz >= lo && freqMinMhz <= hi) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAwacsSeed(TargetView target) {
        if (target == null || !"AWACS".equals(target.getTargetType())) {
            return false;
        }
        return !CommunicationLinkAnalysisService.isLongTxIntervalAir(target);
    }

    public List<OccupancyCluster> clusterByFreq(List<AwacsOccupancyWindow> windows, double freqTolMhz) {
        List<OccupancyCluster> clusters = new ArrayList<OccupancyCluster>();
        if (windows == null || windows.isEmpty()) {
            return clusters;
        }
        double tol = freqTolMhz > 0 ? freqTolMhz : 0.01;
        List<AwacsOccupancyWindow> sorted = new ArrayList<AwacsOccupancyWindow>(windows);
        sorted.sort(Comparator.comparingDouble(AwacsOccupancyWindow::getFreqCenterMhz));
        List<AwacsOccupancyWindow> current = new ArrayList<AwacsOccupancyWindow>();
        double anchor = Double.NaN;
        for (AwacsOccupancyWindow window : sorted) {
            if (current.isEmpty()) {
                current.add(window);
                anchor = window.getFreqCenterMhz();
                continue;
            }
            if (Math.abs(window.getFreqCenterMhz() - anchor) <= tol) {
                current.add(window);
            } else {
                clusters.add(toCluster(current, tol));
                current = new ArrayList<AwacsOccupancyWindow>();
                current.add(window);
                anchor = window.getFreqCenterMhz();
            }
        }
        if (!current.isEmpty()) {
            clusters.add(toCluster(current, tol));
        }
        return clusters;
    }

    public List<DetectionPoint> filterPoints(List<DetectionPoint> points, List<AwacsOccupancyWindow> windows) {
        List<DetectionPoint> out = new ArrayList<DetectionPoint>();
        if (points == null || windows == null || windows.isEmpty()) {
            return out;
        }
        for (DetectionPoint point : points) {
            if (matchesAny(point.getFrequencyMhz(), point.getTime().toEpochMilli(), windows)) {
                out.add(point);
            }
        }
        return out;
    }

    public static boolean matchesAny(double freqMhz, long timeMs, List<AwacsOccupancyWindow> windows) {
        if (windows == null) {
            return false;
        }
        for (AwacsOccupancyWindow window : windows) {
            if (window.matches(freqMhz, timeMs)) {
                return true;
            }
        }
        return false;
    }

    public List<QualityScene> buildScenes(List<OccupancyCluster> clusters, List<BearingTrack> tracks) {
        List<QualityScene> scenes = new ArrayList<QualityScene>();
        if (clusters == null) {
            return scenes;
        }
        int rank = 1;
        for (OccupancyCluster cluster : clusters) {
            List<Integer> trackIds = new ArrayList<Integer>();
            double smoothSum = 0d;
            if (tracks != null) {
                for (BearingTrack track : tracks) {
                    if (trackOverlapsCluster(track, cluster)) {
                        trackIds.add(Integer.valueOf(track.getId()));
                        smoothSum += track.smoothnessScore();
                    }
                }
            }
            double avgSmooth = trackIds.isEmpty() ? 0d : smoothSum / trackIds.size();
            scenes.add(new QualityScene(
                    rank++,
                    SceneType.COMMAND_NET,
                    Instant.ofEpochMilli(cluster.getTStartMs()),
                    Instant.ofEpochMilli(cluster.getTEndMs()),
                    round3(cluster.getFreqCenterMhz()),
                    round3(cluster.getFreqMinMhz()),
                    round3(cluster.getFreqMaxMhz()),
                    Math.max(1, trackIds.size()),
                    1.0,
                    trackIds.size(),
                    0d,
                    round3(avgSmooth),
                    trackIds,
                    0d,
                    0,
                    0d,
                    0d,
                    COMMAND_NET_ANNOTATION
            ));
        }
        return scenes;
    }

    static boolean trackOverlapsCluster(BearingTrack track, OccupancyCluster cluster) {
        if (track == null || cluster == null) {
            return false;
        }
        long t0 = track.startTime().toEpochMilli();
        long t1 = track.endTime().toEpochMilli();
        if (t1 < cluster.getTStartMs() || t0 > cluster.getTEndMs()) {
            return false;
        }
        double freq = FrequencyBandUtils.dominantFrequencyMhz(track);
        if (Math.abs(freq - cluster.getFreqCenterMhz()) > cluster.getFreqToleranceMhz()) {
            return false;
        }
        for (TrackObservation obs : track.getObservations()) {
            if (cluster.matchesPoint(obs.getFrequencyMhz(), obs.getTime().toEpochMilli())) {
                return true;
            }
        }
        return false;
    }

    private List<AwacsOccupancyWindow> windowsFromTarget(TargetView target, long padMs, double tol) {
        List<SeriesPoint> series = target.getFreqSeries();
        if (series != null && !series.isEmpty()) {
            return windowsFromFreqSeries(series, padMs, tol, target.getTargetId());
        }
        return windowsFromFallback(target, padMs, tol);
    }

    /**
     * 按时间相邻且频率落在容差内切成占用段，避免跳频离开后仍把原频点后段点带进来。
     */
    List<AwacsOccupancyWindow> windowsFromFreqSeries(
            List<SeriesPoint> series,
            long padMs,
            double tol,
            String seedTargetId
    ) {
        List<SeriesPoint> sorted = new ArrayList<SeriesPoint>(series);
        sorted.sort(Comparator.comparingLong(SeriesPoint::getT));
        List<AwacsOccupancyWindow> windows = new ArrayList<AwacsOccupancyWindow>();
        List<SeriesPoint> run = new ArrayList<SeriesPoint>();
        double anchor = Double.NaN;
        for (SeriesPoint point : sorted) {
            if (run.isEmpty()) {
                run.add(point);
                anchor = point.getV();
                continue;
            }
            if (Math.abs(point.getV() - anchor) <= tol) {
                run.add(point);
            } else {
                windows.add(windowFromRun(run, padMs, tol, seedTargetId));
                run = new ArrayList<SeriesPoint>();
                run.add(point);
                anchor = point.getV();
            }
        }
        if (!run.isEmpty()) {
            windows.add(windowFromRun(run, padMs, tol, seedTargetId));
        }
        return windows;
    }

    private List<AwacsOccupancyWindow> windowsFromFallback(TargetView target, long padMs, double tol) {
        List<AwacsOccupancyWindow> windows = new ArrayList<AwacsOccupancyWindow>();
        Long start = target.getDetectStartMs();
        Long end = target.getDetectEndMs();
        if (start == null || end == null || end.longValue() < start.longValue()) {
            return windows;
        }
        List<Double> freqs = target.getCommFreqMhzList();
        if (freqs == null || freqs.isEmpty()) {
            return windows;
        }
        long t0 = start.longValue() - padMs;
        long t1 = end.longValue() + padMs;
        for (Double freq : freqs) {
            if (freq == null) {
                continue;
            }
            windows.add(new AwacsOccupancyWindow(freq.doubleValue(), tol, t0, t1, target.getTargetId()));
        }
        return windows;
    }

    private static AwacsOccupancyWindow windowFromRun(
            List<SeriesPoint> run,
            long padMs,
            double tol,
            String seedTargetId
    ) {
        long tMin = run.get(0).getT();
        long tMax = run.get(0).getT();
        double sum = 0d;
        for (SeriesPoint point : run) {
            tMin = Math.min(tMin, point.getT());
            tMax = Math.max(tMax, point.getT());
            sum += point.getV();
        }
        double center = sum / run.size();
        return new AwacsOccupancyWindow(center, tol, tMin - padMs, tMax + padMs, seedTargetId);
    }

    private static OccupancyCluster toCluster(List<AwacsOccupancyWindow> group, double tol) {
        double sum = 0d;
        double minF = group.get(0).getFreqCenterMhz();
        double maxF = minF;
        long t0 = group.get(0).getTStartMs();
        long t1 = group.get(0).getTEndMs();
        for (AwacsOccupancyWindow window : group) {
            sum += window.getFreqCenterMhz();
            minF = Math.min(minF, window.getFreqCenterMhz());
            maxF = Math.max(maxF, window.getFreqCenterMhz());
            t0 = Math.min(t0, window.getTStartMs());
            t1 = Math.max(t1, window.getTEndMs());
        }
        return new OccupancyCluster(sum / group.size(), minF, maxF, tol, t0, t1, group);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
