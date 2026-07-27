package com.pdwfx.signal.service;

import com.pdwfx.signal.imports.ImportFormat;
import com.pdwfx.signal.imports.ImportFormatDetector;
import com.pdwfx.signal.imports.ImportFormatException;
import com.pdwfx.signal.model.ExternalTargetFix;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析外源目标定位 CSV（如雷情导入 Lq1139：detectTime + longitude + latitude）。
 */
@Service
public class ExternalTargetLocateImportService {

    private static final DateTimeFormatter FLEX_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd['T'][' ']HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();

    private static final String[] REQUIRED_COLUMNS = {"DETECTTIME", "LONGITUDE", "LATITUDE"};

    public List<ExternalTargetFix> parse(Path path) throws IOException {
        String fileName = path.getFileName().toString();
        ImportFormat format = ImportFormatDetector.detectFromPath(path);
        if (format != ImportFormat.EXTERNAL_TARGET_LOCATE) {
            throw new ImportFormatException(
                    "文件「" + fileName + "」不是外源目标定位格式。"
                            + " 期望列：detectTime、longitude、latitude。"
                            + " 检测到：" + ImportFormatDetector.formatHint(format),
                    format,
                    fileName
            );
        }
        byte[] head = Files.readAllBytes(path);
        int n = Math.min(head.length, 8192);
        String charset = detectCharset(head, n);
        try (BufferedReader reader = Files.newBufferedReader(path, Charset.forName(charset));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            validateHeaders(parser.getHeaderMap(), fileName);
            return parseRecords(parser, path.toAbsolutePath().normalize().toString());
        }
    }

    public List<ExternalTargetFix> parse(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.csv";
        String charset = detectCharset(file);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), Charset.forName(charset)));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            Map<String, Integer> headers = parser.getHeaderMap();
            ImportFormat format = ImportFormatDetector.detect(ImportFormatDetector.toUpperHeaderIndex(headers));
            if (format != ImportFormat.EXTERNAL_TARGET_LOCATE) {
                throw new ImportFormatException(
                        "文件「" + fileName + "」不是外源目标定位格式。"
                                + " 期望列：detectTime、longitude、latitude。"
                                + " 检测到：" + ImportFormatDetector.formatHint(format),
                        format,
                        fileName
                );
            }
            validateHeaders(headers, fileName);
            return parseRecords(parser, fileName);
        }
    }

    /**
     * 地图展示用均匀抽样，避免一次绘制过多点。
     */
    public static List<ExternalTargetFix> sampleForMap(List<ExternalTargetFix> all, int maxPoints) {
        if (all == null || all.isEmpty()) {
            return Collections.emptyList();
        }
        if (all.size() <= maxPoints) {
            return all;
        }
        List<ExternalTargetFix> out = new ArrayList<>(maxPoints);
        double step = (double) all.size() / maxPoints;
        for (int i = 0; i < maxPoints; i++) {
            out.add(all.get((int) Math.floor(i * step)));
        }
        return out;
    }

    private static void validateHeaders(Map<String, Integer> rawHeaders, String fileName) {
        Map<String, Integer> headers = ImportFormatDetector.toUpperHeaderIndex(rawHeaders);
        List<String> missing = new ArrayList<>();
        for (String col : REQUIRED_COLUMNS) {
            if (!headers.containsKey(col)) {
                missing.add(col.toLowerCase(Locale.ROOT));
            }
        }
        if (!missing.isEmpty()) {
            throw new ImportFormatException(
                    "文件「" + fileName + "」外源定位格式不完整，缺少列：" + String.join(", ", missing),
                    ImportFormat.EXTERNAL_TARGET_LOCATE,
                    fileName
            );
        }
    }

    private static List<ExternalTargetFix> parseRecords(CSVParser parser, String sourceFile) throws IOException {
        List<ExternalTargetFix> result = new ArrayList<>();
        int rowNum = 1;
        for (CSVRecord record : parser) {
            rowNum++;
            Double lon = readDouble(record, "longitude");
            Double lat = readDouble(record, "latitude");
            if (lon == null || lat == null || !isValidLonLat(lon, lat)) {
                continue;
            }
            String timeText = readStr(record, "detectTime");
            if (timeText == null || timeText.isEmpty()) {
                continue;
            }
            LocalDateTime detectTime = parseTime(timeText);
            if (detectTime == null) {
                continue;
            }

            ExternalTargetFix fix = new ExternalTargetFix();
            fix.setRowNum(rowNum);
            fix.setSourceFile(sourceFile);
            fix.setPlatformId(readStr(record, "zbxh"));
            fix.setDetectTimeMs(detectTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            fix.setLongitude(lon);
            fix.setLatitude(lat);
            fix.setAffiliation(readStr(record, "dwsx"));
            fix.setTargetTypeName(readStr(record, "dtlxmc"));
            fix.setTargetId(readStr(record, "mbnm"));
            fix.setTargetName(readStr(record, "mbmc"));
            fix.setModelCode(readStr(record, "jxh"));
            result.add(fix);
        }
        if (result.isEmpty()) {
            throw new ImportFormatException(
                    "外源定位文件未解析到有效行（需含 detectTime、longitude、latitude）",
                    ImportFormat.EXTERNAL_TARGET_LOCATE,
                    sourceFile
            );
        }
        return result;
    }

    private static boolean isValidLonLat(double lon, double lat) {
        return lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90;
    }

    private static LocalDateTime parseTime(String text) {
        try {
            return LocalDateTime.parse(text.trim(), FLEX_TIME);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readStr(CSVRecord record, String column) {
        if (!record.isMapped(column)) {
            return null;
        }
        String v = record.get(column);
        if (v == null) {
            return null;
        }
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static Double readDouble(CSVRecord record, String column) {
        String v = readStr(record, column);
        if (v == null) {
            return null;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String detectCharset(MultipartFile file) throws IOException {
        byte[] head = new byte[8192];
        int n;
        try (InputStream in = file.getInputStream()) {
            n = in.read(head);
        }
        return detectCharset(head, n);
    }

    private static String detectCharset(byte[] head, int n) {
        if (n <= 0) {
            return StandardCharsets.UTF_8.name();
        }
        String utf8 = new String(head, 0, n, StandardCharsets.UTF_8);
        return utf8.contains("\uFFFD") ? "GBK" : StandardCharsets.UTF_8.name();
    }
}
