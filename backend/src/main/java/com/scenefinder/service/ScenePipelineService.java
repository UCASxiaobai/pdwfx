package com.scenefinder.service;



import com.fasterxml.jackson.databind.ObjectMapper;

import com.pdwfx.signal.model.AnalyzeSessionResponse;

import com.scenefinder.model.SceneForwardItemResult;

import com.scenefinder.model.SceneOverlapGroup;

import com.scenefinder.model.SceneSummaryEntry;

import com.scenefinder.model.SceneProcessResponse;

import com.scenefinder.model.SceneReportChunkResponse;

import com.scenefinder.web.SceneOverlapProcessRequest;

import com.scenefinder.web.SceneProcessRequest;

import org.springframework.stereotype.Service;



import java.io.IOException;

import java.nio.file.Paths;

import java.util.ArrayList;

import java.util.Collections;

import java.util.List;



/**

 * 逐优质场景流水线：每个勾选场景导出一次 → 一次信号分析 → 汇总。

 */

@Service

public class ScenePipelineService {



    private final SceneBackendForwardService forwardService;

    private final SceneReportService reportService;

    private final SceneOverlapGrouper overlapGrouper;

    private final ObjectMapper objectMapper;



    public ScenePipelineService(

            SceneBackendForwardService forwardService,

            SceneReportService reportService,

            SceneOverlapGrouper overlapGrouper,

            ObjectMapper objectMapper

    ) {

        this.forwardService = forwardService;

        this.reportService = reportService;

        this.overlapGrouper = overlapGrouper;

        this.objectMapper = objectMapper;

    }



    public SceneProcessResponse processScene(SceneProcessRequest request) throws IOException {

        long t0 = System.currentTimeMillis();



        SceneForwardItemResult item = forwardService.forwardSingle(

                request.getSourceCsvPath(),

                request.getOutputDir(),

                request.getSceneRank(),

                null,

                request.getFreqTolerance(),

                request.isUseSceneEndpoint()

        );



        AnalyzeSessionResponse session = objectMapper.readValue(

                item.getBackendResponseJson(), AnalyzeSessionResponse.class);



        SceneReportChunkResponse chunk = reportService.buildSceneChunk(

                request.getOutputDir(),

                item.getRank(),

                session.getAnalysisId(),

                item.getSceneType().name(),

                request.isPreloadAll(),

                item.getExportedCsvPath(),

                request.getFreqTolerance()

        );



        AnalyzeSessionResponse sessionForClient = reportService.ensureSession(
                session.getAnalysisId(),
                item.getExportedCsvPath(),
                request.getFreqTolerance()
        );



        SceneProcessResponse response = new SceneProcessResponse();

        response.setRank(item.getRank());

        response.setSceneType(item.getSceneType().name());

        response.setExportedCsvPath(item.getExportedCsvPath());

        response.setExportedRowCount(item.getExportedRowCount());

        response.setSession(sessionForClient != null ? sessionForClient : session);

        response.setReportRows(chunk.getRows());

        response.setNetworkCount(chunk.getNetworkCount());

        response.setElapsedMs(System.currentTimeMillis() - t0);

        return response;

    }



    /**

     * 时间重合场景合并为一次分析（已弃用，前端不再调用）。

     */

    public SceneProcessResponse processOverlapGroup(SceneOverlapProcessRequest request) throws IOException {

        long t0 = System.currentTimeMillis();

        List<Integer> ranks = request.getSceneRanks();

        if (ranks == null || ranks.isEmpty()) {

            throw new IllegalArgumentException("sceneRanks must not be empty");

        }



        List<SceneOverlapGroup> planned = overlapGrouper.group(Paths.get(request.getOutputDir()), ranks);

        SceneOverlapGroup group = planned.stream()

                .filter(g -> g.getRanks().size() == ranks.size() && g.getRanks().containsAll(ranks))

                .findFirst()

                .orElse(planned.isEmpty()

                        ? new SceneOverlapGroup(ranks, java.time.Instant.EPOCH, java.time.Instant.EPOCH, Collections.<SceneSummaryEntry>emptyList())

                        : planned.get(0));



        SceneForwardItemResult item = forwardService.forwardOverlapGroup(

                request.getSourceCsvPath(),

                request.getOutputDir(),

                group,

                null,

                request.getFreqTolerance(),

                request.isUseSceneEndpoint()

        );



        AnalyzeSessionResponse session = objectMapper.readValue(

                item.getBackendResponseJson(), AnalyzeSessionResponse.class);



        SceneReportChunkResponse chunk = reportService.buildOverlapGroupChunk(

                request.getOutputDir(),

                group.getRanks(),

                session.getAnalysisId(),

                request.isPreloadAll(),

                item.getExportedCsvPath(),

                request.getFreqTolerance()

        );



        AnalyzeSessionResponse sessionForClient = reportService.ensureSession(
                session.getAnalysisId(),
                item.getExportedCsvPath(),
                request.getFreqTolerance()
        );



        SceneProcessResponse response = new SceneProcessResponse();

        response.setRank(group.getPrimaryRank());

        response.setMemberRanks(new ArrayList<>(group.getRanks()));

        response.setSceneType(item.getSceneType().name());

        response.setExportedCsvPath(item.getExportedCsvPath());

        response.setExportedRowCount(item.getExportedRowCount());

        response.setSession(sessionForClient != null ? sessionForClient : session);

        response.setReportRows(chunk.getRows());

        response.setNetworkCount(chunk.getNetworkCount());

        response.setSpanStartEpochMs(group.getSpanStart().toEpochMilli());

        response.setSpanEndEpochMs(group.getSpanEnd().toEpochMilli());

        response.setElapsedMs(System.currentTimeMillis() - t0);

        return response;

    }



    public List<SceneOverlapGroup> planOverlapGroups(String outputDir, List<Integer> sceneRanks) throws IOException {

        return overlapGrouper.group(Paths.get(outputDir), sceneRanks);

    }

}


