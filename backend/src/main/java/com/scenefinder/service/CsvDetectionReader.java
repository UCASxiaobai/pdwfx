package com.scenefinder.service;

import com.pdwfx.signal.util.NSignalTimeColumns;
import com.scenefinder.model.DetectionPoint;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.io.BufferedReader;
import java.io.IOException;
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
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 读取方位检测 CSV（单文件或目录下全部 .csv），解析为 {@link DetectionPoint} 列表并合并。
 * <p>
 * <b>必需列</b>（列名大小写敏感）：{@code pl}、{@code xhfw}、{@code zcsj}。
 * 可选 {@code nSingnalTime}、{@code nSignalStartTime}、{@code nSignalTime} 驻留列；
 * 场景筛选仅保留 {@link NSignalTimeColumns#isValidSceneInput(CSVRecord)} 为真的行。
 * 目录模式下按文件名排序依次加载，结果按时间升序合并。
 * </p>
 */
@Service
public class CsvDetectionReader {

    private static final DateTimeFormatter FLEX_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd['T'][' ']HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();

    /**
     * 读取单个 CSV 或目录下所有 {@code .csv} 文件，做频率预过滤后按时间合并。
     *
     * @param inputPath  CSV 文件路径，或包含多个 CSV 的目录（仅顶层，不递归子目录）
     * @param freqMin    保留 frequency &gt;= freqMin（MHz）
     * @param freqMax    保留 frequency &lt;= freqMax（MHz）
     */
    public List<DetectionPoint> read(Path inputPath, double freqMin, double freqMax) throws IOException {
        List<Path> csvFiles = resolveCsvFiles(inputPath);
        if (csvFiles.isEmpty()) {
            throw new IllegalArgumentException("No .csv files found at: " + inputPath);
        }

        List<DetectionPoint> points = new ArrayList<>();
        for (Path csvFile : csvFiles) {
            points.addAll(readSingleFile(csvFile, freqMin, freqMax));
        }
        points.sort(Comparator.comparing(DetectionPoint::getTime));
        return points;
    }

    private List<Path> resolveCsvFiles(Path inputPath) throws IOException {
        Path normalized = inputPath.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            throw new IllegalArgumentException("Path not found: " + normalized);
        }
        if (Files.isRegularFile(normalized)) {
            String name = normalized.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".csv")) {
                throw new IllegalArgumentException("Not a CSV file: " + normalized);
            }
            return Arrays.asList(normalized);
        }
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException("Not a file or directory: " + normalized);
        }

        try (Stream<Path> entries = Files.list(normalized)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    private List<DetectionPoint> readSingleFile(Path csvPath, double freqMin, double freqMax) throws IOException {
        String sourceFile = csvPath.toAbsolutePath().normalize().toString();
        List<DetectionPoint> points = new ArrayList<>();

        try (BufferedReader reader = openUtf8WithoutBom(csvPath);
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
                Double bearing = parseDouble(record, "xhfw");
                String timeText = record.isMapped("zcsj") ? record.get("zcsj") : null;

                if (frequency == null || bearing == null || timeText == null || timeText.trim().isEmpty()) {
                    continue;
                }
                if (frequency < freqMin || frequency > freqMax) {
                    continue;
                }
                if (!NSignalTimeColumns.isValidSceneInput(record)) {
                    continue;
                }

                Instant time = parseTime(timeText.trim());
                points.add(new DetectionPoint(
                        sourceFile,
                        row,
                        time,
                        bearing,
                        frequency,
                        record.toString()));
            }
        }
        return points;
    }

    private Instant parseTime(String text) {
        LocalDateTime ldt = LocalDateTime.parse(text, FLEX_TIME);
        return ldt.atZone(ZoneId.systemDefault()).toInstant();
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
}
