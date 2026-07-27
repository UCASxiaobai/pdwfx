package com.scenefinder.service;

import com.scenefinder.config.PdwfxBackendProperties;
import com.scenefinder.model.FreqRowGroup;
import com.scenefinder.model.SceneForwardAnalyzeResult;
import com.scenefinder.model.SceneForwardItemResult;
import com.scenefinder.model.SceneSummaryEntry;
import org.springframework.stereotype.Service;

import com.scenefinder.model.SceneOverlapGroup;
import com.scenefinder.model.SourceRowRef;

import java.nio.file.Paths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 读取场景筛选结果 → 还原源 CSV 格式 → 转发 pdwfx backend 分析。
 */
@Service
public class SceneBackendForwardService {

    private final SceneSummaryReader sceneSummaryReader;
    private final SceneSourceExportService sceneSourceExportService;
    private final SceneTrackIdResolver sceneTrackIdResolver;
    private final PdwfxBackendClient pdwfxBackendClient;
    private final PdwfxBackendProperties pdwfxBackendProperties;

    public SceneBackendForwardService(
            SceneSummaryReader sceneSummaryReader,
            SceneSourceExportService sceneSourceExportService,
            SceneTrackIdResolver sceneTrackIdResolver,
            PdwfxBackendClient pdwfxBackendClient,
            PdwfxBackendProperties pdwfxBackendProperties
    ) {
        this.sceneSummaryReader = sceneSummaryReader;
        this.sceneSourceExportService = sceneSourceExportService;
        this.sceneTrackIdResolver = sceneTrackIdResolver;
        this.pdwfxBackendClient = pdwfxBackendClient;
        this.pdwfxBackendProperties = pdwfxBackendProperties;
    }

    /**
     * 转发单个场景至信号分析（供逐场景流水线调用）。
     */
    /**
     * 转发单个优质场景：导出该场景时间窗+频段内数据，一次信号分析。
     */
    public SceneForwardItemResult forwardSingle(
            String sourceCsvPath,
            String outputDir,
            int sceneRank,
            String backendBaseUrl,
            Double freqTolerance,
            boolean useSceneEndpoint
    ) throws IOException {
        Path sourceCsv = Paths.get(sourceCsvPath).toAbsolutePath().normalize();
        Path outDir = Paths.get(outputDir).toAbsolutePath().normalize();
        double tolerance = freqTolerance != null ? freqTolerance : pdwfxBackendProperties.getDefaultFreqTolerance();
        String baseUrl = resolveBackendLabel(backendBaseUrl);
        return forwardOne(sourceCsv, outDir, sceneRank, baseUrl, tolerance, useSceneEndpoint);
    }

    /**
     * @deprecated 按通信频点逐点拆分会产生过多分析单元，优质场景应整场景一次分析。
     */
    @Deprecated
    public List<SceneForwardItemResult> forwardSceneByFreqUnits(
            String sourceCsvPath,
            String outputDir,
            int sceneRank,
            String backendBaseUrl,
            Double freqTolerance,
            boolean useSceneEndpoint
    ) throws IOException {
        Path sourceCsv = Paths.get(sourceCsvPath).toAbsolutePath().normalize();
        Path outDir = Paths.get(outputDir).toAbsolutePath().normalize();
        double tolerance = freqTolerance != null ? freqTolerance : pdwfxBackendProperties.getDefaultFreqTolerance();
        String baseUrl = resolveBackendLabel(backendBaseUrl);

        SceneSummaryEntry scene = sceneSummaryReader.requireByRank(outDir, sceneRank);
        Set<SourceRowRef> rowRefs = sceneSourceExportService.resolveRowIndexes(sourceCsv, outDir, scene);
        if (rowRefs.isEmpty()) {
            throw new IllegalArgumentException("No source rows for scene rank " + sceneRank);
        }

        List<FreqRowGroup> groups = sceneSourceExportService.groupRowRefsByFreq(sourceCsv, rowRefs, tolerance);
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("No frequency groups for scene rank " + sceneRank);
        }

