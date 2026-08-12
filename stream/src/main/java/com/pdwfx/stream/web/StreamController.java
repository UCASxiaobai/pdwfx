package com.pdwfx.stream.web;

import com.pdwfx.stream.analyze.StreamBatchAnalyzer;
import com.pdwfx.stream.batch.StreamBatchFileWriter;
import com.pdwfx.stream.batch.StreamBatchQueue;
import com.pdwfx.stream.config.StreamProperties;
import com.pdwfx.stream.k187.K187B108Parser;
import com.pdwfx.stream.model.StreamBatchResult;
import com.pdwfx.stream.store.StreamResultStore;
import com.pdwfx.stream.tcp.TcpPdwServer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        m.put("bytesIn", tcpServer.getBytesIn());
        m.put("packetsIn", tcpServer.getPacketsIn());
        K187B108Parser.ParseStats ps = tcpServer.getParseStats();
        Map<String, Object> parse = new HashMap<>();
        parse.put("packets", ps.packets);
        parse.put("b108Packets", ps.b108Packets);
        parse.put("records", ps.records);
        parse.put("skippedDoa", ps.skippedDoa);
        parse.put("skippedOtherType", ps.skippedOtherType);
        parse.put("skippedBadMagic", ps.skippedBadMagic);
        parse.put("skippedBadLength", ps.skippedBadLength);
        m.put("parse", parse);
        m.put("writtenRows", batchWriter.getWrittenRows());
        m.put("sealedBatches", batchWriter.getSealedCount());
        m.put("queueSize", queue.size());
        m.put("queuePaused", queue.isPaused());
        m.put("maxQueueFiles", queue.getMaxQueueFiles());
        m.put("analyzingFile", analyzer.getCurrentFile());
        m.put("batchDir", batchWriter.getRoot() != null ? batchWriter.getRoot().toString() : properties.getBatch().getDir());
        m.put("analyzeBaseUrl", properties.getAnalyze().getBaseUrl());
        m.put("batchDurationMinutes", properties.getBatch().getDurationMinutes());
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
}
