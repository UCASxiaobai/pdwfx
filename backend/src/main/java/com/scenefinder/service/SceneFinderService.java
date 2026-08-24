package com.scenefinder.service;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.AwacsOccupancyWindow;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.FrequencyBandUtils;
import com.scenefinder.model.HopBatchGrouping;
import com.scenefinder.model.OccupancyCluster;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SceneFinderResult;
import com.scenefinder.model.SceneType;
import com.scenefinder.model.SourceRowRef;
import com.scenefinder.model.TrackObservation;
import com.scenefinder.util.CsvHeaderUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分析流程编排：读 CSV → 建轨 → 场景评分 → 导出 CSV/HTML。
 * <p>
 * 单次分析的 effective 参数 = {@link AnalyzeOptions} 中非空字段覆盖 {@link SceneFinderProperties} 默认值。
 * </p>
 */
@Service
public class SceneFinderService {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final CsvDetectionReader csvDetectionReader;
    private final TrackBuilderService trackBuilderService;
    private final SceneScorerService sceneScorerService;
    private final MultiDevicePollingDetectorService pollingDetectorService;
    private final PollingTrackBuilderService pollingTrackBuilderService;
    private final AirHypothesisArbiter airHypothesisArbiter;
    private final SceneFusionService sceneFusionService;
    private final VisualizationService visualizationService;
    private final FrequencyHopTrackService frequencyHopTrackService;
    private final SceneFinderProperties properties;
    private final AwacsCommandNetService awacsCommandNetService;

    public SceneFinderService(
            CsvDetectionReader csvDetectionReader,
            TrackBuilderService trackBuilderService,
            SceneScorerService sceneScorerService,
            MultiDevicePollingDetectorService pollingDetectorService,
            PollingTrackBuilderService pollingTrackBuilderService,
            AirHypothesisArbiter airHypothesisArbiter,
            SceneFusionService sceneFusionService,
            VisualizationService visualizationService,
            FrequencyHopTrackService frequencyHopTrackService,
            SceneFinderProperties properties,
            AwacsCommandNetService awacsCommandNetService
    ) {
        this.csvDetectionReader = csvDetectionReader;
        this.trackBuilderService = trackBuilderService;
        this.sceneScorerService = sceneScorerService;
        this.pollingDetectorService = pollingDetectorService;
        this.pollingTrackBuilderService = pollingTrackBuilderService;
        this.airHypothesisArbiter = airHypothesisArbiter;
        this.sceneFusionService = sceneFusionService;
        this.visualizationService = visualizationService;
        this.frequencyHopTrackService = frequencyHopTrackService;
        this.properties = properties;
        this.awacsCommandNetService = awacsCommandNetService;
    }

    /**
     * 执行完整分析并写出 outputDir 下全部结果文件。
     *
     * @param csvPath 输入 CSV 文件或包含多个 CSV 的目录路径
     * @param options 可覆盖默认配置的参数（null 字段表示用 application.yml 默认值）
     */
    public SceneFinderResult analyze(Path csvPath, AnalyzeOptions options) throws IOException {
        SceneFinderProperties effective = mergeOptions(options);

        List<DetectionPoint> points = csvDetectionReader.read(
                csvPath, effective.getFreqMin(), effective.getFreqMax());
        TrackAssembly assembly = assembleTracks(points, effective);

        List<QualityScene> trackScenes = sceneScorerService.findTopScenes(assembly.continuousForScore, effective);
        double freqTol = effective.getSceneFreqBandGapMhz() > 0
                ? effective.getSceneFreqBandGapMhz() : effective.getFreqClusterGapMhz();
        List<AwacsOccupancyWindow> forceWindows = awacsCommandNetService.collectWindowsFromTracks(
                assembly.tracks, effective.getCommandNetPadSec(), freqTol);
        List<QualityScene> scenes = sceneFusionService.assembleDisjointResults(
                trackScenes, assembly.pollingScenes, effective, forceWindows);

        if (effective.isFullSpanWindow() && !points.isEmpty()) {
            scenes = expandFullSpan(scenes, points);
        }
        return writeOutputs(csvPath, effective, points, assembly.tracks, scenes);
    }