        List<SceneForwardItemResult> results = new ArrayList<>();
        for (FreqRowGroup group : groups) {
            String freqTag = formatFreqFileTag(group.getCenterMhz());
            Path exportPath = outDir.resolve("scene_rank" + sceneRank + "_f" + freqTag + "_backend_source.csv");
            int rowCount = sceneSourceExportService.exportOriginalRows(
                    sourceCsv, group.getRowRefs(), exportPath, resolveTrackIds(sourceCsv, outDir, scene));
            String responseJson = analyzeUnit(
                    exportPath, baseUrl, tolerance, useSceneEndpoint, scene, sceneRank, group.getCenterMhz());
            results.add(new SceneForwardItemResult(
                    sceneRank,
                    scene.getSceneType(),
                    exportPath.toString(),
                    rowCount,
                    responseJson,
                    group.getCenterMhz()
            ));
        }
        return results;
    }

    /**
     * 多个时间重合场景合并导出为一份 CSV，执行一次信号分析。
     */
    public SceneForwardItemResult forwardOverlapGroup(
            String sourceCsvPath,
            String outputDir,
            SceneOverlapGroup group,
            String backendBaseUrl,
            Double freqTolerance,
            boolean useSceneEndpoint
    ) throws IOException {
        if (group == null || group.getRanks().isEmpty()) {
            throw new IllegalArgumentException("overlap group must not be empty");
        }
        Path sourceCsv = Paths.get(sourceCsvPath).toAbsolutePath().normalize();
        Path outDir = Paths.get(outputDir).toAbsolutePath().normalize();
        double tolerance = freqTolerance != null ? freqTolerance : pdwfxBackendProperties.getDefaultFreqTolerance();
        String baseUrl = resolveBackendLabel(backendBaseUrl);
        return forwardOverlap(sourceCsv, outDir, group, baseUrl, tolerance, useSceneEndpoint);
    }

    public SceneForwardAnalyzeResult forward(
            String sourceCsvPath,
            String outputDir,
            List<Integer> sceneRanks,
            String backendBaseUrl,
            Double freqTolerance,
            boolean useSceneEndpoint
    ) throws IOException {
        Path sourceCsv = Paths.get(sourceCsvPath).toAbsolutePath().normalize();
        if (!Files.exists(sourceCsv)) {
            throw new IllegalArgumentException("Source path not found: " + sourceCsv);
        }
        Path outDir = Paths.get(outputDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(outDir)) {
            throw new IllegalArgumentException("Output directory not found: " + outDir);
        }
        if (sceneRanks == null || sceneRanks.isEmpty()) {
            throw new IllegalArgumentException("sceneRanks must not be empty");
        }

        double tolerance = freqTolerance != null ? freqTolerance : pdwfxBackendProperties.getDefaultFreqTolerance();
        String baseUrl = resolveBackendLabel(backendBaseUrl);

        List<SceneForwardItemResult> items = new ArrayList<>();
        for (Integer rank : sceneRanks) {
            items.add(forwardOne(sourceCsv, outDir, rank, baseUrl, tolerance, useSceneEndpoint));
        }

        return new SceneForwardAnalyzeResult(
                sourceCsv.toString(),
                outDir.toString(),
                baseUrl,
                tolerance,
                useSceneEndpoint,
                items
        );
    }

    private String resolveBackendLabel(String backendBaseUrl) {
        if (pdwfxBackendProperties.isInProcess()
                && (backendBaseUrl == null || backendBaseUrl.trim().isEmpty()
                || backendBaseUrl.contains("localhost:18080"))) {
            return "in-process";
        }
        return backendBaseUrl != null && !backendBaseUrl.trim().isEmpty()
                ? backendBaseUrl
                : pdwfxBackendProperties.getBaseUrl();
    }

    private SceneForwardItemResult forwardOne(
            Path sourceCsv,
            Path outputDir,
            int rank,
            String baseUrl,
            double freqTolerance,
            boolean useSceneEndpoint
    ) throws IOException {
        SceneSummaryEntry scene = sceneSummaryReader.requireByRank(outputDir, rank);
        Set<SourceRowRef> rowRefs = sceneSourceExportService.resolveRowIndexes(sourceCsv, outputDir, scene);

        Path exportPath = outputDir.resolve("scene_rank" + rank + "_backend_source.csv");
        Map<SourceRowRef, Integer> trackIdByRow = resolveTrackIds(sourceCsv, outputDir, scene);
        int rowCount = sceneSourceExportService.exportOriginalRows(sourceCsv, rowRefs, exportPath, trackIdByRow);

        String responseJson;
        if (useSceneEndpoint) {
            String sceneId = "scene-rank-" + rank;
            String sceneName = scene.getAnnotation() != null && !scene.getAnnotation().trim().isEmpty()
                    ? scene.getAnnotation()
                    : scene.getSceneType().name() + " #" + rank;
            responseJson = pdwfxBackendClient.analyzeScene(
                    exportPath,
                    baseUrl,
                    freqTolerance,
                    sceneId,
                    sceneName,
                    scene.getWindowStart().toEpochMilli(),
                    scene.getWindowEnd().toEpochMilli()
            );
        } else {
            responseJson = pdwfxBackendClient.analyze(exportPath, baseUrl, freqTolerance);
        }

        return new SceneForwardItemResult(
                rank,
                scene.getSceneType(),
                exportPath.toString(),
                rowCount,
                responseJson
        );
    }

    private String analyzeUnit(
            Path exportPath,
            String baseUrl,
            double freqTolerance,
            boolean useSceneEndpoint,
            SceneSummaryEntry scene,
            int rank,
            double freqCenterMhz
    ) {
        if (useSceneEndpoint) {
            String freqTag = formatFreqFileTag(freqCenterMhz);
            String sceneId = "scene-rank-" + rank + "-f" + freqTag;
            String sceneName = (scene.getAnnotation() != null && !scene.getAnnotation().trim().isEmpty()
                    ? scene.getAnnotation()
                    : scene.getSceneType().name() + " #" + rank)
                    + " @ " + String.format(Locale.ROOT, "%.3f", freqCenterMhz) + "MHz";
            return pdwfxBackendClient.analyzeScene(
                    exportPath,
                    baseUrl,
                    freqTolerance,
                    sceneId,
                    sceneName,
                    scene.getWindowStart().toEpochMilli(),
                    scene.getWindowEnd().toEpochMilli()
            );
        }
        return pdwfxBackendClient.analyze(exportPath, baseUrl, freqTolerance, false);
    }

    private Map<SourceRowRef, Integer> resolveTrackIds(
            Path sourceCsv,
            Path outputDir,
            SceneSummaryEntry scene
    ) throws IOException {
        return sceneTrackIdResolver.resolve(sourceCsv, outputDir, scene);
    }

    private static String formatFreqFileTag(double freqMhz) {
        return String.format(Locale.ROOT, "%.3f", freqMhz).replace('.', '_');
    }

    private SceneForwardItemResult forwardOverlap(
            Path sourceCsv,
            Path outputDir,
            SceneOverlapGroup group,
            String baseUrl,
            double freqTolerance,
            boolean useSceneEndpoint
    ) throws IOException {
        SceneSummaryEntry primary = sceneSummaryReader.requireByRank(outputDir, group.getPrimaryRank());
        Set<SourceRowRef> rowRefs = sceneSourceExportService.resolveMergedRowRefs(sourceCsv, outputDir, group);
        if (rowRefs.isEmpty()) {
            throw new IllegalArgumentException("No source rows for overlap group: " + group.getRanks());
        }

        String ranksTag = group.getRanks().stream()
                .map(String::valueOf)
                .collect(Collectors.joining("_"));
        Path exportPath = outputDir.resolve("scene_overlap_" + ranksTag + "_backend_source.csv");
        int rowCount = sceneSourceExportService.exportOriginalRows(
                sourceCsv, rowRefs, exportPath, resolveTrackIds(sourceCsv, outputDir, primary));

        String responseJson;
        if (useSceneEndpoint) {
            String sceneId = "scene-overlap-" + ranksTag;
            String sceneName = "Overlap scenes " + group.getRanks();
            responseJson = pdwfxBackendClient.analyzeScene(
                    exportPath,
                    baseUrl,
                    freqTolerance,
                    sceneId,
                    sceneName,
                    group.getSpanStart().toEpochMilli(),
                    group.getSpanEnd().toEpochMilli()
            );
        } else {
            responseJson = pdwfxBackendClient.analyze(exportPath, baseUrl, freqTolerance);
        }

        return new SceneForwardItemResult(
                group.getPrimaryRank(),
                primary != null ? primary.getSceneType() : com.scenefinder.model.SceneType.TRACK_CONTINUOUS,
                exportPath.toString(),
                rowCount,
                responseJson
        );
    }
}
