package com.scenefinder.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pdwfx.signal.api.SignalAnalysisFacade;
import com.pdwfx.signal.model.AnalyzeSessionResponse;
import com.scenefinder.config.PdwfxBackendProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 调用信号分析：合并部署时走进程内 {@link SignalAnalysisFacade}，否则 HTTP multipart。
 */
@Service
public class PdwfxBackendClient {

    private final PdwfxBackendProperties properties;
    private final SignalAnalysisFacade signalAnalysisFacade;
    private final ObjectMapper objectMapper;

    public PdwfxBackendClient(
            PdwfxBackendProperties properties,
            SignalAnalysisFacade signalAnalysisFacade,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.signalAnalysisFacade = signalAnalysisFacade;
        this.objectMapper = objectMapper;
    }

    public String analyze(Path csvFile, String baseUrlOverride, double freqTolerance) {
        return analyze(csvFile, baseUrlOverride, freqTolerance, true);
    }

    public String analyze(
            Path csvFile,
            String baseUrlOverride,
            double freqTolerance,
            boolean mergeMultiFreq
    ) {
        if (useInProcess(baseUrlOverride)) {
            return analyzeInProcess(csvFile, freqTolerance, mergeMultiFreq);
        }
        String url = normalizeBase(baseUrlOverride != null ? baseUrlOverride : properties.getBaseUrl())
                + "/api/signals/analyze";
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart(csvFile));
        body.add("freqTolerance", String.valueOf(freqTolerance));
        return postMultipart(url, body);
    }

    public String analyzeScene(
            Path csvFile,
            String baseUrlOverride,
            double freqTolerance,
            String sceneId,
            String sceneName,
            Long startTimeMs,
            Long endTimeMs
    ) {
        if (useInProcess(baseUrlOverride)) {
            return analyzeInProcess(csvFile, freqTolerance, false);
        }
        String url = normalizeBase(baseUrlOverride != null ? baseUrlOverride : properties.getBaseUrl())
                + "/api/signals/analyze/scene";
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", filePart(csvFile));
        body.add("freqTolerance", String.valueOf(freqTolerance));
        body.add("sceneId", sceneId);
        if (sceneName != null && !sceneName.trim().isEmpty()) {
            body.add("sceneName", sceneName);
        }
        if (startTimeMs != null) {
            body.add("startTimeMs", String.valueOf(startTimeMs));
        }
        if (endTimeMs != null) {
            body.add("endTimeMs", String.valueOf(endTimeMs));
        }
        return postMultipart(url, body);
    }

    private boolean useInProcess(String baseUrlOverride) {
        if (!properties.isInProcess()) {
            return false;
        }
        if (baseUrlOverride == null || baseUrlOverride.trim().isEmpty()) {
            return true;
        }
        if ("in-process".equalsIgnoreCase(baseUrlOverride.trim())) {
            return true;
        }
        String normalized = normalizeBase(baseUrlOverride);
        String configured = normalizeBase(properties.getBaseUrl());
        return normalized.equals(configured) || normalized.contains("localhost:18080");
    }

    private String analyzeInProcess(Path csvFile, double freqTolerance) {
        return analyzeInProcess(csvFile, freqTolerance, true);
    }

    private String analyzeInProcess(Path csvFile, double freqTolerance, boolean mergeMultiFreq) {
        try {
            AnalyzeSessionResponse session =
                    signalAnalysisFacade.analyzeFromPath(csvFile, freqTolerance, mergeMultiFreq);
            return objectMapper.writeValueAsString(session);
        } catch (IOException ex) {
            throw new IllegalStateException("In-process signal analysis failed: " + ex.getMessage(), ex);
        }
    }

    private String postMultipart(String url, MultiValueMap<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            return restTemplate().postForObject(url, entity, String.class);
        } catch (RestClientResponseException ex) {
            throw new IllegalStateException(
                    "pdwfx backend request failed: HTTP " + ex.getRawStatusCode()
                            + " — " + ex.getResponseBodyAsString(),
                    ex);
        }
    }

    private RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int connectMs = (int) Duration.ofSeconds(properties.getConnectTimeoutSeconds()).toMillis();
        int readMs = (int) Duration.ofSeconds(properties.getReadTimeoutSeconds()).toMillis();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }

    private static FileSystemResource filePart(Path csvFile) {
        FileSystemResource resource = new FileSystemResource(csvFile);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Export CSV not found: " + csvFile);
        }
        return resource;
    }

    private static String normalizeBase(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
