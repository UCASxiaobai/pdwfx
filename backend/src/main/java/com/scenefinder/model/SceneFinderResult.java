package com.scenefinder.model;

import com.pdwfx.signal.model.ExternalTargetFix;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一次完整分析的返回结果（API JSON / 命令行输出）。
 */
public class SceneFinderResult {

    private final String sourceCsv;
    private final String outputDir;
    private final int totalDetections;
    private final int confirmedTracks;
    private final List<QualityScene> scenes;
    private final List<String> exportedFiles;
    /** 方位轨迹可视化数据，供前端 ECharts 渲染（与 visualization.html 同源）。 */
    private final Map<String, Object> visualization;
    /** 外源目标定位点（地图抽样后），如雷情导入 */
    private List<ExternalTargetFix> externalTargetFixes = Collections.emptyList();
    /** 外源定位原始行数（抽样前） */
    private int externalTargetFixTotalCount;
    /** 外源定位源文件名 */
    private List<String> externalSourceFiles = Collections.emptyList();

    public SceneFinderResult(
            String sourceCsv,
            String outputDir,
            int totalDetections,
            int confirmedTracks,
            List<QualityScene> scenes,
            List<String> exportedFiles,
            Map<String, Object> visualization
    ) {
        this.sourceCsv = sourceCsv;
        this.outputDir = outputDir;
        this.totalDetections = totalDetections;
        this.confirmedTracks = confirmedTracks;
        this.scenes = scenes;
        this.exportedFiles = exportedFiles;
        this.visualization = visualization;
    }

    public String getSourceCsv() {
        return sourceCsv;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public int getTotalDetections() {
        return totalDetections;
    }

    public int getConfirmedTracks() {
        return confirmedTracks;
    }

    public List<QualityScene> getScenes() {
        return scenes;
    }

    public List<String> getExportedFiles() {
        return exportedFiles;
    }

    public Map<String, Object> getVisualization() {
        return visualization;
    }

    public List<ExternalTargetFix> getExternalTargetFixes() {
        return externalTargetFixes;
    }

    public void setExternalTargetFixes(List<ExternalTargetFix> externalTargetFixes) {
        this.externalTargetFixes = externalTargetFixes != null ? externalTargetFixes : Collections.emptyList();
    }

    public int getExternalTargetFixTotalCount() {
        return externalTargetFixTotalCount;
    }

    public void setExternalTargetFixTotalCount(int externalTargetFixTotalCount) {
        this.externalTargetFixTotalCount = externalTargetFixTotalCount;
    }

    public List<String> getExternalSourceFiles() {
        return externalSourceFiles;
    }

    public void setExternalSourceFiles(List<String> externalSourceFiles) {
        this.externalSourceFiles = externalSourceFiles != null ? externalSourceFiles : Collections.emptyList();
    }
}
