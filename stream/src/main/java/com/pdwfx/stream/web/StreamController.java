package com.pdwfx.stream.web;

import com.pdwfx.stream.analyze.StreamBatchAnalyzer;
import com.pdwfx.stream.batch.StreamBatchFileWriter;
import com.pdwfx.stream.batch.StreamBatchQueue;
import com.pdwfx.stream.config.StreamProperties;
import com.pdwfx.stream.k187.K187B108Parser;
import com.pdwfx.stream.model.StreamBatchResult;
import com.pdwfx.stream.store.StreamResultStore;
import com.pdwfx.stream.tcp.TcpPdwServer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stream")
public class StreamController {

    private final StreamProperties properties;
    private final TcpPdwServer tcpServer;
    private final StreamBatchFileWriter batchWriter;
    private final StreamBatchQueue queue;
    private final StreamBatchAnalyzer analyzer;
    private final StreamResultStore resultStore;

    public StreamController(StreamProperties properties,
                            TcpPdwServer tcpServer,
                            StreamBatchFileWriter batchWriter,
                            StreamBatchQueue queue,
                            StreamBatchAnalyzer analyzer,
                            StreamResultStore resultStore) {
        this.properties = properties;
        this.tcpServer = tcpServer;
        this.batchWriter = batchWriter;
        this.queue = queue;
        this.analyzer = analyzer;
        this.resultStore = resultStore;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new HashMap<>();
        m.put("tcpRunning", tcpServer.isRunning());
        m.put("tcpPort", tcpServer.getListenPort());
        m.put("frameMode", "Head3/0x7E8118E7");
        m.put("pipeline", "scene-filter → process-scene → annotation");
        m.put("bytesIn", tcpServer.getBytesIn());
        m.put("packetsIn", tcpServer.getPacketsIn());
        Map<String, Object> frame = new HashMap<>();
        frame.put("syncLoss", tcpServer.getSyncLoss());
        frame.put("magicHits", tcpServer.getMagicHits());
        frame.put("badLengthSkips", tcpServer.getBadLengthSkips());
        frame.put("lastSeenPacketLen", tcpServer.getLastSeenPacketLen());
        frame.put("pendingSize", tcpServer.getPendingSize());
        frame.put("firstChunkHex", tcpServer.getFirstChunkHex());
        frame.put("recentChunkHex", tcpServer.getRecentChunkHex());
        m.put("frame", frame);
        K187B108Parser.ParseStats ps = tcpServer.getParseStats();
        Map<String, Object> parse = new HashMap<>();
        parse.put("packets", ps.packets);
        parse.put("b108Packets", ps.b108Packets);
        parse.put("records", ps.records);
        parse.put("skippedDoa", ps.skippedDoa);
        parse.put("skippedOtherType", ps.skippedOtherType);
        parse.put("skippedBadMagic", ps.skippedBadMagic);
        parse.put("skippedBadLength", ps.skippedBadLength);
        parse.put("head3PackHeadMismatch", ps.head3PackHeadMismatch);
        m.put("parse", parse);
        K187B108Parser.Head3Snapshot last = tcpServer.getLastHead3Snapshot();
        if (last != null) {
            m.put("lastHead3", last.toString());
        }
        m.put("writtenRows", batchWriter.getWrittenRows());
        m.put("skippedDupRows", batchWriter.getSkippedDupRows());
        m.put("dedupEnabled", properties.getBatch().isDedupEnabled());
        m.put("sealedBatches", batchWriter.getSealedCount());
        m.put("queueSize", queue.size());
        m.put("queuePaused", queue.isPaused());
        m.put("maxQueueFiles", queue.getMaxQueueFiles());
        m.put("analyzingFile", analyzer.getCurrentFile());
        m.put("analyzingPhase", analyzer.getCurrentPhase());
        m.put("batchDir", batchWriter.getRoot() != null ? batchWriter.getRoot().toString() : properties.getBatch().getDir());
        m.put("analyzeBaseUrl", properties.getAnalyze().getBaseUrl());
        m.put("batchDurationMinutes", properties.getBatch().getDurationMinutes());
        m.put("preloadAll", properties.getAnalyze().isPreloadAll());
        Map<String, Object> scene = new HashMap<>();
        scene.put("fullSpanWindow", properties.getAnalyze().getScene().isFullSpanWindow());
        scene.put("windowSeconds", properties.getAnalyze().getScene().getWindowSeconds());
        scene.put("topKTrackScenes", properties.getAnalyze().getScene().getTopKTrackScenes());
        scene.put("topKPollingScenes", properties.getAnalyze().getScene().getTopKPollingScenes());
        m.put("scene", scene);
        return m;
    }

    @GetMapping("/batches")
    public List<StreamBatchResult> batches() {
        return resultStore.listRecent();
    }

    @GetMapping("/batches/{id}")
    public ResponseEntity<StreamBatchResult> batch(@PathVariable String id) {
        StreamBatchResult r = resultStore.get(id);
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(r);
    }

    /** 读取批级标注 JSON（显示软件 / 联调）。 */
    @GetMapping(value = "/batches/{id}/annotation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> annotation(@PathVariable String id) {
        StreamBatchResult r = resultStore.get(id);
        Path path = null;
        if (r != null && r.getAnnotationPath() != null && !r.getAnnotationPath().isEmpty()) {
            path = Paths.get(r.getAnnotationPath());
        }
        if (path == null || !Files.isRegularFile(path)) {
            path = resolveAnnotationDir().resolve(id + ".json");
        }
        if (!Files.isRegularFile(path)) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return ResponseEntity.status(500).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Path resolveAnnotationDir() {
        String configured = properties.getAnalyze().getAnnotation().getDir();
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return Paths.get(properties.getBatch().getDir()).toAbsolutePath().normalize().resolve("annotations");
    }
}
