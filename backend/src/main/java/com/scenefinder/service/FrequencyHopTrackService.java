package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.FrequencyBandUtils;
import com.scenefinder.model.HopBatch;
import com.scenefinder.model.HopBatchGrouping;
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
     * 展示仍截断 {@link #MAX_DISPLAY_TARGETS}；匹配编批见 {@link #buildHopBatches}。
     */
    public List<Map<String, Object>> buildHoppingTrackViews(
            List<QualityScene> scenes,
            Map<Integer, BearingTrack> trackById,
            List<DetectionPoint> allPoints,
            SceneFinderProperties props,
            ZoneId zone
    ) {
        HopBatchGrouping grouping = buildHopBatches(scenes, trackById, allPoints, props);
        return toHoppingTrackViews(grouping, props, zone);
    }

    /**
     * 换频链 + 未入链单轨编批。过噪声门的链为 {@code hop:{seedId}}，噪声/短轨为 {@code track:{id}}，
     * 不受展示条数限制。
     */
    public HopBatchGrouping buildHopBatches(
            List<QualityScene> scenes,
            Map<Integer, BearingTrack> trackById,
            List<DetectionPoint> allPoints,
            SceneFinderProperties props
    ) {
        HopBatchGrouping grouping = new HopBatchGrouping();
        if (props == null || !props.isEnableFreqHopAnalysis()
                || trackById == null || trackById.isEmpty()) {
            return grouping;
        }

        Instant[] window = resolveAnalysisWindow(scenes, trackById, allPoints);
        if (window == null) {
            return grouping;
        }
        Instant w0 = window[0];
        Instant w1 = window[1];
        grouping.setWindowStart(w0);
        grouping.setWindowEnd(w1);

        List<BearingTrack> candidates = collectWindowTracks(trackById, w0, w1);
        if (candidates.isEmpty()) {
            return grouping;
        }

        Set<Integer> consumed = new HashSet<Integer>();
        int hopTrackCount = 0;
        int noiseSkipped = 0;
        int displayIdx = 0;

        for (BearingTrack seed : candidates) {
            if (seed == null || consumed.contains(Integer.valueOf(seed.getId()))) {
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
                for (BearingTrack t : chain.tracks) {
                    grouping.add(singletonTrackBatch(t));
                }
                continue;
            }
            boolean hasHop = !chain.hops.isEmpty();
            if (hasHop) {
                hopTrackCount++;
            }
            displayIdx++;
            grouping.add(hopChainBatch(seed, chain, "目标" + displayIdx, hasHop));
        }
        grouping.setNoiseSkippedCount(noiseSkipped);
        grouping.setHopTrackCount(hopTrackCount);
        return grouping;
    }

    List<Map<String, Object>> toHoppingTrackViews(
            HopBatchGrouping grouping,
            SceneFinderProperties props,
            ZoneId zone
    ) {
        if (grouping == null || grouping.getWindowStart() == null || grouping.getWindowEnd() == null) {
            return Collections.emptyList();
        }
        Instant w0 = grouping.getWindowStart();
        Instant w1 = grouping.getWindowEnd();

        List<Map<String, Object>> trackMaps = new ArrayList<Map<String, Object>>();
        double yMin = Double.POSITIVE_INFINITY;
        double yMax = Double.NEGATIVE_INFINITY;
        int displayIdx = 0;
        int hopTrackCount = 0;

        for (HopBatch batch : grouping.getBatches()) {
            if (batch == null || batch.isNoise() || batch.getBatchId() == null
                    || !batch.getBatchId().startsWith("hop:")) {
                continue;
            }
            displayIdx++;
            if (batch.isHasFreqHop()) {
                hopTrackCount++;
            }
            String color = COLORS[(displayIdx - 1) % COLORS.length];
            Map<String, Object> tm = new LinkedHashMap<String, Object>();
            tm.put("trackId", Integer.valueOf(batch.getSeedTrackId()));
            tm.put("seedTrackId", Integer.valueOf(batch.getSeedTrackId()));
            tm.put("linkedTrackIds", batch.getLinkedTrackIds());
            tm.put("label", batch.getLabel());
            tm.put("hopBatchId", batch.getBatchId());
            tm.put("color", color);
            tm.put("noiseCandidate", Boolean.FALSE);
            tm.put("hasFreqHop", Boolean.valueOf(batch.isHasFreqHop()));
            tm.put("hitCount", Integer.valueOf(batch.getHits()));
            tm.put("durationSec", Double.valueOf(round2(batch.getDurationSeconds())));
            tm.put("seedFreqMhz", Double.valueOf(round3(batch.getSeedFreqMhz())));
            tm.put("freqMhz", Double.valueOf(round3(batch.getSeedFreqMhz())));
            tm.put("meanBearing", Double.valueOf(round2(meanBearing(batch.getObservations()))));
            tm.put("hops", batch.getHops());
            if (batch.getPlatformType() != null) {
                tm.put("targetType", batch.getPlatformType());
                tm.put("targetTypeLabel", platformTypeLabel(batch.getPlatformType()));
            }
            if (batch.getPlatformTypes() != null && !batch.getPlatformTypes().isEmpty()) {
                tm.put("targetTypes", batch.getPlatformTypes());
            }
            List<Map<String, Object>> bearingPts = new ArrayList<Map<String, Object>>();
            if (batch.getObservations() != null) {
                for (TrackObservation o : batch.getObservations()) {
                    if (o == null || o.getTime() == null) {
                        continue;
                    }
                    long x = o.getTime().toEpochMilli();
                    double y = o.getBearingDeg();
                    Map<String, Object> bp = new LinkedHashMap<String, Object>();
                    bp.put("x", Long.valueOf(x));
                    bp.put("y", Double.valueOf(round2(y)));
                    bp.put("freqMhz", Double.valueOf(round3(o.getFrequencyMhz())));
                    bearingPts.add(bp);
                    yMin = Math.min(yMin, y);
                    yMax = Math.max(yMax, y);
                }
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

        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("unified", Boolean.TRUE);
        view.put("sceneRank", null);
        view.put("title", "换频研判（本批全部有效检测建轨后融合 · 不按场景拆分）");
        view.put("timeRange", formatRange(w0, w1, zone));
        view.put("xMin", Long.valueOf(w0.toEpochMilli()));
        view.put("xMax", Long.valueOf(w1.toEpochMilli()));
        view.put("yMin", Double.valueOf(round2(yMin - padY)));
        view.put("yMax", Double.valueOf(round2(yMax + padY)));
        view.put("trackCount", Integer.valueOf(trackMaps.size()));
        view.put("hopTrackCount", Integer.valueOf(hopTrackCount));
        view.put("noiseSkippedCount", Integer.valueOf(grouping.getNoiseSkippedCount()));
        view.put("note", String.format(Locale.ROOT,
                "本批全部有效检测一张图（不按场景拆分）；仅轨间衔接处标记换频；短于 %d 点或 %.0fs 的链视为噪声并隐藏。",
                props.getHopMinPoints(), props.getHopMinSeconds()));
        view.put("tracks", trackMaps);
        return Collections.singletonList(view);
    }

    private Instant[] resolveAnalysisWindow(
            List<QualityScene> scenes,
            Map<Integer, BearingTrack> trackById,
            List<DetectionPoint> allPoints
    ) {
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
            return null;
        }
        return new Instant[]{w0, w1};
    }

    private List<BearingTrack> collectWindowTracks(
            Map<Integer, BearingTrack> trackById,
            Instant w0,
            Instant w1
    ) {
        List<BearingTrack> candidates = new ArrayList<BearingTrack>();
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
        return candidates;
    }

    private static HopBatch hopChainBatch(BearingTrack seed, HopChain chain, String label, boolean hasHop) {
        HopBatch batch = new HopBatch();
        batch.setBatchId("hop:" + seed.getId());
        batch.setLabel(label);
        batch.setSeedTrackId(seed.getId());
        batch.setLinkedTrackIds(chain.linkedTrackIds);
        batch.setNoise(false);
        batch.setHasFreqHop(hasHop);
        batch.setHits(chain.hits);
        batch.setDurationSeconds(chain.durationSeconds());
        batch.setSeedFreqMhz(FrequencyBandUtils.dominantFrequencyMhz(seed));
        batch.setPlatformType(chain.platformType);
        batch.setPlatformTypes(chain.platformTypes);
        batch.setHops(chain.hops);
        batch.setObservations(chain.observations);
        return batch;
    }

    private static HopBatch singletonTrackBatch(BearingTrack track) {
        List<TrackObservation> obs = new ArrayList<TrackObservation>();
        if (track.getObservations() != null) {
            for (TrackObservation o : track.getObservations()) {
                if (o != null && o.getTime() != null) {
                    obs.add(o);
                }
            }
        }
        HopBatch batch = new HopBatch();
        batch.setBatchId("track:" + track.getId());
        batch.setLabel("单轨" + track.getId());
        batch.setSeedTrackId(track.getId());
        batch.setLinkedTrackIds(Collections.singletonList(Integer.valueOf(track.getId())));
        batch.setNoise(true);
        batch.setHasFreqHop(false);
        batch.setHits(obs.size());
        batch.setDurationSeconds(durationSeconds(obs));
        batch.setSeedFreqMhz(FrequencyBandUtils.dominantFrequencyMhz(track));
        batch.setPlatformType(track.getSuggestedPlatformType());
        batch.setObservations(obs);
        return batch;
    }

    private static double durationSeconds(List<TrackObservation> observations) {
        if (observations == null || observations.size() < 2) {
            return 0;
        }
        long t0 = observations.get(0).getTime().toEpochMilli();
        long t1 = observations.get(observations.size() - 1).getTime().toEpochMilli();
        return Math.max(0, (t1 - t0) / 1000.0);
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
        chain.tracks = chainTracks;
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
        private List<BearingTrack> tracks = Collections.emptyList();
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
