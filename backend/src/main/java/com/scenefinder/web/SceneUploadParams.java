package com.scenefinder.web;

import com.scenefinder.service.AnalyzeOptions;

/**
 * 场景筛选上传请求的表单参数解析。
 */
public final class SceneUploadParams {

    private SceneUploadParams() {
    }

    public static AnalyzeOptions toOptions(
            Double freqMin,
            Double freqMax,
            Double freqTolerance,
            Double windowSeconds,
            Double windowStepSeconds,
            Integer minTracksInScene,
            Integer topKScenes,
            Integer topKTrackScenes,
            Integer topKPollingScenes,
            String outputDir,
            Boolean enableImportScatter,
            Boolean fullSpanWindow
    ) {
        return new AnalyzeOptions(
                null, null, null, null, null, null,
                windowSeconds, windowStepSeconds, minTracksInScene,
                topKScenes, topKTrackScenes, topKPollingScenes,
                null, freqMin, freqMax, freqTolerance, null, null, null, outputDir,
                enableImportScatter,
                fullSpanWindow
        );
    }
}
