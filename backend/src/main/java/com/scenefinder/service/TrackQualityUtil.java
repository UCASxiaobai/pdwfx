package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.TrackObservation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 场景建轨阶段的轨迹质量辅助：按方位跳变切分片段，剔除短时噪声轨。
 */
final class TrackQualityUtil {

    private TrackQualityUtil() {
    }

    static List<BearingTrack> splitAndFilter(BearingTrack track, SceneFinderProperties props, int startId) {
        List<List<TrackObservation>> segments = splitOnJumps(
                track.getObservations(),
                props.getTrackJumpSplitDegPerSec(),
                props.getTrackJumpSplitGapSec());
        List<BearingTrack> out = new ArrayList<>();
        int nextId = startId;
        for (List<TrackObservation> segment : segments) {
            BearingTrack seg = fromSegment(nextId++, segment);
            if (passesTrackGate(seg, props)) {
                out.add(seg);
            }
        }
        return out;
    }

    static boolean passesTrackGate(BearingTrack track, SceneFinderProperties props) {
        if (track.getHits() < props.getMinTrackPoints()) {
            return false;
        }
        if (track.durationSeconds() < props.getMinTrackSeconds()) {
            return false;
        }
        if (track.getHits() >= 35 || track.durationSeconds() >= 120) {
            return true;
        }
        double maxStep = maxStepRateDegPerSec(track);
        double span = track.durationSeconds();
        if (maxStep > props.getMaxTrackBearingRateDegPerSec() && span < 100) {
            return false;
        }
        if (bearingSpanDeg(track) > 40 && span < 40 && track.getHits() < 18) {
            return false;
        }
        return true;
    }

    private static List<List<TrackObservation>> splitOnJumps(
            List<TrackObservation> observations,
            double maxStepRateDegPerSec,
            double maxGapSec
    ) {
        List<List<TrackObservation>> segments = new ArrayList<>();
        if (observations == null || observations.isEmpty()) {
            return segments;
        }
        List<TrackObservation> sorted = new ArrayList<>(observations);
        sorted.sort(Comparator.comparing(TrackObservation::getTime));

        List<TrackObservation> current = new ArrayList<>();
        current.add(sorted.get(0));
        for (int i = 1; i < sorted.size(); i++) {
            TrackObservation prev = sorted.get(i - 1);
            TrackObservation now = sorted.get(i);
            double dt = (now.getTime().toEpochMilli() - prev.getTime().toEpochMilli()) / 1000.0;
            double rate = 0;
            if (dt > 0.001) {
                rate = Math.abs(BearingMath.shortestDelta(prev.getBearingDeg(), now.getBearingDeg())) / dt;
            }
            if (dt > maxGapSec || rate > maxStepRateDegPerSec) {
                segments.add(current);
                current = new ArrayList<>();
            }
            current.add(now);
        }
        if (!current.isEmpty()) {
            segments.add(current);
        }
        return segments;
    }

    private static BearingTrack fromSegment(int id, List<TrackObservation> segment) {
        BearingTrack track = new BearingTrack();
        track.setId(id);
        track.getObservations().addAll(segment);
        if (!segment.isEmpty()) {
            TrackObservation last = segment.get(segment.size() - 1);
            track.setBearingDeg(last.getBearingDeg());
            track.setLastTime(last.getTime());
        }
        track.setHits(segment.size());
        track.setMissedFrames(0);
        track.setVelocityDegPerSec(0);
        return track;
    }

    static double maxStepRateDegPerSec(BearingTrack track) {
        List<TrackObservation> obs = track.getObservations();
        if (obs.size() < 2) {
            return 0;
        }
        double max = 0;
        for (int i = 1; i < obs.size(); i++) {
            double dt = (obs.get(i).getTime().toEpochMilli() - obs.get(i - 1).getTime().toEpochMilli()) / 1000.0;
            if (dt <= 0.001) {
                continue;
            }
            double rate = Math.abs(BearingMath.shortestDelta(
                    obs.get(i - 1).getBearingDeg(), obs.get(i).getBearingDeg())) / dt;
            max = Math.max(max, rate);
        }
        return max;
    }

    private static double bearingSpanDeg(BearingTrack track) {
        if (track.getObservations().isEmpty()) {
            return 0;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (TrackObservation o : track.getObservations()) {
            double az = BearingMath.normalize360(o.getBearingDeg());
            min = Math.min(min, az);
            max = Math.max(max, az);
        }
        return Math.min(Math.abs(max - min), 360 - Math.abs(max - min));
    }
}
