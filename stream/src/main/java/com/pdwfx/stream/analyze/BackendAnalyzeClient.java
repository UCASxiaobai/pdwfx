package com.pdwfx.stream.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdwfx.stream.config.StreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;

/**
 * 调用原 backend：场景筛选（analyze-upload）+ 逐场景信号分析（process-scene）。
 */
@Component
public class BackendAnalyzeClient {

    private static final Logger log = LoggerFactory.getLogger(BackendAnalyzeClient.class);

    private final StreamProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BackendAnalyzeClient(StreamProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30_000);
        // 场景筛选 + 单场景分析可能较久
        factory.setReadTimeout(30 * 60_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 上传封批 CSV 做场景筛选/建轨。
     *
     * @param csv       本地封批文件
     * @param outputDir backend 侧场景产物目录（如 stream-batch-{id}）
     */
    public JsonNode sceneAnalyzeUpload(Path csv, String outputDir) {
        String base = trimSlash(properties.getAnalyze().getBaseUrl());
        String url = base + "/api/scenes/analyze-upload";
        StreamProperties.Scene scene = properties.getAnalyze().getScene();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(csv.toFile()));
        body.add("outputDir", outputDir);
        body.add("freqTolerance", String.valueOf(properties.getAnalyze().getFreqTolerance()));
        body.add("fullSpanWindow", String.valueOf(scene.isFullSpanWindow()));
        // 流式页需要本批全量散点概览
        body.add("enableImportScatter", "true");
        body.add("topKTrackScenes", String.valueOf(scene.getTopKTrackScenes()));
        body.add("topKPollingScenes", String.valueOf(scene.getTopKPollingScenes()));
        // 全段窗模式下 windowSeconds 不参与评分；非全段时仍传配置值
        if (!scene.isFullSpanWindow()) {
            body.add("windowSeconds", String.valueOf(scene.getWindowSeconds()));
            if (scene.getWindowStepSeconds() != null) {
                body.add("windowStepSeconds", String.valueOf(scene.getWindowStepSeconds()));
            }
        }
        if (scene.getFreqMin() != null) {
            body.add("freqMin", String.valueOf(scene.getFreqMin()));
        }
        if (scene.getFreqMax() != null) {
            body.add("freqMax", String.valueOf(scene.getFreqMax()));
        }
        if (scene.getMinTracksInScene() != null) {
            body.add("minTracksInScene", String.valueOf(scene.getMinTracksInScene()));
        }
        if (scene.getTopKScenes() != null) {
            body.add("topKScenes", String.valueOf(scene.getTopKScenes()));
        }

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        log.info("POST {} file={} outputDir={} fullSpanWindow={}",
                url, csv.getFileName(), outputDir, scene.isFullSpanWindow());
        ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
        return readJson(resp.getBody(), "场景筛选");
    }

    /** 单场景：导出 → 信号分析 */
    public JsonNode processScene(String sourceCsvPath, String outputDir, int sceneRank) {
        String base = trimSlash(properties.getAnalyze().getBaseUrl());
        String url = base + "/api/scenes/process-scene";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("sourceCsvPath", sourceCsvPath);
        body.put("outputDir", outputDir);
        body.put("sceneRank", sceneRank);
        body.put("freqTolerance", properties.getAnalyze().getFreqTolerance());
        body.put("preloadAll", properties.getAnalyze().isPreloadAll());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);

        log.info("POST {} rank={} outputDir={} preloadAll={}",
                url, sceneRank, outputDir, properties.getAnalyze().isPreloadAll());
        ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
        return readJson(resp.getBody(), "process-scene");
    }

    /** 预警机指挥网二次：占用窗筛点、重建并替换重叠一次场景 */
    public JsonNode commandNetPass(String sourceCsvPath, String outputDir, java.util.List<String> analysisIds) {
        String base = trimSlash(properties.getAnalyze().getBaseUrl());
        String url = base + "/api/scenes/command-net-pass";

        ObjectNode body = objectMapper.createObjectNode();
        body.put("sourceCsvPath", sourceCsvPath);
        body.put("outputDir", outputDir);
        body.put("freqTolerance", properties.getAnalyze().getFreqTolerance());
        body.put("preloadAll", properties.getAnalyze().isPreloadAll());
        ArrayNode ids = body.putArray("analysisIds");
        if (analysisIds != null) {
            for (String id : analysisIds) {
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body.toString(), headers);
        log.info("POST {} outputDir={} sessions={}", url, outputDir, ids.size());
        ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
        return readJson(resp.getBody(), "command-net-pass");
    }

    /** @deprecated 保留兼容；流式主路径已改为场景流水线 */
    public JsonNode analyzeFile(Path csv) {
        String base = trimSlash(properties.getAnalyze().getBaseUrl());
        String url = base + "/api/signals/analyze"
                + "?freqTolerance=" + properties.getAnalyze().getFreqTolerance()
                + "&preloadAll=" + properties.getAnalyze().isPreloadAll();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new FileSystemResource(csv.toFile()));
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        log.info("POST {} file={}", url, csv.getFileName());
        ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
        return readJson(resp.getBody(), "signals/analyze");
    }

    private JsonNode readJson(String body, String label) {
        try {
            if (body == null || body.isEmpty()) {
                throw new IllegalStateException(label + " 响应为空");
            }
            return objectMapper.readTree(body);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析" + label + "响应失败: " + e.getMessage(), e);
        }
    }

    private static String trimSlash(String s) {
        if (s == null || s.isEmpty()) return "http://localhost:18080";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
