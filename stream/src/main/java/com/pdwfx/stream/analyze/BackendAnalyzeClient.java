package com.pdwfx.stream.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdwfx.stream.config.StreamProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;

/**
 * 调用原有 backend 的分析接口（跳过场景筛选）。
 */
@Component
public class BackendAnalyzeClient {

    private static final Logger log = LoggerFactory.getLogger(BackendAnalyzeClient.class);

    private final StreamProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BackendAnalyzeClient(StreamProperties properties) {
        this.properties = properties;
    }

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
        try {
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("解析分析响应失败: " + e.getMessage(), e);
        }
    }

    private static String trimSlash(String s) {
        if (s == null || s.isEmpty()) return "http://localhost:18080";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
