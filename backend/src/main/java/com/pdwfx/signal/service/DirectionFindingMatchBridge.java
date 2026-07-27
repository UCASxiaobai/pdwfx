package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.scenefinder.service.DirectionFindingMatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将 PDW 测向与外源雷情定位转为 {@link DirectionFindingMatcher} 输入，并映射回业务 ID。
 */
public final class DirectionFindingMatchBridge {

    private DirectionFindingMatchBridge() {
    }

    /**
     * 雷情点 → 设备航迹。
     * <ul>
     *   <li>deviceId（航迹批号）优先 {@code targetName(mbmc)}，其次 {@code targetId(mbnm)}</li>
     *   <li>mbnm 单独保留在 {@link #buildTargetIdIndex(List)} 供反查</li>
     * </ul>
     */
    public static Map<String, DirectionFindingMatcher.DeviceTrajectory> buildTrajectories(
            List<ExternalTargetFix> fixes
    ) {
        Map<String, DirectionFindingMatcher.DeviceTrajectory> map = new LinkedHashMap<>();
        if (fixes == null) {
            return map;
        }
        for (ExternalTargetFix fix : fixes) {
            String deviceId = resolveDeviceId(fix);
            if (deviceId == null) {
                continue;
            }
            DirectionFindingMatcher.DeviceTrajectory traj = map.computeIfAbsent(
                    deviceId, DirectionFindingMatcher.DeviceTrajectory::new);
            traj.addPoint(new DirectionFindingMatcher.DeviceTrack(
                    deviceId,
                    fix.getDetectTimeMs(),
                    fix.getLongitude(),
                    fix.getLatitude()
            ));
        }
        return map;
    }

    /** deviceId → 目标内码 mbnm（取首个非空） */
    public static Map<String, String> buildTargetIdIndex(List<ExternalTargetFix> fixes) {
        Map<String, String> index = new HashMap<>();
        if (fixes == null) {
            return index;
        }
        for (ExternalTargetFix fix : fixes) {
            String deviceId = resolveDeviceId(fix);
            if (deviceId == null) {
                continue;
            }
            index.putIfAbsent(deviceId, blankToNull(fix.getTargetId()));
        }
        return index;
    }

    /**
     * PDW 侦获 → 测向 Measurement。
     * batchId 缺省按 {@code locPlatId + freq} 分组；仍无则 {@code freq:{Hz}}。
     */
    public static List<DirectionFindingMatcher.Measurement> buildMeasurements(List<DetectSignal> signals) {
        List<DirectionFindingMatcher.Measurement> list = new ArrayList<>();
        if (signals == null) {
            return list;
        }
        for (DetectSignal s : signals) {
            Double rxLon = firstNonNull(s.getLongitude(), s.getTargetLon());
            Double rxLat = firstNonNull(s.getLatitude(), s.getTargetLat());
            if (rxLon == null || rxLat == null) {
                continue;
            }
            long timeMs = s.getDetectTimesss() > 0
                    ? s.getDetectTimesss()
                    : (s.getDetectTime() != null
                    ? s.getDetectTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : 0L);
            if (timeMs <= 0) {
                continue;
            }
            int freqHz = (int) Math.round(s.getFreq() * 1_000_000);
            String batchId = resolveBatchId(s);
            DirectionFindingMatcher.Measurement m = new DirectionFindingMatcher.Measurement(
                    timeMs,
                    rxLon,
                    rxLat,
                    s.getAzimuth(),
                    freqHz,
                    batchId
            );
            m.id = s.getId();
            list.add(m);
        }
        return list;
    }

    public static String resolveDeviceId(ExternalTargetFix fix) {
        if (fix == null) {
            return null;
        }
        String name = blankToNull(fix.getTargetName());
        if (name != null) {
            return name;
        }
        String id = blankToNull(fix.getTargetId());
        if (id != null) {
            return id;
        }
        if (fix.getRowNum() > 0) {
            return "ROW-" + fix.getRowNum();
        }
        return null;
    }

    public static String resolveBatchId(DetectSignal s) {
        String plat = blankToNull(s.getLocPlatId());
        if (plat != null && s.getFreq() > 0) {
            return plat + "@" + formatFreqMhz(s.getFreq());
        }
        if (plat != null) {
            return plat;
        }
        return null;
    }

    private static String formatFreqMhz(double freqMhz) {
        return String.format(Locale.ROOT, "%.4f", freqMhz);
    }

    private static String blankToNull(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    private static Double firstNonNull(Double... values) {
        for (Double v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
