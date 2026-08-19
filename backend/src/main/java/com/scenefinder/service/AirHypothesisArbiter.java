package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.FrequencyBandUtils;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.TrackObservation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 飞机点的连续轨假设 T 与轮询假设 P 用同一套 0–1 质量比较，禁止直接比两套原始 scene score。
 */
@Service
public class AirHypothesisArbiter {

    /**
     * 保留 Qp ≥ 门槛且 Qp ≥ Qt 的轮询窗；否则该窗走连续飞机轨。
     */
    public List<QualityScene> selectPollingWindows(
            List<QualityScene> pollingCandidates,
            List<BearingTrack> airTracks,
            SceneFinderProperties props
    ) {
        if (pollingCandidates == null || pollingCandidates.isEmpty()) {
            return Collections.emptyList();
        }
        double minQp = props.getPollingMinPeriodicityScore();
        List<QualityScene> kept = new ArrayList<QualityScene>();
        for (QualityScene scene : pollingCandidates) {
            double qp = scene.getHypothesisQuality();
            if (qp <= 0) {
                qp = fallbackQp(scene);
            }
            double qt = computeQt(scene, airTracks, props);
            if (qp >= minQp && qp >= qt) {
                kept.add(scene);
            }
        }
        return kept;
    }

    private static double fallbackQp(QualityScene scene) {
        double periodicity = clamp01(scene.getPeriodicityScore());
        double n = Math.max(1.0, scene.getDistinctDeviceCount());
        double nStab = clamp01(1.0 - Math.abs(scene.getAvgBearingsPerBurst() - n) / n);
        return clamp01(periodicity * nStab);
    }

    private double computeQt(QualityScene scene, List<BearingTrack> airTracks, SceneFinderProperties props) {
        List<BearingTrack> overlapping = overlappingAirTracks(scene, airTracks, props);
        if (overlapping.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (BearingTrack track : overlapping) {
            double smoothness = track.smoothnessScore();
            double qtSmooth = 1.0 / (1.0 + smoothness / 4.0);
            double fill = occupancyFill(track, props);
            double roundish = oneHitPerRoundPenalty(track, scene);
            sum += clamp01(qtSmooth * fill * roundish);
        }
        return sum / overlapping.size();
    }

    private static List<BearingTrack> overlappingAirTracks(
            QualityScene scene,
            List<BearingTrack> airTracks,
            SceneFinderProperties props
    ) {
        List<BearingTrack> out = new ArrayList<BearingTrack>();
        if (airTracks == null || airTracks.isEmpty()) {
            return out;
        }
        double freqGap = Math.max(0.01, props.getFreqClusterGapMhz());
        for (BearingTrack track : airTracks) {
            if (MultiDevicePollingDetectorService.isPersistentPlatform(track)) {
                continue;
            }
            if (track.endTime().isBefore(scene.getWindowStart()) || track.startTime().isAfter(scene.getWindowEnd())) {
                continue;
            }
            double f = FrequencyBandUtils.dominantFrequencyMhz(track);
            if (f < scene.getFreqMinMhz() - freqGap || f > scene.getFreqMaxMhz() + freqGap) {
                continue;
            }
            out.add(track);
        }
        return out;
    }

    /**
     * 连续填满则接近 1；一轮一个点的稀疏占用拉低 Qt。
     */
    private static double occupancyFill(BearingTrack track, SceneFinderProperties props) {
        double duration = track.durationSeconds();
        if (duration <= 0 || track.getObservations().size() < 2) {
            return 0.0;
        }
        double frameSec = Math.max(0.2, props.getFrameSeconds());
        double expected = duration / frameSec;
        return clamp01(track.getObservations().size() / Math.max(expected, 1.0));
    }

    /**
     * 近方位被并成一条时，若相邻点间隔接近轮询周期（一轮一个点）则降 Qt。
     */
    private static double oneHitPerRoundPenalty(BearingTrack track, QualityScene scene) {
        double period = scene.getPollingPeriodSec();
        if (period < 0.8 || track.getObservations().size() < 3) {
            return 1.0;
        }
        List<TrackObservation> obs = track.getObservations();
        List<Double> dts = new ArrayList<Double>();
        for (int i = 1; i < obs.size(); i++) {
            double dt = (obs.get(i).getTime().toEpochMilli()
                    - obs.get(i - 1).getTime().toEpochMilli()) / 1000.0;
            if (dt > 0) {
                dts.add(Double.valueOf(dt));
            }
        }
        if (dts.isEmpty()) {
            return 1.0;
        }
        Collections.sort(dts);
        double medianDt = dts.get(dts.size() / 2).doubleValue();
        if (medianDt >= period * 0.7) {
            double span = 0;
            for (int i = 1; i < obs.size(); i++) {
                span = Math.max(span, Math.abs(BearingMath.shortestDelta(
                        obs.get(i - 1).getBearingDeg(), obs.get(i).getBearingDeg())));
            }
            if (span >= 1.0) {
                return 0.45;
            }
            return 0.65;
        }
        return 1.0;
    }

    private static double clamp01(double v) {
        if (v < 0) {
            return 0;
        }
        if (v > 1) {
            return 1;
        }
        return v;
    }
}
