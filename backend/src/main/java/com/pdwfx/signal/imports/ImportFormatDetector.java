package com.pdwfx.signal.imports;

import com.pdwfx.signal.util.NSignalTimeColumns;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 根据表头识别 CSV 格式；未知表头返回 {@link ImportFormat#UNKNOWN}。
 */
public final class ImportFormatDetector {

    private ImportFormatDetector() {
    }

    public static ImportFormat detectFromPath(Path csvPath) throws IOException {
        byte[] head = Files.readAllBytes(csvPath);
        int n = Math.min(head.length, 8192);
        String charset = detectCharset(head, n);
        try (BufferedReader reader = Files.newBufferedReader(csvPath, Charset.forName(charset));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            return detect(toUpperHeaderIndex(parser.getHeaderMap()));
        }
    }

    public static ImportFormat detect(Map<String, Integer> headerIndexUpper) {
        if (headerIndexUpper == null || headerIndexUpper.isEmpty()) {
            return ImportFormat.UNKNOWN;
        }

        boolean hasZbxh = headerIndexUpper.containsKey("ZBXH");
        boolean hasDetectTime = headerIndexUpper.containsKey("DETECTTIME");
        boolean hasLon = headerIndexUpper.containsKey("LONGITUDE");
        boolean hasLat = headerIndexUpper.containsKey("LATITUDE");
        boolean hasDwsx = headerIndexUpper.containsKey("DWSX");
        boolean hasZcsj = headerIndexUpper.containsKey("ZCSJ");
        boolean hasXhfw = headerIndexUpper.containsKey("XHFW");
        boolean hasPl = headerIndexUpper.containsKey("PL");
        boolean hasXhfd = headerIndexUpper.containsKey("XHFD");
        boolean hasFreq = headerIndexUpper.containsKey("FREQ");
        boolean hasAzimuth = headerIndexUpper.containsKey("AZIMUTH");

        if (hasDetectTime && hasLon && hasLat && !hasXhfw && !hasZcsj && !hasPl && !hasXhfd) {
            if (hasZbxh || hasDwsx || headerIndexUpper.containsKey("MBNM")) {
                return ImportFormat.EXTERNAL_TARGET_LOCATE;
            }
        }

        if (hasPl && hasXhfw && hasZcsj) {
            return ImportFormat.PDW_TABLE;
        }
        if (hasZbxh && (hasXhfw || hasZcsj || hasPl || hasXhfd)) {
            return ImportFormat.PDW_TABLE;
        }
        if (hasFreq && hasAzimuth) {
            return ImportFormat.STANDARD;
        }
        return ImportFormat.UNKNOWN;
    }

    public static Map<String, Integer> toUpperHeaderIndex(Map<String, Integer> raw) {
        Map<String, Integer> map = new HashMap<>();
        if (raw == null) {
            return map;
        }
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            map.put(entry.getKey().trim().toUpperCase(Locale.ROOT), entry.getValue());
        }
        NSignalTimeColumns.aliasIntoHeaderIndex(map);
        return map;
    }

    public static String formatHint(ImportFormat format) {
        switch (format) {
            case PDW_TABLE:
                return "PDW 侦获表（需含 pl、xhfw、zcsj；可选 nSingnalTime/nSignalStartTime/nSignalTime）";
            case STANDARD:
                return "标准信号表（需含 FREQ、AZIMUTH）";
            case EXTERNAL_TARGET_LOCATE:
                return "外源目标定位表（需含 detectTime、longitude、latitude，如 Lq1139 雷情导入）";
            default:
                return "未知格式";
        }
    }

    private static String detectCharset(byte[] head, int n) {
        if (n <= 0) {
            return StandardCharsets.UTF_8.name();
        }
        String utf8 = new String(head, 0, n, StandardCharsets.UTF_8);
        return utf8.contains("\uFFFD") ? "GBK" : StandardCharsets.UTF_8.name();
    }
}