    /**
     * 指挥网二次：对占用窗筛后的点建轨+轮询，不走 Top-K，按占用频簇各出一条场景。
     */
    public SceneFinderResult analyzeCommandNet(
            Path csvPath,
            List<AwacsOccupancyWindow> windows,
            AnalyzeOptions options
    ) throws IOException {
        SceneFinderProperties effective = mergeOptions(options);
        effective.setFullSpanWindow(false);
        List<DetectionPoint> points = csvDetectionReader.read(
                csvPath, effective.getFreqMin(), effective.getFreqMax());
        TrackAssembly assembly = assembleTracks(points, effective);
        double freqTol = effective.getSceneFreqBandGapMhz() > 0
                ? effective.getSceneFreqBandGapMhz() : effective.getFreqClusterGapMhz();
        List<OccupancyCluster> clusters = awacsCommandNetService.clusterByFreq(windows, freqTol);
        List<QualityScene> scenes = awacsCommandNetService.buildScenes(clusters, assembly.tracks);
        return writeOutputs(csvPath, effective, points, assembly.tracks, scenes);
    }

    private TrackAssembly assembleTracks(List<DetectionPoint> points, SceneFinderProperties effective) {
        List<BearingTrack> firstPass = trackBuilderService.buildTracks(points, effective);
        List<BearingTrack> persistentTracks = new ArrayList<BearingTrack>();
        List<BearingTrack> airHypTracks = new ArrayList<BearingTrack>();
        for (BearingTrack track : firstPass) {
            if (MultiDevicePollingDetectorService.isPersistentPlatform(track)) {
                persistentTracks.add(track);
            } else {
                airHypTracks.add(track);
            }
        }
        Set<SourceRowRef> persistentRows = collectTrackRows(persistentTracks);
        List<DetectionPoint> airPoints = pointsNotIn(points, persistentRows);

        List<QualityScene> rawPollingScenes = pollingDetectorService.findPollingScenes(
                airPoints, persistentTracks, effective);
        List<QualityScene> pollingKept = airHypothesisArbiter.selectPollingWindows(
                rawPollingScenes, airHypTracks, effective);

        List<BearingTrack> tracks = new ArrayList<BearingTrack>(persistentTracks);
        List<QualityScene> pollingScenes = new ArrayList<QualityScene>();
        Set<SourceRowRef> claimed = new HashSet<SourceRowRef>(persistentRows);
        int nextTrackId = nextTrackIdAfter(persistentTracks);
        for (QualityScene scene : pollingKept) {
            PollingTrackBuilderService.LaneBuildResult built =
                    pollingTrackBuilderService.buildLaneTracks(scene, airPoints, effective, nextTrackId);
            if (built.getTracks().size() < 2) {
                continue;
            }
            tracks.addAll(built.getTracks());
            nextTrackId += built.getTracks().size();
            claimed.addAll(built.getRowToTrackId().keySet());
            List<Integer> laneIds = new ArrayList<Integer>();
            for (BearingTrack t : built.getTracks()) {
                laneIds.add(Integer.valueOf(t.getId()));
            }
            Integer interrogatorId = scene.getInterrogatorTrackId() != null
                    ? scene.getInterrogatorTrackId() : built.getInterrogatorTrackId();
            Double interrogatorBearing = scene.getInterrogatorBearingDeg() != null
                    ? scene.getInterrogatorBearingDeg() : built.getInterrogatorBearingDeg();
            String note = String.format(
                    java.util.Locale.CHINA,
                    "点名组：询问机为已有连续轨%s，应答机 %d 条飞机 lane；应答机仅相邻轮次同槽位相连",
                    interrogatorId != null ? " #" + interrogatorId : "",
                    built.getResponderLaneCount());
            QualityScene updated = scene.withPollingLaneTracks(
                    laneIds, built.getLaneCount(), built.getAlignedRoundCount());
            updated = updated.withCallsignMeta(
                    interrogatorId,
                    interrogatorBearing,
                    built.getResponderLaneCount(),
                    built.getChannelTargetCount(),
                    note);
            pollingScenes.add(updated);
        }

        List<DetectionPoint> leftoverAir = pointsNotIn(airPoints, claimed);
        List<BearingTrack> leftoverTracks = trackBuilderService.buildTracks(leftoverAir, effective);
        for (BearingTrack t : leftoverTracks) {
            t.setId(nextTrackId++);
        }
        tracks.addAll(leftoverTracks);

        List<BearingTrack> continuousForScore = new ArrayList<BearingTrack>(persistentTracks);
        continuousForScore.addAll(leftoverTracks);
        return new TrackAssembly(tracks, pollingScenes, continuousForScore);
    }

