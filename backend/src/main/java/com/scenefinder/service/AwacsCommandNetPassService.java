package com.scenefinder.service;

import com.pdwfx.signal.api.SignalAnalysisFacade;
import com.pdwfx.signal.model.AnalyzeSessionResponse;
import com.pdwfx.signal.model.NetworkSummary;
import com.pdwfx.signal.model.NetworkView;
import com.pdwfx.signal.model.SeriesPoint;
import com.pdwfx.signal.model.TargetView;
import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.AnalysisReportRow;
import com.scenefinder.model.AwacsOccupancyWindow;
import com.scenefinder.model.CommandNetAwacsPanel;
import com.scenefinder.model.CommandNetPassResponse;
import com.scenefinder.model.OccupancyCluster;
import com.scenefinder.model.SceneFinderResult;
import com.scenefinder.model.SceneProcessResponse;
import com.scenefinder.model.SceneReportChunkResponse;
import com.scenefinder.model.SceneSummaryEntry;
import com.scenefinder.model.SceneType;
import com.scenefinder.model.SourceRowRef;
import com.scenefinder.web.CommandNetPassRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预警机指挥网二次分析编排：占用窗筛点 → commandNet 建轨 → 信号分析 → 标记一次重叠场景。
 */
@Service
public class AwacsCommandNetPassService {

    private static final Logger log = LoggerFactory.getLogger(AwacsCommandNetPassService.class);
    static final int COMMAND_NET_RANK_OFFSET = 10000;
    private static final String[] PANEL_TRACK_COLORS = new String[] {
            "#ef4444", "#2563eb", "#16a34a", "#f59e0b", "#7c3aed", "#0f766e",
            "#db2777", "#0891b2", "#ca8a04", "#4f46e5", "#059669", "#dc2626"
    };

    private final SceneFinderProperties properties;
    private final AwacsCommandNetService occupancyService;
    private final SceneSourceExportService sourceExportService;
    private final SceneFinderService sceneFinderService;
    private final SceneTrackIdResolver trackIdResolver;
    private final SceneSummaryReader sceneSummaryReader;
    private final SignalAnalysisFacade signalAnalysisFacade;
    private final SceneReportService sceneReportService;

    public AwacsCommandNetPassService(
            SceneFinderProperties properties,
            AwacsCommandNetService occupancyService,
            SceneSourceExportService sourceExportService,
            SceneFinderService sceneFinderService,
            SceneTrackIdResolver trackIdResolver,
            SceneSummaryReader sceneSummaryReader,
            SignalAnalysisFacade signalAnalysisFacade,
            SceneReportService sceneReportService
    ) {
        this.properties = properties;
        this.occupancyService = occupancyService;
        this.sourceExportService = sourceExportService;
        this.sceneFinderService = sceneFinderService;
        this.trackIdResolver = trackIdResolver;
        this.sceneSummaryReader = sceneSummaryReader;
        this.signalAnalysisFacade = signalAnalysisFacade;
        this.sceneReportService = sceneReportService;
    }

