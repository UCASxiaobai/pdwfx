package com.scenefinder.service;

import com.scenefinder.model.HopBatch;
import com.scenefinder.model.HopBatchGrouping;
import com.scenefinder.model.TrackObservation;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 换频编批索引：{@code hop_batches.csv} 的读写，以及源行号/采样键 → 批号解析。
 */
public final class HopBatchIndex {

    public static final String FILE_NAME = "hop_batches.csv";

    private final Map<Long, String> byRowIndex;
    private final Map<String, String> bySampleKey;
    private final Map<String, String> labels;
    private final boolean present;

    private HopBatchIndex(
            Map<Long, String> byRowIndex,
            Map<String, String> bySampleKey,
            Map<String, String> labels,
            boolean present
    ) {
        this.byRowIndex = byRowIndex;
        this.bySampleKey = bySampleKey;
        this.labels = labels;
        this.present = present;
    }

    public static HopBatchIndex empty() {
        return new HopBatchIndex(
                Collections.<Long, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                false);
    }

    public boolean isPresent() {
        return present;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public String resolve(Long sourceRowIndex, long timeMs, double freqMhz, double bearingDeg) {
        if (sourceRowIndex != null) {
            String byRow = byRowIndex.get(sourceRowIndex);
            if (byRow != null) {
                return byRow;
            }
        }
        if (timeMs > 0) {
            return bySampleKey.get(sampleKey(timeMs, freqMhz, bearingDeg));
        }
        return null;
    }

    public static String sampleKey(long timeMs, double freqMhz, double bearingDeg) {
        return timeMs + "|" + Math.round(freqMhz * 1000.0) + "|" + Math.round(bearingDeg * 10.0);
    }

    public static HopBatchIndex loadQuietly(String outputDir) {
        if (outputDir == null) {
            return empty();
        }
        String trimmed = outputDir.trim();
        if (trimmed.isEmpty()) {
            return empty();
        }
        Path file = Paths.get(trimmed).resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return empty();
        }
        try {
            return load(file);
        } catch (IOException e) {
            return empty();
        }
    }

    public static HopBatchIndex load(Path csvPath) throws IOException {
        Map<Long, String> byRow = new HashMap<Long, String>();
        Map<String, String> byKey = new HashMap<String, String>();
        Map<String, String> labelMap = new LinkedHashMap<String, String>();
        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord record : parser) {
                String batchId = read(record, "hop_batch_id");
                if (batchId == null) {
                    continue;
                }
                String label = read(record, "label");
                if (label != null) {
                    labelMap.put(batchId, label);
                }
                String rowText = read(record, "source_row_index");
                if (rowText != null) {
                    try {
                        byRow.put(Long.parseLong(rowText), batchId);
                    } catch (NumberFormatException ignored) {
                        // skip bad row index
                    }
                }
                Long timeMs = readLong(record, "time_ms");
                Double freq = readDouble(record, "freq_mhz");
                Double bearing = readDouble(record, "bearing_deg");
                if (timeMs != null && freq != null && bearing != null) {
                    byKey.put(sampleKey(timeMs.longValue(), freq.doubleValue(), bearing.doubleValue()), batchId);
                }
            }
        }
        boolean present = !byRow.isEmpty() || !byKey.isEmpty();
        return new HopBatchIndex(byRow, byKey, labelMap, present);
    }

    public static String writeCsv(Path outputDir, HopBatchGrouping grouping) throws IOException {
        if (outputDir == null || grouping == null || grouping.isEmpty()) {
            return null;
        }
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve(FILE_NAME);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader(
                             "source_row_index",
                             "hop_batch_id",
                             "seed_track_id",
                             "label",
                             "time_ms",
                             "freq_mhz",
                             "bearing_deg")
                     .build())) {
            for (HopBatch batch : grouping.getBatches()) {
                if (batch.getObservations() == null) {
                    continue;
                }
                for (TrackObservation obs : batch.getObservations()) {
                    if (obs == null || obs.getTime() == null) {
                        continue;
                    }
                    printer.printRecord(
                            Long.valueOf(obs.getRowIndex()),
                            batch.getBatchId(),
                            Integer.valueOf(batch.getSeedTrackId()),
                            batch.getLabel(),
                            Long.valueOf(obs.getTime().toEpochMilli()),
                            Double.valueOf(round3(obs.getFrequencyMhz())),
                            Double.valueOf(round2(obs.getBearingDeg()))
                    );
                }
            }
        }
        return file.toString();
    }

    private static String read(CSVRecord record, String name) {
        if (record == null || !record.isMapped(name)) {
            return null;
        }
        String v = record.get(name);
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static Long readLong(CSVRecord record, String name) {
        String v = read(record, name);
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double readDouble(CSVRecord record, String name) {
        String v = read(record, name);
        if (v == null) {
            return null;
        }
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
