package com.pdwfx.signal.api;

import com.pdwfx.signal.api.dto.AnalyzeSignalsRequest;
import com.pdwfx.signal.api.dto.DetectSignalDto;
import com.pdwfx.signal.model.AnalyzeSessionResponse;
import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.DetectionBatchResponse;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.pdwfx.signal.model.ExternalTargetImportResponse;
import com.pdwfx.signal.model.NetworkAnalysisResponse;
import com.pdwfx.signal.model.NetworkResultResponse;
import com.pdwfx.signal.model.NetworkView;
import com.pdwfx.signal.service.AnalysisSessionService;
import com.pdwfx.signal.service.ExcelImportService;
import com.pdwfx.signal.service.ExternalTargetLocateImportService;
import com.pdwfx.signal.service.SignalAnalysisService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 对外集成统一入口：输入/输出函数一览。
 *
 * <pre>
 * 【输入】
 *   importSignalsFromFile(file)     → List&lt;DetectSignal&gt;   // 文件，行内可含多频
 *   importSignalsFromJson(request)  → List&lt;DetectSignal&gt;   // JSON，signals[] 多频混合
 *
 * 【分析】
 *   analyzeFromFile(file, tol)      → AnalyzeSessionResponse  // 分网摘要 + sessionId
 *   analyzeFromJson(request)        → AnalyzeSessionResponse
 *   analyzeSignals(signals, tol)    → AnalyzeSessionResponse  // 已有 List 时直接调用
 *   analyzeFull(signals, tol)       → NetworkAnalysisResponse // 全量详情（大文件慎用）
 *
 * 【输出】
 *   getNetworkDetail(sessionId, networkId) → NetworkView   // 单网目标/周期/burst/图表
 *   getNetworkResult(sessionId, networkId) → NetworkResultResponse // 单网目标表+文字结论（无图表）
 *   exportDetections(sessionId, includeUnassigned) → DetectionBatchResponse // 逐条编批（方位/时间/频率/类型/波道）
 *   preloadNetworks(sessionId, networkIds) → AnalyzeSessionResponse // 预构建并回写 targets
 *   preloadAllNetworks(sessionId)           → AnalyzeSessionResponse // 预构建全部网络
 * </pre>
 *
 * <p>多频：每条 {@link DetectSignal#getFreq()} 可不同；核心按 {@code freqTolerance} 分网，
 * 时间重叠且方位/调制相近的异频簇会合并为 {@code commMode=MULTI_FREQ} 网络。
 */
@Service
public class SignalAnalysisFacade {

    private final ExcelImportService excelImportService;
    private final ExternalTargetLocateImportService externalTargetLocateImportService;
    private final SignalAnalysisService signalAnalysisService;
    private final AnalysisSessionService analysisSessionService;

    public SignalAnalysisFacade(ExcelImportService excelImportService,
                                ExternalTargetLocateImportService externalTargetLocateImportService,
                                SignalAnalysisService signalAnalysisService,
                                AnalysisSessionService analysisSessionService) {
        this.excelImportService = excelImportService;
        this.externalTargetLocateImportService = externalTargetLocateImportService;
        this.signalAnalysisService = signalAnalysisService;
        this.analysisSessionService = analysisSessionService;
    }

    // ---------- 输入 ----------

    public List<DetectSignal> importSignalsFromFile(MultipartFile file) throws IOException {
        return excelImportService.parse(file);
    }

    public List<DetectSignal> importSignalsFromJson(AnalyzeSignalsRequest request) {
        if (request == null || request.getSignals() == null || request.getSignals().isEmpty()) {
            throw new IllegalArgumentException("signals 不能为空");
        }
        return SignalInputMapper.toSignals(request.getSignals());
    }

    // ---------- 分析 ----------

    public AnalyzeSessionResponse analyzeFromFile(MultipartFile file, double freqTolerance) throws IOException {
        return analyzeFromFile(file, freqTolerance, null);
    }

    public AnalyzeSessionResponse analyzeFromFile(
            MultipartFile file,
            double freqTolerance,
            MultipartFile referenceFile
    ) throws IOException {
        List<ExternalTargetFix> externalFixes = parseOptionalReference(referenceFile);
        AnalyzeSessionResponse session = analyzeSignals(importSignalsFromFile(file), freqTolerance);
        attachExternalFixes(session, externalFixes);
        return session;
    }

    public AnalyzeSessionResponse analyzeFromPath(Path csvOrExcelPath, double freqTolerance) throws IOException {
        return analyzeSignals(excelImportService.parse(csvOrExcelPath), freqTolerance);
    }

    public AnalyzeSessionResponse analyzeFromPath(
            Path csvOrExcelPath,
            double freqTolerance,
            boolean mergeMultiFreq
    ) throws IOException {
        return analyzeSignals(excelImportService.parse(csvOrExcelPath), freqTolerance, mergeMultiFreq);
    }

    public AnalyzeSessionResponse analyzeFromJson(AnalyzeSignalsRequest request) {
        double tol = request.getFreqTolerance() != null ? request.getFreqTolerance() : 0.01;
        return analyzeSignals(importSignalsFromJson(request), tol);
    }

    public AnalyzeSessionResponse analyzeSignals(List<DetectSignal> signals, double freqTolerance) {
        return analyzeSignals(signals, freqTolerance, true);
    }

    public AnalyzeSessionResponse analyzeSignals(
            List<DetectSignal> signals,
            double freqTolerance,
            boolean mergeMultiFreq
    ) {
        List<SignalAnalysisService.NetworkBucket> buckets =
                signalAnalysisService.partitionNetworks(signals, freqTolerance, mergeMultiFreq);
        return analysisSessionService.createSession(buckets, freqTolerance);
    }

    public NetworkAnalysisResponse analyzeFull(List<DetectSignal> signals, double freqTolerance) {
        return signalAnalysisService.analyze(signals, freqTolerance);
    }

    // ---------- 输出 ----------

    public NetworkView getNetworkDetail(String analysisId, int networkId) {
        return analysisSessionService.getNetwork(analysisId, networkId);
    }

    /** 单网业务结果：目标表 + analysisSummary，不含图表时序 */
    public NetworkResultResponse getNetworkResult(String analysisId, int networkId) {
        return analysisSessionService.getNetworkResult(analysisId, networkId);
    }

    /**
     * 逐条侦测编批导出（同一目标同一 batchId）。
     * 会按需构建尚未缓存的网络详情。
     */
    public DetectionBatchResponse exportDetections(String analysisId, boolean includeUnassigned) {
        return analysisSessionService.exportDetections(analysisId, includeUnassigned);
    }

    /** 预构建指定网络，摘要中回写 targets / analysisSummary */
    public AnalyzeSessionResponse preloadNetworks(String analysisId, java.util.List<Integer> networkIds) {
        return analysisSessionService.preloadNetworks(analysisId, networkIds);
    }

    public AnalyzeSessionResponse preloadAllNetworks(String analysisId) {
        return analysisSessionService.preloadAllNetworks(analysisId);
    }

    /** 获取当前会话的网络摘要列表（不触发详情构建） */
    public AnalyzeSessionResponse getSessionResponse(String analysisId) {
        return analysisSessionService.getSessionResponse(analysisId);
    }

    public ExternalTargetImportResponse importExternalTargetFixes(MultipartFile file) throws IOException {
        List<ExternalTargetFix> all = externalTargetLocateImportService.parse(file);
        ExternalTargetImportResponse response = new ExternalTargetImportResponse();
        response.setFileName(file.getOriginalFilename());
        response.setTotalCount(all.size());
        List<ExternalTargetFix> sampled = ExternalTargetLocateImportService.sampleForMap(all, 600);
        response.setMapSampleCount(sampled.size());
        response.setFixes(sampled);
        return response;
    }

    private List<ExternalTargetFix> parseOptionalReference(MultipartFile referenceFile) throws IOException {
        if (referenceFile == null || referenceFile.isEmpty()) {
            return null;
        }
        return externalTargetLocateImportService.parse(referenceFile);
    }

    private void attachExternalFixes(AnalyzeSessionResponse session, List<ExternalTargetFix> all) {
        if (session == null || all == null || all.isEmpty()) {
            return;
        }
        session.setExternalTargetFixTotalCount(all.size());
        session.setExternalTargetFixes(ExternalTargetLocateImportService.sampleForMap(all, 600));
        analysisSessionService.storeExternalFixes(session.getAnalysisId(), session.getExternalTargetFixes(),
                session.getExternalTargetFixTotalCount());
    }
}
