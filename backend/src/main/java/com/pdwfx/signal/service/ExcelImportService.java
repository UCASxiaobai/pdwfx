package com.pdwfx.signal.service;

import com.pdwfx.signal.imports.ImportFormat;
import com.pdwfx.signal.imports.ImportFormatDetector;
import com.pdwfx.signal.imports.ImportFormatException;
import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.util.NSignalTimeColumns;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CSV / Excel 导入，统一映射为 {@link DetectSignal}。
 *
 * <p>支持两种表头：
 * <ul>
 *   <li>标准字段：FREQ, AZIMUTH, SIGNAL_LEVEL, TARGET_LON, DETECT_TIMESSS …</li>
 *   <li>表格字段（PrcFf 等）：PL→freq, XHFW→azimuth, XHFD→signalLevel,
 *       DWJD/DWD→targetLon/Lat, ZCSJ→detectTime,
 *       nSingnalTime/nSignalTime→signalDwellMs（10µs/单位；优先 nSignalTime）</li>
 * </ul>
 *
 * <p>PDW 表格式：{@link NSignalTimeColumns#isValidSceneInput(CSVRecord)} 为假的行不导入（与场景筛选一致）。
 *
 * <p>定位字段 targetLon/targetLat 供 {@link MotionClassificationService} 误差椭圆判定。
 */
@Service
public class ExcelImportService {
    /** CSV 时间：yyyy-MM-dd HH:mm:ss.SSS */
    private static final DateTimeFormatter CSV_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /** ISO 本地日期时间 */
    private static final DateTimeFormatter ISO_TIME_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public List<DetectSignal> parse(Path path) throws IOException {
        String name = path.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return parseCsvPath(path);
        }
        try (InputStream inputStream = Files.newInputStream(path); Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.rowIterator();
            if (!rows.hasNext()) {
                return Collections.emptyList();
            }
            Map<String, Integer> headerIndex = parseHeader(rows.next());
            ImportFormat format = ImportFormatDetector.detect(toHeaderIndex(headerIndex));
            validateSignalImportFormat(format, name);
            boolean tableFormat = format == ImportFormat.PDW_TABLE;
            List<DetectSignal> result = new ArrayList<>();
            int rowNum = 1;
            while (rows.hasNext()) {
                Row row = rows.next();
                if (tableFormat && !NSignalTimeColumns.isValidSceneInput(row, headerIndex)) {
                    continue;
                }
                result.add(tableFormat ? mapTableRow(row, headerIndex, rowNum++) : mapStandardRow(row, headerIndex));
            }
            return result;
        }
    }

    public List<DetectSignal> parse(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename();
        if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            return parseCsv(file);
        }
        try (InputStream inputStream = file.getInputStream(); Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.rowIterator();
            if (!rows.hasNext()) {
                return Collections.emptyList();
            }
            Map<String, Integer> headerIndex = parseHeader(rows.next());
            ImportFormat format = ImportFormatDetector.detect(toHeaderIndex(headerIndex));
            validateSignalImportFormat(format, name != null ? name : "upload");
            boolean tableFormat = format == ImportFormat.PDW_TABLE;
            List<DetectSignal> result = new ArrayList<>();
            int rowNum = 1;
            while (rows.hasNext()) {
                Row row = rows.next();
                if (tableFormat && !NSignalTimeColumns.isValidSceneInput(row, headerIndex)) {
                    continue;
                }
                result.add(tableFormat ? mapTableRow(row, headerIndex, rowNum++) : mapStandardRow(row, headerIndex));
            }
            return result;
        }
    }

    private List<DetectSignal> parseCsv(MultipartFile file) throws IOException {
        String charset = detectCsvCharset(file);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), charset));
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            return parseCsvRecords(parser);
        }
    }

    private List<DetectSignal> parseCsvPath(Path path) throws IOException {
        byte[] head = Files.readAllBytes(path);
        int n = Math.min(head.length, 8192);
        String charset = detectCsvCharset(head, n);
        try (BufferedReader reader = Files.newBufferedReader(path, java.nio.charset.Charset.forName(charset));
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            return parseCsvRecords(parser);
        }
    }

    private List<DetectSignal> parseCsvRecords(CSVParser parser) throws IOException {
        Map<String, Integer> headerIndex = toHeaderIndex(parser.getHeaderMap());
        ImportFormat format = ImportFormatDetector.detect(headerIndex);
        validateSignalImportFormat(format, "upload.csv");
        boolean tableFormat = format == ImportFormat.PDW_TABLE;
        List<DetectSignal> result = new ArrayList<>();
        int rowNum = 1;
        for (CSVRecord record : parser) {
            if (tableFormat && !NSignalTimeColumns.isValidSceneInput(record)) {
                continue;
            }
            result.add(tableFormat ? mapTableRecord(record, rowNum++) : mapStandardRecord(record));
        }
        return result;
    }

    private String detectCsvCharset(MultipartFile file) throws IOException {
        byte[] head = new byte[8192];
        int n;
        try (InputStream in = file.getInputStream()) {
            n = in.read(head);
        }
        return detectCsvCharset(head, n);
    }

    private String detectCsvCharset(byte[] head, int n) {
        if (n <= 0) {
            return "UTF-8";
        }
        String utf8 = new String(head, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        return utf8.contains("\uFFFD") ? "GBK" : "UTF-8";
    }

    private DetectSignal mapStandardRow(Row row, Map<String, Integer> headerIndex) {
        DetectSignal signal = new DetectSignal();
        signal.setId(readStr(row, headerIndex, "ID"));
        signal.setDataFileId(readStr(row, headerIndex, "DATA_FILE_ID"));
        signal.setDataType(readStr(row, headerIndex, "DATA_TYPE"));
        signal.setLocPlatId(readStr(row, headerIndex, "LOC_PLAT_ID"));
        signal.setAntennaSelect(readStr(row, headerIndex, "ANTENNA_SELECT"));
        signal.setDetectTime(readTime(row, headerIndex, "DETECT_TIME"));
        signal.setDetectTimesss(readLong(row, headerIndex, "DETECT_TIMESSS", 0L));
        signal.setFreq(readDouble(row, headerIndex, "FREQ", 0d));
        signal.setSignalBw(readDouble(row, headerIndex, "SIGNAL_BW", 0d));
        signal.setSignalLevel(readDouble(row, headerIndex, "SIGNAL_LEVEL", 0d));
        signal.setModulateStyle(readStr(row, headerIndex, "MODULATESTYLE"));
        signal.setModulateDimension(readStr(row, headerIndex, "MODULATEDIMENSION"));
        signal.setBitRate(readDouble(row, headerIndex, "BIT_RATE", 0d));
        signal.setAzimuth(readDouble(row, headerIndex, "AZIMUTH", 0d));
        signal.setRelAzimuth(readDouble(row, headerIndex, "REL_AZIMUTH", 0d));
        signal.setSnr(readDouble(row, headerIndex, "SNR", 0d));
        signal.setLongitude(readNullableDouble(row, headerIndex, "LONGITUDE"));
        signal.setLatitude(readNullableDouble(row, headerIndex, "LATITUDE"));
        signal.setTargetLon(readNullableDouble(row, headerIndex, "TARGET_LON"));
        signal.setTargetLat(readNullableDouble(row, headerIndex, "TARGET_LAT"));
        signal.setEquipId(readStr(row, headerIndex, "EQUIP_ID"));
        signal.setClzt(readStr(row, headerIndex, "CLZT"));
        applySceneTrackId(signal, row, headerIndex);
        fillDetectTimesss(signal);
        return signal;
    }

    private DetectSignal mapStandardRecord(CSVRecord record) {
        DetectSignal signal = new DetectSignal();
        signal.setId(readStr(record, "ID"));
        signal.setDataFileId(readStr(record, "DATA_FILE_ID"));
        signal.setDataType(readStr(record, "DATA_TYPE"));
        signal.setLocPlatId(readStr(record, "LOC_PLAT_ID"));
        signal.setAntennaSelect(readStr(record, "ANTENNA_SELECT"));
        signal.setDetectTime(parseFlexibleDateTime(readStr(record, "DETECT_TIME")));
        signal.setDetectTimesss(readTimeMillis(record, "DETECT_TIMESSS", 0L));
        signal.setFreq(readDouble(record, "FREQ", 0d));
        signal.setSignalBw(readDouble(record, "SIGNAL_BW", 0d));
        signal.setSignalLevel(readDouble(record, "SIGNAL_LEVEL", 0d));
        signal.setModulateStyle(readStr(record, "MODULATESTYLE"));
        signal.setModulateDimension(readStr(record, "MODULATEDIMENSION"));
        signal.setBitRate(readDouble(record, "BIT_RATE", 0d));
        signal.setAzimuth(readDouble(record, "AZIMUTH", 0d));
        signal.setRelAzimuth(readDouble(record, "REL_AZIMUTH", 0d));
        signal.setSnr(readDouble(record, "SNR", 0d));
        signal.setLongitude(readNullableDouble(record, "LONGITUDE"));
        signal.setLatitude(readNullableDouble(record, "LATITUDE"));
        signal.setTargetLon(readNullableDouble(record, "TARGET_LON"));
        signal.setTargetLat(readNullableDouble(record, "TARGET_LAT"));
        signal.setEquipId(readStr(record, "EQUIP_ID"));
        signal.setClzt(readStr(record, "CLZT"));
        applySceneTrackId(signal, record);
        fillDetectTimesss(signal);
        return signal;
    }

    /**
     * PDW 侦获表（Excel 行）→ DetectSignal。
     * 列映射：ZBXH→id/平台；ZCSJ→时间；PL→freq；XHFW→azimuth；XHFD→幅度；
     * XHBK→带宽；TZYS→调制；DWJD/DWD→目标经纬度；ZJWZJD/ZJWZWD→测站位置；
     * nSignalTime→驻留 ms。
     */
    private DetectSignal mapTableRow(Row row, Map<String, Integer> headerIndex, int rowNum) {
        DetectSignal signal = new DetectSignal();
        String zbxh = readStr(row, headerIndex, "ZBXH");
        signal.setId(zbxh == null ? "ROW-" + rowNum : zbxh + "-" + rowNum);
        signal.setLocPlatId(zbxh);
        signal.setEquipId(zbxh);
        String headers = readStr(row, headerIndex, "HEADERS");
        if (headers != null && headers.length() <= 64) {
            signal.setDataFileId(headers);
        }
        signal.setDataType(firstNonBlank(readStr(row, headerIndex, "DATATYPE"), readStr(row, headerIndex, "XHLX")));
        signal.setDetectTime(readTableTime(row, headerIndex, "ZCSJ"));
        signal.setFreq(readTableFreq(row, headerIndex));
        signal.setSignalBw(readTableSignalBw(row, headerIndex));
        signal.setSignalLevel(readDouble(row, headerIndex, "XHFD", 0d));
        signal.setModulateStyle(readStr(row, headerIndex, "TZYS"));
        signal.setAzimuth(readDouble(row, headerIndex, "XHFW", 0d));
        signal.setRelAzimuth(readDouble(row, headerIndex, "ZJWZFYJ", 0d));
        signal.setSnr(readDouble(row, headerIndex, "KXD", 0d));
        signal.setLongitude(firstNonNull(readNullableDouble(row, headerIndex, "ZJWZJD"), readNullableDouble(row, headerIndex, "DWJD")));
        signal.setLatitude(firstNonNull(readNullableDouble(row, headerIndex, "ZJWZWD"), readNullableDouble(row, headerIndex, "DWD")));
        signal.setTargetLon(firstNonNull(readNullableDouble(row, headerIndex, "DWJD"), readNullableDouble(row, headerIndex, "TARGET_LON")));
        signal.setTargetLat(firstNonNull(readNullableDouble(row, headerIndex, "DWD"), readNullableDouble(row, headerIndex, "DWWD"), readNullableDouble(row, headerIndex, "TARGET_LAT")));
        signal.setClzt(readStr(row, headerIndex, "IFCDW"));
        signal.setSignalDwellMs(readSignalDwellMs(row, headerIndex));
        applySceneTrackId(signal, row, headerIndex);
        fillDetectTimesss(signal);
        return signal;
    }

    /**
     * PDW 侦获表（CSV 行）→ DetectSignal，列含义同 {@link #mapTableRow}。
     */
    private DetectSignal mapTableRecord(CSVRecord record, int rowNum) {
        DetectSignal signal = new DetectSignal();
        String zbxh = readStr(record, "ZBXH");
        signal.setId(zbxh == null ? "ROW-" + rowNum : zbxh + "-" + rowNum);
        signal.setLocPlatId(zbxh);
        signal.setEquipId(zbxh);
        String headers = readStr(record, "HEADERS");
        if (headers != null && headers.length() <= 64) {
            signal.setDataFileId(headers);
        }
        signal.setDataType(firstNonBlank(readStr(record, "DATATYPE"), readStr(record, "XHLX")));
        signal.setDetectTime(parseFlexibleDateTime(readStr(record, "ZCSJ")));
        signal.setFreq(readTableFreq(record));
        signal.setSignalBw(readTableSignalBw(record));
        signal.setSignalLevel(readDouble(record, "XHFD", 0d));
        signal.setModulateStyle(readStr(record, "TZYS"));
        signal.setAzimuth(readDouble(record, "XHFW", 0d));
        signal.setRelAzimuth(readDouble(record, "ZJWZFYJ", 0d));
        signal.setSnr(readDouble(record, "KXD", 0d));
        signal.setLongitude(firstNonNull(readNullableDouble(record, "ZJWZJD"), readNullableDouble(record, "DWJD")));
        signal.setLatitude(firstNonNull(readNullableDouble(record, "ZJWZWD"), readNullableDouble(record, "DWD")));
        signal.setTargetLon(firstNonNull(readNullableDouble(record, "DWJD"), readNullableDouble(record, "TARGET_LON")));
        signal.setTargetLat(firstNonNull(readNullableDouble(record, "DWD"), readNullableDouble(record, "DWWD"), readNullableDouble(record, "TARGET_LAT")));
        signal.setClzt(readStr(record, "IFCDW"));
        signal.setSignalDwellMs(readSignalDwellMs(record));
        applySceneTrackId(signal, record);
        fillDetectTimesss(signal);
        return signal;
    }

    private void applySceneTrackId(DetectSignal signal, CSVRecord record) {
        String v = readStr(record, "TRACK_ID");
        signal.setSceneTrackId(parsePositiveInt(v));
    }

    private void applySceneTrackId(DetectSignal signal, Row row, Map<String, Integer> headerIndex) {
        String v = readStr(row, headerIndex, "TRACK_ID");
        signal.setSceneTrackId(parsePositiveInt(v));
    }

    private static Integer parsePositiveInt(String v) {
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        try {
            int id = (int) Double.parseDouble(v.trim());
            return id > 0 ? Integer.valueOf(id) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void validateSignalImportFormat(ImportFormat format, String fileName) {
        if (format == ImportFormat.EXTERNAL_TARGET_LOCATE) {
            throw new ImportFormatException(
                    "文件「" + fileName + "」为外源目标定位格式（雷情导入），不能作为 PDW 信号分析输入。"
                            + " 请与 PDW 文件一并上传，或调用外源定位导入接口。",
                    format,
                    fileName
            );
        }
        if (format == ImportFormat.UNKNOWN) {
            throw new ImportFormatException(
                    "文件「" + fileName + "」格式无法识别。"
                            + " 支持：PDW 侦获表（pl/xhfw/zcsj）、标准信号表（FREQ/AZIMUTH）、"
                            + "外源定位表（detectTime/longitude/latitude）。",
                    format,
                    fileName
            );
        }
    }

    private boolean isTableFormat(Map<String, Integer> headerIndex) {
        return ImportFormatDetector.detect(headerIndex) == ImportFormat.PDW_TABLE;
    }

    private Map<String, Integer> toHeaderIndex(Map<String, Integer> raw) {
        Map<String, Integer> map = new HashMap<>();
        for (Map.Entry<String, Integer> entry : raw.entrySet()) {
            map.put(entry.getKey().trim().toUpperCase(Locale.ROOT), entry.getValue());
        }
        NSignalTimeColumns.aliasIntoHeaderIndex(map);
        return map;
    }

    private double readTableFreq(Row row, Map<String, Integer> headerIndex) {
        double pl = readDouble(row, headerIndex, "PL", Double.NaN);
        if (!Double.isNaN(pl) && pl > 0d) return pl;
        return readDouble(row, headerIndex, "DRT", 0d);
    }

    private double readTableFreq(CSVRecord record) {
        double pl = readDouble(record, "PL", Double.NaN);
        if (!Double.isNaN(pl) && pl > 0d) return pl;
        return readDouble(record, "DRT", 0d);
    }

    private double readTableSignalBw(Row row, Map<String, Integer> headerIndex) {
        double xhdk = readDouble(row, headerIndex, "XHDK", Double.NaN);
        if (!Double.isNaN(xhdk) && xhdk > 0d) return xhdk;
        return readDouble(row, headerIndex, "XHLX", 0d);
    }

    private double readTableSignalBw(CSVRecord record) {
        double xhdk = readDouble(record, "XHDK", Double.NaN);
        if (!Double.isNaN(xhdk) && xhdk > 0d) return xhdk;
        return readDouble(record, "XHLX", 0d);
    }

    private LocalDateTime readTableTime(Row row, Map<String, Integer> headerIndex, String name) {
        LocalDateTime fromCell = readTime(row, headerIndex, name);
        if (fromCell != null) return fromCell;
        return parseFlexibleDateTime(readStr(row, headerIndex, name));
    }

    private void fillDetectTimesss(DetectSignal signal) {
        if (signal.getDetectTimesss() <= 0L && signal.getDetectTime() != null) {
            signal.setDetectTimesss(signal.getDetectTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }
    }

    /** nSignalTime / nSingnalTime：驻留时间，单位 10µs → 毫秒 */
    private double readSignalDwellMs(Row row, Map<String, Integer> headerIndex) {
        return NSignalTimeColumns.toDwellMs(NSignalTimeColumns.readRaw10us(row, headerIndex));
    }

    private double readSignalDwellMs(CSVRecord record) {
        return NSignalTimeColumns.toDwellMs(NSignalTimeColumns.readRaw10us(record));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return null;
    }

    private Double firstNonNull(Double... values) {
        for (Double value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private String readStr(CSVRecord record, String name) {
        String key = resolveCsvHeader(record, name);
        if (key == null) return null;
        String v = record.get(key);
        return v == null ? null : v.trim();
    }

    private String resolveCsvHeader(CSVRecord record, String name) {
        if (record.isMapped(name)) return name;
        String upper = name.toUpperCase(Locale.ROOT);
        if (record.isMapped(upper)) return upper;
        for (String header : record.getParser().getHeaderNames()) {
            if (header != null && header.trim().equalsIgnoreCase(name)) return header;
        }
        return null;
    }

    private double readDouble(CSVRecord record, String name, double def) {
        String v = readStr(record, name);
        if (v == null || v.isEmpty()) return def;
        try {
            return Double.parseDouble(v);
        } catch (Exception e) {
            return def;
        }
    }

    private Double readNullableDouble(CSVRecord record, String name) {
        String v = readStr(record, name);
        if (v == null || v.isEmpty()) return null;
        try {
            return Double.parseDouble(v);
        } catch (Exception e) {
            return null;
        }
    }

    private long readLong(CSVRecord record, String name, long def) {
        String v = readStr(record, name);
        if (v == null || v.isEmpty()) return def;
        try {
            return Long.parseLong(v);
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(v);
            } catch (Exception ignored) {
                return def;
            }
        }
    }

    private long readTimeMillis(CSVRecord record, String name, long def) {
        String v = readStr(record, name);
        if (v == null || v.isEmpty()) return def;
        try {
            return LocalDateTime.parse(v, CSV_TIME_FORMAT).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return readLong(record, name, def);
        }
    }

    private LocalDateTime parseFlexibleDateTime(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String trimmed = value.trim();
        try {
            return LocalDateTime.parse(trimmed, ISO_TIME_FORMAT);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, CSV_TIME_FORMAT);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Integer> parseHeader(Row header) {
        Map<String, Integer> map = new HashMap<>();
        for (Cell cell : header) {
            map.put(cell.getStringCellValue().trim().toUpperCase(Locale.ROOT), cell.getColumnIndex());
        }
        NSignalTimeColumns.aliasIntoHeaderIndex(map);
        return map;
    }

    private Cell getCell(Row row, Map<String, Integer> idx, String name) {
        Integer i = idx.get(name);
        return i == null ? null : row.getCell(i);
    }

    private String readStr(Row row, Map<String, Integer> idx, String name) {
        Cell cell = getCell(row, idx, name);
        if (cell == null) return null;
        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                return String.valueOf(cell.getNumericCellValue());
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return null;
        }
    }

    private double readDouble(Row row, Map<String, Integer> idx, String name, double def) {
        Cell cell = getCell(row, idx, name);
        if (cell == null) return def;
        try {
            return cell.getCellType() == CellType.NUMERIC ? cell.getNumericCellValue() : Double.parseDouble(cell.toString().trim());
        } catch (Exception e) {
            return def;
        }
    }

    private Double readNullableDouble(Row row, Map<String, Integer> idx, String name) {
        Cell cell = getCell(row, idx, name);
        if (cell == null || cell.toString().trim().isEmpty()) return null;
        try {
            return cell.getCellType() == CellType.NUMERIC ? cell.getNumericCellValue() : Double.parseDouble(cell.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private long readLong(Row row, Map<String, Integer> idx, String name, long def) {
        Cell cell = getCell(row, idx, name);
        if (cell == null) return def;
        try {
            return cell.getCellType() == CellType.NUMERIC ? (long) cell.getNumericCellValue() : Long.parseLong(cell.toString().trim());
        } catch (Exception e) {
            try {
                return (long) Double.parseDouble(cell.toString().trim());
            } catch (Exception ignored) {
                return def;
            }
        }
    }

    private LocalDateTime readTime(Row row, Map<String, Integer> idx, String name) {
        Cell cell = getCell(row, idx, name);
        if (cell == null) return null;
        try {
            if (DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
