package com.pdwfx.signal.controller;

import com.pdwfx.signal.api.SignalAnalysisFacade;
import com.pdwfx.signal.api.dto.AnalyzeSignalsRequest;
import com.pdwfx.signal.model.AnalyzeSessionResponse;
import com.pdwfx.signal.model.DetectionBatchResponse;
import com.pdwfx.signal.model.ExternalTargetImportResponse;
import com.pdwfx.signal.model.NetworkResultResponse;
import com.pdwfx.signal.model.NetworkView;
import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * REST 对外接口（委托 {@link SignalAnalysisFacade}）：
 * <ul>
 *   <li>POST /api/signals/analyze — 文件上传，支持行内多频</li>
 *   <li>POST /api/signals/analyze/json — JSON 批量侦获，支持多频混合</li>
 *   <li>POST /api/signals/analyze/detections — 文件上传，一次返回逐条编批结果</li>
 *   <li>POST /api/signals/analyze/detections/json — JSON 侦获，一次返回逐条编批结果</li>
 *   <li>GET  /api/signals/analysis/{id}/detections — 已有 session 的逐条编批结果</li>
 *   <li>GET  /api/signals/analysis/{id}/networks/{networkId} — 单网详情（含图表）</li>
 *   <li>GET  /api/signals/analysis/{id}/networks/{networkId}/result — 单网业务结果（目标表+结论，无图表）</li>
 *   <li>POST /api/signals/analysis/{id}/preload — 预构建指定网络（networkIds=1,2,3）</li>
 *   <li>POST /api/signals/analysis/{id}/preload-all — 预构建全部网络属性</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/signals")
public class SignalAnalysisController {
    private static final Logger log = LoggerFactory.getLogger(SignalAnalysisController.class);
    private final SignalAnalysisFacade facade;

