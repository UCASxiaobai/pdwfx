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
import java.nio.charset.Charset;
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
        Charset charset = ImportFormatDetector.detectFileCharset(path);
        try (BufferedReader reader = ImportFormatDetector.openReader(path, charset);
             CSVParser parser = locateCsvFormat().parse(reader)) {
            validateHeaders(parser.getHeaderMap(), fileName);
            return parseRecords(parser, path.toAbsolutePath().normalize().toString());
        }
    }

    public List<ExternalTargetFix> parse(MultipartFile file) throws IOException {
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload.csv";
        Charset charset = detectCharset(file);
        try (BufferedReader reader = ImportFormatDetector.openReader(file.getInputStream(), charset);
             CSVParser parser = locateCsvFormat().parse(reader)) {
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
            if (!record.isConsistent()) {
                continue;
            }
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
        // #region agent log
        try {
            int named = 0;
            int idOnly = 0;
            int rowOnly = 0;
            java.util.List<String> namedSamples = new java.util.ArrayList<String>();
            java.util.List<String> emptySamples = new java.util.ArrayList<String>();
            for (ExternalTargetFix f : result) {
                boolean hasName = f.getTargetName() != null && !f.getTargetName().trim().isEmpty();
                boolean hasId = f.getTargetId() != null && !f.getTargetId().trim().isEmpty();
                if (hasName) {
                    named++;
                    if (namedSamples.size() < 5) {
                        namedSamples.add(f.getTargetName() + "|" + f.getTargetId() + "|row=" + f.getRowNum());
                    }
                } else if (hasId) {
                    idOnly++;
                } else {
                    rowOnly++;
                    if (emptySamples.size() < 5) {
                        emptySamples.add("ROW-" + f.getRowNum() + "|dtlxmc=" + f.getTargetTypeName()
                                + "|jxh=" + f.getModelCode());
                    }
                }
            }
            boolean mappedMbmc = false;
            boolean mappedMbnm = false;
            java.util.List<String> headers = new java.util.ArrayList<String>();
            if (parser.getHeaderMap() != null) {
                headers.addAll(parser.getHeaderMap().keySet());
            }
            if (!result.isEmpty()) {
                // peek via last parser record is unavailable; use header map
                mappedMbmc = parser.getHeaderMap() != null && parser.getHeaderMap().containsKey("mbmc");
                mappedMbnm = parser.getHeaderMap() != null && parser.getHeaderMap().containsKey("mbnm");
                if (!mappedMbmc && parser.getHeaderMap() != null) {
                    for (String h : parser.getHeaderMap().keySet()) {
                        if (h != null && "mbmc".equalsIgnoreCase(h.trim())) {
                            mappedMbmc = true;
                        }
                        if (h != null && "mbnm".equalsIgnoreCase(h.trim())) {
                            mappedMbnm = true;
                        }
                    }
                }
            }
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
            data.put("sourceFile", sourceFile);
            data.put("headers", headers);
            data.put("mappedMbmc", Boolean.valueOf(mappedMbmc));
            data.put("mappedMbnm", Boolean.valueOf(mappedMbnm));
            data.put("fixCount", Integer.valueOf(result.size()));
            data.put("namedMbmc", Integer.valueOf(named));
            data.put("idOnlyMbnm", Integer.valueOf(idOnly));
            data.put("emptyNameAndId", Integer.valueOf(rowOnly));
            data.put("namedSamples", namedSamples);
            data.put("emptySamples", emptySamples);
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("sessionId", "b9c0b8");
            payload.put("runId", "pre-fix");
            payload.put("hypothesisId", "A,B");
            payload.put("location", "ExternalTargetLocateImportService.java:parseRecords");
            payload.put("message", "locate mbmc/mbnm fill stats");
            payload.put("timestamp", Long.valueOf(System.currentTimeMillis()));
            payload.put("data", data);
            String line = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload) + "\n";
            java.nio.file.Files.write(
                    java.nio.file.Paths.get("D:/Documents/Code/Java/pdwfx/debug-b9c0b8.log"),
                    line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // debug only
        }
        // #endregion
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

    private static CSVFormat locateCsvFormat() {
        return CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .setIgnoreHeaderCase(true)
                .build();
    }

    private static LocalDateTime parseTime(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        LocalDateTime iso = tryParse(trimmed, FLEX_TIME);
        if (iso != null) {
            return iso;
        }
        DateTimeFormatter[] excelOrLoose = {
                DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss.SSS"),
                DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
                DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-M-d H:mm")
        };
        for (int i = 0; i < excelOrLoose.length; i++) {
            LocalDateTime parsed = tryParse(trimmed, excelOrLoose[i]);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static LocalDateTime tryParse(String text, DateTimeFormatter formatter) {
        try {
            return LocalDateTime.parse(text, formatter);
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

    private static Charset detectCharset(MultipartFile file) throws IOException {
        byte[] head = new byte[256 * 1024];
        int n;
        try (InputStream in = file.getInputStream()) {
            n = in.read(head);
        }
        return ImportFormatDetector.charsetOf(head, n);
    }
}
