package com.scenefinder.service;

import com.pdwfx.signal.util.NSignalTimeColumns;
import com.scenefinder.model.AwacsOccupancyWindow;
import com.scenefinder.model.FreqRowGroup;
import com.scenefinder.model.SceneOverlapGroup;
import com.scenefinder.model.SceneSummaryEntry;
import com.scenefinder.model.SceneType;
import com.scenefinder.model.SourceRowRef;
import com.scenefinder.util.CsvHeaderUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.nio.file.Paths;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 从源 CSV（单文件或目录）按行号提取场景数据，写出与原始输入相同表头的合并文件。
 */
@Service
public class SceneSourceExportService {

    private final SceneSummaryReader sceneSummaryReader;

    public SceneSourceExportService(SceneSummaryReader sceneSummaryReader) {
        this.sceneSummaryReader = sceneSummaryReader;
    }

    private static final DateTimeFormatter FLEX_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd['T'][' ']HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();

    /**
     * 时间重合组合并：在并集时段 [spanStart, spanEnd] 内，采集各成员场景频段内的原始行。
     */
    public Set<SourceRowRef> resolveMergedRowRefs(
            Path sourceInput,
            Path outputDir,
            SceneOverlapGroup group
    ) throws IOException {
        if (group == null || group.getRanks().isEmpty()) {
            return Collections.emptySet();
        }
        if (group.getRanks().size() == 1) {
            SceneSummaryEntry scene = sceneSummaryReader.requireByRank(outputDir, group.getPrimaryRank());
            return resolveRowIndexes(sourceInput, outputDir, scene);
        }

        Set<SourceRowRef> refs = scanRowRefsForOverlapGroup(sourceInput, group);
        if (!refs.isEmpty()) {
            return refs;
        }
        Set<SourceRowRef> fallback = new LinkedHashSet<>();
        for (int rank : group.getRanks()) {
            SceneSummaryEntry scene = sceneSummaryReader.requireByRank(outputDir, rank);
            fallback.addAll(resolveRowIndexes(sourceInput, outputDir, scene));
        }
        return fallback;
    }

    public Set<SourceRowRef> resolveRowIndexes(
            Path sourceInput,
            Path outputDir,
            SceneSummaryEntry scene
    ) throws IOException {
        Set<SourceRowRef> fromExport = loadRowRefsFromSceneExport(outputDir, scene.getRank(), sourceInput);
        if (!fromExport.isEmpty()) {
            return fromExport;
        }
        if ((scene.getSceneType() == SceneType.TRACK_CONTINUOUS
                || scene.getSceneType() == SceneType.COMMAND_NET)
                && !scene.getTrackIds().isEmpty()) {
            Set<SourceRowRef> fromTrackRows = loadRowRefsFromTrackRows(outputDir, scene.getTrackIds(), sourceInput);
            if (!fromTrackRows.isEmpty()) {
                return fromTrackRows;
            }
        }
        return scanRowRefsByWindow(sourceInput, scene);
    }

    /**
     * 扫描全批 CSV：命中任一预警机占用窗的行（含未建轨点）。
     */
    public Set<SourceRowRef> scanRowRefsByOccupancyWindows(
            Path sourceInput,
            List<AwacsOccupancyWindow> windows
    ) throws IOException {
        Set<SourceRowRef> refs = new LinkedHashSet<>();
        if (windows == null || windows.isEmpty()) {
            return refs;
        }
        for (Path csvFile : listCsvFiles(sourceInput)) {
            String sourceFile = csvFile.toAbsolutePath().normalize().toString();
            try (BufferedReader reader = openUtf8WithoutBom(csvFile);
                 CSVParser parser = CSVFormat.DEFAULT.builder()
                         .setHeader()
                         .setSkipHeaderRecord(true)
                         .setIgnoreEmptyLines(true)
                         .setTrim(true)
                         .build()
                         .parse(reader)) {
                long row = 1;
                for (CSVRecord record : parser) {
                    row++;
                    Double frequency = parseDouble(record, "pl");
                    String timeText = record.isMapped("zcsj") ? record.get("zcsj") : null;
                    if (frequency == null || timeText == null || timeText.trim().isEmpty()) {
                        continue;
                    }
                    if (!NSignalTimeColumns.isValidSceneInput(record)) {
                        continue;
                    }
                    Instant time = parseTime(timeText.trim());
                    if (AwacsCommandNetService.matchesAny(frequency.doubleValue(), time.toEpochMilli(), windows)) {
                        refs.add(new SourceRowRef(sourceFile, row));
                    }
                }
            }
        }
        return refs;
    }

