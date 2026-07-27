package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scenefinder.model.BearingMath;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.PeriodFormatUtils;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SceneType;
import com.scenefinder.model.TrackObservation;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 生成交互式 {@code visualization.html}：场景汇总表 + Chart.js 方位-时间轨迹图。
 * <p>
 * 数据来自 {@link QualityScene} 与 {@link BearingTrack}；不单独配置参数，
 * 场景个数由分析时的 {@code topKScenes} 决定，轨迹图 tab 受 {@link #MAX_SCENES} 限制。
 * </p>
 */
@Service
public class VisualizationService {

    private static final DateTimeFormatter DATE_LABEL =
            DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA);
    private static final DateTimeFormatter CLOCK =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.CHINA);

    /**
     * 轨迹图 tab 数量上限。场景表展示 topKScenes 条；超过此值的场景仅有表格无轨迹图切换按钮。
     * 需更多 tab 时增大此常量并重新编译。
     */
    private static final int MAX_SCENES = 40;

    /** 每个场景轨迹图中最多绘制的代表轨迹条数（按平均方位均匀选取）。 */
    private static final int MAX_TRACKS_PER_SCENE = 10;
    private static final String[] TARGET_COLORS = {
            "#D95319", "#0072BD", "#77AC30", "#4DBEEE", "#A2142F", "#7E2F8E"
    };

    private final ObjectMapper objectMapper;
    private final SceneFinderProperties sceneFinderProperties;

    public VisualizationService(ObjectMapper objectMapper, SceneFinderProperties sceneFinderProperties) {
        this.objectMapper = objectMapper;
        this.sceneFinderProperties = sceneFinderProperties;
    }

    /**
     * 构建前端可视化所需 JSON（场景表 + 方位-时间轨迹图数据）。
     */
    public Map<String, Object> buildPayload(
            List<QualityScene> scenes,
            List<BearingTrack> tracks,
            Map<Integer, BearingTrack> trackById,
            List<DetectionPoint> allPoints,
            int totalDetections,
            ZoneId zone,
            double frameSeconds,
            int maxScatterPoints,
            boolean includeImportScatter
    ) {
        int scatterCap = Math.max(500, maxScatterPoints);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalDetections", totalDetections);
        payload.put("confirmedTracks", tracks.size());
        payload.put("sceneCount", scenes.size());
        payload.put("scenes", buildSceneRows(scenes, zone));
        payload.put("trajectoryViews", buildTrajectoryViews(
                scenes, trackById, allPoints, zone, frameSeconds, scatterCap));
        if (includeImportScatter) {
            payload.put("importScatter", buildImportScatterByFreq(allPoints, scatterCap));
        }
        return payload;
    }

    public Map<String, Object> buildPayload(
            List<QualityScene> scenes,
            List<BearingTrack> tracks,
            Map<Integer, BearingTrack> trackById,
            List<DetectionPoint> allPoints,
            int totalDetections,
            ZoneId zone,
            double frameSeconds,
            int maxScatterPoints
    ) {
        return buildPayload(
                scenes, tracks, trackById, allPoints, totalDetections,
                zone, frameSeconds, maxScatterPoints, true);
    }

    public String exportHtml(
            Path outputDir,
            List<QualityScene> scenes,
            List<BearingTrack> tracks,
            Map<Integer, BearingTrack> trackById,
            List<DetectionPoint> allPoints,
            int totalDetections,
            ZoneId zone,
            double frameSeconds,
            int maxScatterPoints
    ) throws IOException {
        return exportHtml(outputDir, buildPayload(
                scenes, tracks, trackById, allPoints, totalDetections, zone, frameSeconds, maxScatterPoints));
    }

    public String exportJson(Path outputDir, Map<String, Object> payload) throws IOException {
        Path file = outputDir.resolve("visualization-data.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), payload);
        return file.toString();
    }

    public Map<String, Object> readJson(Path outputDir) throws IOException {
        Path file = outputDir.resolve("visualization-data.json");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("visualization-data.json not found in: " + outputDir);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.readValue(file.toFile(), Map.class);
        return payload;
    }

    public String exportHtml(Path outputDir, Map<String, Object> payload) throws IOException {
        String json = objectMapper.writeValueAsString(payload);
        Path file = outputDir.resolve("visualization.html");
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(renderPage(json));
        }
        return file.toString();
    }

    private static final String[] FREQ_COLORS = {
            "#D95319", "#0072BD", "#77AC30", "#4DBEEE", "#A2142F", "#7E2F8E",
            "#EDB120", "#636363", "#00A9CE", "#8C564B", "#9467BD", "#17BECF"
    };
    /** 全量散点图最多着色的频点数（与 FREQ_COLORS 一致） */
    private static final int MAX_IMPORT_SCATTER_FREQS = FREQ_COLORS.length;

    private Map<String, Object> buildImportScatterByFreq(List<DetectionPoint> allPoints, int maxScatterPoints) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (allPoints == null || allPoints.isEmpty()) {
            result.put("totalPoints", 0);
            result.put("displayedPoints", 0);
            result.put("totalFreqCount", 0);
            result.put("plottedFreqCount", 0);
            result.put("omittedFreqCount", 0);
            result.put("series", Collections.emptyList());
            return result;
        }
        Map<Double, List<DetectionPoint>> byFreq = new LinkedHashMap<>();
        for (DetectionPoint p : allPoints) {
            double key = round3(p.getFrequencyMhz());
            byFreq.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        List<Map.Entry<Double, List<DetectionPoint>>> ranked = new ArrayList<>(byFreq.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

        int totalFreqCount = ranked.size();
        int plotCount = Math.min(MAX_IMPORT_SCATTER_FREQS, totalFreqCount);
        int omittedFreqCount = totalFreqCount - plotCount;
        int perFreqCap = Math.max(300, maxScatterPoints / Math.max(1, plotCount));

        List<Map<String, Object>> series = new ArrayList<>();
        int displayed = 0;
        for (int i = 0; i < plotCount; i++) {
            Map.Entry<Double, List<DetectionPoint>> entry = ranked.get(i);
            List<DetectionPoint> band = new ArrayList<>(entry.getValue());
            band.sort(Comparator.comparing(DetectionPoint::getTime));
            ScatterBuild build = buildDownsampledScatter(band, perFreqCap);
            List<List<Object>> points = new ArrayList<>(build.scatter.size());
            for (Map<String, Object> pt : build.scatter) {
                points.add(java.util.Arrays.asList(pt.get("x"), pt.get("y")));
            }
            displayed += points.size();
            Map<String, Object> ser = new LinkedHashMap<>();
            ser.put("freqMhz", entry.getKey());
            ser.put("label", String.format(Locale.ROOT, "%.3f MHz", entry.getKey()));
            ser.put("color", FREQ_COLORS[i % FREQ_COLORS.length]);
            ser.put("points", points);
            ser.put("totalPoints", band.size());
            ser.put("displayedPoints", points.size());
            series.add(ser);
        }
        result.put("totalPoints", allPoints.size());
        result.put("displayedPoints", displayed);
        result.put("totalFreqCount", totalFreqCount);
        result.put("plottedFreqCount", plotCount);
        result.put("omittedFreqCount", omittedFreqCount);
        result.put("freqCount", plotCount);
        result.put("series", series);
        return result;
    }

    private List<Map<String, Object>> buildSceneRows(List<QualityScene> scenes, ZoneId zone) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QualityScene scene : scenes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", scene.getRank());
            row.put("windowStart", scene.getWindowStart().atZone(zone).format(CLOCK));
            row.put("windowEnd", scene.getWindowEnd().atZone(zone).format(CLOCK));
            row.put("freqCenterMhz", scene.getFreqCenterMhz());
            row.put("distinctDeviceCount", scene.getDistinctDeviceCount());
            row.put("freqMinMhz", scene.getFreqMinMhz());
            row.put("freqMaxMhz", scene.getFreqMaxMhz());
            row.put("score", scene.getScore());
            row.put("trackCount", scene.getTrackCount());
            row.put("medianSeparationDeg", scene.getMedianSeparationDeg());
            row.put("averageSmoothness", scene.getAverageSmoothness());
            row.put("sceneType", scene.getSceneType().name());
            row.put("pollingPeriodSec", scene.getPollingPeriodSec());
            row.put("pollingPeriodLabel", PeriodFormatUtils.formatSecMsUs(scene.getPollingPeriodSec()));
            row.put("annotation", scene.getAnnotation());
            row.put("trackIds", scene.getTrackIds());
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> buildTrajectoryViews(
            List<QualityScene> scenes,
            Map<Integer, BearingTrack> trackById,
            List<DetectionPoint> allPoints,
            ZoneId zone,
            double frameSeconds,
            int maxScatterPoints
    ) {
        List<Map<String, Object>> views = new ArrayList<>();
        int limit = Math.min(MAX_SCENES, scenes.size());
        for (int i = 0; i < limit; i++) {
            QualityScene scene = scenes.get(i);
            if (scene.getSceneType() == SceneType.MULTI_DEVICE_POLLING) {
                views.add(buildPollingSceneView(scene, allPoints, trackById, zone, maxScatterPoints));
            } else {
                views.add(buildTrackSceneView(scene, trackById, zone, frameSeconds));
            }
        }
        return views;
    }

    private Map<String, Object> buildPollingSceneView(
            QualityScene scene,
            List<DetectionPoint> allPoints,
            Map<Integer, BearingTrack> trackById,
            ZoneId zone,
            int maxScatterPoints
    ) {
        List<DetectionPoint> inWindow = new ArrayList<>();
        for (DetectionPoint p : allPoints) {
            if (p.getTime().isBefore(scene.getWindowStart()) || p.getTime().isAfter(scene.getWindowEnd())) {
                continue;
            }
            if (p.getFrequencyMhz() < scene.getFreqMinMhz() - 0.01
                    || p.getFrequencyMhz() > scene.getFreqMaxMhz() + 0.01) {
                continue;
            }
            inWindow.add(p);
        }
        inWindow.sort(Comparator.comparing(DetectionPoint::getTime));

        int windowTotal = inWindow.size();
        List<DetectionPoint> plotPoints = inWindow;
        List<Map<String, Object>> pollingTargets = buildPollingTargetSeries(scene, trackById);
        boolean participantsOnly = !pollingTargets.isEmpty();
        if (participantsOnly) {
            plotPoints = collectPollingParticipantPoints(inWindow, scene, trackById);
        }

        ScatterBuild scatterBuild = buildDownsampledScatter(plotPoints, maxScatterPoints);
        List<Map<String, Object>> scatter = scatterBuild.scatter;
        double yMin = scatterBuild.yMin;
        double yMax = scatterBuild.yMax;
        long xMin = scatterBuild.xMin;
        long xMax = scatterBuild.xMax;

        List<DetectionPoint> annotationSample = annotationSample(plotPoints);
        Map<String, Object> chartAnnotations = buildPollingAnnotations(annotationSample, scene);
        if (participantsOnly) {
            chartAnnotations.put("explanation",
                    "仅显示已识别轮询目标在各轮 burst 中的测向点（共 "
                            + scene.getDistinctDeviceCount() + " 个目标）。");
        }

        double pad = Math.max(3.0, (yMax - yMin) * 0.08);
        if (!Double.isFinite(yMin)) {
            yMin = 0;
            yMax = 360;
        }
        if (xMin == Long.MAX_VALUE) {
            xMin = scene.getWindowStart().toEpochMilli();
            xMax = scene.getWindowEnd().toEpochMilli();
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("viewMode", "polling");
        view.put("sceneRank", scene.getRank());
        view.put("title", "场景 " + scene.getRank() + " · 多设备轮询通信");
        view.put("dateLabel", DATE_LABEL.format(scene.getWindowStart().atZone(zone)));
        view.put("timeRange", CLOCK.format(scene.getWindowStart().atZone(zone))
                + " — " + CLOCK.format(scene.getWindowEnd().atZone(zone)));
        view.put("trackCount", scene.getTrackCount());
        view.put("pollingParticipantsOnly", participantsOnly);
        view.put("pollingTargetCount", participantsOnly ? pollingTargets.size() : scene.getDistinctDeviceCount());
        view.put("windowPointTotal", windowTotal);
        view.put("scatterTotal", plotPoints.size());
        view.put("scatterDownsampled", plotPoints.size() > scatter.size());
        view.put("displayedTracks", scatter.size());
        view.put("xMin", xMin);
        view.put("xMax", xMax);
        view.put("yMin", Math.floor((yMin - pad) * 10) / 10.0);
        view.put("yMax", Math.ceil((yMax + pad) * 10) / 10.0);
        view.put("scatterPoints", scatter);
        view.put("pollingTargets", pollingTargets);
        view.put("chartAnnotations", chartAnnotations);
        view.put("note", scene.getAnnotation());
        return view;
    }

    /** 轮询 lane 已建轨时，仅保留参与轮询目标的测向点。 */
    private List<DetectionPoint> collectPollingParticipantPoints(
            List<DetectionPoint> inWindow,
            QualityScene scene,
            Map<Integer, BearingTrack> trackById
    ) {
        if (scene.getTrackIds().isEmpty()) {
            return inWindow;
        }
        java.util.Set<com.scenefinder.model.SourceRowRef> rows = new java.util.HashSet<>();
        for (Integer trackId : scene.getTrackIds()) {
            BearingTrack track = trackById.get(trackId);
            if (track == null) {
                continue;
            }
            for (TrackObservation obs : track.getObservations()) {
                rows.add(obs.sourceRow());
            }
        }
        if (rows.isEmpty()) {
            return inWindow;
        }
        List<DetectionPoint> filtered = new ArrayList<>();
        for (DetectionPoint p : inWindow) {
            if (rows.contains(p.sourceRow())) {
                filtered.add(p);
            }
        }
        return filtered;
    }

    private List<Map<String, Object>> buildPollingTargetSeries(
            QualityScene scene,
            Map<Integer, BearingTrack> trackById
    ) {
        if (scene.getTrackIds().isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> sortedIds = new ArrayList<>(scene.getTrackIds());
        sortedIds.sort(Integer::compareTo);
        List<Map<String, Object>> targets = new ArrayList<>();
        int index = 0;
        for (Integer trackId : sortedIds) {
            BearingTrack track = trackById.get(trackId);
            if (track == null || track.getObservations().isEmpty()) {
                continue;
            }
            List<Map<String, Object>> points = new ArrayList<>();
            for (TrackObservation obs : track.getObservations()) {
                if (obs.getTime().isBefore(scene.getWindowStart()) || obs.getTime().isAfter(scene.getWindowEnd())) {
                    continue;
                }
                Map<String, Object> pt = new LinkedHashMap<>();
                pt.put("x", obs.getTime().toEpochMilli());
                pt.put("y", round1(BearingMath.normalize360(obs.getBearingDeg())));
                points.add(pt);
            }
            if (points.isEmpty()) {
                continue;
            }
            index++;
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("trackId", trackId);
            target.put("label", "轮询目标" + index);
            target.put("color", TARGET_COLORS[(index - 1) % TARGET_COLORS.length]);
            target.put("points", points);
            targets.add(target);
        }
        return targets;
    }

    /**
     * 为轮询图生成注释：示例垂直簇、周期间隔示意。
     */
    private Map<String, Object> buildPollingAnnotations(List<DetectionPoint> inWindow, QualityScene scene) {
        Map<String, Object> ann = new LinkedHashMap<>();
        double periodSec = scene.getPollingPeriodSec();
        ann.put("periodSec", periodSec);
        ann.put("periodLabel", PeriodFormatUtils.formatSecMsUs(periodSec));
        long totalMicros = Math.round(periodSec * 1_000_000.0);
        ann.put("periodSecPart", totalMicros / 1_000_000L);
        ann.put("periodMsPart", (totalMicros % 1_000_000L) / 1000L);
        ann.put("periodUsPart", totalMicros % 1000L);
        ann.put("explanation",
                "短时窗内多个测向点视为多台设备同时发信（方位近距不合并为单设备连发）；"
                        + "空域混叠，但可通过轮询周期规律分辨");

        if (inWindow.size() < 3) {
            return ann;
        }

        long coalesceMillis = Math.max(1, Math.round(sceneFinderProperties.getPollingBurstCoalesceSec() * 1000.0));
        List<List<DetectionPoint>> groups = new ArrayList<>();
        int i = 0;
        while (i < inWindow.size()) {
            Instant start = inWindow.get(i).getTime();
            List<DetectionPoint> g = new ArrayList<>();
            g.add(inWindow.get(i));
            int j = i + 1;
            while (j < inWindow.size()
                    && inWindow.get(j).getTime().toEpochMilli() - start.toEpochMilli() <= coalesceMillis) {
                g.add(inWindow.get(j));
                j++;
            }
            i = j;
            List<Double> bearings = g.stream()
                    .map(p -> BearingMath.normalize360(p.getBearingDeg()))
                    .collect(Collectors.toList());
            int minDevices = Math.max(2, scene.getDistinctDeviceCount());
            if (BearingMath.countBearingClusters(bearings, 0.5) >= minDevices) {
                groups.add(g);
            }
        }

        if (!groups.isEmpty() && groups.size() >= 2) {
            long t0 = groups.get(0).get(0).getTime().toEpochMilli();
            long t1 = groups.get(1).get(0).getTime().toEpochMilli();
            ann.put("periodArrowX1", t0);
            ann.put("periodArrowX2", t1);
        }
        return ann;
    }

    private Map<String, Object> buildTrackSceneView(
            QualityScene scene,
            Map<Integer, BearingTrack> trackById,
            ZoneId zone,
            double frameSeconds
    ) {
        List<TrackSeries> candidates = new ArrayList<>();
        for (Integer trackId : scene.getTrackIds()) {
            BearingTrack track = trackById.get(trackId);
            if (track == null) {
                continue;
            }
            List<FramePoint> frames = aggregateByFrame(
                    track, scene.getWindowStart(), scene.getWindowEnd(), frameSeconds);
            if (frames.size() < 2) {
                continue;
            }
            candidates.add(new TrackSeries(trackId, frames, slopeDegPerSec(frames), meanBearing(frames)));
        }

        List<TrackSeries> selected = selectTracksForDisplay(candidates);
        selected.sort(Comparator.comparingDouble(TrackSeries::getMeanBearing));

        List<Map<String, Object>> trackPayload = new ArrayList<>();
        int targetIndex = 0;
        for (TrackSeries series : selected) {
            String label = "目标" + (++targetIndex);
            String color = TARGET_COLORS[(targetIndex - 1) % TARGET_COLORS.length];
            trackPayload.add(toTrackPayload(series, label, color));
        }

        double yMin = Double.POSITIVE_INFINITY;
        double yMax = Double.NEGATIVE_INFINITY;
        long xMin = Long.MAX_VALUE;
        long xMax = Long.MIN_VALUE;
        for (Map<String, Object> track : trackPayload) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> points = (List<Map<String, Object>>) track.get("points");
            for (Map<String, Object> p : points) {
                double y = ((Number) p.get("y")).doubleValue();
                long x = ((Number) p.get("x")).longValue();
                yMin = Math.min(yMin, y);
                yMax = Math.max(yMax, y);
                xMin = Math.min(xMin, x);
                xMax = Math.max(xMax, x);
            }
        }
        double pad = Math.max(3.0, (yMax - yMin) * 0.08);
        if (!Double.isFinite(yMin)) {
            yMin = 0;
            yMax = 360;
        }
        if (xMin == Long.MAX_VALUE) {
            xMin = scene.getWindowStart().toEpochMilli();
            xMax = scene.getWindowEnd().toEpochMilli();
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("viewMode", "track");
        view.put("sceneRank", scene.getRank());
        view.put("title", "场景 " + scene.getRank() + " · 方位-时间轨迹");
        view.put("dateLabel", DATE_LABEL.format(scene.getWindowStart().atZone(zone)));
        view.put("timeRange", CLOCK.format(scene.getWindowStart().atZone(zone))
                + " — " + CLOCK.format(scene.getWindowEnd().atZone(zone)));
        view.put("trackCount", scene.getTrackCount());
        view.put("displayedTracks", trackPayload.size());
        view.put("xMin", xMin);
        view.put("xMax", xMax);
        view.put("yMin", Math.floor((yMin - pad) * 10) / 10.0);
        view.put("yMax", Math.ceil((yMax + pad) * 10) / 10.0);
        view.put("tracks", trackPayload);
        return view;
    }

    private Map<String, Object> toTrackPayload(TrackSeries series, String label, String color) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (FramePoint fp : series.getFrames()) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", fp.getEpochMillis());
            p.put("y", round1(fp.getBearing()));
            points.add(p);
        }
        Map<String, Object> track = new LinkedHashMap<>();
        track.put("trackId", series.getTrackId());
        track.put("label", label);
        track.put("role", "target");
        track.put("color", color);
        track.put("points", points);
        return track;
    }

    /** 按平均方位均匀选取代表轨迹（最多 {@link #MAX_TRACKS_PER_SCENE} 条，标注为目标1~N）。 */
    private List<TrackSeries> selectTracksForDisplay(List<TrackSeries> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        List<TrackSeries> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(TrackSeries::getMeanBearing));
        if (sorted.size() <= MAX_TRACKS_PER_SCENE) {
            return sorted;
        }

        List<TrackSeries> picked = new ArrayList<>();
        int slots = MAX_TRACKS_PER_SCENE;
        for (int i = 0; i < slots; i++) {
            int idx = (int) Math.round((i + 0.5) * sorted.size() / slots) - 1;
            idx = Math.max(0, Math.min(sorted.size() - 1, idx));
            TrackSeries candidate = sorted.get(idx);
            if (picked.stream().noneMatch(t -> t.getTrackId() == candidate.getTrackId())) {
                picked.add(candidate);
            }
        }
        for (TrackSeries t : sorted) {
            if (picked.size() >= MAX_TRACKS_PER_SCENE) {
                break;
            }
            if (picked.stream().noneMatch(p -> p.getTrackId() == t.getTrackId())) {
                picked.add(t);
            }
        }
        return picked;
    }

    private List<FramePoint> aggregateByFrame(
            BearingTrack track,
            Instant windowStart,
            Instant windowEnd,
            double frameSeconds
    ) {
        long frameMillis = Math.max(1, Math.round(frameSeconds * 1000.0));
        TreeMap<Long, List<Double>> buckets = new TreeMap<>();

        for (TrackObservation obs : track.getObservations()) {
            if (obs.getTime().isBefore(windowStart) || obs.getTime().isAfter(windowEnd)) {
                continue;
            }
            long key = obs.getTime().toEpochMilli() / frameMillis;
            buckets.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(BearingMath.normalize360(obs.getBearingDeg()));
        }

        List<FramePoint> frames = new ArrayList<>();
        double reference = buckets.isEmpty() ? 0 : circularMean(buckets.firstEntry().getValue());
        for (Map.Entry<Long, List<Double>> entry : buckets.entrySet()) {
            double mean = circularMean(entry.getValue());
            double unwrapped = BearingMath.unwrapToward(reference, mean);
            reference = unwrapped;
            long epochMillis = entry.getKey() * frameMillis;
            frames.add(new FramePoint(epochMillis, unwrapped));
        }
        return frames;
    }

    private double circularMean(List<Double> bearings) {
        double sin = 0;
        double cos = 0;
        for (double b : bearings) {
            double rad = Math.toRadians(b);
            sin += Math.sin(rad);
            cos += Math.cos(rad);
        }
        if (sin == 0 && cos == 0) {
            return bearings.get(0);
        }
        return BearingMath.normalize360(Math.toDegrees(Math.atan2(sin, cos)));
    }

    private double meanBearing(List<FramePoint> frames) {
        return frames.stream().mapToDouble(FramePoint::getBearing).average().orElse(0);
    }

    private double slopeDegPerSec(List<FramePoint> frames) {
        if (frames.size() < 2) {
            return 0;
        }
        FramePoint first = frames.get(0);
        FramePoint last = frames.get(frames.size() - 1);
        double dt = (last.getEpochMillis() - first.getEpochMillis()) / 1000.0;
        if (dt <= 0) {
            return 0;
        }
        return Math.abs(last.getBearing() - first.getBearing()) / dt;
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private List<DetectionPoint> annotationSample(List<DetectionPoint> inWindow) {
        final int cap = 50_000;
        if (inWindow.size() <= cap) {
            return inWindow;
        }
        List<DetectionPoint> sample = new ArrayList<>(cap);
        int stride = (int) Math.ceil((double) inWindow.size() / cap);
        for (int i = 0; i < inWindow.size() && sample.size() < cap; i += stride) {
            sample.add(inWindow.get(i));
        }
        return sample;
    }

    private ScatterBuild buildDownsampledScatter(List<DetectionPoint> inWindow, int maxScatterPoints) {
        List<Map<String, Object>> scatter = new ArrayList<>();
        double yMin = Double.POSITIVE_INFINITY;
        double yMax = Double.NEGATIVE_INFINITY;
        long xMin = Long.MAX_VALUE;
        long xMax = Long.MIN_VALUE;
        if (inWindow.isEmpty()) {
            return new ScatterBuild(scatter, yMin, yMax, xMin, xMax);
        }
        int stride = Math.max(1, (int) Math.ceil((double) inWindow.size() / maxScatterPoints));
        for (int i = 0; i < inWindow.size(); i += stride) {
            DetectionPoint p = inWindow.get(i);
            long x = p.getTime().toEpochMilli();
            double y = round1(BearingMath.normalize360(p.getBearingDeg()));
            Map<String, Object> pt = new LinkedHashMap<>();
            pt.put("x", x);
            pt.put("y", y);
            scatter.add(pt);
            yMin = Math.min(yMin, y);
            yMax = Math.max(yMax, y);
            xMin = Math.min(xMin, x);
            xMax = Math.max(xMax, x);
        }
        return new ScatterBuild(scatter, yMin, yMax, xMin, xMax);
    }

    private static final class ScatterBuild {
        private final List<Map<String, Object>> scatter;
        private final double yMin;
        private final double yMax;
        private final long xMin;
        private final long xMax;

        private ScatterBuild(
                List<Map<String, Object>> scatter,
                double yMin,
                double yMax,
                long xMin,
                long xMax
        ) {
            this.scatter = scatter;
            this.yMin = yMin;
            this.yMax = yMax;
            this.xMin = xMin;
            this.xMax = xMax;
        }
    }

    private String renderPage(String jsonPayload) throws IOException {
        InputStream in = VisualizationService.class.getResourceAsStream("/visualization/visualization-page.html");
        if (in == null) {
            throw new IllegalStateException("Missing classpath resource: visualization/visualization-page.html");
        }
        try {
            return readUtf8(in).replace("__DATA_JSON__", jsonPayload);
        } finally {
            in.close();
        }
    }

    private static String readUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class FramePoint {
        private final long epochMillis;
        private final double bearing;

        FramePoint(long epochMillis, double bearing) {
            this.epochMillis = epochMillis;
            this.bearing = bearing;
        }

        long getEpochMillis() {
            return epochMillis;
        }

        double getBearing() {
            return bearing;
        }
    }

    private static final class TrackSeries {
        private final int trackId;
        private final List<FramePoint> frames;
        private final double slope;
        private final double meanBearing;

        TrackSeries(int trackId, List<FramePoint> frames, double slope, double meanBearing) {
            this.trackId = trackId;
            this.frames = frames;
            this.slope = slope;
            this.meanBearing = meanBearing;
        }

        int getTrackId() {
            return trackId;
        }

        List<FramePoint> getFrames() {
            return frames;
        }

        double getSlope() {
            return slope;
        }

        double getMeanBearing() {
            return meanBearing;
        }
    }
}
