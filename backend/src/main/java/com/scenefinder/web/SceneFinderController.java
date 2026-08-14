package com.scenefinder.web;

import com.scenefinder.model.SceneFinderResult;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.pdwfx.signal.service.ExternalTargetLocateImportService;
import com.scenefinder.service.AnalyzeOptions;
import com.scenefinder.service.SceneFinderService;
import com.scenefinder.service.SceneUploadClassifierService;
import com.scenefinder.service.SceneUploadStorageService;
import com.scenefinder.service.VisualizationService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * REST API：分析 CSV、返回 JSON 结果、提供 visualization.html 访问。
 */
@RestController
@RequestMapping("/api/scenes")
public class SceneFinderController {

    private static final int MAP_EXTERNAL_SAMPLE_MAX = 600;

    private final SceneFinderService sceneFinderService;
    private final SceneUploadStorageService uploadStorageService;
    private final SceneUploadClassifierService uploadClassifierService;
    private final VisualizationService visualizationService;

    public SceneFinderController(
            SceneFinderService sceneFinderService,
            SceneUploadStorageService uploadStorageService,
            SceneUploadClassifierService uploadClassifierService,
            VisualizationService visualizationService
    ) {
        this.sceneFinderService = sceneFinderService;
        this.uploadStorageService = uploadStorageService;
        this.uploadClassifierService = uploadClassifierService;
        this.visualizationService = visualizationService;
    }

    @GetMapping(value = "/visualization", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> visualization(
            @RequestParam(value = "outputDir", defaultValue = "./output") String outputDir
    ) {
        Path file = Paths.get(outputDir).toAbsolutePath().normalize().resolve("visualization.html");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("visualization.html not found in: " + file.getParent()
                    + " — run analyze first.");
        }
        return ResponseEntity.ok(new FileSystemResource(file));
    }

    /** 大数据量时 analyze 响应不内联 visualization，由此接口按需加载（已降采样散点）。 */
    @GetMapping(value = "/visualization-data", produces = MediaType.APPLICATION_JSON_VALUE)
    public java.util.Map<String, Object> visualizationData(
            @RequestParam(value = "outputDir", defaultValue = "./output") String outputDir
    ) throws IOException {
        Path dir = Paths.get(outputDir).toAbsolutePath().normalize();
        return visualizationService.readJson(dir);
    }

    @PostMapping("/analyze-path")
    public SceneFinderResult analyzePath(@RequestBody AnalyzePathRequest request) throws IOException {
        Path csvPath = Paths.get(request.getCsvPath());
        if (!Files.exists(csvPath)) {
            throw new IllegalArgumentException("CSV path not found: " + csvPath);
        }
        return sceneFinderService.analyze(csvPath, request.toOptions());
    }

    /**
     * 上传一个或多个 CSV（选文件夹时浏览器会提交多个 file 部件），合并后做场景筛选。
     * 参数：频段 freqMin/freqMax、时间窗 windowSeconds/windowStepSeconds、TOP-K 等。
     */
    @PostMapping(value = "/analyze-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SceneFinderResult analyzeUpload(
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "freqMin", required = false) Double freqMin,
            @RequestParam(value = "freqMax", required = false) Double freqMax,
            @RequestParam(value = "freqTolerance", required = false) Double freqTolerance,
            @RequestParam(value = "windowSeconds", required = false) Double windowSeconds,
            @RequestParam(value = "windowStepSeconds", required = false) Double windowStepSeconds,
            @RequestParam(value = "minTracksInScene", required = false) Integer minTracksInScene,
            @RequestParam(value = "topKScenes", required = false) Integer topKScenes,
            @RequestParam(value = "topKTrackScenes", required = false) Integer topKTrackScenes,
            @RequestParam(value = "topKPollingScenes", required = false) Integer topKPollingScenes,
            @RequestParam(value = "outputDir", required = false) String outputDir,
            @RequestParam(value = "enableImportScatter", required = false) Boolean enableImportScatter,
            @RequestParam(value = "fullSpanWindow", required = false) Boolean fullSpanWindow
    ) throws IOException {
        Path input = uploadStorageService.storeUploads(file, files);
        SceneUploadClassifierService.UploadClassification classified = uploadClassifierService.classify(input);
        AnalyzeOptions options = SceneUploadParams.toOptions(
                freqMin, freqMax, freqTolerance, windowSeconds, windowStepSeconds,
                minTracksInScene, topKScenes, topKTrackScenes, topKPollingScenes, outputDir,
                enableImportScatter, fullSpanWindow
        );
        SceneFinderResult result = sceneFinderService.analyze(classified.getPdwInputPath(), options);
        attachExternalFixes(result, classified);
        return result;
    }

    private static void attachExternalFixes(
            SceneFinderResult result,
            SceneUploadClassifierService.UploadClassification classified
    ) {
        List<ExternalTargetFix> all = classified.getExternalFixes();
        if (all == null || all.isEmpty()) {
            return;
        }
        result.setExternalTargetFixTotalCount(all.size());
        result.setExternalTargetFixes(ExternalTargetLocateImportService.sampleForMap(all, MAP_EXTERNAL_SAMPLE_MAX));
        List<String> names = new java.util.ArrayList<>();
        for (java.nio.file.Path p : classified.getExternalFiles()) {
            names.add(p.getFileName().toString());
        }
        result.setExternalSourceFiles(names);
    }
}