    public CommandNetPassResponse run(CommandNetPassRequest request) throws IOException {
        long t0 = System.currentTimeMillis();
        CommandNetPassResponse response = new CommandNetPassResponse();
        if (request == null || request.getSourceCsvPath() == null || request.getOutputDir() == null) {
            return skipped(response, "sourceCsvPath 与 outputDir 必填", t0);
        }
        if (!properties.isCommandNetSecondPass()) {
            return skipped(response, "指挥网二次分析已关闭", t0);
        }

        Path sourceCsv = Paths.get(request.getSourceCsvPath()).toAbsolutePath().normalize();
        Path firstPassDir = Paths.get(request.getOutputDir()).toAbsolutePath().normalize();
        double freqTol = request.getFreqTolerance() > 0 ? request.getFreqTolerance() : properties.getSceneFreqBandGapMhz();
        double padSec = properties.getCommandNetPadSec();

        List<TargetView> seeds = loadAwacsTargets(request.getAnalysisIds(), request.isPreloadAll(), freqTol);
        List<AwacsOccupancyWindow> windows = occupancyService.collectWindows(seeds, padSec, freqTol);
        response.setOccupancyWindowCount(windows.size());
        if (windows.isEmpty()) {
            return skipped(response, "一次分析没有预警机占用窗", t0);
        }

        Set<SourceRowRef> allRefs = sourceExportService.scanRowRefsByOccupancyWindows(sourceCsv, windows);
        if (allRefs.isEmpty()) {
            return skipped(response, "占用窗内没有可导出的原始点", t0);
        }

        Path commandNetDir = firstPassDir.resolve("command-net");
        Path occupancyCsv = commandNetDir.resolve("command_net_source.csv");
        Files.createDirectories(commandNetDir);
        sourceExportService.exportOriginalRows(sourceCsv, allRefs, occupancyCsv);

        SceneFinderResult finderResult = sceneFinderService.analyzeCommandNet(
                occupancyCsv,
                windows,
                AnalyzeOptions.forCommandNet(commandNetDir.toString(), Double.valueOf(freqTol)));
        response.setCommandNetOutputDir(commandNetDir.toString());

        List<OccupancyCluster> clusters = occupancyService.clusterByFreq(windows, freqTol);
        response.setClusterCount(clusters.size());
        Map<SourceRowRef, Integer> trackIdByRow = trackIdResolver.loadAllTrackRows(commandNetDir, sourceCsv);

        List<SceneProcessResponse> items = new ArrayList<SceneProcessResponse>();
        int localRank = 1;
        for (OccupancyCluster cluster : clusters) {
            Set<SourceRowRef> clusterRefs = filterRefs(sourceCsv, cluster);
            if (clusterRefs.isEmpty()) {
                localRank++;
                continue;
            }
            Path clusterCsv = commandNetDir.resolve("command_net_rank" + localRank + "_backend_source.csv");
            int rowCount = sourceExportService.exportOriginalRows(sourceCsv, clusterRefs, clusterCsv, trackIdByRow);
            AnalyzeSessionResponse session = signalAnalysisFacade.analyzeFromPath(clusterCsv, freqTol);
            if (request.isPreloadAll() && session != null) {
                AnalyzeSessionResponse preloaded = signalAnalysisFacade.preloadAllNetworks(session.getAnalysisId());
                if (preloaded != null) {
                    session = preloaded;
                }
            }
            int displayRank = COMMAND_NET_RANK_OFFSET + localRank;
            SceneReportChunkResponse chunk = sceneReportService.buildSceneChunk(
                    commandNetDir.toString(),
                    localRank,
                    session != null ? session.getAnalysisId() : "",
                    SceneType.COMMAND_NET.name(),
                    request.isPreloadAll(),
                    clusterCsv.toString(),
                    freqTol,
                    Double.valueOf(cluster.getFreqCenterMhz())
            );
            markCommandNetRows(chunk.getRows(), displayRank);
            SceneProcessResponse item = new SceneProcessResponse();
            item.setRank(displayRank);
            item.setSceneType(SceneType.COMMAND_NET.name());
            item.setExportedCsvPath(clusterCsv.toString());
            item.setExportedRowCount(rowCount);
            item.setSession(session);
            item.setReportRows(chunk.getRows());
            item.setNetworkCount(chunk.getNetworkCount());
            item.setCommandNet(true);
            item.setSpanStartEpochMs(cluster.getTStartMs());
            item.setSpanEndEpochMs(cluster.getTEndMs());
            items.add(item);
            localRank++;
        }
        response.setItems(items);
        response.setAwacsPanels(buildAwacsPanels(seeds, windows, clusters, items, finderResult));
        response.setReplacedRanks(findReplacedRanks(firstPassDir, windows));
        if (finderResult != null) {
            log.info("指挥网二次建轨完成: detections={} tracks={} scenes={}",
                    finderResult.getTotalDetections(), finderResult.getConfirmedTracks(),
                    finderResult.getScenes() != null ? finderResult.getScenes().size() : 0);
        }
        response.setElapsedMs(System.currentTimeMillis() - t0);
        return response;
    }

