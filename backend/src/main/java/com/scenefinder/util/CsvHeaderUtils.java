package com.scenefinder.util;

import com.pdwfx.signal.util.NSignalTimeColumns;
import org.apache.commons.csv.CSVRecord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 场景导出合并多源 CSV 表头时，保留 FF 导入驻留列块（nSingnalTime / nSignalStartTime / nSignalTime）。
 */
public final class CsvHeaderUtils {

    private CsvHeaderUtils() {
    }

    /**
     * 合并多个源文件表头：保留列顺序，并将驻留相关列规范为 FF 三列布局。
     */
    public static List<String> mergeSourceHeaders(List<List<String>> headersPerFile) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> merged = new ArrayList<>();
        boolean hasLegacy = false;
        boolean hasStart = false;
        boolean hasCanonical = false;

        for (List<String> headers : headersPerFile) {
            if (headers == null) {
                continue;
            }
            for (String h : headers) {
                if (h == null) {
                    continue;
                }
                String trimmed = h.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (NSignalTimeColumns.isLegacyDwellColumn(trimmed)) {
                    hasLegacy = true;
                    continue;
                }
                if (NSignalTimeColumns.isSignalStartColumn(trimmed)) {
                    hasStart = true;
                    continue;
                }
                if (NSignalTimeColumns.isCanonicalDwellColumn(trimmed)) {
                    hasCanonical = true;
                    continue;
                }
                if (NSignalTimeColumns.isDwellColumn(trimmed)) {
                    hasLegacy = true;
                    continue;
                }
                if (seen.add(trimmed)) {
                    merged.add(trimmed);
                }
            }
        }

        insertSignalTimeBlock(merged, hasLegacy, hasStart, hasCanonical);
        return merged;
    }

    /**
     * 将源表头规范为 FF 导入列序（保留非驻留列，驻留块统一为三列）。
     */
    public static List<String> normalizeHeaderRow(List<String> sourceHeader) {
        List<String> out = new ArrayList<>();
        boolean hasLegacy = false;
        boolean hasStart = false;
        boolean hasCanonical = false;

        for (String h : sourceHeader) {
            if (h == null) {
                continue;
            }
            String trimmed = h.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (NSignalTimeColumns.isLegacyDwellColumn(trimmed)) {
                hasLegacy = true;
                continue;
            }
            if (NSignalTimeColumns.isSignalStartColumn(trimmed)) {
                hasStart = true;
                continue;
            }
            if (NSignalTimeColumns.isCanonicalDwellColumn(trimmed)) {
                hasCanonical = true;
                continue;
            }
            if (NSignalTimeColumns.isDwellColumn(trimmed)) {
                hasLegacy = true;
                continue;
            }
            out.add(trimmed);
        }

        insertSignalTimeBlock(out, hasLegacy, hasStart, hasCanonical);
        return out;
    }

    public static String canonicalDwellHeaderName() {
        return NSignalTimeColumns.COL_CANONICAL;
    }

    public static String mapDwellHeaderForExport(String headerCell) {
        if (NSignalTimeColumns.isLegacyDwellColumn(headerCell)) {
            return NSignalTimeColumns.COL_LEGACY;
        }
        if (NSignalTimeColumns.isSignalStartColumn(headerCell)) {
            return NSignalTimeColumns.COL_START;
        }
        if (NSignalTimeColumns.isCanonicalDwellColumn(headerCell)) {
            return NSignalTimeColumns.COL_CANONICAL;
        }
        if (NSignalTimeColumns.isDwellColumn(headerCell)) {
            return NSignalTimeColumns.COL_CANONICAL;
        }
        return headerCell;
    }

    /** 按规范表头从源记录取值，避免列数与表头不一致。 */
    public static List<String> mapRecordToRow(CSVRecord record, List<String> normalizedHeader) {
        List<String> row = new ArrayList<>(normalizedHeader.size());
        for (String column : normalizedHeader) {
            row.add(resolveCell(record, column));
        }
        return row;
    }

    private static void insertSignalTimeBlock(
            List<String> headers,
            boolean hasLegacy,
            boolean hasStart,
            boolean hasCanonical
    ) {
        if (!hasLegacy && !hasStart && !hasCanonical) {
            return;
        }
        int insertAt = indexAfter(headers, "tzys");
        if (insertAt < 0) {
            insertAt = indexAfter(headers, "xhdk");
        }
        if (insertAt < 0) {
            insertAt = indexAfter(headers, "pl");
        }
        if (insertAt < 0) {
            insertAt = headers.size();
        } else {
            insertAt++;
        }

        List<String> block = new ArrayList<>();
        if (hasLegacy || (!hasStart && !hasCanonical)) {
            block.add(NSignalTimeColumns.COL_LEGACY);
        }
        block.add(NSignalTimeColumns.COL_START);
        block.add(NSignalTimeColumns.COL_CANONICAL);
        headers.addAll(Math.min(insertAt, headers.size()), block);
    }

    private static int indexAfter(List<String> headers, String columnLower) {
        for (int i = 0; i < headers.size(); i++) {
            if (columnLower.equalsIgnoreCase(headers.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static String resolveCell(CSVRecord record, String column) {
        return readMapped(record, column);
    }

    private static String readMapped(CSVRecord record, String column) {
        String key = NSignalTimeColumns.resolveCsvHeader(record, column);
        if (key == null) {
            return "";
        }
        String value = record.get(key);
        return value == null ? "" : value.trim();
    }
}
