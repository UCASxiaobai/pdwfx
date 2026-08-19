package com.pdwfx.stream.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdwfx.stream.batch.StreamBatchQueue;
import com.pdwfx.stream.config.StreamProperties;
import com.pdwfx.stream.model.StreamBatchResult;
import com.pdwfx.stream.model.StreamSceneSummary;
import com.pdwfx.stream.model.StreamTrackLabel;
import com.pdwfx.stream.store.StreamResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自动分析 worker：封批 CSV → 场景筛选/建轨 → 逐场景信号分析 → 标注 JSON。
 */
@Component
public class StreamBatchAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(StreamBatchAnalyzer.class);

    private final StreamProperties properties;
    private final StreamBatchQueue queue;
    private final BackendAnalyzeClient analyzeClient;
    private final StreamResultStore resultStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> currentFile = new AtomicReference<>("");
    private final AtomicReference<String> currentPhase = new AtomicReference<>("");
    /** 重启后递增；进行中的分析若世代落后则丢弃结果，避免旧批写回。 */
    private final AtomicLong restartEpoch = new AtomicLong(0L);
    /** 单 worker 线程：当前 processOne 捕获的重启世代。 */
    private final ThreadLocal<Long> processEpoch = new ThreadLocal<>();
    private ExecutorService worker;

    public StreamBatchAnalyzer(StreamProperties properties,
                               StreamBatchQueue queue,
                               BackendAnalyzeClient analyzeClient,
                               StreamResultStore resultStore) {
        this.properties = properties;
        this.queue = queue;
        this.analyzeClient = analyzeClient;
        this.resultStore = resultStore;
    }

    @PostConstruct
    public void start() {
        if (!properties.getAnalyze().isEnabled()) {
            log.info("Stream analyze worker disabled");
            return;
        }
        running.set(true);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "stream-batch-analyzer");
            t.setDaemon(true);
            return t;
        });
        worker.submit(this::loop);
        log.info("Stream batch analyzer started (scene pipeline, workers=1)");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (worker != null) worker.shutdownNow();
    }

    public String getCurrentFile() { return currentFile.get(); }
    public String getCurrentPhase() { return currentPhase.get(); }

    public long getRestartEpoch() {
        return restartEpoch.get();
    }

    /**
     * 标记重启：进行中的分析完成后不再写回结果；清空当前相位显示。
     *
     * @return 新的世代号
     */
    public long markRestart() {
        long gen = restartEpoch.incrementAndGet();
        currentFile.set("");
        currentPhase.set("");
        log.info("Analyzer marked restart epoch={}", gen);
        return gen;
    }

    private void loop() {
        Path root = Paths.get(properties.getBatch().getDir()).toAbsolutePath().normalize();
        Path processing = root.resolve("processing");
        Path done = root.resolve("done");
        Path failed = root.resolve("failed");
        while (running.get()) {
            try {
                Path inboxFile = queue.poll(1000);
                if (inboxFile == null) continue;
                processOne(inboxFile, processing, done, failed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("analyzer loop error: {}", e.getMessage(), e);
            }
        }
    }

    private void processOne(Path inboxFile, Path processing, Path done, Path failed) {
        long epochAtStart = restartEpoch.get();
        processEpoch.set(Long.valueOf(epochAtStart));
        String batchId = stripExt(inboxFile.getFileName().toString());
        currentFile.set(batchId);
        Path work = processing.resolve(inboxFile.getFileName());
        StreamBatchResult result = new StreamBatchResult();
        result.setStreamBatchId(batchId);
        result.setCsvPath(inboxFile.toString());
        result.setFinishedAt(Instant.now());
        try {
            if (epochAtStart != restartEpoch.get()) {
                log.info("Skip batch {} — restart during dequeue", batchId);
                moveToDiscarded(inboxFile);
                return;
            }
            Files.createDirectories(processing);
            Files.createDirectories(done);
            Files.createDirectories(failed);
            Files.move(inboxFile, work, StandardCopyOption.REPLACE_EXISTING);
            result.setCsvPath(work.toString());

            // ---- 1) 场景筛选 + 建轨 ----
            result.setStatus("SCENE_FILTER");
            currentPhase.set("SCENE_FILTER");
            publish(result);

            String outputDir = "stream-batch-" + batchId;
            log.info("批次 {} 场景筛选 outputDir={}", batchId, outputDir);
            JsonNode sceneResult = analyzeClient.sceneAnalyzeUpload(work, outputDir);

            String sourceCsv = text(sceneResult, "sourceCsv");
            String sceneOutputDir = text(sceneResult, "outputDir");
            if (sceneOutputDir == null || sceneOutputDir.isEmpty()) {
                sceneOutputDir = outputDir;
            }
            result.setSourceCsvOnBackend(sourceCsv);
            result.setSceneOutputDir(sceneOutputDir);
            result.setTotalDetections(sceneResult.path("totalDetections").asInt(0));
            result.setConfirmedTracks(sceneResult.path("confirmedTracks").asInt(0));

            List<StreamSceneSummary> sceneSummaries = new ArrayList<>();
            List<Integer> ranks = new ArrayList<>();
            int trackScenes = 0;
            int pollingScenes = 0;
            JsonNode scenesNode = sceneResult.path("scenes");
            if (scenesNode.isArray()) {
                for (JsonNode s : scenesNode) {
                    int rank = s.path("rank").asInt(0);
                    if (rank <= 0) continue;
                    ranks.add(rank);
                    StreamSceneSummary sum = new StreamSceneSummary();
                    sum.setRank(rank);
                    String type = text(s, "sceneType");
                    sum.setSceneType(type);
                    sum.setTrackCount(s.path("trackIds").isArray() ? s.path("trackIds").size() : 0);
                    sum.setWindowStartMs(parseTimeMs(s.get("windowStart")));
                    sum.setWindowEndMs(parseTimeMs(s.get("windowEnd")));
                    sum.setFreqCenterMhz(parseDoubleOrNull(s.get("freqCenterMhz")));
                    sum.setFreqMinMhz(parseDoubleOrNull(s.get("freqMinMhz")));
                    sum.setFreqMaxMhz(parseDoubleOrNull(s.get("freqMaxMhz")));
                    sceneSummaries.add(sum);
                    if (isPolling(type)) {
                        pollingScenes++;
                    } else {
                        trackScenes++;
                    }
                }
            }
            Collections.sort(ranks);
            sceneSummaries.sort(Comparator.comparingInt(StreamSceneSummary::getRank));
            result.setScenes(sceneSummaries);
            result.setSceneCount(sceneSummaries.size());
            result.setTrackSceneCount(trackScenes);
            result.setPollingSceneCount(pollingScenes);
            publish(result);

            if (sourceCsv == null || sourceCsv.isEmpty()) {
                throw new IllegalStateException("场景筛选未返回 sourceCsv");
            }

            // ---- 2) 逐场景信号分析 ----
            result.setStatus("PROCESS_SCENE");
            currentPhase.set("PROCESS_SCENE");
            publish(result);

            List<StreamTrackLabel> allLabels = new ArrayList<>();
            List<Map<String, Object>> allReportRows = new ArrayList<>();
            List<String> analysisIds = new ArrayList<>();
            int networkTotal = 0;
            Map<Integer, StreamSceneSummary> byRank = new HashMap<>();
            for (StreamSceneSummary s : sceneSummaries) {
                byRank.put(s.getRank(), s);
            }

            for (int rank : ranks) {
                currentPhase.set("PROCESS_SCENE#" + rank);
                log.info("批次 {} 分析场景 rank={}", batchId, rank);
                JsonNode proc = analyzeClient.processScene(sourceCsv, sceneOutputDir, rank);
                StreamSceneSummary sum = byRank.get(rank);
                if (sum == null) {
                    sum = new StreamSceneSummary();
                    sum.setRank(rank);
                    sceneSummaries.add(sum);
                    byRank.put(rank, sum);
                }
                boolean skipped = proc.path("skipped").asBoolean(false);
                sum.setSkipped(skipped);
                sum.setSkipReason(text(proc, "skipReason"));
                String sceneType = text(proc, "sceneType");
                if (sceneType != null && !sceneType.isEmpty()) {
                    sum.setSceneType(sceneType);
                }
                if (skipped) {
                    publish(result);
                    continue;
                }
                JsonNode session = proc.path("session");
                String analysisId = text(session, "analysisId");
                int netCount = session.path("networkCount").asInt(proc.path("networkCount").asInt(0));
                sum.setAnalysisId(analysisId);
                sum.setNetworkCount(netCount);
                if (analysisId != null && !analysisId.isEmpty()) {
                    analysisIds.add(analysisId);
                    if (result.getAnalysisId() == null || result.getAnalysisId().isEmpty()) {
                        result.setAnalysisId(analysisId);
                    }
                }
                networkTotal += netCount;

                JsonNode reportRowsNode = proc.path("reportRows");
                if (reportRowsNode.isArray()) {
                    for (JsonNode row : reportRowsNode) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = objectMapper.convertValue(row, Map.class);
                        allReportRows.add(map);
                    }
                }
                allLabels.addAll(extractLabels(batchId, rank, sum.getSceneType(), analysisId, proc));
                publish(result);
            }

            applyCommandNetPass(batchId, sourceCsv, sceneOutputDir, analysisIds,
                    sceneSummaries, byRank, allLabels, allReportRows, result);

            int netsAfter = 0;
            int trackScenesAfter = 0;
            int pollingScenesAfter = 0;
            for (StreamSceneSummary s : sceneSummaries) {
                netsAfter += s.getNetworkCount();
                if (isPolling(s.getSceneType())) {
                    pollingScenesAfter++;
                } else if (!"COMMAND_NET".equals(s.getSceneType())) {
                    trackScenesAfter++;
                }
            }
            result.setScenes(sceneSummaries);
            result.setSceneCount(sceneSummaries.size());
            result.setTrackSceneCount(trackScenesAfter);
            result.setPollingSceneCount(pollingScenesAfter);
            result.setAnalysisIds(analysisIds);
            result.setNetworkCount(netsAfter);
            result.setLabels(allLabels);
            result.setTrackCount(allLabels.size());
            result.setReportRows(allReportRows);
            ObjectNode occupancyNode = buildOccupancy(allLabels);
            @SuppressWarnings("unchecked")
            Map<String, Object> occupancyMap = objectMapper.convertValue(occupancyNode, Map.class);
            result.setOccupancy(occupancyMap);

            Path annotationPath = writeAnnotation(result);
            result.setAnnotationPath(annotationPath != null ? annotationPath.toString() : null);

            result.setStatus("DONE");
            currentPhase.set("DONE");
            Files.move(work, done.resolve(work.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            log.info("Batch {} done: scenes={} networks={} labels={} annotation={}",
                    batchId, result.getSceneCount(), result.getNetworkCount(),
                    result.getTrackCount(), result.getAnnotationPath());
        } catch (Exception e) {
            result.setStatus("FAILED");
            result.setError(e.getMessage());
            currentPhase.set("FAILED");
            log.error("Batch {} failed: {}", batchId, e.getMessage(), e);
            try {
                if (Files.exists(work)) {
                    Files.move(work, failed.resolve(work.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                } else if (Files.exists(inboxFile)) {
                    Files.move(inboxFile, failed.resolve(inboxFile.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception moveEx) {
                log.warn("move failed file error: {}", moveEx.getMessage());
            }
        } finally {
            result.setFinishedAt(Instant.now());
            if (epochAtStart == restartEpoch.get()) {
                publish(result);
            } else {
                log.info("Discard analysis result for {} — restart epoch {} -> {}",
                        batchId, epochAtStart, restartEpoch.get());
            }
            if (restartEpoch.get() == epochAtStart) {
                currentFile.set("");
                currentPhase.set("");
            }
            processEpoch.remove();
        }
    }

    private void moveToDiscarded(Path file) {
        try {
            if (file == null || !Files.exists(file)) {
                return;
            }
            Path root = Paths.get(properties.getBatch().getDir()).toAbsolutePath().normalize();
            Path discarded = root.resolve("discarded");
            Files.createDirectories(discarded);
            Files.move(file, discarded.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("move discarded failed: {}", e.getMessage());
        }
    }

    private void publish(StreamBatchResult result) {
        if (result == null) {
            return;
        }
        Long ep = processEpoch.get();
        if (ep != null && ep.longValue() != restartEpoch.get()) {
            return;
        }
        result.setFinishedAt(Instant.now());
        resultStore.put(result);
    }

    private Path writeAnnotation(StreamBatchResult result) throws Exception {
        Path dir = resolveAnnotationDir();
        Files.createDirectories(dir);
        Path out = dir.resolve(result.getStreamBatchId() + ".json");

        ObjectNode root = objectMapper.createObjectNode();
        root.put("streamBatchId", result.getStreamBatchId());
        root.put("csvPath", nullToEmpty(result.getCsvPath()));
        root.put("sceneOutputDir", nullToEmpty(result.getSceneOutputDir()));
        root.put("sourceCsvOnBackend", nullToEmpty(result.getSourceCsvOnBackend()));
        root.put("status", nullToEmpty(result.getStatus()));

        ObjectNode timeRange = root.putObject("timeRange");
        Long startMs = null;
        Long endMs = null;
        // 优先用场景窗（全段窗下即本批起止）
        for (StreamSceneSummary s : result.getScenes()) {
            if (s.getWindowStartMs() != null) {
                startMs = startMs == null ? s.getWindowStartMs() : Math.min(startMs, s.getWindowStartMs());
            }
            if (s.getWindowEndMs() != null) {
                endMs = endMs == null ? s.getWindowEndMs() : Math.max(endMs, s.getWindowEndMs());
            }
        }
        if (startMs == null || endMs == null) {
            for (StreamTrackLabel l : result.getLabels()) {
                if (l.getDetectStartMs() != null) {
                    startMs = startMs == null ? l.getDetectStartMs() : Math.min(startMs, l.getDetectStartMs());
                }
                if (l.getDetectEndMs() != null) {
                    endMs = endMs == null ? l.getDetectEndMs() : Math.max(endMs, l.getDetectEndMs());
                }
            }
        }
        if (startMs != null) timeRange.put("startMs", startMs);
        else timeRange.putNull("startMs");
        if (endMs != null) timeRange.put("endMs", endMs);
        else timeRange.putNull("endMs");

        ArrayNode scenesArr = root.putArray("scenes");
        for (StreamSceneSummary s : result.getScenes()) {
            ObjectNode n = scenesArr.addObject();
            n.put("rank", s.getRank());
            n.put("sceneType", nullToEmpty(s.getSceneType()));
            n.put("analysisId", nullToEmpty(s.getAnalysisId()));
            n.put("networkCount", s.getNetworkCount());
            n.put("trackCount", s.getTrackCount());
            n.put("skipped", s.isSkipped());
            if (s.getWindowStartMs() != null) n.put("windowStartMs", s.getWindowStartMs());
            if (s.getWindowEndMs() != null) n.put("windowEndMs", s.getWindowEndMs());
            if (s.getFreqCenterMhz() != null) n.put("freqCenterMhz", s.getFreqCenterMhz());
            if (s.getFreqMinMhz() != null) n.put("freqMinMhz", s.getFreqMinMhz());
            if (s.getFreqMaxMhz() != null) n.put("freqMaxMhz", s.getFreqMaxMhz());
        }

        root.set("labels", objectMapper.valueToTree(result.getLabels()));
        root.set("reportRows", objectMapper.valueToTree(result.getReportRows()));
        if (result.getCommandNetPass() != null && !result.getCommandNetPass().isEmpty()) {
            root.set("commandNetPass", objectMapper.valueToTree(result.getCommandNetPass()));
        }
        if (result.getOccupancy() != null && !result.getOccupancy().isEmpty()) {
            root.set("occupancy", objectMapper.valueToTree(result.getOccupancy()));
        } else {
            root.set("occupancy", buildOccupancy(result.getLabels()));
        }

        Files.write(out, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
        return out.toAbsolutePath().normalize();
    }

    private ObjectNode buildOccupancy(List<StreamTrackLabel> labels) {
        Map<String, ObjectNode> channels = new HashMap<>();
        Map<String, ObjectNode> freqs = new HashMap<>();
        Map<String, ObjectNode> types = new LinkedHashMap<>();
        ObjectNode occ = objectMapper.createObjectNode();
        ArrayNode chArr = occ.putArray("channels");
        ArrayNode freqArr = occ.putArray("freqs");
        ArrayNode typeArr = occ.putArray("targetTypes");

        for (StreamTrackLabel l : labels) {
            String chKey = l.getChannel() != null && !l.getChannel().isEmpty()
                    ? l.getChannel() : "_";
            ObjectNode ch = channels.get(chKey);
            if (ch == null) {
                ch = objectMapper.createObjectNode();
                ch.put("channel", nullToEmpty(l.getChannel()));
                ch.put("label", nullToEmpty(l.getChannelLabel()));
                ch.put("targetCount", 0);
                ch.put("trafficSharePctSum", 0.0);
                channels.put(chKey, ch);
            }
            ch.put("targetCount", ch.path("targetCount").asInt() + 1);
            ch.put("trafficSharePctSum",
                    ch.path("trafficSharePctSum").asDouble() + l.getTrafficSharePct());

            String fKey = String.format("%.6f", l.getFreqMhz());
            ObjectNode f = freqs.get(fKey);
            if (f == null) {
                f = objectMapper.createObjectNode();
                f.put("freqMhz", l.getFreqMhz());
                f.put("targetCount", 0);
                f.put("detectCount", 0);
                freqs.put(fKey, f);
            }
            f.put("targetCount", f.path("targetCount").asInt() + 1);
            f.put("detectCount", f.path("detectCount").asInt() + l.getDetectCount());

            String typeKey = l.getTargetType() != null && !l.getTargetType().isEmpty()
                    ? l.getTargetType() : "UNKNOWN";
            ObjectNode ty = types.get(typeKey);
            if (ty == null) {
                ty = objectMapper.createObjectNode();
                ty.put("type", typeKey);
                ty.put("label", nullToEmpty(l.getTargetTypeLabel()).isEmpty()
                        ? typeKey : l.getTargetTypeLabel());
                ty.put("targetCount", 0);
                types.put(typeKey, ty);
            }
            ty.put("targetCount", ty.path("targetCount").asInt() + 1);
        }
        for (ObjectNode ch : channels.values()) {
            chArr.add(ch);
        }
        for (ObjectNode f : freqs.values()) {
            freqArr.add(f);
        }
        for (ObjectNode ty : types.values()) {
            typeArr.add(ty);
        }
        return occ;
    }

    private Path resolveAnnotationDir() {
        String configured = properties.getAnalyze().getAnnotation().getDir();
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return Paths.get(properties.getBatch().getDir()).toAbsolutePath().normalize().resolve("annotations");
    }

    /**
     * 优先用 process-scene 的 reportRows（与主流程明细表同源，含目标类型/波道）；
     * 若无则回退 session.networks.targets。
     */
    private void applyCommandNetPass(
            String batchId,
            String sourceCsv,
            String sceneOutputDir,
            List<String> analysisIds,
            List<StreamSceneSummary> sceneSummaries,
            Map<Integer, StreamSceneSummary> byRank,
            List<StreamTrackLabel> allLabels,
            List<Map<String, Object>> allReportRows,
            StreamBatchResult result
    ) {
        result.setStatus("COMMAND_NET");
        currentPhase.set("COMMAND_NET");
        publish(result);
        log.info("批次 {} 指挥网二次分析 sessions={}", batchId, analysisIds.size());
        JsonNode pass;
        try {
            if (analysisIds == null || analysisIds.isEmpty()) {
                ObjectNode skip = objectMapper.createObjectNode();
                skip.put("skipped", true);
                skip.put("skipReason", "没有一次分析会话");
                pass = skip;
            } else {
                pass = analyzeClient.commandNetPass(sourceCsv, sceneOutputDir, analysisIds);
            }
        } catch (Exception e) {
            log.warn("批次 {} 指挥网二次失败，保留一次结果: {}", batchId, e.getMessage());
            ObjectNode skip = objectMapper.createObjectNode();
            skip.put("skipped", true);
            skip.put("skipReason", e.getMessage() == null ? "指挥网二次失败" : e.getMessage());
            result.setCommandNetPass(slimCommandNetPass(skip));
            return;
        }
        result.setCommandNetPass(slimCommandNetPass(pass));
        if (pass.path("skipped").asBoolean(false)) {
            log.info("批次 {} 指挥网二次跳过: {}", batchId, text(pass, "skipReason"));
            return;
        }
        Set<Integer> replaced = new HashSet<Integer>();
        JsonNode replacedNode = pass.path("replacedRanks");
        if (replacedNode.isArray()) {
            for (JsonNode n : replacedNode) {
                replaced.add(Integer.valueOf(n.asInt()));
            }
        }
        if (!replaced.isEmpty()) {
            sceneSummaries.removeIf(s -> replaced.contains(Integer.valueOf(s.getRank())));
            allLabels.removeIf(l -> replaced.contains(Integer.valueOf(l.getSceneRank())));
            allReportRows.removeIf(row -> {
                Object rank = row.get("sceneRank");
                return rank instanceof Number && replaced.contains(Integer.valueOf(((Number) rank).intValue()));
            });
            for (Integer rank : replaced) {
                byRank.remove(rank);
            }
        }
        JsonNode items = pass.path("items");
        if (!items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            int rank = item.path("rank").asInt(0);
            if (rank <= 0) {
                continue;
            }
            StreamSceneSummary sum = new StreamSceneSummary();
            sum.setRank(rank);
            sum.setSceneType(text(item, "sceneType"));
            JsonNode session = item.path("session");
            String analysisId = text(session, "analysisId");
            int netCount = session.path("networkCount").asInt(item.path("networkCount").asInt(0));
            sum.setAnalysisId(analysisId);
            sum.setNetworkCount(netCount);
            sum.setWindowStartMs(item.path("spanStartEpochMs").asLong(0) > 0
                    ? Long.valueOf(item.path("spanStartEpochMs").asLong()) : null);
            sum.setWindowEndMs(item.path("spanEndEpochMs").asLong(0) > 0
                    ? Long.valueOf(item.path("spanEndEpochMs").asLong()) : null);
            sceneSummaries.add(sum);
            byRank.put(Integer.valueOf(rank), sum);
            if (analysisId != null && !analysisId.isEmpty() && !analysisIds.contains(analysisId)) {
                analysisIds.add(analysisId);
            }
            JsonNode reportRowsNode = item.path("reportRows");
            if (reportRowsNode.isArray()) {
                for (JsonNode row : reportRowsNode) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = objectMapper.convertValue(row, Map.class);
                    allReportRows.add(map);
                }
            }
            allLabels.addAll(extractLabels(batchId, rank, sum.getSceneType(), analysisId, item));
        }
        sceneSummaries.sort(Comparator.comparingInt(StreamSceneSummary::getRank));
        log.info("批次 {} 指挥网二次完成: replaced={} newScenes={}",
                batchId, replaced.size(), items.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> slimCommandNetPass(JsonNode pass) {
        ObjectNode slim = objectMapper.createObjectNode();
        if (pass == null) {
            slim.put("skipped", true);
            slim.put("skipReason", "指挥网二次无响应");
            return objectMapper.convertValue(slim, Map.class);
        }
        slim.put("skipped", pass.path("skipped").asBoolean(false));
        slim.put("skipReason", text(pass, "skipReason"));
        slim.put("occupancyWindowCount", pass.path("occupancyWindowCount").asInt(0));
        slim.put("clusterCount", pass.path("clusterCount").asInt(0));
        slim.put("commandNetOutputDir", text(pass, "commandNetOutputDir"));
        slim.put("elapsedMs", pass.path("elapsedMs").asLong(0L));
        if (pass.has("replacedRanks")) {
            slim.set("replacedRanks", pass.get("replacedRanks"));
        }
        if (pass.has("awacsPanels")) {
            slim.set("awacsPanels", pass.get("awacsPanels"));
        }
        return objectMapper.convertValue(slim, Map.class);
    }

    private List<StreamTrackLabel> extractLabels(String streamBatchId,
                                                 int sceneRank,
                                                 String sceneType,
                                                 String analysisId,
                                                 JsonNode proc) {
        List<StreamTrackLabel> fromRows = extractLabelsFromReportRows(
                streamBatchId, sceneRank, sceneType, analysisId, proc.path("reportRows"));
        if (!fromRows.isEmpty()) {
            mergeMeanAzimuthFromSession(fromRows, proc.path("session"));
            return fromRows;
        }
        return extractLabelsFromSession(streamBatchId, sceneRank, sceneType, analysisId, proc.path("session"));
    }

    private List<StreamTrackLabel> extractLabelsFromReportRows(String streamBatchId,
                                                               int sceneRank,
                                                               String sceneType,
                                                               String analysisId,
                                                               JsonNode reportRows) {
        List<StreamTrackLabel> labels = new ArrayList<>();
        if (reportRows == null || !reportRows.isArray()) {
            return labels;
        }
        for (JsonNode r : reportRows) {
            String targetId = text(r, "targetId");
            if (targetId == null || targetId.isEmpty()) {
                continue;
            }
            int networkId = r.path("networkId").asInt(0);
            StreamTrackLabel label = new StreamTrackLabel();
            label.setStreamBatchId(streamBatchId);
            label.setSceneRank(r.path("sceneRank").asInt(sceneRank));
            String rowSceneType = text(r, "sceneType");
            label.setSceneType(rowSceneType.isEmpty() ? sceneType : rowSceneType);
            String rowAnalysisId = text(r, "analysisId");
            label.setAnalysisId(rowAnalysisId.isEmpty() ? analysisId : rowAnalysisId);
            label.setNetworkId(networkId);
            label.setTargetId(targetId);
            label.setBatchId(streamBatchId + "-s" + label.getSceneRank() + "-" + networkId + "-" + targetId);
            String targetType = text(r, "targetType");
            label.setTargetType(targetType);
            label.setTargetTypeLabel(targetTypeLabelOf(targetType));
            label.setDutyCycle(r.path("avgDutyCycle").asDouble(0));
            label.setTrafficSharePct(r.path("emissionSharePct").asDouble(0));
            label.setChannel(text(r, "commLinkChannel"));
            label.setChannelLabel(text(r, "commLinkChannelLabel"));
            label.setTargetChannelsUsed(text(r, "targetChannelsUsed"));
            label.setFreqMhz(r.path("networkFreqMhz").asDouble(0));
            label.setDetectCount(r.path("detectCount").asInt(0));
            label.setRole(text(r, "role"));
            label.setConfidence(r.path("confidence").asDouble(0));
            label.setLocateMethod(text(r, "locateMethod"));
            label.setLocateMethodLabel(text(r, "locateMethodLabel"));
            Long startMs = parseDetectTimeMs(r.get("detectStartTime"), r.get("detectStartMs"));
            Long endMs = parseDetectTimeMs(r.get("detectEndTime"), r.get("detectEndMs"));
            if (startMs != null) label.setDetectStartMs(startMs);
            if (endMs != null) label.setDetectEndMs(endMs);
            labels.add(label);
        }
        return labels;
    }

    private List<StreamTrackLabel> extractLabelsFromSession(String streamBatchId,
                                                            int sceneRank,
                                                            String sceneType,
                                                            String analysisId,
                                                            JsonNode session) {
        List<StreamTrackLabel> labels = new ArrayList<>();
        JsonNode networks = session.path("networks");
        if (!networks.isArray()) return labels;
        for (JsonNode net : networks) {
            int networkId = net.path("networkId").asInt();
            double freq = net.path("freq").asDouble();
            String channel = text(net, "commLinkChannel");
            String channelLabel = text(net, "commLinkChannelLabel");
            JsonNode targets = net.path("targets");
            if (!targets.isArray()) continue;
            for (JsonNode t : targets) {
                StreamTrackLabel label = new StreamTrackLabel();
                String targetId = text(t, "targetId");
                label.setStreamBatchId(streamBatchId);
                label.setSceneRank(sceneRank);
                label.setSceneType(sceneType);
                label.setAnalysisId(analysisId);
                label.setNetworkId(networkId);
                label.setTargetId(targetId);
                label.setBatchId(streamBatchId + "-s" + sceneRank + "-" + networkId + "-" + targetId);
                String targetType = text(t, "targetType");
                label.setTargetType(targetType);
                String typeLabel = text(t, "targetTypeLabel");
                label.setTargetTypeLabel(typeLabel.isEmpty() ? targetTypeLabelOf(targetType) : typeLabel);
                label.setDutyCycle(t.path("avgDutyCycle").asDouble(0));
                label.setTrafficSharePct(t.path("emissionSharePct").asDouble(0));
                label.setChannel(channel);
                label.setChannelLabel(channelLabel);
                label.setTargetChannelsUsed(channelLabel.isEmpty() ? channel : channelLabel);
                label.setFreqMhz(freq);
                label.setDetectCount(t.path("detectCount").asInt(0));
                label.setRole(text(t, "role"));
                label.setConfidence(t.path("confidence").asDouble(0));
                label.setTargetTypeReason(text(t, "targetTypeReason"));
                label.setLocateMethod(text(t, "locateMethod"));
                label.setLocateMethodLabel(text(t, "locateMethodLabel"));
                if (t.hasNonNull("detectStartMs")) label.setDetectStartMs(t.path("detectStartMs").asLong());
                if (t.hasNonNull("detectEndMs")) label.setDetectEndMs(t.path("detectEndMs").asLong());
                Double meanAz = meanAzimuthFromSeries(t.path("azimuthSeries"));
                if (meanAz == null) {
                    meanAz = meanAzimuthFromSeries(t.path("rawAzimuthSeries"));
                }
                if (meanAz != null) {
                    label.setMeanAzimuthDeg(meanAz);
                }
                labels.add(label);
            }
        }
        return labels;
    }

    /** 报表行无方位序列时，从 session 目标补 meanAzimuthDeg。 */
    private void mergeMeanAzimuthFromSession(List<StreamTrackLabel> labels, JsonNode session) {
        if (labels.isEmpty() || session == null || session.isMissingNode()) {
            return;
        }
        Map<String, Double> azByKey = new HashMap<>();
        JsonNode networks = session.path("networks");
        if (!networks.isArray()) return;
        for (JsonNode net : networks) {
            int networkId = net.path("networkId").asInt();
            JsonNode targets = net.path("targets");
            if (!targets.isArray()) continue;
            for (JsonNode t : targets) {
                String targetId = text(t, "targetId");
                Double meanAz = meanAzimuthFromSeries(t.path("azimuthSeries"));
                if (meanAz == null) {
                    meanAz = meanAzimuthFromSeries(t.path("rawAzimuthSeries"));
                }
                if (meanAz != null) {
                    azByKey.put(networkId + "|" + targetId, meanAz);
                }
            }
        }
        for (StreamTrackLabel l : labels) {
            if (l.getMeanAzimuthDeg() != null) continue;
            Double az = azByKey.get(l.getNetworkId() + "|" + l.getTargetId());
            if (az != null) {
                l.setMeanAzimuthDeg(az);
            }
        }
    }

    private static String targetTypeLabelOf(String type) {
        if (type == null || type.isEmpty()) return "未知";
        if ("GROUND".equals(type)) return "地面站";
        if ("AWACS".equals(type)) return "预警机";
        if ("AIR".equals(type)) return "飞机";
        return type;
    }

    private static Long parseDetectTimeMs(JsonNode isoOrNull, JsonNode msOrNull) {
        if (msOrNull != null && msOrNull.isNumber()) {
            return msOrNull.asLong();
        }
        if (isoOrNull != null && isoOrNull.isTextual()) {
            try {
                return Instant.parse(isoOrNull.asText()).toEpochMilli();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double meanAzimuthFromSeries(JsonNode series) {
        if (series == null || !series.isArray() || series.size() == 0) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (JsonNode p : series) {
            double v;
            if (p.isArray() && p.size() >= 2) {
                v = p.get(1).asDouble(Double.NaN);
            } else if (p.has("v")) {
                v = p.path("v").asDouble(Double.NaN);
            } else if (p.has("b")) {
                v = p.path("b").asDouble(Double.NaN);
            } else {
                continue;
            }
            if (Double.isFinite(v)) {
                sum += v;
                n++;
            }
        }
        return n > 0 ? Double.valueOf(sum / n) : null;
    }

    private static boolean isPolling(String sceneType) {
        return "MULTI_DEVICE_POLLING".equals(sceneType) || "POLLING_MULTI_DEVICE".equals(sceneType);
    }

    private static Long parseTimeMs(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.asLong();
        if (node.isTextual()) {
            try {
                return Instant.parse(node.asText()).toEpochMilli();
            } catch (Exception ignored) {
                return null;
            }
        }
        // Jackson 默认可能序列化为 [sec, nanos]
        if (node.isArray() && node.size() >= 1) {
            long sec = node.get(0).asLong();
            int nanos = node.size() > 1 ? node.get(1).asInt(0) : 0;
            return sec * 1000L + nanos / 1_000_000L;
        }
        return null;
    }

    private static Double parseDoubleOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isNumber()) return node.asDouble();
        if (node.isTextual()) {
            try {
                return Double.valueOf(node.asText().trim());
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return "";
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