    private List<QualityScene> expandFullSpan(List<QualityScene> scenes, List<DetectionPoint> points) {
        Instant dataStart = points.stream()
                .map(DetectionPoint::getTime)
                .min(Instant::compareTo)
                .orElse(null);
        Instant dataEnd = points.stream()
                .map(DetectionPoint::getTime)
                .max(Instant::compareTo)
                .orElse(null);
        if (dataStart == null || dataEnd == null || dataEnd.isBefore(dataStart)) {
            return scenes;
        }
        List<QualityScene> expanded = new ArrayList<QualityScene>(scenes.size());
        for (QualityScene scene : scenes) {
            expanded.add(scene.withWindow(dataStart, dataEnd));
        }
        return expanded;
    }

    private SceneFinderResult writeOutputs(
            Path csvPath,
            SceneFinderProperties effective,
            List<DetectionPoint> points,
            List<BearingTrack> tracks,
            List<QualityScene> scenes
    ) throws IOException {
        Path outputDir = Paths.get(effective.getOutputDir()).toAbsolutePath().normalize();
        Files.createDirectories(outputDir);

        List<String> exportedFiles = new ArrayList<String>();
        exportedFiles.add(exportSceneSummary(outputDir, csvPath, scenes));

        Map<Integer, BearingTrack> trackById = tracks.stream()
                .collect(Collectors.toMap(BearingTrack::getId, track -> track, (a, b) -> a, LinkedHashMap::new));

        exportedFiles.add(exportTracks(outputDir, trackById));
        exportedFiles.add(exportTrackRows(outputDir, trackById));
        exportedFiles.add(exportTrackDetections(outputDir, trackById));

        for (QualityScene scene : scenes) {
            if (scene.getSceneType() == SceneType.MULTI_DEVICE_POLLING) {
                exportedFiles.add(exportScenePollingDetections(
                        outputDir, scene, points, rowMapFromSceneTracks(scene, trackById)));
            } else {
                exportedFiles.add(exportSceneTrackDetections(outputDir, scene, trackById));
            }
        }

        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        int maxScatter = effective.getMaxVisualizationScatterPoints();
        java.util.Map<String, Object> visualization = visualizationService.buildPayload(
                scenes,
                tracks,
                trackById,
                points,
                points.size(),
                zone,
                effective.getFrameSeconds(),
                maxScatter,
                effective.isEnableImportScatter());
        HopBatchGrouping hopBatches = frequencyHopTrackService.buildHopBatches(
                scenes, trackById, points, effective);
        visualization.put(
                "hoppingTrackViews",
                frequencyHopTrackService.toHoppingTrackViews(hopBatches, effective, zone));
        String hopBatchFile = HopBatchIndex.writeCsv(outputDir, hopBatches);
        if (hopBatchFile != null) {
            exportedFiles.add(hopBatchFile);
        }
        exportedFiles.add(visualizationService.exportHtml(outputDir, visualization));
        exportedFiles.add(visualizationService.exportJson(outputDir, visualization));

        java.util.Map<String, Object> apiVisualization =
                points.size() <= effective.getInlineVisualizationMaxDetections()
                        ? visualization
                        : null;

        return new SceneFinderResult(
                csvPath.toAbsolutePath().toString(),
                outputDir.toString(),
                points.size(),
                tracks.size(),
                scenes,
                exportedFiles,
                apiVisualization
        );
    }