    /**
     * 将多文件中的指定行合并写出为原始表头 CSV（单文件输出，供 backend 解析）。
     */
    /**
     * 将场景行引用按通信频点（容差内）分组，用于「时间窗 + 单频」独立分析。
     */
    public List<FreqRowGroup> groupRowRefsByFreq(
            Path sourceInput,
            Set<SourceRowRef> rowRefs,
            double freqToleranceMhz
    ) throws IOException {
        if (rowRefs == null || rowRefs.isEmpty()) {
            return Collections.emptyList();
        }
        Map<SourceRowRef, Double> freqByRef = loadFrequenciesForRefs(sourceInput, rowRefs);
        List<Map.Entry<SourceRowRef, Double>> sorted = new ArrayList<>(freqByRef.entrySet());
        sorted.sort(Comparator.comparingDouble(Map.Entry::getValue));

        List<FreqRowGroup> groups = new ArrayList<>();
        Set<SourceRowRef> currentRefs = new LinkedHashSet<>();
        double anchor = Double.NaN;
        double sum = 0;
        int count = 0;

        for (Map.Entry<SourceRowRef, Double> entry : sorted) {
            double freq = entry.getValue();
            if (currentRefs.isEmpty()) {
                currentRefs.add(entry.getKey());
                anchor = freq;
                sum = freq;
                count = 1;
                continue;
            }
            if (Math.abs(freq - anchor) <= freqToleranceMhz) {
                currentRefs.add(entry.getKey());
                sum += freq;
                count++;
            } else {
                groups.add(new FreqRowGroup(sum / count, new LinkedHashSet<>(currentRefs)));
                currentRefs = new LinkedHashSet<>();
                currentRefs.add(entry.getKey());
                anchor = freq;
                sum = freq;
                count = 1;
            }
        }
        if (!currentRefs.isEmpty()) {
            groups.add(new FreqRowGroup(sum / count, currentRefs));
        }
        return groups;
    }

    private Map<SourceRowRef, Double> loadFrequenciesForRefs(
            Path sourceInput,
            Set<SourceRowRef> rowRefs
    ) throws IOException {
        Map<String, Set<Long>> rowsByFile = new LinkedHashMap<>();
        for (SourceRowRef ref : rowRefs) {
            rowsByFile.computeIfAbsent(ref.getSourceFile(), ignored -> new HashSet<>())
                    .add(ref.getRowIndex());
        }
        Map<SourceRowRef, Double> out = new HashMap<>();
        for (Map.Entry<String, Set<Long>> fileEntry : rowsByFile.entrySet()) {
            Path sourceCsv = Paths.get(fileEntry.getKey());
            Set<Long> wantedRows = fileEntry.getValue();
            String sourceFile = sourceCsv.toAbsolutePath().normalize().toString();
            try (BufferedReader reader = openUtf8WithoutBom(sourceCsv);
                 CSVParser parser = CSVFormat.DEFAULT.builder()
                         .setHeader()
                         .setSkipHeaderRecord(true)
                         .setIgnoreEmptyLines(true)
                         .setTrim(true)
                         .build()
                         .parse(reader)) {
                long row = 1;
                for (CSVRecord record : parser) {
                    row++;
                    if (!wantedRows.contains(row)) {
                        continue;
                    }
                    Double frequency = parseDouble(record, "pl");
                    if (frequency != null) {
                        out.put(new SourceRowRef(sourceFile, row), frequency);
                    }
                }
            }
        }
        return out;
    }

    public int exportOriginalRows(Path sourceInput, Set<SourceRowRef> rowRefs, Path outCsv) throws IOException {
        return exportOriginalRows(sourceInput, rowRefs, outCsv, Collections.emptyMap());
    }

