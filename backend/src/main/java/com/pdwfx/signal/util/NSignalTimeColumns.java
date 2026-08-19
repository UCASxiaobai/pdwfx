package com.pdwfx.signal.util;

import org.apache.commons.csv.CSVRecord;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PrcFf / FF 导入表驻留相关列：
 * <ul>
 *   <li>{@code nSingnalTime} — 旧拼写，驻留时间（10µs/单位）</li>
 *   <li>{@code nSignalStartTime} — 信号起始时间（10µs/单位，非驻留）</li>
 *   <li>{@code nSignalTime} — 规范驻留时间（10µs/单位）</li>
 * </ul>
 */
public final class NSignalTimeColumns {

    /** 规范驻留列压缩大写名（去下划线）→ 映射键 NSIGNALTIME */
    public static final String CANONICAL_UPPER = "NSIGNALTIME";
    /** 历史拼写驻留列 nSingnalTime 的压缩大写名 */
    public static final String LEGACY_UPPER = "NSINGNALTIME";
    /** 信号起始时刻列 nSignalStartTime 的压缩大写名（非驻留） */
    public static final String START_UPPER = "NSIGNALSTARTTIME";

    /** 规范驻留列原始表头名 */
    public static final String COL_CANONICAL = "nSignalTime";
    /** 历史拼写驻留列原始表头名 */
    public static final String COL_LEGACY = "nSingnalTime";
    /** 信号起始时刻列原始表头名 */
    public static final String COL_START = "nSignalStartTime";

    /** 10µs/计数 → 毫秒的换算系数（×0.01） */
    public static final double TO_MS = 10.0 / 1000.0;

    private NSignalTimeColumns() {
    }

    public static boolean isCanonicalDwellColumn(String header) {
        return "NSIGNALTIME".equals(compact(header));
    }

    public static boolean isLegacyDwellColumn(String header) {
        return "NSINGNALTIME".equals(compact(header));
    }

    public static boolean isSignalStartColumn(String header) {
        return START_UPPER.equals(compact(header));
    }

    public static boolean isDwellColumn(String header) {
        return isCanonicalDwellColumn(header) || isLegacyDwellColumn(header);
    }

    public static boolean isSignalTimeBlockColumn(String header) {
        return isDwellColumn(header) || isSignalStartColumn(header);
    }

    /**
     * 在已大写的表头索引中注册 {@link #CANONICAL_UPPER}，优先 {@code nSignalTime} 列。
     */
    public static void aliasIntoHeaderIndex(Map<String, Integer> upperHeaderIndex) {
        if (upperHeaderIndex == null || upperHeaderIndex.containsKey(CANONICAL_UPPER)) {
            return;
        }
        Integer idx = null;
        for (String key : new String[]{CANONICAL_UPPER, LEGACY_UPPER}) {
            idx = upperHeaderIndex.get(key);
            if (idx != null) {
                break;
            }
        }
        if (idx == null) {
            for (Map.Entry<String, Integer> entry : upperHeaderIndex.entrySet()) {
                if (isCanonicalDwellColumn(entry.getKey())) {
                    idx = entry.getValue();
                    break;
                }
            }
        }
        if (idx == null) {
            for (Map.Entry<String, Integer> entry : upperHeaderIndex.entrySet()) {
                if (isLegacyDwellColumn(entry.getKey())) {
                    idx = entry.getValue();
                    break;
                }
            }
        }
        if (idx != null) {
            upperHeaderIndex.put(CANONICAL_UPPER, idx);
        }
    }

    public static long readRaw10us(CSVRecord record) {
        long canonical = readColumnRaw10us(record, COL_CANONICAL);
        if (canonical > 0L) {
            return canonical;
        }
        return readColumnRaw10us(record, COL_LEGACY);
    }

    public static long readRaw10us(org.apache.poi.ss.usermodel.Row row, Map<String, Integer> headerIndex) {
        long canonical = readColumnRaw10us(row, headerIndex, CANONICAL_UPPER);
        if (canonical > 0L) {
            return canonical;
        }
        return readColumnRaw10us(row, headerIndex, LEGACY_UPPER);
    }