    private static final class TrackAssembly {
        final List<BearingTrack> tracks;
        final List<QualityScene> pollingScenes;
        final List<BearingTrack> continuousForScore;

        TrackAssembly(
                List<BearingTrack> tracks,
                List<QualityScene> pollingScenes,
                List<BearingTrack> continuousForScore
        ) {
            this.tracks = tracks;
            this.pollingScenes = pollingScenes;
            this.continuousForScore = continuousForScore;
        }
    }

    private static Set<SourceRowRef> collectTrackRows(List<BearingTrack> tracks) {
        Set<SourceRowRef> rows = new HashSet<SourceRowRef>();
        if (tracks == null) {
            return rows;
        }
        for (BearingTrack track : tracks) {
            for (TrackObservation obs : track.getObservations()) {
                rows.add(obs.sourceRow());
            }
        }
        return rows;
    }

    private static List<DetectionPoint> pointsNotIn(List<DetectionPoint> points, Set<SourceRowRef> claimed) {
        List<DetectionPoint> out = new ArrayList<DetectionPoint>();
        if (points == null) {
            return out;
        }
        for (DetectionPoint p : points) {
            if (claimed == null || !claimed.contains(p.sourceRow())) {
                out.add(p);
            }
        }
        return out;
    }

    private static int nextTrackIdAfter(List<BearingTrack> tracks) {
        int next = 1;
        if (tracks == null) {
            return next;
        }
        for (BearingTrack track : tracks) {
            if (track.getId() + 1 > next) {
                next = track.getId() + 1;
            }
        }
        return next;
    }

    /** 将 API/CLI 传入的 options 与 Spring 注入的 properties 合并为一次分析使用的配置。 */
    private SceneFinderProperties mergeOptions(AnalyzeOptions options) {
        SceneFinderProperties merged = new SceneFinderProperties();
        merged.copyFrom(properties);

        merged.setFrameSeconds(pick(options.getFrameSeconds(), merged.getFrameSeconds()));
        merged.setBearingClusterGapDeg(pick(options.getBearingClusterGapDeg(), merged.getBearingClusterGapDeg()));
        merged.setAssociationGateDeg(pick(options.getAssociationGateDeg(), merged.getAssociationGateDeg()));
        merged.setMaxMissedFrames(pick(options.getMaxMissedFrames(), merged.getMaxMissedFrames()));
        merged.setMinTrackSeconds(pick(options.getMinTrackSeconds(), merged.getMinTrackSeconds()));
        merged.setMinTrackPoints(pick(options.getMinTrackPoints(), merged.getMinTrackPoints()));
        merged.setWindowSeconds(pick(options.getWindowSeconds(), merged.getWindowSeconds()));
        merged.setWindowStepSeconds(pick(options.getWindowStepSeconds(), merged.getWindowStepSeconds()));
        merged.setMinTracksInScene(pick(options.getMinTracksInScene(), merged.getMinTracksInScene()));
        applyTopKOptions(merged, options);
        merged.setMinSeparationDeg(pick(options.getMinSeparationDeg(), merged.getMinSeparationDeg()));
        merged.setFreqMin(pick(options.getFreqMin(), merged.getFreqMin()));
        merged.setFreqMax(pick(options.getFreqMax(), merged.getFreqMax()));
        if (options.getFreqTolerance() != null) {
            merged.setFreqClusterGapMhz(options.getFreqTolerance());
            merged.setSceneFreqBandGapMhz(options.getFreqTolerance());
        } else {
            merged.setFreqClusterGapMhz(pick(options.getFreqClusterGapMhz(), merged.getFreqClusterGapMhz()));
            merged.setSceneFreqBandGapMhz(pick(options.getSceneFreqBandGapMhz(), merged.getSceneFreqBandGapMhz()));
        }
        merged.setMergeMaxGapSeconds(pick(options.getMergeMaxGapSeconds(), merged.getMergeMaxGapSeconds()));
        merged.setOutputDir(options.getOutputDir() != null ? options.getOutputDir() : merged.getOutputDir());
        if (options.getEnableImportScatter() != null) {
            merged.setEnableImportScatter(options.getEnableImportScatter());
        }
        if (options.getFullSpanWindow() != null) {
            merged.setFullSpanWindow(options.getFullSpanWindow());
        }
        return merged;
    }