    private List<TargetView> loadAwacsTargets(List<String> analysisIds, boolean preloadAll, double freqTol)
            throws IOException {
        List<TargetView> targets = new ArrayList<TargetView>();
        if (analysisIds == null) {
            return targets;
        }
        for (String analysisId : analysisIds) {
            if (analysisId == null || analysisId.trim().isEmpty()) {
                continue;
            }
            AnalyzeSessionResponse session = sceneReportService.ensureSession(
                    analysisId, null, freqTol);
            if (session == null) {
                continue;
            }
            if (preloadAll) {
                AnalyzeSessionResponse updated = signalAnalysisFacade.preloadAllNetworks(session.getAnalysisId());
                if (updated != null) {
                    session = updated;
                }
            }
            if (session.getNetworks() == null) {
                continue;
            }
            for (NetworkSummary summary : session.getNetworks()) {
                NetworkView view = signalAnalysisFacade.getNetworkDetail(session.getAnalysisId(), summary.getNetworkId());
                if (view != null && view.getTargets() != null) {
                    targets.addAll(view.getTargets());
                }
            }
        }
        return targets;
    }

    private List<CommandNetAwacsPanel> buildAwacsPanels(
            List<TargetView> seeds,
            List<AwacsOccupancyWindow> windows,
            List<OccupancyCluster> clusters,
            List<SceneProcessResponse> items,
            SceneFinderResult finderResult
    ) {
        List<CommandNetAwacsPanel> panels = new ArrayList<CommandNetAwacsPanel>();
        if (windows == null || windows.isEmpty()) {
            return panels;
        }
        Map<String, TargetView> seedById = new LinkedHashMap<String, TargetView>();
        if (seeds != null) {
            for (int i = 0; i < seeds.size(); i++) {
                TargetView seed = seeds.get(i);
                if (seed != null && seed.getTargetId() != null) {
                    seedById.put(seed.getTargetId(), seed);
                }
            }
        }
        Map<Integer, OccupancyCluster> clusterByLocal = new LinkedHashMap<Integer, OccupancyCluster>();
        int localRank = 1;
        for (int i = 0; i < clusters.size(); i++) {
            clusterByLocal.put(Integer.valueOf(localRank), clusters.get(i));
            localRank++;
        }
        Map<Integer, SceneProcessResponse> itemByLocal = new LinkedHashMap<Integer, SceneProcessResponse>();
        for (int i = 0; i < items.size(); i++) {
            SceneProcessResponse item = items.get(i);
            int lr = item.getRank() - COMMAND_NET_RANK_OFFSET;
            itemByLocal.put(Integer.valueOf(lr), item);
        }
        Map<Integer, Map<String, Object>> trajByLocal = trajectoryByLocalRank(finderResult);

        int seq = 1;
        for (Map.Entry<Integer, OccupancyCluster> e : clusterByLocal.entrySet()) {
            OccupancyCluster cluster = e.getValue();
            if (cluster == null || cluster.getWindows() == null || cluster.getWindows().isEmpty()) {
                continue;
            }
            LinkedHashSet<String> clusterSeeds = new LinkedHashSet<String>();
            List<AwacsOccupancyWindow> clusterWindows = cluster.getWindows();
            for (int i = 0; i < clusterWindows.size(); i++) {
                String id = clusterWindows.get(i).getSeedTargetId();
                if (id != null && !id.isEmpty()) {
                    clusterSeeds.add(id);
                }
            }
            int lr = e.getKey().intValue();
            Map<String, Object> view = trajByLocal.get(e.getKey());
            SceneProcessResponse item = itemByLocal.get(e.getKey());
            for (String seedId : clusterSeeds) {
                List<AwacsOccupancyWindow> seedWindows = new ArrayList<AwacsOccupancyWindow>();
                for (int i = 0; i < clusterWindows.size(); i++) {
                    if (seedId.equals(clusterWindows.get(i).getSeedTargetId())) {
                        seedWindows.add(clusterWindows.get(i));
                    }
                }
                if (seedWindows.isEmpty()) {
                    continue;
                }
                CommandNetAwacsPanel panel = new CommandNetAwacsPanel();
                panel.setSeedTargetId(seedId);
                String freqTxt = String.format(java.util.Locale.ROOT, "%.3f",
                        Double.valueOf(cluster.getFreqCenterMhz()));
                panel.setPanelId(seedId + "@" + freqTxt);
                TargetView seed = seedById.get(seedId);
                panel.setTargetType(seed != null ? seed.getTargetType() : "AWACS");
                panel.setTargetTypeLabel("预警机");
                panel.setLabel("预警机" + seq + " · " + seedId + " · " + freqTxt + " MHz");
                seq++;
                panel.setWindows(seedWindows);
                long t0 = Long.MAX_VALUE;
                long t1 = Long.MIN_VALUE;
                for (int i = 0; i < seedWindows.size(); i++) {
                    AwacsOccupancyWindow w = seedWindows.get(i);
                    t0 = Math.min(t0, w.getTStartMs());
                    t1 = Math.max(t1, w.getTEndMs());
                }
                panel.setTStartMs(t0 == Long.MAX_VALUE ? 0L : t0);
                panel.setTEndMs(t1 == Long.MIN_VALUE ? 0L : t1);
                panel.setFreqMhz(cluster.getFreqCenterMhz());

                List<Map<String, Object>> finderTracks = filterTracks(view, seedWindows);
                List<AnalysisReportRow> mergedRows = item != null
                        ? filterRows(item.getReportRows(), seedWindows)
                        : new ArrayList<AnalysisReportRow>();
                List<Map<String, Object>> analysisTracks = tracksFromAnalysis(mergedRows, seedWindows);
                List<Map<String, Object>> plotTracks = analysisTracks.isEmpty() ? finderTracks : analysisTracks;
                // #region agent log
                debugPanelTracks(panel.getPanelId(), finderTracks.size(), mergedRows.size(),
                        analysisTracks.size(), plotTracks.size(), cluster.getFreqCenterMhz());
                // #endregion
                panel.setDisplayRank(COMMAND_NET_RANK_OFFSET + lr);
                panel.setTrajectoryView(buildPanelView(panel, plotTracks));
                panel.setReportRows(mergedRows);
                panels.add(panel);
            }
        }
        return panels;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Map<String, Object>> trajectoryByLocalRank(SceneFinderResult finderResult) {
        Map<Integer, Map<String, Object>> out = new LinkedHashMap<Integer, Map<String, Object>>();
        if (finderResult == null || finderResult.getVisualization() == null) {
            return out;
        }
        Object raw = finderResult.getVisualization().get("trajectoryViews");
        if (!(raw instanceof List)) {
            return out;
        }
        List<?> views = (List<?>) raw;
        for (int i = 0; i < views.size(); i++) {
            Object v = views.get(i);
            if (!(v instanceof Map)) {
                continue;
            }
            Map<String, Object> view = (Map<String, Object>) v;
            Object rank = view.get("sceneRank");
            if (rank instanceof Number) {
                out.put(Integer.valueOf(((Number) rank).intValue()), view);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> filterTracks(
            Map<String, Object> view,
            List<AwacsOccupancyWindow> windows
    ) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (view == null) {
            return out;
        }
        appendFilteredSeries(out, view.get("tracks"), windows);
        appendFilteredSeries(out, view.get("pollingTargets"), windows);
        Object overlay = view.get("interrogatorOverlay");
        if (overlay instanceof Map) {
            Map<String, Object> kept = filterOneSeries((Map<String, Object>) overlay, windows);
            if (kept != null) {
                out.add(kept);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void appendFilteredSeries(
            List<Map<String, Object>> out,
            Object raw,
            List<AwacsOccupancyWindow> windows
    ) {
        if (!(raw instanceof List)) {
            return;
        }
        List<?> list = (List<?>) raw;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> kept = filterOneSeries((Map<String, Object>) item, windows);
            if (kept != null) {
                out.add(kept);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> filterOneSeries(
            Map<String, Object> series,
            List<AwacsOccupancyWindow> windows
    ) {
        Object pts = series.get("points");
        if (!(pts instanceof List)) {
            return null;
        }
        double freq = series.get("freqMhz") instanceof Number
                ? ((Number) series.get("freqMhz")).doubleValue()
                : Double.NaN;
        List<?> points = (List<?>) pts;
        List<Map<String, Object>> keptPts = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < points.size(); i++) {
            Object p = points.get(i);
            if (!(p instanceof Map)) {
                continue;
            }
            Map<String, Object> pt = (Map<String, Object>) p;
            Object x = pt.get("x");
            if (!(x instanceof Number)) {
                continue;
            }
            double pf = pt.get("freqMhz") instanceof Number
                    ? ((Number) pt.get("freqMhz")).doubleValue()
                    : freq;
            if (!Double.isFinite(pf)) {
                continue;
            }
            if (AwacsCommandNetService.matchesAny(pf, ((Number) x).longValue(), windows)) {
                keptPts.add(pt);
            }
        }
        if (keptPts.size() < 2) {
            return null;
        }
        Map<String, Object> copy = new LinkedHashMap<String, Object>(series);
        copy.put("points", keptPts);
        return copy;
    }

    private static List<AnalysisReportRow> filterRows(
            List<AnalysisReportRow> rows,
            List<AwacsOccupancyWindow> windows
    ) {
        List<AnalysisReportRow> out = new ArrayList<AnalysisReportRow>();
        if (rows == null) {
            return out;
        }
        for (int i = 0; i < rows.size(); i++) {
            AnalysisReportRow row = rows.get(i);
            if (row == null) {
                continue;
            }
            if (rowMatchesWindows(row, windows)) {
                out.add(row);
            }
        }
        return out;
    }

    private static boolean rowMatchesWindows(AnalysisReportRow row, List<AwacsOccupancyWindow> windows) {
        double freq = row.getNetworkFreqMhz();
        Long start = parseIsoMs(row.getDetectStartTime());
        Long end = parseIsoMs(row.getDetectEndTime());
        for (int i = 0; i < windows.size(); i++) {
            AwacsOccupancyWindow w = windows.get(i);
            if (Math.abs(freq - w.getFreqCenterMhz()) > w.getFreqToleranceMhz()) {
                continue;
            }
            if (start == null || end == null) {
                return true;
            }
            if (end.longValue() >= w.getTStartMs() && start.longValue() <= w.getTEndMs()) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> tracksFromAnalysis(
            List<AnalysisReportRow> rows,
            List<AwacsOccupancyWindow> windows
    ) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        Map<String, NetworkView> viewCache = new LinkedHashMap<String, NetworkView>();
        LinkedHashSet<String> seen = new LinkedHashSet<String>();
        int colorIdx = 0;
        for (int i = 0; i < rows.size(); i++) {
            AnalysisReportRow row = rows.get(i);
            if (row == null || row.getTargetId() == null || row.getTargetId().isEmpty()) {
                continue;
            }
            String key = row.getAnalysisId() + "|" + row.getNetworkId() + "|" + row.getTargetId();
            if (seen.contains(key)) {
                continue;
            }
            seen.add(key);
            NetworkView net = loadNetworkView(viewCache, row.getAnalysisId(), row.getNetworkId());
            TargetView target = findTarget(net, row.getTargetId());
            if (target == null) {
                continue;
            }
            double netFreq = net != null ? net.getFreq() : row.getNetworkFreqMhz();
            Map<String, Object> series = seriesFromTarget(target, row, netFreq, windows, colorIdx);
            if (series != null) {
                out.add(series);
                colorIdx++;
            }
        }
        return out;
    }

    private NetworkView loadNetworkView(Map<String, NetworkView> cache, String analysisId, int networkId) {
        if (analysisId == null || analysisId.trim().isEmpty()) {
            return null;
        }
        String key = analysisId + "|" + networkId;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        NetworkView view = null;
        try {
            view = signalAnalysisFacade.getNetworkDetail(analysisId, networkId);
        } catch (RuntimeException ex) {
            log.warn("指挥网面板取网络详情失败 analysisId={} networkId={}: {}",
                    analysisId, Integer.valueOf(networkId), ex.getMessage());
        }
        cache.put(key, view);
        return view;
    }

    private static TargetView findTarget(NetworkView net, String targetId) {
        if (net == null || net.getTargets() == null || targetId == null) {
            return null;
        }
        List<TargetView> targets = net.getTargets();
        for (int i = 0; i < targets.size(); i++) {
            TargetView t = targets.get(i);
            if (t != null && targetId.equals(t.getTargetId())) {
                return t;
            }
        }
        return null;
    }

    private static Map<String, Object> seriesFromTarget(
            TargetView target,
            AnalysisReportRow row,
            double netFreq,
            List<AwacsOccupancyWindow> windows,
            int colorIdx
    ) {
        List<SeriesPoint> az = target.getAzimuthSeries();
        if (az == null || az.size() < 2) {
            az = target.getRawAzimuthSeries();
        }
        if (az == null || az.size() < 2) {
            return null;
        }
        List<SeriesPoint> freqSeries = target.getFreqSeries();
        List<Map<String, Object>> kept = new ArrayList<Map<String, Object>>();
        double ySum = 0d;
        for (int i = 0; i < az.size(); i++) {
            SeriesPoint p = az.get(i);
            if (p == null) {
                continue;
            }
            double freq = freqAt(freqSeries, p.getT(), netFreq);
            if (!AwacsCommandNetService.matchesAny(freq, p.getT(), windows)) {
                continue;
            }
            Map<String, Object> pt = new LinkedHashMap<String, Object>();
            pt.put("x", Long.valueOf(p.getT()));
            pt.put("y", Double.valueOf(Math.round(p.getV() * 10.0) / 10.0));
            pt.put("freqMhz", Double.valueOf(freq));
            kept.add(pt);
            ySum += p.getV();
        }
        if (kept.size() < 2) {
            return null;
        }
        String type = row.getTargetType() != null ? row.getTargetType() : target.getTargetType();
        String typeLabel = typeLabelOf(type);
        String id = row.getTargetId();
        String freqTxt = String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(row.getNetworkFreqMhz()));
        Map<String, Object> track = new LinkedHashMap<String, Object>();
        track.put("trackId", id + "@" + freqTxt);
        track.put("targetId", id);
        track.put("label", id + "-" + typeLabel + " " + freqTxt);
        track.put("targetType", type);
        track.put("targetTypeLabel", typeLabel);
        track.put("role", row.getRole() != null ? row.getRole() : target.getRole());
        track.put("color", PANEL_TRACK_COLORS[colorIdx % PANEL_TRACK_COLORS.length]);
        track.put("freqMhz", Double.valueOf(row.getNetworkFreqMhz()));
        track.put("meanBearing", Double.valueOf(Math.round((ySum / kept.size()) * 10.0) / 10.0));
        track.put("points", kept);
        return track;
    }

    private static double freqAt(List<SeriesPoint> freqSeries, long timeMs, double fallback) {
        if (freqSeries == null || freqSeries.isEmpty()) {
            return fallback;
        }
        SeriesPoint best = null;
        long bestDt = Long.MAX_VALUE;
        for (int i = 0; i < freqSeries.size(); i++) {
            SeriesPoint p = freqSeries.get(i);
            if (p == null) {
                continue;
            }
            long dt = Math.abs(p.getT() - timeMs);
            if (dt < bestDt) {
                bestDt = dt;
                best = p;
            }
        }
        if (best == null || bestDt > 3000L) {
            return fallback;
        }
        return best.getV();
    }

    private static String typeLabelOf(String type) {
        if ("AWACS".equals(type)) {
            return "预警机";
        }
        if ("AIR".equals(type)) {
            return "飞机";
        }
        if ("GROUND".equals(type)) {
            return "地面站";
        }
        return type == null || type.isEmpty() ? "目标" : type;
    }

    // #region agent log
    private static void debugPanelTracks(
            String panelId,
            int finderN,
            int rowsN,
            int analysisN,
            int plotN,
            double freqMhz
    ) {
        try {
            String sid = panelId == null ? "" : panelId.replace("\"", "'");
            String data = "{\"panelId\":\"" + sid + "\",\"freqMhz\":" + freqMhz
                    + ",\"finderTracks\":" + finderN
                    + ",\"reportRows\":" + rowsN
                    + ",\"analysisTracks\":" + analysisN
                    + ",\"plotTracks\":" + plotN + "}";
            String line = "{\"sessionId\":\"0cb39e\",\"runId\":\"post-fix\",\"hypothesisId\":\"F\",\"location\":\"AwacsCommandNetPassService.java:buildAwacsPanels\",\"message\":\"panel per freq\",\"data\":" + data + ",\"timestamp\":" + System.currentTimeMillis() + "}\n";
            Files.write(
                    Paths.get("D:\\Documents\\Code\\Java\\pdwfx\\.cursor\\debug-0cb39e.log"),
                    line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
    // #endregion

    private static Long parseIsoMs(String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(java.time.Instant.parse(iso).toEpochMilli());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Map<String, Object> buildPanelView(
            CommandNetAwacsPanel panel,
            List<Map<String, Object>> tracks
    ) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("unified", true);
        view.put("viewMode", "track");
        view.put("sceneRank", Integer.valueOf(panel.getDisplayRank()));
        view.put("freqCenterMhz", Double.valueOf(panel.getFreqMhz()));
        view.put("title", panel.getLabel() + " · 同频同时段建轨");
        view.put("xMin", Long.valueOf(panel.getTStartMs()));
        view.put("xMax", Long.valueOf(panel.getTEndMs()));
        double yMin = Double.POSITIVE_INFINITY;
        double yMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < tracks.size(); i++) {
            Object pts = tracks.get(i).get("points");
            if (!(pts instanceof List)) {
                continue;
            }
            List<?> list = (List<?>) pts;
            for (int j = 0; j < list.size(); j++) {
                Object p = list.get(j);
                if (!(p instanceof Map)) {
                    continue;
                }
                Object y = ((Map<?, ?>) p).get("y");
                if (y instanceof Number) {
                    yMin = Math.min(yMin, ((Number) y).doubleValue());
                    yMax = Math.max(yMax, ((Number) y).doubleValue());
                }
            }
        }
        if (!Double.isFinite(yMin)) {
            yMin = 0;
            yMax = 360;
        }
        double pad = Math.max(2.0, (yMax - yMin) * 0.08);
        view.put("yMin", Double.valueOf(yMin - pad));
        view.put("yMax", Double.valueOf(yMax + pad));
        view.put("trackCount", Integer.valueOf(tracks.size()));
        view.put("tracks", tracks);
        view.put("note", "仅显示该预警机占用窗内同频同时段的建轨结果");
        return view;
    }

    private Set<SourceRowRef> filterRefs(Path sourceCsv, OccupancyCluster cluster) throws IOException {
        return sourceExportService.scanRowRefsByOccupancyWindows(sourceCsv, cluster.getWindows());
    }

    private List<Integer> findReplacedRanks(Path firstPassDir, List<AwacsOccupancyWindow> windows)
            throws IOException {
        Set<Integer> ranks = new LinkedHashSet<Integer>();
        try {
            List<SceneSummaryEntry> entries = sceneSummaryReader.load(firstPassDir);
            for (SceneSummaryEntry scene : entries) {
                if (sceneOverlapsAnyWindow(scene, windows)) {
                    ranks.add(Integer.valueOf(scene.getRank()));
                }
            }
        } catch (IllegalArgumentException ex) {
            log.warn("一次场景摘要不可用，跳过替换标记: {}", ex.getMessage());
        }
        return new ArrayList<Integer>(ranks);
    }

    static boolean sceneOverlapsAnyWindow(SceneSummaryEntry scene, List<AwacsOccupancyWindow> windows) {
        if (scene == null) {
            return false;
        }
        return AwacsCommandNetService.sceneOverlapsAnyWindow(
                scene.getWindowStart(),
                scene.getWindowEnd(),
                scene.getFreqMinMhz(),
                scene.getFreqMaxMhz(),
                windows
        );
    }

    private static void markCommandNetRows(List<AnalysisReportRow> rows, int displayRank) {
        if (rows == null) {
            return;
        }
        for (AnalysisReportRow row : rows) {
            row.setSceneRank(displayRank);
            row.setSceneType(SceneType.COMMAND_NET.name());
            row.setCommandNet(true);
        }
    }

    private static CommandNetPassResponse skipped(CommandNetPassResponse response, String reason, long t0) {
        response.setSkipped(true);
        response.setSkipReason(reason);
        response.setElapsedMs(System.currentTimeMillis() - t0);
        return response;
    }
}