    public SignalAnalysisController(SignalAnalysisFacade facade) {
        this.facade = facade;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnalyzeSessionResponse analyze(@RequestParam("file") MultipartFile file,
                                          @RequestParam(required = false) MultipartFile referenceFile,
                                          @RequestParam(defaultValue = "0.01")
                                          @DecimalMin("0.0") double freqTolerance,
                                          @RequestParam(required = false) String preloadNetworkIds,
                                          @RequestParam(defaultValue = "false") boolean preloadAll) throws IOException {
        log.info("开始分析文件: {}, 频率容差: {}", file.getOriginalFilename(), freqTolerance);
        long t0 = System.currentTimeMillis();
        AnalyzeSessionResponse session = facade.analyzeFromFile(file, freqTolerance, referenceFile);
        session = applyPreload(session, preloadNetworkIds, preloadAll);
        log.info("分析完成, 网络数 {}, session={}, 总耗时 {} ms",
                session.getNetworkCount(), session.getAnalysisId(), System.currentTimeMillis() - t0);
        return session;
    }

    /** JSON 输入：signals 数组内每条 freq 可不同，即多频一次提交 */
    @PostMapping(value = "/analyze/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    public AnalyzeSessionResponse analyzeJson(@Valid @RequestBody AnalyzeSignalsRequest request,
                                              @RequestParam(required = false) String preloadNetworkIds,
                                              @RequestParam(defaultValue = "false") boolean preloadAll) {
        double tol = request.getFreqTolerance() != null ? request.getFreqTolerance() : 0.01;
        log.info("JSON 分析: 条数={}, freqTolerance={}", request.getSignals().size(), tol);
        long t0 = System.currentTimeMillis();
        AnalyzeSessionResponse session = facade.analyzeFromJson(request);
        session = applyPreload(session, firstNonBlank(preloadNetworkIds, request.getPreloadNetworkIds()), preloadAll);
        log.info("JSON 分析完成, 网络数 {}, session={}, 耗时 {} ms",
                session.getNetworkCount(), session.getAnalysisId(), System.currentTimeMillis() - t0);
        return session;
    }

    /**
     * 上传文件并一次返回逐条编批结果（方位、时间、频率、目标类型、占用波道）。
     * 会预构建全部网络后再展开。
     */
    @PostMapping(value = "/analyze/detections", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DetectionBatchResponse analyzeDetections(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) MultipartFile referenceFile,
            @RequestParam(defaultValue = "0.01")
            @DecimalMin("0.0") double freqTolerance,
            @RequestParam(defaultValue = "true") boolean includeUnassigned
    ) throws IOException {
        log.info("开始逐条编批导出(文件): {}, 频率容差: {}", file.getOriginalFilename(), freqTolerance);
        long t0 = System.currentTimeMillis();
        AnalyzeSessionResponse session = facade.analyzeFromFile(file, freqTolerance, referenceFile);
        facade.preloadAllNetworks(session.getAnalysisId());
        DetectionBatchResponse response = facade.exportDetections(session.getAnalysisId(), includeUnassigned);
        if (response == null) {
            throw new IllegalStateException("分析会话不可用");
        }
        response.setElapsedMs(System.currentTimeMillis() - t0);
        return response;
    }

    /**
     * JSON 侦获一次返回逐条编批结果。
     */
    @PostMapping(value = "/analyze/detections/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DetectionBatchResponse analyzeDetectionsJson(
            @Valid @RequestBody AnalyzeSignalsRequest request,
            @RequestParam(defaultValue = "true") boolean includeUnassigned
    ) {
        double tol = request.getFreqTolerance() != null ? request.getFreqTolerance() : 0.01;
        log.info("开始逐条编批导出(JSON): 条数={}, freqTolerance={}", request.getSignals().size(), tol);
        long t0 = System.currentTimeMillis();
        AnalyzeSessionResponse session = facade.analyzeFromJson(request);
        facade.preloadAllNetworks(session.getAnalysisId());
        DetectionBatchResponse response = facade.exportDetections(session.getAnalysisId(), includeUnassigned);
        if (response == null) {
            throw new IllegalStateException("分析会话不可用");
        }
        response.setElapsedMs(System.currentTimeMillis() - t0);
        return response;
    }

    /**
     * 已有分析会话的逐条编批结果（未预构建的网络会在此按需分析）。
     */
    @GetMapping("/analysis/{analysisId}/detections")
    public ResponseEntity<DetectionBatchResponse> getDetections(
            @PathVariable String analysisId,
            @RequestParam(defaultValue = "true") boolean includeUnassigned
    ) {
        DetectionBatchResponse response = facade.exportDetections(analysisId, includeUnassigned);
        if (response == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/analysis/{analysisId}/preload")
    public ResponseEntity<AnalyzeSessionResponse> preloadNetworks(@PathVariable String analysisId,
                                                                  @RequestParam(required = false) String networkIds) {
        List<Integer> ids = parseNetworkIds(networkIds);
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        AnalyzeSessionResponse session = facade.preloadNetworks(analysisId, ids);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(session);
    }

    @PostMapping("/analysis/{analysisId}/preload-all")
    public ResponseEntity<AnalyzeSessionResponse> preloadAllNetworks(@PathVariable String analysisId) {
        log.info("预分析全部网络: session={}", analysisId);
        long t0 = System.currentTimeMillis();
        AnalyzeSessionResponse session = facade.preloadAllNetworks(analysisId);
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        log.info("预分析全部完成: session={}, 网络数={}, 耗时 {} ms",
                analysisId, session.getNetworkCount(), System.currentTimeMillis() - t0);
        return ResponseEntity.ok(session);
    }

    @GetMapping("/analysis/{analysisId}/networks/{networkId}")
    public ResponseEntity<NetworkView> getNetwork(@PathVariable String analysisId,
                                                  @PathVariable int networkId) {
        log.info("加载网络详情: session={}, networkId={}", analysisId, networkId);
        NetworkView view = facade.getNetworkDetail(analysisId, networkId);
        if (view == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
        return ResponseEntity.ok(view);
    }

    /** 单网业务结果：目标表 + 网络/目标文字结论（与界面表格、分析区一致，不含图表序列） */
    @GetMapping("/analysis/{analysisId}/networks/{networkId}/result")
    public ResponseEntity<NetworkResultResponse> getNetworkResult(@PathVariable String analysisId,
                                                                    @PathVariable int networkId) {
        log.info("加载网络业务结果: session={}, networkId={}", analysisId, networkId);
        NetworkResultResponse result = facade.getNetworkResult(analysisId, networkId);
        if (result == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }
        return ResponseEntity.ok(result);
    }

    private AnalyzeSessionResponse applyPreload(AnalyzeSessionResponse session, String preloadNetworkIds,
                                                boolean preloadAll) {
        if (session == null) {
            return null;
        }
        if (preloadAll) {
            AnalyzeSessionResponse updated = facade.preloadAllNetworks(session.getAnalysisId());
            return updated != null ? updated : session;
        }
        List<Integer> ids = parseNetworkIds(preloadNetworkIds);
        if (ids.isEmpty()) {
            return session;
        }
        AnalyzeSessionResponse updated = facade.preloadNetworks(session.getAnalysisId(), ids);
        return updated != null ? updated : session;
    }

    /** 导入并校验外源目标定位 CSV（如雷情导入），返回抽样后的定位点供地图叠加。 */
    @PostMapping(value = "/external/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExternalTargetImportResponse importExternal(@RequestParam("file") MultipartFile file) throws IOException {
        return facade.importExternalTargetFixes(file);
    }

    static List<Integer> parseNetworkIds(String raw) {
        List<Integer> ids = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return ids;
        }
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                ids.add(Integer.parseInt(p));
            } catch (NumberFormatException ignored) {
                log.warn("忽略无效 preloadNetworkId: {}", p);
            }
        }
        return ids;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}
