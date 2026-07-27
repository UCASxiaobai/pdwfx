package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.DirectionFindingMatchResponse;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.scenefinder.service.DirectionFindingMatcher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
        DirectionFindingMatcher.MatchConfig cfg = config != null ? config : new DirectionFindingMatcher.MatchConfig();
        List<DirectionFindingMatcher.Measurement> ms = DirectionFindingMatchBridge.buildMeasurements(measurements);
        Map<String, DirectionFindingMatcher.DeviceTrajectory> trajs =
                DirectionFindingMatchBridge.buildTrajectories(trajectories);
        Map<String, String> targetIdIndex = DirectionFindingMatchBridge.buildTargetIdIndex(trajectories);

        Map<DirectionFindingMatcher.Measurement, String> pointMatches =
                DirectionFindingMatcher.match(ms, trajs, cfg);
        Map<String, String> batchSummary =
                DirectionFindingMatcher.matchBatchSummary(ms, trajs, cfg);

        DirectionFindingMatchResponse response = new DirectionFindingMatchResponse();
        response.setConfig(cfg);
        response.setMeasurementCount(ms.size());
        response.setTrajectoryDeviceCount(trajs.size());
        response.setBatchToDevice(batchSummary);

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
        List<DetectSignal> signals = excelImportService.parse(bearingFile);
        List<ExternalTargetFix> fixes = externalImportService.parse(locateFile);
        return match(signals, fixes, config);
    }
}