    public static double toDwellMs(long raw10us) {
        return raw10us <= 0L ? 0d : raw10us * TO_MS;
    }

    /**
     * FF 场景筛选有效数据判定（与导入端 Type=2 逻辑一致）。
     * <p>
     * 仅当 {@code nSignalTime} 落在 (1300, 7100) 且
     * {@code (nSignalTime - 1320) % 4800} 处于下列窗口之一时为有效：
     * {@code [461, +∞) ∪ (-∞, 18]}、{@code [1514, 1535]}、{@code [2469, 2497]}。
     * </p>
     */
    public static boolean isValidSceneInput(long nSignalTime) {
        if (nSignalTime <= 1300L || nSignalTime >= 7100L) {
            return false;
        }
        long index = Math.floorMod(nSignalTime - 1320L, 4800L);
        if (index >= 461L || index <= 18L) {
            return true;
        }
        if (index >= 1514L && index <= 1535L) {
            return true;
        }
        return index >= 2469L && index <= 2497L;
    }

    /** 从 CSV 记录读取驻留原始值并做场景有效判定（优先 {@code nSignalTime}，回退 {@code nSingnalTime}）。 */
    public static boolean isValidSceneInput(CSVRecord record) {
        long raw = readRaw10us(record);
        return raw > 0L && isValidSceneInput(raw);
    }

    /** Excel 行：与 {@link #isValidSceneInput(CSVRecord)} 相同，{@code headerIndex} 须已 {@link #aliasIntoHeaderIndex(Map)}。 */
    public static boolean isValidSceneInput(
            org.apache.poi.ss.usermodel.Row row,
            Map<String, Integer> headerIndex
    ) {
        long raw = readRaw10us(row, headerIndex);
        return raw > 0L && isValidSceneInput(raw);
    }

    /** FF 导入标准驻留列顺序（置于 tzys 之后）。 */
    public static List<String> standardSignalTimeHeaders(boolean includeLegacyTypo) {
        List<String> cols = new ArrayList<>();
        if (includeLegacyTypo) {
            cols.add(COL_LEGACY);
        }
        cols.add(COL_START);
        cols.add(COL_CANONICAL);
        return cols;
    }

    private static long readColumnRaw10us(CSVRecord record, String columnName) {
        String key = resolveCsvHeader(record, columnName);
        if (key == null) {
            return 0L;
        }
        return parseLong(record.get(key));
    }

    private static long readColumnRaw10us(
            org.apache.poi.ss.usermodel.Row row,
            Map<String, Integer> headerIndex,
            String upperName
    ) {
        if (headerIndex == null) {
            return 0L;
        }
        Integer idx = headerIndex.get(upperName);
        if (idx == null) {
            return 0L;
        }
        org.apache.poi.ss.usermodel.Cell cell = row.getCell(idx);
        if (cell == null) {
            return 0L;
        }
        try {
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                return (long) cell.getNumericCellValue();
            }
            return parseLong(cell.toString());
        } catch (Exception ex) {
            return 0L;
        }
    }

    /** 在 CSV 记录中定位列名（保留原始表头拼写）。 */
    public static String resolveCsvHeader(CSVRecord record, String columnName) {
        if (record == null || record.getParser() == null || columnName == null) {
            return null;
        }
        if (record.isMapped(columnName)) {
            return columnName;
        }
        String upper = columnName.toUpperCase(Locale.ROOT);
        if (record.isMapped(upper)) {
            return upper;
        }
        for (String header : record.getParser().getHeaderNames()) {
            if (header != null && header.trim().equalsIgnoreCase(columnName)) {
                return header;
            }
        }
        return null;
    }

    /** 兼容旧调用：定位首个驻留列。 */
    public static String resolveCsvHeader(CSVRecord record) {
        String key = resolveCsvHeader(record, COL_CANONICAL);
        if (key != null) {
            return key;
        }
        return resolveCsvHeader(record, COL_LEGACY);
    }

    private static long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            try {
                return (long) Double.parseDouble(value.trim());
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
    }

    private static String compact(String header) {
        return header.trim().toUpperCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
