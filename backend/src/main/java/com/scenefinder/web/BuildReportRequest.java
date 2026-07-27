package com.scenefinder.web;

import com.scenefinder.model.SceneFinderResult;
import com.scenefinder.model.SceneForwardAnalyzeResult;

/**
 * 根据场景筛选与转发结果聚合网络/目标明细。
 */
public class BuildReportRequest {

    private SceneFinderResult sceneResult;
    private SceneForwardAnalyzeResult forwardResult;
    /** 是否在拉取详情前对每个 analysisId 执行 preload-all */
    private boolean preloadAll = true;

    public SceneFinderResult getSceneResult() {
        return sceneResult;
    }

    public void setSceneResult(SceneFinderResult sceneResult) {
        this.sceneResult = sceneResult;
    }

    public SceneForwardAnalyzeResult getForwardResult() {
        return forwardResult;
    }

    public void setForwardResult(SceneForwardAnalyzeResult forwardResult) {
        this.forwardResult = forwardResult;
    }

    public boolean isPreloadAll() {
        return preloadAll;
    }

    public void setPreloadAll(boolean preloadAll) {
        this.preloadAll = preloadAll;
    }
}
