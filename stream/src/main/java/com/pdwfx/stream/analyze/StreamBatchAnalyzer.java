package com.pdwfx.stream.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.pdwfx.stream.batch.StreamBatchQueue;
import com.pdwfx.stream.config.StreamProperties;
import com.pdwfx.stream.model.StreamBatchResult;
import com.pdwfx.stream.model.StreamTrackLabel;
import com.pdwfx.stream.store.StreamResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单 worker 串行消费 inbox 文件，调用原分析服务并生成批标签。
 */
@Component
public class StreamBatchAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(StreamBatchAnalyzer.class);

    private final StreamProperties properties;
    private final StreamBatchQueue queue;
    private final BackendAnalyzeClient analyzeClient;
    private final StreamResultStore resultStore;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> currentFile = new AtomicReference<>("");
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
        log.info("Stream batch analyzer started (workers=1)");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (worker != null) worker.shutdownNow();
    }

    public String getCurrentFile() { return currentFile.get(); }

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
        String batchId = stripExt(inboxFile.getFileName().toString());
        currentFile.set(batchId);
        Path work = processing.resolve(inboxFile.getFileName());
        StreamBatchResult result = new StreamBatchResult();
        result.setStreamBatchId(batchId);
        result.setCsvPath(inboxFile.toString());
        result.setFinishedAt(Instant.now());
        try {
            Files.createDirectories(processing);
            Files.move(inboxFile, work, StandardCopyOption.REPLACE_EXISTING);
            result.setCsvPath(work.toString());
            JsonNode session = analyzeClient.analyzeFile(work);
            result.setAnalysisId(text(session, "analysisId"));
            result.setNetworkCount(session.path("networkCount").asInt(0));
            List<StreamTrackLabel> labels = extractLabels(batchId, session);
            result.setLabels(labels);
            result.setTrackCount(labels.size());
            result.setStatus("DONE");
            Files.move(work, done.resolve(work.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            log.info("Batch {} analyzed: networks={}, tracks={}, analysisId={}",
                    batchId, result.getNetworkCount(), result.getTrackCount(), result.getAnalysisId());
        } catch (Exception e) {
            result.setStatus("FAILED");
            result.setError(e.getMessage());
            log.error("Batch {} failed: {}", batchId, e.getMessage());
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
            resultStore.put(result);
            currentFile.set("");
        }
    }

    private List<StreamTrackLabel> extractLabels(String streamBatchId, JsonNode session) {
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
                label.setNetworkId(networkId);
                label.setTargetId(targetId);
                label.setBatchId(streamBatchId + "-" + networkId + "-" + targetId);
                label.setTargetType(text(t, "targetType"));
                label.setTargetTypeLabel(text(t, "targetTypeLabel"));
                label.setDutyCycle(t.path("avgDutyCycle").asDouble(0));
                label.setTrafficSharePct(t.path("emissionSharePct").asDouble(0));
                label.setChannel(channel);
                label.setChannelLabel(channelLabel);
                label.setFreqMhz(freq);
                label.setDetectCount(t.path("detectCount").asInt(0));
                if (t.hasNonNull("detectStartMs")) label.setDetectStartMs(t.path("detectStartMs").asLong());
                if (t.hasNonNull("detectEndMs")) label.setDetectEndMs(t.path("detectEndMs").asLong());
                labels.add(label);
            }
        }
        return labels;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? "" : v.asText("");
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }
}
