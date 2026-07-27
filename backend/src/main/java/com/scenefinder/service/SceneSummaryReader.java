package com.scenefinder.service;

import com.scenefinder.model.SceneSummaryEntry;
import com.scenefinder.model.SceneType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SceneSummaryReader {

    public List<SceneSummaryEntry> load(Path outputDir) throws IOException {
        Path summary = outputDir.resolve("scene_summary.csv");
        if (!Files.exists(summary)) {
            throw new IllegalArgumentException("scene_summary.csv not found in: " + outputDir);
        }

        List<SceneSummaryEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(summary, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                entries.add(parseRecord(record));
            }
        }
        return entries;
    }

    public SceneSummaryEntry requireByRank(Path outputDir, int rank) throws IOException {
        return load(outputDir).stream()
                .filter(e -> e.getRank() == rank)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Scene rank not found: " + rank));
    }

    private SceneSummaryEntry parseRecord(CSVRecord record) {
        int rank = Integer.parseInt(record.get("rank"));
        SceneType type = SceneType.valueOf(record.get("scene_type"));
        Instant start = Instant.parse(record.get("window_start"));
        Instant end = Instant.parse(record.get("window_end"));
        double freqMin = parseDouble(record, "freq_min_mhz");
        double freqMax = parseDouble(record, "freq_max_mhz");
        List<Integer> trackIds = parseTrackIds(record.get("track_ids"));
        String annotation = record.isMapped("annotation") ? record.get("annotation") : "";
        int distinctDeviceCount = parseInt(record, "distinct_device_count", 0);
        double pollingPeriodSec = parseDouble(record, "polling_period_sec");
        return new SceneSummaryEntry(
                rank, type, start, end, freqMin, freqMax,
                distinctDeviceCount, pollingPeriodSec, trackIds, annotation);
    }

    private static int parseInt(CSVRecord record, String column, int fallback) {
        if (!record.isMapped(column)) {
            return fallback;
        }
        String v = record.get(column);
        if (v == null || v.trim().isEmpty()) {
            return fallback;
        }
        return Integer.parseInt(v.trim());
    }

    private static List<Integer> parseTrackIds(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return Arrays.stream(raw.split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .collect(Collectors.toList());
    }

    private static double parseDouble(CSVRecord record, String column) {
        String v = record.get(column);
        if (v == null || v.trim().isEmpty()) {
            return 0;
        }
        return Double.parseDouble(v);
    }
}
