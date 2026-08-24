package com.pdwfx.signal.service;

import com.pdwfx.signal.imports.ImportFormat;
import com.pdwfx.signal.imports.ImportFormatDetector;
import com.pdwfx.signal.imports.ImportFormatException;
import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.DirectionFindingMatchResponse;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.scenefinder.service.DirectionFindingMatcher;
import com.scenefinder.service.HopBatchIndex;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DirectionFindingMatchService {

    private final ExcelImportService excelImportService;
    private final ExternalTargetLocateImportService externalImportService;

    public DirectionFindingMatchService(
            ExcelImportService excelImportService,
            ExternalTargetLocateImportService externalImportService
    ) {
        this.excelImportService = excelImportService;
        this.externalImportService = externalImportService;
    }

    public DirectionFindingMatchResponse match(
            List<DetectSignal> measurements,
            List<ExternalTargetFix> trajectories,
            DirectionFindingMatcher.MatchConfig config
    ) {
        return match(measurements, trajectories, config, null);
    }

    public DirectionFindingMatchResponse match(
            List<DetectSignal> measurements,
            List<ExternalTargetFix> trajectories,
            DirectionFindingMatcher.MatchConfig config,
            String outputDir
    ) {
        DirectionFindingMatcher.MatchConfig cfg = config != null ? config : new DirectionFindingMatcher.MatchConfig();
        HopBatchIndex hopIndex = HopBatchIndex.loadQuietly(outputDir);
        List<DirectionFindingMatcher.Measurement> ms =
                DirectionFindingMatchBridge.buildMeasurements(measurements, hopIndex);
        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajs =
                DirectionFindingMatchBridge.buildTrajectories(trajectories);
        Map<String, String> targetIdIndex = DirectionFindingMatchBridge.buildTargetIdIndex(trajectories);
        // #region agent log
        try {
            java.util.Set<String> mbmcSet = new java.util.HashSet<String>();
            java.util.Set<String> mbnmSet = new java.util.HashSet<String>();
            java.util.Map<String, java.util.Set<String>> idsByName = new java.util.LinkedHashMap<String, java.util.Set<String>>();
            if (trajectories != null) {
                for (ExternalTargetFix f : trajectories) {
                    if (f == null) {
                        continue;
                    }
                    String name = f.getTargetName() == null ? "" : f.getTargetName().trim();
                    String id = f.getTargetId() == null ? "" : f.getTargetId().trim();
                    if (!name.isEmpty()) {
                        mbmcSet.add(name);
                        if (!id.isEmpty()) {
                            mbnmSet.add(id);
                            java.util.Set<String> ids = idsByName.get(name);
                            if (ids == null) {
                                ids = new java.util.LinkedHashSet<String>();
                                idsByName.put(name, ids);
                            }
                            ids.add(id);
                        }
                    } else if (!id.isEmpty()) {
                        mbnmSet.add(id);
                    }
                }
            }
            java.util.Map<String, Integer> idsPerName = new java.util.LinkedHashMap<String, Integer>();
            for (java.util.Map.Entry<String, java.util.Set<String>> e : idsByName.entrySet()) {
                idsPerName.put(e.getKey(), Integer.valueOf(e.getValue().size()));
            }
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
            data.put("uniqueMbmc", Integer.valueOf(mbmcSet.size()));
            data.put("uniqueMbnm", Integer.valueOf(mbnmSet.size()));
            data.put("trajDeviceCount", Integer.valueOf(trajs.size()));
            data.put("idsPerName", idsPerName);
            data.put("angleDeg", Double.valueOf(cfg.angleThresholdDeg));
            data.put("distM", Double.valueOf(cfg.distanceThresholdM));
            data.put("timeMs", Long.valueOf(cfg.maxTimeDeltaMs));
            data.put("coarse", Boolean.valueOf(cfg.coarseMatch));
            data.put("exclusive", Boolean.valueOf(cfg.exclusiveAssign));
            data.put("minHits", Integer.valueOf(cfg.minHits));
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("sessionId", "b9c0b8");
            payload.put("runId", "pre-fix");
            payload.put("hypothesisId", "F");
            payload.put("location", "DirectionFindingMatchService.java:match");
            payload.put("message", "mbmc vs mbnm grouping");
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

        DirectionFindingMatcher.MatchAllResult matched = DirectionFindingMatcher.matchAll(ms, trajs, cfg);
        Map<DirectionFindingMatcher.Measurement, String> pointMatches = matched.pointMatches;
        Map<String, String> batchSummary = matched.batchSummary;

        DirectionFindingMatchResponse response = new DirectionFindingMatchResponse();
        response.setConfig(cfg);
        response.setMeasurementCount(ms.size());
        response.setTrajectoryDeviceCount(trajs.size());
        response.setBatchToDevice(batchSummary);
        // #region agent log
        try {
            int rowLocks = 0;
            int namedLocks = 0;
            java.util.List<String> samples = new java.util.ArrayList<String>();
            for (Map.Entry<String, String> e : batchSummary.entrySet()) {
                String device = e.getValue();
                boolean row = device != null && device.startsWith("ROW-");
                if (row) {
                    rowLocks++;
                } else {
                    namedLocks++;
                }
                if (samples.size() < 12) {
                    samples.add(e.getKey() + "->" + device
                            + "|mbnm=" + targetIdIndex.get(device));
                }
            }
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<String, Object>();
            data.put("lockCount", Integer.valueOf(batchSummary.size()));
            data.put("rowLocks", Integer.valueOf(rowLocks));
            data.put("namedLocks", Integer.valueOf(namedLocks));
            data.put("trajDeviceCount", Integer.valueOf(trajs.size()));
            data.put("samples", samples);
            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("sessionId", "b9c0b8");
            payload.put("runId", "pre-fix");
            payload.put("hypothesisId", "A,D,E");
            payload.put("location", "DirectionFindingMatchService.java:match");
            payload.put("message", "batchToDevice lock ids");
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
        if (hopIndex.isPresent()) {
            response.setBatchLabels(hopIndex.getLabels());
        }

        Map<String, String> batchToTarget = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : batchSummary.entrySet()) {
            String mbnm = targetIdIndex.get(e.getValue());
            if (mbnm != null) {
                batchToTarget.put(e.getKey(), mbnm);
            }
        }
        response.setBatchToTargetId(batchToTarget);

        List<DirectionFindingMatchResponse.MatchedDfPoint> points = new ArrayList<>();
        for (Map.Entry<DirectionFindingMatcher.Measurement, String> e : pointMatches.entrySet()) {
            DirectionFindingMatcher.Measurement m = e.getKey();
            DirectionFindingMatchResponse.MatchedDfPoint p = new DirectionFindingMatchResponse.MatchedDfPoint();
            p.setBatchId(m.resolvedBatchId());
            p.setSignalId(m.id);
            p.setTimeMs(m.time);
            p.setBearing(m.bearing);
            p.setDeviceId(e.getValue());
            p.setTargetId(targetIdIndex.get(e.getValue()));
            points.add(p);
        }
        response.setMatchedPoints(points);
        response.setMatchedPointCount(points.size());

        Map<String, List<DirectionFindingMatchResponse.BearingMeasurementRow>> byBatch = new java.util.LinkedHashMap<>();
        for (DirectionFindingMatcher.Measurement m : ms) {
            String batchId = m.resolvedBatchId();
            DirectionFindingMatchResponse.BearingMeasurementRow row =
                    new DirectionFindingMatchResponse.BearingMeasurementRow();
            row.setBatchId(batchId);
            row.setSignalId(m.id);
            row.setTimeMs(m.time);
            row.setBearing(m.bearing);
            row.setRxLon(m.rxLon);
            row.setRxLat(m.rxLat);
            row.setFreqHz(m.freq);
            byBatch.computeIfAbsent(batchId, k -> new ArrayList<>()).add(row);
        }
        for (List<DirectionFindingMatchResponse.BearingMeasurementRow> list : byBatch.values()) {
            list.sort(java.util.Comparator.comparingLong(DirectionFindingMatchResponse.BearingMeasurementRow::getTimeMs));
        }
        response.setMeasurementsByBatch(byBatch);
        return response;
    }

    public DirectionFindingMatchResponse matchFiles(
            MultipartFile bearingFile,
            MultipartFile locateFile,
            DirectionFindingMatcher.MatchConfig config
    ) throws IOException {
        return matchFiles(bearingFile, locateFile, config, null);
    }

    public DirectionFindingMatchResponse matchFiles(
            MultipartFile bearingFile,
            MultipartFile locateFile,
            DirectionFindingMatcher.MatchConfig config,
            String outputDir
    ) throws IOException {
        Path bearingPath = Files.createTempFile("df-bearing-", ".csv");
        Path locatePath = Files.createTempFile("df-locate-", ".csv");
        try {
            copyPart(bearingFile, bearingPath);
            copyPart(locateFile, locatePath);
            ImportFormat bearingFormat = ImportFormatDetector.detectFromPath(bearingPath);
            ImportFormat locateFormat = ImportFormatDetector.detectFromPath(locatePath);
            if (bearingFormat == ImportFormat.EXTERNAL_TARGET_LOCATE
                    && (locateFormat == ImportFormat.PDW_TABLE || locateFormat == ImportFormat.STANDARD)) {
                Path swapped = bearingPath;
                bearingPath = locatePath;
                locatePath = swapped;
            } else if (bearingFormat == ImportFormat.EXTERNAL_TARGET_LOCATE) {
                throw new ImportFormatException(
                        "测向文件「" + bearingFile.getOriginalFilename() + "」表头是定位列（detectTime、longitude、latitude），"
                                + "不是测向列（pl、xhfw、zcsj）。请改选真正的 PrcFf 侦获 CSV。",
                        bearingFormat,
                        bearingFile.getOriginalFilename()
                );
            }
            List<DetectSignal> signals = excelImportService.parse(bearingPath);
            List<ExternalTargetFix> fixes = externalImportService.parse(locatePath);
            return match(signals, fixes, config, outputDir);
        } finally {
            Files.deleteIfExists(bearingPath);
            Files.deleteIfExists(locatePath);
        }
    }

    private static void copyPart(MultipartFile file, Path target) throws IOException {
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