    public int exportOriginalRows(
            Path sourceInput,
            Set<SourceRowRef> rowRefs,
            Path outCsv,
            Map<SourceRowRef, Integer> trackIdByRow
    ) throws IOException {
        if (rowRefs.isEmpty()) {
            throw new IllegalArgumentException("No source rows selected for export");
        }

        Map<String, Set<Long>> rowsByFile = new LinkedHashMap<>();
        for (SourceRowRef ref : rowRefs) {
            rowsByFile.computeIfAbsent(ref.getSourceFile(), ignored -> new HashSet<>()).add(ref.getRowIndex());
        }

        Path firstSource = Paths.get(rowsByFile.keySet().iterator().next());
        List<String> header;
        try (BufferedReader reader = openUtf8WithoutBom(firstSource);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            header = CsvHeaderUtils.normalizeHeaderRow(new ArrayList<>(parser.getHeaderNames()));
        }

        boolean includeTrackId = trackIdByRow != null && !trackIdByRow.isEmpty();
        List<String> outHeader = includeTrackId
                ? concatTrackIdHeader(header)
                : header;

        int written = 0;
        try (Writer writer = Files.newBufferedWriter(outCsv, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader(outHeader.toArray(new String[0]))
                     .build())) {

            for (Map.Entry<String, Set<Long>> fileEntry : rowsByFile.entrySet()) {
                Path sourceCsv = Paths.get(fileEntry.getKey());
                Set<Long> wantedRows = fileEntry.getValue();
                String sourceFile = sourceCsv.toAbsolutePath().normalize().toString();

                try (BufferedReader reader = openUtf8WithoutBom(sourceCsv);
                     CSVParser parser = CSVFormat.DEFAULT.builder()
                             .setHeader()
                             .setSkipHeaderRecord(true)
                             .setIgnoreEmptyLines(true)
                             .setTrim(true)
                             .build()
                             .parse(reader)) {

                    long row = 1;
                    for (CSVRecord record : parser) {
                        row++;
                        if (!wantedRows.contains(row)) {
                            continue;
                        }
                        List<Object> line = new ArrayList<>(CsvHeaderUtils.mapRecordToRow(record, header));
                        if (includeTrackId) {
                            Integer trackId = trackIdByRow.get(new SourceRowRef(sourceFile, row));
                            line.add(0, trackId != null ? trackId : 0);
                        }
                        printer.printRecord(line);
                        written++;
                    }
                }
            }
        }
        return written;
    }

    private static List<String> concatTrackIdHeader(List<String> sourceHeader) {
        List<String> out = new ArrayList<>(sourceHeader.size() + 1);
        out.add("track_id");
        out.addAll(sourceHeader);
        return out;
    }

