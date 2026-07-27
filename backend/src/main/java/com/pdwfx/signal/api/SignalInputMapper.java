package com.pdwfx.signal.api;

import com.pdwfx.signal.api.dto.DetectSignalDto;
import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.util.NSignalTimeColumns;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 前置：外部 DTO → {@link DetectSignal} */
public final class SignalInputMapper {
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private SignalInputMapper() {}

    public static List<DetectSignal> toSignals(List<DetectSignalDto> dtos) {
        List<DetectSignal> out = new ArrayList<>();
        if (dtos == null) return out;
        int row = 0;
        for (DetectSignalDto dto : dtos) {
            row++;
            out.add(toSignal(dto, row));
        }
        return out;
    }

    public static DetectSignal toSignal(DetectSignalDto dto, int rowNum) {
        if (dto == null) throw new IllegalArgumentException("signals[" + rowNum + "] 为空");
        if (dto.getFreq() == null || dto.getAzimuth() == null || dto.getSignalLevel() == null) {
            throw new IllegalArgumentException("signals[" + rowNum + "] 缺少 freq/azimuth/signalLevel");
        }
        DetectSignal s = new DetectSignal();
        s.setId(dto.getId() != null ? dto.getId() : "ROW-" + rowNum);
        s.setFreq(dto.getFreq());
        s.setAzimuth(dto.getAzimuth());
        s.setSignalLevel(dto.getSignalLevel());
        if (dto.getSnr() != null) s.setSnr(dto.getSnr());
        if (dto.getTargetLon() != null) s.setTargetLon(dto.getTargetLon());
        if (dto.getTargetLat() != null) s.setTargetLat(dto.getTargetLat());
        if (dto.getLongitude() != null) s.setLongitude(dto.getLongitude());
        if (dto.getLatitude() != null) s.setLatitude(dto.getLatitude());
        s.setModulateStyle(dto.getModulateStyle());
        s.setDetectTimesss(resolveTimeMs(dto, rowNum));
        s.setSignalDwellMs(resolveDwellMs(dto));
        return s;
    }

    private static long resolveTimeMs(DetectSignalDto dto, int rowNum) {
        if (dto.getDetectTimesss() != null && dto.getDetectTimesss() > 0) {
            return dto.getDetectTimesss();
        }
        if (dto.getDetectTime() != null && !dto.getDetectTime().trim().isEmpty()) {
            LocalDateTime t = parseTime(dto.getDetectTime().trim());
            if (t != null) {
                return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
        }
        throw new IllegalArgumentException("signals[" + rowNum + "] 缺少 detectTimesss 或 detectTime(zcsj)");
    }

    private static double resolveDwellMs(DetectSignalDto dto) {
        if (dto.getSignalDwellMs() != null && dto.getSignalDwellMs() > 0) {
            return dto.getSignalDwellMs();
        }
        if (dto.getNSignalTime10us() != null && dto.getNSignalTime10us() > 0) {
            return NSignalTimeColumns.toDwellMs(dto.getNSignalTime10us());
        }
        return 0d;
    }

    private static LocalDateTime parseTime(String trimmed) {
        try {
            return LocalDateTime.parse(trimmed, ISO_TIME);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, CSV_TIME);
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            return null;
        }
    }
}
