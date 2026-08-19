package com.pdwfx.signal.service;



import com.pdwfx.signal.model.AnalyzeSessionResponse;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.DetectionBatchResponse;

import com.pdwfx.signal.model.ExternalTargetFix;
import com.pdwfx.signal.model.NetworkResultResponse;

import com.pdwfx.signal.model.NetworkSummary;

import com.pdwfx.signal.model.NetworkView;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;



import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;



/**

 * 分析会话：上传阶段只存分网原始数据；网络详情按需构建并缓存。

 */

@Service

public class AnalysisSessionService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisSessionService.class);

    private final int maxSessions;

    private final SignalAnalysisService signalAnalysisService;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAccessEpochMs = new ConcurrentHashMap<>();

    public AnalysisSessionService(
            SignalAnalysisService signalAnalysisService,
            @Value("${signal-analysis.max-sessions:128}") int maxSessions
    ) {
        this.signalAnalysisService = signalAnalysisService;
        this.maxSessions = Math.max(20, maxSessions);
    }



    public AnalyzeSessionResponse createSession(List<SignalAnalysisService.NetworkBucket> buckets,

                                                double freqTolerance) {

        evictOldestIfNeeded();

        String analysisId = UUID.randomUUID().toString().replace("-", "");

        Session session = new Session(buckets, freqTolerance);

        sessions.put(analysisId, session);
        touch(analysisId);



        AnalyzeSessionResponse response = new AnalyzeSessionResponse();

        response.setAnalysisId(analysisId);

        response.setNetworkCount(buckets.size());

        response.setNetworks(session.summaries);

        return response;

    }



    public NetworkView getNetwork(String analysisId, int networkId) {

        Session session = sessions.get(analysisId);

        if (session == null) {

            return null;

        }
        touch(analysisId);

        NetworkView view = session.viewCache.computeIfAbsent(networkId, id -> {

            SignalAnalysisService.NetworkBucket bucket = session.bucketById.get(id);

            if (bucket == null) {

                return null;

            }

            long t0 = System.currentTimeMillis();

            NetworkView built = signalAnalysisService.buildNetworkView(id, bucket.signals, session.freqTolerance);

            log.info("按需构建网络详情: session={}, networkId={}, 信号数={}, 耗时 {} ms",

                    analysisId, id, bucket.signals.size(), System.currentTimeMillis() - t0);

            return built;

        });

        if (view != null) {

            syncSummaryFromView(session, networkId, view);

        }

        return view;

    }



    /** 外部模块：单网业务结果（目标表 + 文字结论，无图表序列） */

    public NetworkResultResponse getNetworkResult(String analysisId, int networkId) {

        NetworkView view = getNetwork(analysisId, networkId);

        return view == null ? null : NetworkResultResponse.from(view);

    }



    /**

     * 预构建指定网络详情，并回写 {@link NetworkSummary#targets} / {@link NetworkSummary#analysisSummary}。

     */

    public AnalyzeSessionResponse preloadNetworks(String analysisId, List<Integer> networkIds) {

        Session session = sessions.get(analysisId);

        if (session == null) {

            return null;

        }

        if (networkIds != null) {

            for (Integer id : networkIds) {

                if (id != null) {

                    getNetwork(analysisId, id);

                }

            }

        }

        return toSessionResponse(analysisId, session);

    }



    /** 预构建会话内全部网络（编批、平台类型、通信链等），回写列表摘要 */

    public AnalyzeSessionResponse preloadAllNetworks(String analysisId) {

        Session session = sessions.get(analysisId);

        if (session == null) {

            return null;

        }

        List<Integer> ids = new ArrayList<>(session.bucketById.keySet());

        ids.sort(Integer::compareTo);

        long t0 = System.currentTimeMillis();

        int total = ids.size();

        for (int i = 0; i < total; i++) {

            getNetwork(analysisId, ids.get(i));

            int done = i + 1;

            if (done % 100 == 0 || done == total) {

                log.info("预分析进度: session={}, {}/{}", analysisId, done, total);

            }

        }

        log.info("预分析全部完成: session={}, 网络数={}, 耗时 {} ms",

                analysisId, total, System.currentTimeMillis() - t0);

        return toSessionResponse(analysisId, session);

    }

    /**
     * 逐条导出编批结果：同一目标同一 {@code batchId}，含方位、时间、频率、类型、波道。
     */
    public DetectionBatchResponse exportDetections(String analysisId, boolean includeUnassigned) {
        Session session = sessions.get(analysisId);
        if (session == null) {
            return null;
        }
        touch(analysisId);
        long t0 = System.currentTimeMillis();
        DetectionBatchAssembler assembler = new DetectionBatchAssembler();
        assembler.setAnalysisId(analysisId);
        List<Integer> ids = new ArrayList<Integer>(session.bucketById.keySet());
        ids.sort(Integer::compareTo);
        for (int i = 0; i < ids.size(); i++) {
            Integer networkId = ids.get(i);
            NetworkView view = getNetwork(analysisId, networkId.intValue());
            SignalAnalysisService.NetworkBucket bucket = session.bucketById.get(networkId);
            List<DetectSignal> signals = bucket == null
                    ? Collections.<DetectSignal>emptyList()
                    : bucket.signals;
            assembler.addNetwork(view, signals, includeUnassigned);
        }
        return assembler.build(System.currentTimeMillis() - t0);
    }

    public void storeExternalFixes(String analysisId, List<ExternalTargetFix> fixes, int totalCount) {
        Session session = sessions.get(analysisId);
        if (session == null) {
            return;
        }
        session.externalTargetFixes = fixes != null ? fixes : Collections.emptyList();
        session.externalTargetFixTotalCount = totalCount;
    }

    public AnalyzeSessionResponse getSessionResponse(String analysisId) {

        Session session = sessions.get(analysisId);

        if (session == null) {
            return null;
        }
        touch(analysisId);
        return toSessionResponse(analysisId, session);

    }

    private void touch(String analysisId) {
        lastAccessEpochMs.put(analysisId, System.currentTimeMillis());
    }

    private void evictOldestIfNeeded() {
        while (sessions.size() >= maxSessions && !sessions.isEmpty()) {
            String oldest = lastAccessEpochMs.entrySet().stream()
                    .filter(e -> sessions.containsKey(e.getKey()))
                    .min(Comparator.comparingLong(Map.Entry::getValue))
                    .map(Map.Entry::getKey)
                    .orElse(sessions.keySet().iterator().next());
            sessions.remove(oldest);
            lastAccessEpochMs.remove(oldest);
            log.warn("分析会话数达上限 {}，已驱逐最久未访问会话 {}", maxSessions, oldest);
        }
    }



    private static AnalyzeSessionResponse toSessionResponse(String analysisId, Session session) {

        AnalyzeSessionResponse response = new AnalyzeSessionResponse();

        response.setAnalysisId(analysisId);

        response.setNetworkCount(session.summaries.size());

        response.setNetworks(session.summaries);
        response.setExternalTargetFixes(session.externalTargetFixes);
        response.setExternalTargetFixTotalCount(session.externalTargetFixTotalCount);

        return response;

    }



    private static void syncSummaryFromView(Session session, int networkId, NetworkView view) {

        for (NetworkSummary summary : session.summaries) {

            if (summary.getNetworkId() == networkId) {

                summary.enrichFromView(view);

                return;

            }

        }

    }



    private static final class Session {

        final double freqTolerance;

        final List<NetworkSummary> summaries;

        final Map<Integer, SignalAnalysisService.NetworkBucket> bucketById;

        final Map<Integer, NetworkView> viewCache = new ConcurrentHashMap<>();
        List<ExternalTargetFix> externalTargetFixes = Collections.emptyList();
        int externalTargetFixTotalCount;



        Session(List<SignalAnalysisService.NetworkBucket> buckets, double freqTolerance) {

            this.freqTolerance = freqTolerance;

            this.summaries = new ArrayList<>();

            this.bucketById = new ConcurrentHashMap<>();

            for (SignalAnalysisService.NetworkBucket b : buckets) {

                bucketById.put(b.networkId, b);

                summaries.add(b.summary);

            }

        }

    }

}


