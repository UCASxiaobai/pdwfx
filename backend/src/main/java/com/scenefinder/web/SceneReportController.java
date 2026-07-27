package com.scenefinder.web;

import com.pdwfx.signal.model.AnalyzeSessionResponse;
import com.scenefinder.model.SceneAnalysisReport;
import com.scenefinder.model.SceneReportChunkResponse;
import com.scenefinder.model.SceneProcessResponse;
import com.scenefinder.model.SceneOverlapGroup;
import com.scenefinder.web.SceneOverlapProcessRequest;
import com.scenefinder.web.SceneProcessRequest;
import com.scenefinder.service.ScenePipelineService;
import com.scenefinder.service.SceneReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/scenes")
public class SceneReportController {

    private final SceneReportService sceneReportService;
    private final ScenePipelineService scenePipelineService;

    public SceneReportController(
            SceneReportService sceneReportService,
            ScenePipelineService scenePipelineService
    ) {
        this.sceneReportService = sceneReportService;
        this.scenePipelineService = scenePipelineService;
    }

    /**
     * 将 forward-analyze 结果展开为表格行（可选 preload 后加载各网络目标详情）。
     */
    @PostMapping("/build-report")
    public SceneAnalysisReport buildReport(@RequestBody BuildReportRequest request) throws IOException {
        if (request.getForwardResult() == null) {
            throw new IllegalArgumentException("forwardResult is required");
        }
        return sceneReportService.buildReport(
                request.getSceneResult(),
                request.getForwardResult(),
                request.isPreloadAll()
        );
    }

    /**
     * 按场景增量汇总（推荐：多场景时前端逐个调用，避免 Failed to fetch / 超时）。
     */
    @PostMapping("/build-report-scene")
    public SceneReportChunkResponse buildReportScene(@RequestBody SceneReportChunkRequest request)
            throws IOException {
        if (request.getAnalysisId() == null || request.getAnalysisId().trim().isEmpty()) {
            throw new IllegalArgumentException("analysisId is required");
        }
        return sceneReportService.buildSceneChunk(
                request.getOutputDir(),
                request.getSceneRank(),
                request.getAnalysisId(),
                request.getSceneType(),
                request.isPreloadAll(),
                request.getExportedCsvPath(),
                request.getFreqTolerance()
        );
    }

    /**
     * 单场景完整处理：导出 → 信号分析 → 汇总（推荐用于多场景，避免会话被驱逐）。
     */
    /**
     * 方位图等按需加载前调用：会话被 LRU 驱逐时从导出 CSV 自动恢复。
     */
    @PostMapping("/ensure-session")
    public AnalyzeSessionResponse ensureSession(@RequestBody EnsureSessionRequest request)
            throws IOException {
        if (request.getExportedCsvPath() == null || request.getExportedCsvPath().trim().isEmpty()) {
            throw new IllegalArgumentException("exportedCsvPath is required");
        }
        return sceneReportService.ensureSession(
                request.getAnalysisId(),
                request.getExportedCsvPath(),
                request.getFreqTolerance()
        );
    }

    @PostMapping("/process-scene")
    public SceneProcessResponse processScene(@RequestBody SceneProcessRequest request) throws IOException {
        if (request.getSourceCsvPath() == null || request.getOutputDir() == null) {
            throw new IllegalArgumentException("sourceCsvPath and outputDir are required");
        }
        if (request.getSceneRank() <= 0) {
            throw new IllegalArgumentException("sceneRank must be positive");
        }
        return scenePipelineService.processScene(request);
    }

    /**
     * 时间重合场景合并分析（异频合并，当前前端未使用）。
     */
    @PostMapping("/process-overlap-group")
    public SceneProcessResponse processOverlapGroup(@RequestBody SceneOverlapProcessRequest request)
            throws IOException {
        if (request.getSourceCsvPath() == null || request.getOutputDir() == null) {
            throw new IllegalArgumentException("sourceCsvPath and outputDir are required");
        }
        if (request.getSceneRanks() == null || request.getSceneRanks().isEmpty()) {
            throw new IllegalArgumentException("sceneRanks must not be empty");
        }
        return scenePipelineService.processOverlapGroup(request);
    }

    @GetMapping("/overlap-groups")
    public List<SceneOverlapGroup> overlapGroups(
            @RequestParam String outputDir,
            @RequestParam String ranks
    ) throws IOException {
        List<Integer> rankList = Arrays.stream(ranks.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toList());
        return scenePipelineService.planOverlapGroups(outputDir, rankList);
    }
}
