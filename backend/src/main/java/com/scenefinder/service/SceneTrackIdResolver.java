package com.scenefinder.service;

import com.scenefinder.model.SceneSummaryEntry;
import com.scenefinder.model.SceneType;
import com.scenefinder.model.SourceRowRef;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 为场景转发 CSV 解析源行对应的场景建轨 {@code track_id}。
 * <p>
 * 优先读取场景导出 {@code *_track_detections.csv}，其次 {@code track_rows.csv} /
 * {@code track_detections.csv}（按场景 {@code track_ids} 过滤）。
 */
@Service
public class SceneTrackIdResolver {

    public Map<SourceRowRef, Integer> resolve(
            Path sourceCsv,
            Path outputDir,
            SceneSummaryEntry scene
    ) throws IOException {
        if (scene == null || outputDir == null) {
            return Collections.emptyMap();
        }

        Map<SourceRowRef, Integer> fromSceneExport =
                loadFromSceneDetectionExports(outputDir, scene.getRank(), sourceCsv);
        if (!fromSceneExport.isEmpty()) {
            return fromSceneExport;
        }

        if (scene.getSceneType() == SceneType.TRACK_CONTINUOUS && !scene.getTrackIds().isEmpty()) {
            Map<SourceRowRef, Integer> fromTrackRows =
                    loadFromTrackMapping(outputDir.resolve("track_rows.csv"), scene.getTrackIds(), sourceCsv);
            if (!fromTrackRows.isEmpty()) {
                return fromTrackRows;
            }
            Map<SourceRowRef, Integer> fromDetections =
                    loadFromTrackMapping(outputDir.resolve("track_detections.csv"), scene.getTrackIds(), sourceCsv);
            if (!fromDetections.isEmpty()) {
                return fromDetections;
            }
        }

        if (scene.getSceneType() == SceneType.MULTI_DEVICE_POLLING) {
            Map<SourceRowRef, Integer> fromPolling =
                    loadFromScenePollingExports(outputDir, scene.getRank(), sourceCsv);
            if (!fromPolling.isEmpty()) {
                return fromPolling;
            }
        }

        return Collections.emptyMap();
    }

    private Map<SourceRowRef, Integer> loadFromScenePollingExports(
            Path outputDir,
            int rank,
            Path sourceInput
    ) throws IOException {
        String prefix = "scene_rank" + rank + "_";
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> matches = files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith("_polling_detections.csv");
                    })
                    .collect(Collectors.toList());
            if (matches.isEmpty()) {
                return Collections.emptyMap();
            }
            matches.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException ex) {
                    return b.getFileName().toString().compareTo(a.getFileName().toString());
                }
            });
            Map<SourceRowRef, Integer> all = loadTrackIdMapFromCsv(matches.get(0), sourceInput, null);
            Map<SourceRowRef, Integer> lanes = new LinkedHashMap<>();
            for (Map.Entry<SourceRowRef, Integer> e : all.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0) {
                    lanes.put(e.getKey(), e.getValue());
                }
            }
            return lanes;
        }
    }

    private Map<SourceRowRef, Integer> loadFromSceneDetectionExports(
            Path outputDir,
            int rank,
            Path sourceInput
    ) throws IOException {
        String prefix = "scene_rank" + rank + "_";
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> matches = files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith("_track_detections.csv");
                    })
                    .collect(Collectors.toList());
            if (matches.isEmpty()) {
                return Collections.emptyMap();
            }
            matches.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException ex) {
                    return b.getFileName().toString().compareTo(a.getFileName().toString());
                }
            });
            return loadTrackIdMapFromCsv(matches.get(0), sourceInput, null);
        }
    }

    private Map<SourceRowRef, Integer> loadFromTrackMapping(
            Path mappingCsv,
            List<Integer> trackIds,
            Path sourceInput
    ) throws IOException {
        if (!Files.isRegularFile(mappingCsv) || trackIds == null || trackIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return loadTrackIdMapFromCsv(mappingCsv, sourceInput, new HashSet<>(trackIds));
    }

    private Map<SourceRowRef, Integer> loadTrackIdMapFromCsv(
            Path csvPath,
            Path sourceInput,
            Set<Integer> wantedTrackIds
    ) throws IOException {
        String defaultFile = defaultSourceFile(sourceInput);
        Map<SourceRowRef, Integer> map = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            if (!parser.getHeaderMap().containsKey("track_id")
                    || !parser.getHeaderMap().containsKey("source_row_index")) {
                return Collections.emptyMap();
            }
            boolean hasSourceFile = parser.getHeaderMap().containsKey("source_file");
            for (CSVRecord record : parser) {
                int trackId = Integer.parseInt(record.get("track_id").trim());
                if (wantedTrackIds != null && !wantedTrackIds.contains(trackId)) {
                    continue;
                }
                long rowIndex = Long.parseLong(record.get("source_row_index").trim());
                String sourceFile = hasSourceFile && record.isMapped("source_file")
                        ? record.get("source_file").trim()
                        : defaultFile;
                if (sourceFile.isEmpty()) {
                    continue;
                }
                map.put(new SourceRowRef(sourceFile, rowIndex), trackId);
            }
        }
        return map;
    }

    private static String defaultSourceFile(Path sourceInput) {
        if (sourceInput == null) {
            return "";
        }
        Path normalized = sourceInput.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) {
            return normalized.toString();
        }
        return "";
    }
}
