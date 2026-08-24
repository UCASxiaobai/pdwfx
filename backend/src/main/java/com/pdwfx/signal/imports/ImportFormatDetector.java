package com.pdwfx.signal.imports;

import com.pdwfx.signal.util.NSignalTimeColumns;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
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

    private static final int CHARSET_SAMPLE_BYTES = 256 * 1024;

    private ImportFormatDetector() {
    }

    public static ImportFormat detectFromPath(Path csvPath) throws IOException {
        ImportFormat utf8Format = detectWithCharset(csvPath, StandardCharsets.UTF_8);
        if (utf8Format == ImportFormat.PDW_TABLE || utf8Format == ImportFormat.STANDARD
                || utf8Format == ImportFormat.EXTERNAL_TARGET_LOCATE) {
            return utf8Format;
        }
        ImportFormat gbkFormat = detectWithCharset(csvPath, Charset.forName("GBK"));
        return gbkFormat != ImportFormat.UNKNOWN ? gbkFormat : utf8Format;
    }

    private static ImportFormat detectWithCharset(Path csvPath, Charset charset) throws IOException {
        try (BufferedReader reader = openReader(csvPath, charset);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreHeaderCase(true)
                     .build()
                     .parse(reader)) {
            return detect(toUpperHeaderIndex(parser.getHeaderMap()));
        } catch (MalformedInputException ex) {
            return ImportFormat.UNKNOWN;
        }
    }

    /**
     * 按抽样识别编码，非法字节替换而不是抛错（雷情 CSV 常混有 GBK/截断 UTF-8）。
     */
    public static BufferedReader openReader(Path csvPath) throws IOException {
        return openReader(csvPath, detectFileCharset(csvPath));
    }

    public static BufferedReader openReader(Path csvPath, Charset charset) throws IOException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedReader(new InputStreamReader(Files.newInputStream(csvPath), decoder));
    }

    public static BufferedReader openReader(InputStream in, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        return new BufferedReader(new InputStreamReader(in, decoder));
    }

    public static Charset detectFileCharset(Path csvPath) throws IOException {
        byte[] sample = new byte[CHARSET_SAMPLE_BYTES];
        int n;
        try (InputStream in = Files.newInputStream(csvPath)) {
            n = in.read(sample);
        }
        return charsetOf(sample, n);
    }

    public static Charset charsetOf(byte[] head, int n) {
        if (n <= 0) {
            return StandardCharsets.UTF_8;
        }
        int end = n;
        while (end > 0 && (head[end - 1] & 0xC0) == 0x80) {
            end--;
        }
        if (end > 0 && (head[end - 1] & 0x80) != 0) {
            end--;
        }
        if (end <= 0) {
            return StandardCharsets.UTF_8;
        }
        String utf8 = new String(head, 0, end, StandardCharsets.UTF_8);
        return utf8.contains("\uFFFD") ? Charset.forName("GBK") : StandardCharsets.UTF_8;
    }

    /**
     * 根据已大写的表头索引判定格式。
     * <ul>
     *   <li>detectTime+lon+lat 且无 PDW 测向列 → 外源定位</li>
     *   <li>pl+xhfw+zcsj（或 ZBXH+测向相关列）→ PDW 表</li>
     *   <li>FREQ+AZIMUTH → 标准英文字段</li>
     * </ul>
     */
    public static ImportFormat detect(Map<String, Integer> headerIndexUpper) {
        if (headerIndexUpper == null || headerIndexUpper.isEmpty()) {
            return ImportFormat.UNKNOWN;
        }

        boolean hasZbxh = headerIndexUpper.containsKey("ZBXH");           // 装备/批序号
        boolean hasDetectTime = headerIndexUpper.containsKey("DETECTTIME");
        boolean hasLon = headerIndexUpper.containsKey("LONGITUDE");
        boolean hasLat = headerIndexUpper.containsKey("LATITUDE");
        boolean hasDwsx = headerIndexUpper.containsKey("DWSX");           // 定位属性（雷情）
        boolean hasZcsj = headerIndexUpper.containsKey("ZCSJ");           // 侦获时间
        boolean hasXhfw = headerIndexUpper.containsKey("XHFW");           // 信号方位
        boolean hasPl = headerIndexUpper.containsKey("PL");               // 频率
        boolean hasXhfd = headerIndexUpper.containsKey("XHFD");           // 信号幅度
        boolean hasFreq = headerIndexUpper.containsKey("FREQ");
        boolean hasAzimuth = headerIndexUpper.containsKey("AZIMUTH");

        if (hasPl && hasXhfw && hasZcsj) {
            return ImportFormat.PDW_TABLE;
        }
        if (hasZbxh && (hasXhfw || hasZcsj || hasPl || hasXhfd)) {
            return ImportFormat.PDW_TABLE;
        }
        if (hasDetectTime && hasLon && hasLat && !hasXhfw && !hasZcsj && !hasPl && !hasXhfd) {
            if (hasZbxh || hasDwsx || headerIndexUpper.containsKey("MBNM")) {
                return ImportFormat.EXTERNAL_TARGET_LOCATE;
            }
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
}