    private Set<SourceRowRef> loadRowRefsFromSceneExport(
            Path outputDir,
            int rank,
            Path sourceInput
    ) throws IOException {
        String prefix = "scene_rank" + rank + "_";
        try (Stream<Path> files = Files.list(outputDir)) {
            List<Path> matches = files
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(prefix) && name.endsWith("_detections.csv");
                    })
                    .collect(Collectors.toList());
            if (matches.isEmpty()) {
                return Collections.emptySet();
            }
            matches.sort((a, b) -> {
                try {
                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                } catch (IOException ex) {
                    return b.getFileName().toString().compareTo(a.getFileName().toString());
                }
            });
            return readSourceRowRefs(matches.get(0), sourceInput);
        }
    }

    private Set<SourceRowRef> readSourceRowRefs(Path sceneExportCsv, Path sourceInput) throws IOException {
        String defaultFile = defaultSourceFile(sourceInput);
        Set<SourceRowRef> refs = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(sceneExportCsv, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            boolean hasSourceFile = parser.getHeaderMap().containsKey("source_file");
            if (!parser.getHeaderMap().containsKey("source_row_index")) {
                return Collections.emptySet();
            }
            for (CSVRecord record : parser) {
                String rowText = record.get("source_row_index");
                if (rowText == null || rowText.trim().isEmpty()) {
                    continue;
                }
                long rowIndex = Long.parseLong(rowText.trim());
                String sourceFile = hasSourceFile && record.isMapped("source_file")
                        ? record.get("source_file").trim()
                        : defaultFile;
                if (!sourceFile.trim().isEmpty()) {
                    refs.add(new SourceRowRef(sourceFile, rowIndex));
                }
            }
        }
        return refs;
    }

    private Set<SourceRowRef> loadRowRefsFromTrackRows(
            Path outputDir,
            List<Integer> trackIds,
            Path sourceInput
    ) throws IOException {
        Path trackRows = outputDir.resolve("track_rows.csv");
        if (!Files.exists(trackRows)) {
            return Collections.emptySet();
        }
        String defaultFile = defaultSourceFile(sourceInput);
        Set<Integer> wanted = new HashSet<>(trackIds);
        Set<SourceRowRef> refs = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(trackRows, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            boolean hasSourceFile = parser.getHeaderMap().containsKey("source_file");
            for (CSVRecord record : parser) {
                int trackId = Integer.parseInt(record.get("track_id"));
                if (!wanted.contains(trackId)) {
                    continue;
                }
                long rowIndex = Long.parseLong(record.get("source_row_index"));
                String sourceFile = hasSourceFile && record.isMapped("source_file")
                        ? record.get("source_file").trim()
                        : defaultFile;
                refs.add(new SourceRowRef(sourceFile, rowIndex));
            }
        }
        return refs;
    }

    private Set<SourceRowRef> scanRowRefsForOverlapGroup(Path sourceInput, SceneOverlapGroup group) throws IOException {
        Instant spanStart = group.getSpanStart();
        Instant spanEnd = group.getSpanEnd();
        List<SceneOverlapGroup.SceneMemberBand> bands = group.getMemberBands();
        Set<SourceRowRef> refs = new HashSet<>();
        for (Path csvFile : listCsvFiles(sourceInput)) {
            String sourceFile = csvFile.toAbsolutePath().normalize().toString();
            try (BufferedReader reader = openUtf8WithoutBom(csvFile);
                 CSVParser parser = CSVFormat.DEFAULT.builder()
                         .setHeader()
                         .setSkipHeaderRecord(true)
                         .setIgnoreEmptyLines(true)
                         .setTrim(true)
                         .build()
                         .parse(reader)) {

                long row = 1;
                for (CSVRecord record : parser) {
                    row++;
                    Double frequency = parseDouble(record, "pl");
                    String timeText = record.isMapped("zcsj") ? record.get("zcsj") : null;
                    if (frequency == null || timeText == null || timeText.trim().isEmpty()) {
                        continue;
                    }
                    Instant time = parseTime(timeText.trim());
                    if (time.isBefore(spanStart) || time.isAfter(spanEnd)) {
                        continue;
                    }
                    boolean inMemberBand = false;
                    for (SceneOverlapGroup.SceneMemberBand band : bands) {
                        if (frequency >= band.getFreqMinMhz() - 0.01
                                && frequency <= band.getFreqMaxMhz() + 0.01) {
                            inMemberBand = true;
                            break;
                        }
                    }
                    if (inMemberBand && NSignalTimeColumns.isValidSceneInput(record)) {
                        refs.add(new SourceRowRef(sourceFile, row));
                    }
                }
            }
        }
        return refs;
    }

    private Set<SourceRowRef> scanRowRefsByWindow(Path sourceInput, SceneSummaryEntry scene) throws IOException {
        Set<SourceRowRef> refs = new HashSet<>();
        for (Path csvFile : listCsvFiles(sourceInput)) {
            String sourceFile = csvFile.toAbsolutePath().normalize().toString();
            try (BufferedReader reader = openUtf8WithoutBom(csvFile);
                 CSVParser parser = CSVFormat.DEFAULT.builder()
                         .setHeader()
                         .setSkipHeaderRecord(true)
                         .setIgnoreEmptyLines(true)
                         .setTrim(true)
                         .build()
                         .parse(reader)) {

                long row = 1;
                for (CSVRecord record : parser) {
                    row++;
                    Double frequency = parseDouble(record, "pl");
                    String timeText = record.isMapped("zcsj") ? record.get("zcsj") : null;
                    if (frequency == null || timeText == null || timeText.trim().isEmpty()) {
                        continue;
                    }
                    if (frequency < scene.getFreqMinMhz() - 0.01 || frequency > scene.getFreqMaxMhz() + 0.01) {
                        continue;
                    }
                    Instant time = parseTime(timeText.trim());
                    if (time.isBefore(scene.getWindowStart()) || time.isAfter(scene.getWindowEnd())) {
                        continue;
                    }
                    if (!NSignalTimeColumns.isValidSceneInput(record)) {
                        continue;
                    }
                    refs.add(new SourceRowRef(sourceFile, row));
                }
            }
        }
        return refs;
    }

    private static String defaultSourceFile(Path sourceInput) {
        Path normalized = sourceInput.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) {
            return normalized.toString();
        }
        return "";
    }

    private static List<Path> listCsvFiles(Path sourceInput) throws IOException {
        Path normalized = sourceInput.toAbsolutePath().normalize();
        if (Files.isRegularFile(normalized)) {
            return Arrays.asList(normalized);
        }
        try (Stream<Path> entries = Files.list(normalized)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private Instant parseTime(String text) {
        LocalDateTime ldt = LocalDateTime.parse(text, FLEX_TIME);
        return ldt.atZone(ZoneId.systemDefault()).toInstant();
    }

    private Double parseDouble(CSVRecord record, String column) {
        if (!record.isMapped(column)) {
            return null;
        }
        String value = record.get(column);
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private BufferedReader openUtf8WithoutBom(Path csvPath) throws IOException {
        BufferedReader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
        reader.mark(4);
        int first = reader.read();
        if (first != 0xFEFF) {
            reader.reset();
        }
        return reader;
    }
}