    /**
     * 分项 Top-K 优先；仅传 {@code topKScenes} 时两者同值（兼容旧命令行）。
     */
    private void applyTopKOptions(SceneFinderProperties merged, AnalyzeOptions options) {
        boolean hasSplit = options.getTopKTrackScenes() != null || options.getTopKPollingScenes() != null;
        if (hasSplit) {
            if (options.getTopKTrackScenes() != null) {
                merged.setTopKTrackScenes(options.getTopKTrackScenes());
            }
            if (options.getTopKPollingScenes() != null) {
                merged.setTopKPollingScenes(options.getTopKPollingScenes());
            }
            if (options.getTopKScenes() != null) {
                merged.setTopKScenes(options.getTopKScenes());
            }
            return;
        }
        if (options.getTopKScenes() != null) {
            merged.setTopKTrackScenes(options.getTopKScenes());
            merged.setTopKPollingScenes(options.getTopKScenes());
            merged.setTopKScenes(options.getTopKScenes());
        }
    }

    private String exportSceneSummary(Path outputDir, Path csvPath, List<QualityScene> scenes) throws IOException {
        Path file = outputDir.resolve("scene_summary.csv");
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader("rank", "scene_type", "window_start", "window_end",
                             "freq_center_mhz", "freq_min_mhz", "freq_max_mhz", "distinct_device_count",
                             "score", "track_count", "median_separation_deg", "average_smoothness",
                             "polling_period_sec", "periodic_burst_count", "avg_bearings_per_burst",
                             "periodicity_score", "annotation", "track_ids")
                     .build())) {
            for (QualityScene scene : scenes) {
                printer.printRecord(
                        scene.getRank(),
                        scene.getSceneType().name(),
                        scene.getWindowStart(),
                        scene.getWindowEnd(),
                        scene.getFreqCenterMhz(),
                        scene.getFreqMinMhz(),
                        scene.getFreqMaxMhz(),
                        scene.getDistinctDeviceCount(),
                        scene.getScore(),
                        scene.getTrackCount(),
                        scene.getMedianSeparationDeg(),
                        scene.getAverageSmoothness(),
                        scene.getPollingPeriodSec() > 0 ? scene.getPollingPeriodSec() : "",
                        scene.getPeriodicBurstCount() > 0 ? scene.getPeriodicBurstCount() : "",
                        scene.getAvgBearingsPerBurst() > 0 ? scene.getAvgBearingsPerBurst() : "",
                        scene.getPeriodicityScore() > 0 ? scene.getPeriodicityScore() : "",
                        scene.getAnnotation(),
                        scene.getTrackIds().stream().map(String::valueOf).collect(Collectors.joining("|"))
                );
            }
        }
        return file.toString();
    }

    private String exportTracks(Path outputDir, Map<Integer, BearingTrack> trackById) throws IOException {
        Path file = outputDir.resolve("tracks.csv");
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader("track_id", "start_time", "end_time", "duration_sec", "points", "smoothness",
                             "dominant_freq_mhz", "source_row_count", "source_row_min", "source_row_max", "source_rows")
                     .build())) {
            for (BearingTrack track : trackById.values()) {
                List<Long> rows = sourceRowIndexNumbers(track);
                printer.printRecord(
                        track.getId(),
                        track.startTime(),
                        track.endTime(),
                        Math.round(track.durationSeconds() * 1000.0) / 1000.0,
                        track.getObservations().size(),
                        Math.round(track.smoothnessScore() * 1000.0) / 1000.0,
                        round3(FrequencyBandUtils.dominantFrequencyMhz(track)),
                        rows.size(),
                        rows.isEmpty() ? "" : rows.get(0),
                        rows.isEmpty() ? "" : rows.get(rows.size() - 1),
                        rows.stream().map(String::valueOf).collect(Collectors.joining("|"))
                );
            }
        }
        return file.toString();
    }

    /** 轨迹与源行号映射（一行一条，便于按行号回提或 SQL 关联） */
    private String exportTrackRows(Path outputDir, Map<Integer, BearingTrack> trackById) throws IOException {
        Path file = outputDir.resolve("track_rows.csv");
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader("track_id", "source_file", "source_row_index")
                     .build())) {
            for (BearingTrack track : trackById.values()) {
                for (SourceRowRef row : sourceRowRefs(track)) {
                    printer.printRecord(track.getId(), row.getSourceFile(), row.getRowIndex());
                }
            }
        }
        return file.toString();
    }

    /** 全部确认轨迹对应的源 CSV 检测行（仅航迹关联行，非时间/频段粗筛） */
    private String exportTrackDetections(
            Path outputDir,
            Map<Integer, BearingTrack> trackById
    ) throws IOException {
        Path file = outputDir.resolve("track_detections.csv");
        exportDetectionsByRowMap(file, rowToTrackMap(trackById));
        return file.toString();
    }

    /**
     * 场景内各轨迹的完整航迹源行（该轨迹关联到的全部检测行，不按场景时间窗裁剪，避免漏掉航迹点）。
     */
    private String exportSceneTrackDetections(
            Path outputDir,
            QualityScene scene,
            Map<Integer, BearingTrack> trackById
    ) throws IOException {
        Map<SourceRowRef, Integer> rowToTrack = new LinkedHashMap<>();
        for (Integer trackId : scene.getTrackIds()) {
            BearingTrack track = trackById.get(trackId);
            if (track == null) {
                continue;
            }
            for (TrackObservation obs : track.getObservations()) {
                rowToTrack.putIfAbsent(obs.sourceRow(), trackId);
            }
        }

        String suffix = FILE_TIME.format(scene.getWindowStart().atZone(java.time.ZoneId.systemDefault()));
        Path file = outputDir.resolve("scene_rank" + scene.getRank() + "_" + suffix + "_track_detections.csv");
        exportDetectionsByRowMap(file, rowToTrack);
        return file.toString();
    }

    private Map<SourceRowRef, Integer> rowMapFromSceneTracks(
            QualityScene scene,
            Map<Integer, BearingTrack> trackById
    ) {
        Map<SourceRowRef, Integer> rowToTrack = new LinkedHashMap<>();
        for (Integer trackId : scene.getTrackIds()) {
            BearingTrack track = trackById.get(trackId);
            if (track == null) {
                continue;
            }
            for (TrackObservation obs : track.getObservations()) {
                if (obs.getTime().isBefore(scene.getWindowStart()) || obs.getTime().isAfter(scene.getWindowEnd())) {
                    continue;
                }
                if (obs.getFrequencyMhz() < scene.getFreqMinMhz() - 0.01
                        || obs.getFrequencyMhz() > scene.getFreqMaxMhz() + 0.01) {
                    continue;
                }
                rowToTrack.putIfAbsent(obs.sourceRow(), trackId);
            }
        }
        return rowToTrack;
    }

    /** 轮询通信场景：导出时间窗内检测源行；参与轮询的点位带 lane {@code track_id}，其余为 0。 */
    private String exportScenePollingDetections(
            Path outputDir,
            QualityScene scene,
            List<DetectionPoint> allPoints,
            Map<com.scenefinder.model.SourceRowRef, Integer> laneRowToTrack
    ) throws IOException {
        Map<com.scenefinder.model.SourceRowRef, Integer> rowToTrack = new LinkedHashMap<>();
        for (DetectionPoint point : allPoints) {
            if (point.getTime().isBefore(scene.getWindowStart()) || point.getTime().isAfter(scene.getWindowEnd())) {
                continue;
            }
            if (point.getFrequencyMhz() < scene.getFreqMinMhz() - 0.01
                    || point.getFrequencyMhz() > scene.getFreqMaxMhz() + 0.01) {
                continue;
            }
            Integer laneId = laneRowToTrack.get(point.sourceRow());
            rowToTrack.putIfAbsent(point.sourceRow(), laneId != null ? laneId : 0);
        }

        String suffix = FILE_TIME.format(scene.getWindowStart().atZone(java.time.ZoneId.systemDefault()));
        Path file = outputDir.resolve("scene_rank" + scene.getRank() + "_" + suffix + "_polling_detections.csv");
        exportDetectionsByRowMap(file, rowToTrack);
        return file.toString();
    }

    private Map<SourceRowRef, Integer> rowToTrackMap(Map<Integer, BearingTrack> trackById) {
        Map<SourceRowRef, Integer> rowToTrack = new LinkedHashMap<>();
        for (BearingTrack track : trackById.values()) {
            for (TrackObservation obs : track.getObservations()) {
                rowToTrack.putIfAbsent(obs.sourceRow(), track.getId());
            }
        }
        return rowToTrack;
    }

    private void exportDetectionsByRowMap(Path outFile, Map<SourceRowRef, Integer> rowToTrack) throws IOException {
        if (rowToTrack.isEmpty()) {
            Files.write(outFile, "track_id,source_file,source_row_index\n".getBytes(StandardCharsets.UTF_8));
            return;
        }

        Map<String, Map<Long, Integer>> rowsByFile = new LinkedHashMap<>();
        for (Map.Entry<SourceRowRef, Integer> entry : rowToTrack.entrySet()) {
            rowsByFile
                    .computeIfAbsent(entry.getKey().getSourceFile(), ignored -> new LinkedHashMap<>())
                    .put(entry.getKey().getRowIndex(), entry.getValue());
        }

        Path firstSource = Paths.get(rowsByFile.keySet().iterator().next());
        List<String> sourceHeader;
        try (BufferedReader reader = openUtf8WithoutBom(firstSource);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            sourceHeader = CsvHeaderUtils.normalizeHeaderRow(new ArrayList<>(parser.getHeaderNames()));
        }

        List<String> header = new ArrayList<>(Arrays.asList("track_id", "source_file", "source_row_index"));
        header.addAll(sourceHeader);

        try (Writer writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader(header.toArray(new String[0]))
                     .build())) {

            for (Map.Entry<String, Map<Long, Integer>> fileEntry : rowsByFile.entrySet()) {
                Path sourceCsv = Paths.get(fileEntry.getKey());
                Map<Long, Integer> rowsInFile = fileEntry.getValue();

                try (BufferedReader reader = openUtf8WithoutBom(sourceCsv);
                     CSVParser parser = CSVFormat.DEFAULT.builder()
                             .setHeader()
                             .setSkipHeaderRecord(true)
                             .setIgnoreEmptyLines(true)
                             .setTrim(true)
                             .build()
                             .parse(reader)) {

                    long row = 1;
                    for (CSVRecord record : parser) {
                        row++;
                        Integer trackId = rowsInFile.get(row);
                        if (trackId == null) {
                            continue;
                        }
                        List<Object> line = new ArrayList<>();
                        line.add(trackId);
                        line.add(fileEntry.getKey());
                        line.add(row);
                        line.addAll(CsvHeaderUtils.mapRecordToRow(record, sourceHeader));
                        printer.printRecord(line);
                    }
                }
            }
        }
    }

    private List<SourceRowRef> sourceRowRefs(BearingTrack track) {
        return track.getObservations().stream()
                .map(TrackObservation::sourceRow)
                .distinct()
                .sorted(Comparator.comparing(SourceRowRef::getSourceFile).thenComparingLong(SourceRowRef::getRowIndex))
                .collect(Collectors.toList());
    }

    private List<Long> sourceRowIndexNumbers(BearingTrack track) {
        return track.getObservations().stream()
                .map(TrackObservation::getRowIndex)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private BufferedReader openUtf8WithoutBom(Path csvPath) throws IOException {
        BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
        reader.mark(4);
        int first = reader.read();
        if (first != 0xFEFF) {
            reader.reset();
        }
        return reader;
    }

    private static double pick(Double value, double fallback) {
        return value == null ? fallback : value;
    }

    private static int pick(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

}
