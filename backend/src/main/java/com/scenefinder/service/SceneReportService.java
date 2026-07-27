package com.scenefinder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdwfx.signal.api.SignalAnalysisFacade;
import com.pdwfx.signal.model.AnalyzeSessionResponse;
import com.pdwfx.signal.model.NetworkSummary;
import com.pdwfx.signal.model.TargetSummary;
import com.scenefinder.model.AnalysisReportRow;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SceneAnalysisReport;
import com.scenefinder.model.SceneFinderResult;
import com.scenefinder.model.SceneForwardAnalyzeResult;
import com.scenefinder.model.SceneForwardItemResult;
import com.scenefinder.model.SceneReportChunkResponse;
import com.scenefinder.model.SceneSummaryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 汇总报表：仅使用 {@link NetworkSummary} / {@link TargetSummary}，不重复拉取含图表序列的 NetworkView。
 */
@Service
public class SceneReportService {

    private static final Logger log = LoggerFactory.getLogger(SceneReportService.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final SignalAnalysisFacade signalAnalysisFacade;
    private final ObjectMapper objectMapper;
    private final SceneSummaryReader sceneSummaryReader;

    public SceneReportService(
            SignalAnalysisFacade signalAnalysisFacade,
            ObjectMapper objectMapper,
            SceneSummaryReader sceneSummaryReader
    ) {
        this.signalAnalysisFacade = signalAnalysisFacade;
        this.objectMapper = objectMapper;
        this.sceneSummaryReader = sceneSummaryReader;
    }

    /**
     * 单场景汇总（前端按场景依次调用，避免一次请求超时）。
     */
    public SceneReportChunkResponse buildSceneChunk(
            String outputDir,
            int sceneRank,
            String analysisId,
            String sceneType,
            boolean preloadAll,
            String exportedCsvPath,
            double freqTolerance
    ) throws IOException {
        return buildSceneChunk(
                outputDir, sceneRank, analysisId, sceneType, preloadAll, exportedCsvPath, freqTolerance, null);
    }

    public SceneReportChunkResponse buildSceneChunk(
            String outputDir,
            int sceneRank,
            String analysisId,
            String sceneType,
            boolean preloadAll,
            String exportedCsvPath,
            double freqTolerance,
            Double freqCenterMhz
    ) throws IOException {
        long t0 = System.currentTimeMillis();
        QualityScene scene = resolveScene(null, outputDir, sceneRank);
        String type = sceneType != null ? sceneType : (scene != null ? scene.getSceneType().name() : "UNKNOWN");

        AnalyzeSessionResponse session = resolveSession(
                analysisId, exportedCsvPath, freqTolerance, preloadAll);
        if (session.getNetworks() == null) {
            throw new IllegalStateException("分析会话无网络数据: " + session.getAnalysisId());
        }

        List<AnalysisReportRow> rows = new ArrayList<>();
        for (NetworkSummary summary : session.getNetworks()) {
            if (freqCenterMhz != null
                    && Math.abs(summary.getFreq() - freqCenterMhz) > freqTolerance + 0.05) {
                continue;
            }
            rows.addAll(flattenFromSummary(scene, sceneRank, type, analysisId, summary));
        }
        enrichTargetChannels(rows);

        SceneReportChunkResponse chunk = new SceneReportChunkResponse();
        chunk.setSceneRank(sceneRank);
        chunk.setNetworkCount(session.getNetworks().size());
        chunk.setRowCount(rows.size());
        chunk.setRows(rows);
        chunk.setElapsedMs(System.currentTimeMillis() - t0);
        return chunk;
    }

    /**
     * 时间重合场景组：一次分析结果按各成员场景元数据展开为多行（供筛选表使用）。
     */
    public SceneReportChunkResponse buildOverlapGroupChunk(
            String outputDir,
            List<Integer> memberRanks,
            String analysisId,
            boolean preloadAll,
            String exportedCsvPath,
            double freqTolerance
    ) throws IOException {
        long t0 = System.currentTimeMillis();
        AnalyzeSessionResponse session = resolveSession(
                analysisId, exportedCsvPath, freqTolerance, preloadAll);
        if (session.getNetworks() == null) {
            throw new IllegalStateException("分析会话无网络数据: " + session.getAnalysisId());
        }

        List<AnalysisReportRow> rows = new ArrayList<>();
        for (Integer rank : memberRanks) {
            if (rank == null) {
                continue;
            }
            QualityScene scene = resolveScene(null, outputDir, rank);
            String type = scene != null ? scene.getSceneType().name() : "UNKNOWN";
            for (NetworkSummary summary : session.getNetworks()) {
                rows.addAll(flattenFromSummary(scene, rank, type, analysisId, summary));
            }
        }

        enrichTargetChannels(rows);

        SceneReportChunkResponse chunk = new SceneReportChunkResponse();
        chunk.setSceneRank(memberRanks.isEmpty() ? 0 : memberRanks.get(0));
        chunk.setNetworkCount(session.getNetworks().size());
        chunk.setRowCount(rows.size());
        chunk.setRows(rows);
        chunk.setElapsedMs(System.currentTimeMillis() - t0);
        return chunk;
    }

    public SceneAnalysisReport buildReport(
            SceneFinderResult sceneResult,
            SceneForwardAnalyzeResult forwardResult,
            boolean preloadAll
    ) throws IOException {
        long t0 = System.currentTimeMillis();
        Map<Integer, QualityScene> sceneByRank = new HashMap<>();
        if (sceneResult != null && sceneResult.getScenes() != null) {
            for (QualityScene s : sceneResult.getScenes()) {
                sceneByRank.put(s.getRank(), s);
            }
        }

        List<AnalysisReportRow> rows = new ArrayList<>();
        int networkDetails = 0;
        String outputDir = forwardResult != null ? forwardResult.getOutputDir() : null;

        if (forwardResult != null && forwardResult.getScenes() != null) {
            for (SceneForwardItemResult item : forwardResult.getScenes()) {
                AnalyzeSessionResponse session = parseSession(item.getBackendResponseJson());
                if (session == null || session.getAnalysisId() == null) {
                    continue;
                }
                SceneReportChunkResponse chunk = buildSceneChunk(
                        outputDir,
                        item.getRank(),
                        session.getAnalysisId(),
                        item.getSceneType().name(),
                        preloadAll,
                        item.getExportedCsvPath(),
                        forwardResult.getFreqTolerance()
                );
                rows.addAll(chunk.getRows());
                networkDetails += chunk.getNetworkCount();
            }
        }

        SceneAnalysisReport report = new SceneAnalysisReport();
        report.setSceneResult(sceneResult);
        report.setForwardResult(forwardResult);
        report.setRows(rows);
        report.setNetworkDetailCount(networkDetails);
        report.setBuildTimeMs(System.currentTimeMillis() - t0);
        return report;
    }

    public AnalyzeSessionResponse getSessionForClient(String analysisId) {
        return signalAnalysisFacade.getSessionResponse(analysisId);
    }

    /**
     * 确保分析会话可用：内存中不存在时从场景导出 CSV 重新分析（LRU 驱逐后恢复）。
     */
    public AnalyzeSessionResponse ensureSession(
            String analysisId,
            String exportedCsvPath,
            double freqTolerance
    ) throws IOException {
        return resolveSession(analysisId, exportedCsvPath, freqTolerance, false);
    }

    private QualityScene resolveScene(Map<Integer, QualityScene> sceneByRank, String outputDir, int rank) {
        if (sceneByRank != null) {
            QualityScene scene = sceneByRank.get(rank);
            if (scene != null) {
                return scene;
            }
        }
        if (outputDir == null || outputDir.trim().isEmpty()) {
            return null;
        }
        try {
            SceneSummaryEntry entry = sceneSummaryReader.requireByRank(Paths.get(outputDir), rank);
            double center = (entry.getFreqMinMhz() + entry.getFreqMaxMhz()) / 2.0;
            return new QualityScene(
                    entry.getRank(),
                    entry.getSceneType(),
                    entry.getWindowStart(),
                    entry.getWindowEnd(),
                    center,
                    entry.getFreqMinMhz(),
                    entry.getFreqMaxMhz(),
                    0, 0, 0, 0, 0,
                    entry.getTrackIds(),
                    0, 0, 0, 0,
                    entry.getAnnotation()
            );
        } catch (IOException ex) {
            return null;
        }
    }

    private AnalyzeSessionResponse resolveSession(
            String analysisId,
            String exportedCsvPath,
            double freqTolerance,
            boolean preloadAll
    ) throws IOException {
        AnalyzeSessionResponse session = null;
        if (analysisId != null && !analysisId.trim().isEmpty()) {
            session = signalAnalysisFacade.getSessionResponse(analysisId);
        }
        if (session == null && exportedCsvPath != null && !exportedCsvPath.trim().isEmpty()) {
            Path csv = Paths.get(exportedCsvPath);
            if (Files.isRegularFile(csv)) {
                log.warn("Analysis session {} expired, re-analyzing {}", analysisId, csv);
                session = signalAnalysisFacade.analyzeFromPath(csv, freqTolerance);
            }
        }
        if (session == null) {
            throw new IllegalStateException("分析会话不存在或已过期: " + analysisId
                    + "（可尝试重新执行信号分析）");
        }
        if (preloadAll) {
            AnalyzeSessionResponse updated = signalAnalysisFacade.preloadAllNetworks(session.getAnalysisId());
            if (updated != null) {
                return updated;
            }
        }
        return session;
    }

    private AnalyzeSessionResponse parseSession(String json) throws IOException {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        return objectMapper.readValue(json, AnalyzeSessionResponse.class);
    }

    private List<AnalysisReportRow> flattenFromSummary(
            QualityScene scene,
            int rank,
            String sceneType,
            String analysisId,
            NetworkSummary summary
    ) {
        List<AnalysisReportRow> rows = new ArrayList<>();
        String wStart = scene != null && scene.getWindowStart() != null ? ISO.format(scene.getWindowStart()) : null;
        String wEnd = scene != null && scene.getWindowEnd() != null ? ISO.format(scene.getWindowEnd()) : null;
        double sceneFc = scene != null ? scene.getFreqCenterMhz() : 0;
        double sceneFmin = scene != null ? scene.getFreqMinMhz() : 0;
        double sceneFmax = scene != null ? scene.getFreqMaxMhz() : 0;
        double sceneScore = scene != null ? scene.getScore() : 0;

        List<TargetSummary> targets = summary.getTargets();
        if (targets == null || targets.isEmpty()) {
            rows.add(baseRow(rank, sceneType, wStart, wEnd, sceneFc, sceneFmin, sceneFmax, sceneScore, analysisId, summary));
            return rows;
        }
        for (TargetSummary t : targets) {
            AnalysisReportRow row = baseRow(rank, sceneType, wStart, wEnd, sceneFc, sceneFmin, sceneFmax, sceneScore,
                    analysisId, summary);
            row.setTargetId(t.getTargetId());
            row.setTargetType(t.getTargetType());
            row.setRole(t.getRole());
            row.setConfidence(t.getConfidence());
            row.setEmissionSharePct(t.getEmissionSharePct());
            row.setPeriodMs(t.getPeriodMs());
            row.setBurstDurationMeanMs(t.getBurstDurationMeanMs());
            row.setAvgDutyCycle(t.getAvgDutyCycle());
            row.setConvergence(t.getConvergence());
            row.setTargetLocateLon(t.getLocateLon());
            row.setTargetLocateLat(t.getLocateLat());
            row.setLocateMethod(t.getLocateMethod());
            row.setLocateMethodLabel(t.getLocateMethodLabel());
            row.setDetectCount(t.getDetectCount());
            if (t.getDetectStartMs() != null) {
                row.setDetectStartTime(ISO.format(Instant.ofEpochMilli(t.getDetectStartMs())));
            }
            if (t.getDetectEndMs() != null) {
                row.setDetectEndTime(ISO.format(Instant.ofEpochMilli(t.getDetectEndMs())));
            }
            rows.add(row);
        }
        return rows;
    }

    private static AnalysisReportRow baseRow(
            int rank,
            String sceneType,
            String wStart,
            String wEnd,
            double sceneFc,
            double sceneFmin,
            double sceneFmax,
            double sceneScore,
            String analysisId,
            NetworkSummary summary
    ) {
        AnalysisReportRow row = new AnalysisReportRow();
        row.setSceneRank(rank);
        row.setSceneType(sceneType);
        row.setWindowStart(wStart);
        row.setWindowEnd(wEnd);
        row.setSceneFreqCenterMhz(sceneFc);
        row.setSceneFreqMinMhz(sceneFmin);
        row.setSceneFreqMaxMhz(sceneFmax);
        row.setSceneScore(sceneScore);
        row.setAnalysisId(analysisId);
        row.setNetworkId(summary.getNetworkId());
        row.setNetworkFreqMhz(summary.getFreq());
        row.setCommMode(summary.getCommMode());
        row.setNetworkType(summary.getNetworkType());
        row.setCommLinkChannel(summary.getCommLinkChannel());
        row.setCommLinkChannelLabel(summary.getCommLinkChannelLabel());
        row.setSignalCount(summary.getSignalCount());
        row.setNetworkTargetCount(summary.getTargetCount());
        return row;
    }

    /** 汇总同一分析会话内各目标占用的波道列表。 */
    private static void enrichTargetChannels(List<AnalysisReportRow> rows) {
        Map<String, Set<String>> channelsByTarget = new HashMap<>();
        for (AnalysisReportRow row : rows) {
            if (row.getTargetId() == null) continue;
            String key = row.getAnalysisId() + "|" + row.getSceneRank() + "|" + row.getTargetId();
            String ch = row.getCommLinkChannelLabel();
            if (isBlank(ch)) {
                ch = row.getCommLinkChannel();
            }
            if (isBlank(ch)) continue;
            channelsByTarget.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(ch);
        }
        for (AnalysisReportRow row : rows) {
            if (row.getTargetId() == null) continue;
            String key = row.getAnalysisId() + "|" + row.getSceneRank() + "|" + row.getTargetId();
            Set<String> chs = channelsByTarget.get(key);
            if (chs != null && !chs.isEmpty()) {
                row.setTargetChannelsUsed(String.join("、", chs));
            } else {
                String single = row.getCommLinkChannelLabel();
                if (isBlank(single)) single = row.getCommLinkChannel();
                row.setTargetChannelsUsed(single);
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
