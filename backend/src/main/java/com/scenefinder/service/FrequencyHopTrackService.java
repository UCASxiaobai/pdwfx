package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.FrequencyBandUtils;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.TrackObservation;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TOPK 场景之后的换频研判：在已确认轨迹的<strong>末尾</strong>与其他确认轨的<strong>开头</strong>
 * （及反向）做方位/时间衔接。异频记换频，同频仅续接不记 hop。
 * 输出<strong>单张</strong>全批统一视图（不按场景/频率拆 Tab）；噪声链直接丢弃不入图。
 */
@Service
public class FrequencyHopTrackService {

    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA);
    private static final String[] COLORS = {
            "#D95319", "#0072BD", "#77AC30", "#4DBEEE", "#A2142F", "#7E2F8E"
    };
    private static final int MAX_DISPLAY_TARGETS = 40;
    /** 超过此间隙（秒）用更紧的方位门，减轻密条带误并 */
    private static final double LONG_GAP_THRESHOLD_SEC = 8.0;

    /**
     * 构建全批统一换频轨迹视图（列表至多 1 项）；关闭 {@code enableFreqHopAnalysis} 时返回空列表。
     */
    public List<Map<String, Object>> buildHoppingTrackViews(
            List<QualityScene> scenes,
            Map<Integer, BearingTrack> trackById,
            List<DetectionPoint> allPoints,
            SceneFinderProperties props,
            ZoneId zone
    ) {
        if (props == null || !props.isEnableFreqHopAnalysis()
                || trackById == null || trackById.isEmpty()) {
            return Collections.emptyList();
        }

        Instant w0 = null;
        Instant w1 = null;
        if (allPoints != null) {
            for (DetectionPoint p : allPoints) {
                if (p == null || p.getTime() == null) {
                    continue;
                }
                Instant t = p.getTime();
                if (w0 == null || t.isBefore(w0)) {
                    w0 = t;
                }
                if (w1 == null || t.isAfter(w1)) {
                    w1 = t;
                }
            }
        }
        if (w0 == null || w1 == null) {
            if (scenes != null) {
                for (QualityScene scene : scenes) {
                    if (scene == null) {
                        continue;
                    }
                    Instant s = scene.getWindowStart();
                    Instant e = scene.getWindowEnd();
                    if (s != null && (w0 == null || s.isBefore(w0))) {
                        w0 = s;
                    }
                    if (e != null && (w1 == null || e.isAfter(w1))) {
                        w1 = e;
                    }
                }
            }
        }
        if (w0 == null || w1 == null) {
            for (BearingTrack t : trackById.values()) {
                if (t == null || t.getObservations().isEmpty()) {
                    continue;
                }
                Instant ts = t.startTime();
                Instant te = t.endTime();
                if (ts != null && (w0 == null || ts.isBefore(w0))) {
                    w0 = ts;
                }
                if (te != null && (w1 == null || te.isAfter(w1))) {
                    w1 = te;
                }
            }
        }
        if (w0 == null || w1 == null) {
            return Collections.emptyList();
        }

        List<BearingTrack> candidates = new ArrayList<>();
        for (BearingTrack t : trackById.values()) {
            if (t == null || t.getObservations().isEmpty()) {
                continue;
            }
            Instant ts = t.startTime();
            Instant te = t.endTime();
            if (ts == null || te == null) {
                continue;
            }
            if (te.isBefore(w0) || ts.isAfter(w1)) {
                continue;
            }
            candidates.add(t);
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(candidates, new Comparator<BearingTrack>() {
            @Override
            public int compare(BearingTrack a, BearingTrack b) {
                Instant sa = a.startTime();
                Instant sb = b.startTime();
                if (sa == null && sb == null) {
                    return Integer.compare(a.getId(), b.getId());
                }
                if (sa == null) {
                    return 1;
                }
                if (sb == null) {
                    return -1;
                }
                int c = sa.compareTo(sb);
                return c != 0 ? c : Integer.compare(a.getId(), b.getId());
            }
        });

        Set<Integer> consumed = new HashSet<>();
        List<Map<String, Object>> trackMaps = new ArrayList<>();
        int hopTrackCount = 0;
        int noiseSkipped = 0;
        double yMin = Double.POSITIVE_INFINITY;
        double yMax = Double.NEGATIVE_INFINITY;
        int displayIdx = 0;

        for (BearingTrack seed : candidates) {
            if (seed == null || consumed.contains(seed.getId())) {
                continue;
            }
            HopChain chain = linkTracksFromSeed(seed, candidates, props, consumed);
            if (chain.observations.isEmpty()) {
                continue;
            }
            boolean noise = chain.hits < props.getHopMinPoints()
                    || chain.durationSeconds() < props.getHopMinSeconds();
            if (noise) {
                noiseSkipped++;
                continue;
            }
            boolean hasHop = !chain.hops.isEmpty();
            if (hasHop) {
                hopTrackCount++;
            }

            displayIdx++;
            String color = COLORS[(displayIdx - 1) % COLORS.length];
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("trackId", seed.getId());
            tm.put("seedTrackId", seed.getId());
            tm.put("linkedTrackIds", chain.linkedTrackIds);
            tm.put("label", "目标" + displayIdx);
            tm.put("color", color);
            tm.put("noiseCandidate", false);
            tm.put("hasFreqHop", hasHop);
            tm.put("hitCount", chain.hits);
            tm.put("durationSec", round2(chain.durationSeconds()));
            tm.put("seedFreqMhz", round3(FrequencyBandUtils.dominantFrequencyMhz(seed)));
            tm.put("freqMhz", round3(FrequencyBandUtils.dominantFrequencyMhz(seed)));
            tm.put("meanBearing", round2(meanBearing(chain.observations)));
            tm.put("hops", chain.hops);
            if (chain.platformType != null) {
                tm.put("targetType", chain.platformType);
                tm.put("targetTypeLabel", platformTypeLabel(chain.platformType));
            }
            if (chain.platformTypes != null && !chain.platformTypes.isEmpty()) {
                tm.put("targetTypes", chain.platformTypes);
            }
            List<Map<String, Object>> bearingPts = new ArrayList<>();
            for (TrackObservation o : chain.observations) {
                long x = o.getTime().toEpochMilli();
                double y = o.getBearingDeg();
                Map<String, Object> bp = new LinkedHashMap<>();
                bp.put("x", x);
                bp.put("y", round2(y));
                bp.put("freqMhz", round3(o.getFrequencyMhz()));
                bearingPts.add(bp);
                yMin = Math.min(yMin, y);
                yMax = Math.max(yMax, y);
            }
            tm.put("points", bearingPts);
            trackMaps.add(tm);
            if (trackMaps.size() >= MAX_DISPLAY_TARGETS) {
                break;
            }
        }

        if (trackMaps.isEmpty()) {
            return Collections.emptyList();
        }
        if (!Double.isFinite(yMin)) {
            yMin = 0;
            yMax = 360;
        }
        double padY = Math.max(2.0, (yMax - yMin) * 0.08);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("unified", true);
        view.put("sceneRank", null);
        view.put("title", "换频研判（本批全部有效检测建轨后融合 · 不按场景拆分）");
        view.put("timeRange", formatRange(w0, w1, zone));
        view.put("xMin", w0.toEpochMilli());
        view.put("xMax", w1.toEpochMilli());
        view.put("yMin", round2(yMin - padY));
        view.put("yMax", round2(yMax + padY));
        view.put("trackCount", trackMaps.size());
        view.put("hopTrackCount", hopTrackCount);
        view.put("noiseSkippedCount", noiseSkipped);
        view.put("note", String.format(Locale.ROOT,
                "本批全部有效检测一张图（不按场景拆分）；仅轨间衔接处标记换频；短于 %d 点或 %.0fs 的链视为噪声并隐藏。",
                props.getHopMinPoints(), props.getHopMinSeconds()));
        view.put("tracks", trackMaps);

        // #region agent log
        try {
            java.util.Set<Double> sceneFreqs = new java.util.HashSet<>();
            if (scenes != null) {
                for (QualityScene sc : scenes) {
                    if (sc != null) {
                        sceneFreqs.add(Double.valueOf(round3(sc.getFreqCenterMhz())));
                    }
                }
            }
            java.util.List<Map<String, Object>> hopGaps = new ArrayList<>();
            for (Map<String, Object> tm : trackMaps) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> hops = (List<Map<String, Object>>) tm.get("hops");
                if (hops == null) {
                    continue;
                }
                for (Map<String, Object> h : hops) {
                    double toF = h.get("toFreqMhz") instanceof Number
                            ? ((Number) h.get("toFreqMhz")).doubleValue() : Double.NaN;
                    double fromF = h.get("fromFreqMhz") instanceof Number
                            ? ((Number) h.get("fromFreqMhz")).doubleValue() : Double.NaN;
                    boolean toInScene = false;
                    boolean fromInScene = false;
                    for (Double sf : sceneFreqs) {
                        if (sf == null) {
                            continue;
                        }
                        if (Math.abs(sf.doubleValue() - toF) <= 0.01) {
                            toInScene = true;
                        }
                        if (Math.abs(sf.doubleValue() - fromF) <= 0.01) {
                            fromInScene = true;
                        }
                    }
                    if (!toInScene || !fromInScene
                            || Math.abs(toF - 460.625) <= 0.01
                            || Math.abs(fromF - 246.075) <= 0.01) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("label", tm.get("label"));
                        row.put("linkedTrackIds", tm.get("linkedTrackIds"));
                        row.put("fromFreqMhz", h.get("fromFreqMhz"));
                        row.put("toFreqMhz", h.get("toFreqMhz"));
                        row.put("fromTrackId", h.get("fromTrackId"));
                        row.put("toTrackId", h.get("toTrackId"));
                        row.put("fromInSelectedScenes", Boolean.valueOf(fromInScene));
                        row.put("toInSelectedScenes", Boolean.valueOf(toInScene));
                        hopGaps.add(row);
                    }
                }
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", "0cb39e");
            payload.put("runId", "pre-fix");
            payload.put("hypothesisId", "A,C");
            payload.put("location", "FrequencyHopTrackService.java:buildHoppingTrackViews");
            payload.put("message", "hop links vs selected scene freqs");
            payload.put("timestamp", Long.valueOf(System.currentTimeMillis()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("selectedSceneCount", Integer.valueOf(sceneFreqs.size()));
            data.put("selectedSceneFreqs", new ArrayList<>(sceneFreqs));
            data.put("hopGapOrInterest", hopGaps);
            data.put("hopTrackCount", Integer.valueOf(hopTrackCount));
            payload.put("data", data);
            String line = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload) + "\n";
            java.nio.file.Path logPath = java.nio.file.Paths.get("D:/Documents/Code/Java/pdwfx/debug-0cb39e.log");
            java.nio.file.Files.write(logPath, line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // debug only
        }
        // #endregion

        return Collections.singletonList(view);
    }

    private HopChain linkTracksFromSeed(
            BearingTrack seed,
            List<BearingTrack> candidates,
            SceneFinderProperties props,
            Set<Integer> consumed
    ) {
        List<BearingTrack> chainTracks = new ArrayList<>();
        chainTracks.add(seed);
        consumed.add(seed.getId());

        while (true) {
            BearingTrack tip = chainTracks.get(chainTracks.size() - 1);
            BearingTrack next = bestForwardLink(tip, candidates, props, consumed);
            if (next == null) {
                break;
            }
            chainTracks.add(next);
            consumed.add(next.getId());
        }
        while (true) {
            BearingTrack tip = chainTracks.get(0);
            BearingTrack prev = bestBackwardLink(tip, candidates, props, consumed);
            if (prev == null) {
                break;
            }
            chainTracks.add(0, prev);
            consumed.add(prev.getId());
        }

        List<TrackObservation> merged = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        List<Map<String, Object>> hops = new ArrayList<>();
        double deltaMhz = hopDeltaMhz(props);

        for (int i = 0; i < chainTracks.size(); i++) {
            BearingTrack t = chainTracks.get(i);
            ids.add(t.getId());
            if (i > 0) {
                BearingTrack prev = chainTracks.get(i - 1);
                TrackObservation a = lastObs(prev);
                TrackObservation b = firstObs(t);
                if (a != null && b != null
                        && Math.abs(b.getFrequencyMhz() - a.getFrequencyMhz()) > deltaMhz) {
                    Map<String, Object> hop = new LinkedHashMap<>();
                    hop.put("atMs", b.getTime().toEpochMilli());
                    hop.put("fromFreqMhz", round3(FrequencyBandUtils.dominantFrequencyMhz(prev)));
                    hop.put("toFreqMhz", round3(FrequencyBandUtils.dominantFrequencyMhz(t)));
                    hop.put("bearingDeg", round2(b.getBearingDeg()));
                    hop.put("fromTrackId", prev.getId());
                    hop.put("toTrackId", t.getId());
                    hops.add(hop);
                }
            }
            for (TrackObservation o : t.getObservations()) {
                if (o != null && o.getTime() != null) {
                    merged.add(o);
                }
            }
        }

        HopChain chain = new HopChain();
        chain.observations = merged;
        chain.hits = merged.size();
        chain.hops = hops;
        chain.linkedTrackIds = ids;
        chain.platformTypes = suggestedTypes(chainTracks);
        chain.platformType = majorityPlatformType(chainTracks);
        return chain;
    }

    private BearingTrack bestForwardLink(
            BearingTrack from,
            List<BearingTrack> candidates,
            SceneFinderProperties props,
            Set<Integer> consumed
    ) {
        TrackObservation end = lastObs(from);
        if (end == null) {
            return null;
        }
        double maxGapSec = linkGapSec(props);
        double fromVel = terminalVelocity(from, true);

        BearingTrack best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (BearingTrack cand : candidates) {
            if (cand == null || consumed.contains(cand.getId()) || cand.getId() == from.getId()) {
                continue;
            }
            TrackObservation start = firstObs(cand);
            if (start == null) {
                continue;
            }
            long gapMs = start.getTime().toEpochMilli() - end.getTime().toEpochMilli();
            if (gapMs < 0) {
                continue;
            }
            double gapSec = gapMs / 1000.0;
            if (gapSec > maxGapSec) {
                continue;
            }
            double dt = Math.max(0.001, gapSec);
            double predicted = end.getBearingDeg() + fromVel * dt;
            double azErr = Math.abs(BearingMath.shortestDelta(predicted, start.getBearingDeg()));
            if (azErr > azimuthGateDeg(props, gapSec)) {
                continue;
            }
            if (azErr / dt > props.getMaxTrackBearingRateDegPerSec()) {
                continue;
            }
            double cost = azErr + gapSec * 0.5;
            if (cost < bestCost) {
                bestCost = cost;
                best = cand;
            }
        }
        return best;
    }

    private BearingTrack bestBackwardLink(
            BearingTrack to,
            List<BearingTrack> candidates,
            SceneFinderProperties props,
            Set<Integer> consumed
    ) {
        TrackObservation start = firstObs(to);
        if (start == null) {
            return null;
        }
        double maxGapSec = linkGapSec(props);
        double toVel = terminalVelocity(to, false);

        BearingTrack best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (BearingTrack cand : candidates) {
            if (cand == null || consumed.contains(cand.getId()) || cand.getId() == to.getId()) {
                continue;
            }
            TrackObservation end = lastObs(cand);
            if (end == null) {
                continue;
            }
            long gapMs = start.getTime().toEpochMilli() - end.getTime().toEpochMilli();
            if (gapMs < 0) {
                continue;
            }
            double gapSec = gapMs / 1000.0;
            if (gapSec > maxGapSec) {
                continue;
            }
            double dt = Math.max(0.001, gapSec);
            double predicted = start.getBearingDeg() - toVel * dt;
            double azErr = Math.abs(BearingMath.shortestDelta(predicted, end.getBearingDeg()));
            if (azErr > azimuthGateDeg(props, gapSec)) {
                continue;
            }
            if (azErr / dt > props.getMaxTrackBearingRateDegPerSec()) {
                continue;
            }
            double cost = azErr + gapSec * 0.5;
            if (cost < bestCost) {
                bestCost = cost;
                best = cand;
            }
        }
        return best;
    }

    private double linkGapSec(SceneFinderProperties props) {
        return props.getHopLinkMaxGapSec();
    }

    private double azimuthGateDeg(SceneFinderProperties props, double gapSec) {
        double base = props.getAssociationGateDeg();
        if (gapSec > LONG_GAP_THRESHOLD_SEC) {
            return Math.min(base, props.getHopLinkLongGapGateDeg());
        }
        return base;
    }

    private double hopDeltaMhz(SceneFinderProperties props) {
        double configured = props.getHopMinDeltaMhz();
        if (configured > 0) {
            return configured;
        }
        return Math.max(0.01, props.getFreqClusterGapMhz());
    }

    private static TrackObservation firstObs(BearingTrack t) {
        List<TrackObservation> obs = t.getObservations();
        return obs.isEmpty() ? null : obs.get(0);
    }

    private static TrackObservation lastObs(BearingTrack t) {
        List<TrackObservation> obs = t.getObservations();
        return obs.isEmpty() ? null : obs.get(obs.size() - 1);
    }

    private static double terminalVelocity(BearingTrack track, boolean atEnd) {
        List<TrackObservation> obs = track.getObservations();
        if (obs.size() < 2) {
            return 0;
        }
        TrackObservation a;
        TrackObservation b;
        if (atEnd) {
            a = obs.get(obs.size() - 2);
            b = obs.get(obs.size() - 1);
        } else {
            a = obs.get(0);
            b = obs.get(1);
        }
        double dt = Math.max(0.001,
                (b.getTime().toEpochMilli() - a.getTime().toEpochMilli()) / 1000.0);
        return BearingMath.shortestDelta(a.getBearingDeg(), b.getBearingDeg()) / dt;
    }

    private static double meanBearing(List<TrackObservation> obs) {
        if (obs == null || obs.isEmpty()) {
            return 0;
        }
        double s = 0;
        for (TrackObservation o : obs) {
            s += o.getBearingDeg();
        }
        return s / obs.size();
    }

    private static String formatRange(Instant start, Instant end, ZoneId zone) {
        ZoneId z = zone == null ? ZoneId.systemDefault() : zone;
        return CLOCK.format(start.atZone(z)) + " – " + CLOCK.format(end.atZone(z));
    }

    private static List<String> suggestedTypes(List<BearingTrack> tracks) {
        List<String> out = new ArrayList<>();
        for (BearingTrack t : tracks) {
            String p = t.getSuggestedPlatformType();
            if (!isPlatformType(p) || out.contains(p)) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private static String majorityPlatformType(List<BearingTrack> tracks) {
        int ground = 0;
        int awacs = 0;
        int air = 0;
        for (BearingTrack t : tracks) {
            String p = t.getSuggestedPlatformType();
            if ("GROUND".equals(p)) {
                ground++;
            } else if ("AWACS".equals(p)) {
                awacs++;
            } else if ("AIR".equals(p)) {
                air++;
            }
        }
        if (ground == 0 && awacs == 0 && air == 0) {
            return null;
        }
        if (ground >= awacs && ground >= air) {
            return "GROUND";
        }
        if (awacs >= air) {
            return "AWACS";
        }
        return "AIR";
    }

    private static boolean isPlatformType(String p) {
        return "GROUND".equals(p) || "AWACS".equals(p) || "AIR".equals(p);
    }

    private static String platformTypeLabel(String type) {
        if ("GROUND".equals(type)) {
            return "地面站";
        }
        if ("AWACS".equals(type)) {
            return "预警机";
        }
        if ("AIR".equals(type)) {
            return "飞机";
        }
        return type;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static final class HopChain {
        private List<TrackObservation> observations = Collections.emptyList();
        private int hits;
        private List<Map<String, Object>> hops = Collections.emptyList();
        private List<Integer> linkedTrackIds = Collections.emptyList();
        private String platformType;
        private List<String> platformTypes = Collections.emptyList();

        private double durationSeconds() {
            if (observations == null || observations.size() < 2) {
                return 0;
            }
            long t0 = observations.get(0).getTime().toEpochMilli();
            long t1 = observations.get(observations.size() - 1).getTime().toEpochMilli();
            return Math.max(0, (t1 - t0) / 1000.0);
        }
    }
}
