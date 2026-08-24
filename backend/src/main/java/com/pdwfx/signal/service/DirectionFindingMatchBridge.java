package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.scenefinder.service.DirectionFindingMatcher;
import com.scenefinder.service.HopBatchIndex;

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
     *   <li>两者皆空的点不参与匹配（避免锁到 CSV 行号 {@code ROW-n}）</li>
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
        // #region agent log
        try {
            int rowDevices = 0;
            int namedDevices = 0;
            java.util.List<String> rowSamples = new java.util.ArrayList<String>();
            java.util.List<String> namedSamples = new java.util.ArrayList<String>();
            for (String id : map.keySet()) {
                if (id != null && id.startsWith("ROW-")) {
                    rowDevices++;
                    if (rowSamples.size() < 8) {
                        rowSamples.add(id + ":pts=" + map.get(id).trackPoints.size());
                    }
                } else {
                    namedDevices++;
                    if (namedSamples.size() < 8) {
                        namedSamples.add(id + ":pts=" + map.get(id).trackPoints.size());
                    }
                }
            }
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
            data.put("fixCount", Integer.valueOf(fixes.size()));
            data.put("uniqueDevices", Integer.valueOf(map.size()));
            data.put("rowPrefixedDevices", Integer.valueOf(rowDevices));
            data.put("namedOrIdDevices", Integer.valueOf(namedDevices));
            data.put("rowSamples", rowSamples);
            data.put("namedSamples", namedSamples);
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("sessionId", "b9c0b8");
            payload.put("runId", "pre-fix");
            payload.put("hypothesisId", "A,D,E");
            payload.put("location", "DirectionFindingMatchBridge.java:buildTrajectories");
            payload.put("message", "trajectory deviceId grouping");
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
     * 提供换频编批索引时，用 {@code hop:{seedId}} / {@code track:{id}} 覆盖按频批号。
     */
    public static List<DirectionFindingMatcher.Measurement> buildMeasurements(List<DetectSignal> signals) {
        return buildMeasurements(signals, null);
    }

    public static List<DirectionFindingMatcher.Measurement> buildMeasurements(
            List<DetectSignal> signals,
            HopBatchIndex hopIndex
    ) {
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
            long timeMs = detectTimeMs(s);
            if (timeMs <= 0) {
                continue;
            }
            int freqHz = (int) Math.round(s.getFreq() * 1_000_000);
            String batchId = resolveBatchId(s, hopIndex, timeMs);
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
            // #region agent log
            try {
                if (fix.getRowNum() % 2000 == 0) {
                    java.util.Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
                    data.put("rowNum", Integer.valueOf(fix.getRowNum()));
                    data.put("targetName", fix.getTargetName());
                    data.put("targetId", fix.getTargetId());
                    data.put("targetTypeName", fix.getTargetTypeName());
                    data.put("modelCode", fix.getModelCode());
                    java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
                    payload.put("sessionId", "b9c0b8");
                    payload.put("runId", "pre-fix");
                    payload.put("hypothesisId", "A,C");
                    payload.put("location", "DirectionFindingMatchBridge.java:resolveDeviceId");
                    payload.put("message", "ROW fallback");
                    payload.put("timestamp", Long.valueOf(System.currentTimeMillis()));
                    payload.put("data", data);
                    String line = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload) + "\n";
                    java.nio.file.Files.write(
                            java.nio.file.Paths.get("D:/Documents/Code/Java/pdwfx/debug-b9c0b8.log"),
                            line.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                }
            } catch (Exception ignored) {
                // debug only
            }
            // #endregion
        }
        return null;
    }

    public static String resolveBatchId(DetectSignal s) {
        return resolveBatchId(s, null, detectTimeMs(s));
    }

    public static String resolveBatchId(DetectSignal s, HopBatchIndex hopIndex, long timeMs) {
        if (hopIndex != null && hopIndex.isPresent() && s != null) {
            String hopId = hopIndex.resolve(s.getSourceRowIndex(), timeMs, s.getFreq(), s.getAzimuth());
            if (hopId != null && !hopId.trim().isEmpty()) {
                return hopId;
            }
        }
        if (s == null) {
            return null;
        }
        String plat = blankToNull(s.getLocPlatId());
        if (plat != null && s.getFreq() > 0) {
            return plat + "@" + formatFreqMhz(s.getFreq());
        }
        if (plat != null) {
            return plat;
        }
        return null;
    }

    private static long detectTimeMs(DetectSignal s) {
        if (s == null) {
            return 0L;
        }
        if (s.getDetectTimesss() > 0) {
            return s.getDetectTimesss();
        }
        if (s.getDetectTime() != null) {
            return s.getDetectTime().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        return 0L;
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
