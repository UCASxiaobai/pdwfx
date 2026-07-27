package com.scenefinder.web;

import com.scenefinder.model.SceneForwardAnalyzeResult;
import com.scenefinder.service.SceneBackendForwardService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 将 bearing-scene-finder 筛选后的场景数据还原为源 CSV 格式，并转发至 pdwfx backend 分析。
 */
@RestController
@RequestMapping("/api/scenes")
public class SceneForwardController {

    private final SceneBackendForwardService sceneBackendForwardService;

    public SceneForwardController(SceneBackendForwardService sceneBackendForwardService) {
        this.sceneBackendForwardService = sceneBackendForwardService;
    }

    /**
     * 按场景排名转发至 pdwfx backend。
     * <p>
     * 默认调用 {@code POST /api/signals/analyze}；{@code useSceneEndpoint=true} 时调用
     * {@code POST /api/signals/analyze/scene}（携带 sceneId、时间窗等，与 backend 集成文档一致）。
     * </p>
     * <p>需先完成场景分析，保证 {@code outputDir} 下存在 {@code scene_summary.csv}。</p>
     */
    @PostMapping("/forward-analyze")
    public SceneForwardAnalyzeResult forwardAnalyze(@RequestBody ForwardAnalyzeRequest request) throws IOException {
        return sceneBackendForwardService.forward(
                request.getSourceCsvPath(),
                request.getOutputDir(),
                request.getSceneRanks(),
                request.getBackendBaseUrl(),
                request.getFreqTolerance(),
                Boolean.TRUE.equals(request.getUseSceneEndpoint())
        );
    }
}
