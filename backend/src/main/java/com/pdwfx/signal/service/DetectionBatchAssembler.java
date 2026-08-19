package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.DetectionBatchResponse;
import com.pdwfx.signal.model.DetectionBatchRow;
import com.pdwfx.signal.model.DetectionBatchSummary;
import com.pdwfx.signal.model.NetworkView;
import com.pdwfx.signal.model.SeriesPoint;
import com.pdwfx.signal.model.TargetView;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 把单网编批结果展开为「每条侦测一行」。
 */
public class DetectionBatchAssembler {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private final DetectionBatchResponse response = new DetectionBatchResponse();
    private int nextBatchId = 1;
    private int unassignedCount;

    public void setAnalysisId(String analysisId) {
        response.setAnalysisId(analysisId);
    }

    public void addNetwork(NetworkView view, List<DetectSignal> bucketSignals, boolean includeUnassigned) {
        if (view == null) {
            return;
        }
        Set<DetectSignal> assigned = Collections.newSetFromMap(new IdentityHashMap<DetectSignal, Boolean>());
        List<TargetView> targets = view.getTargets();
        if (targets != null) {
            for (int i = 0; i < targets.size(); i++) {
                TargetView target = targets.get(i);
                if (target == null) {
                    continue;
                }
                int batchId = nextBatchId++;
                List<DetectSignal> clustered = target.getClusteredSignals();
                int detectCount;
                if (clustered != null && !clustered.isEmpty()) {
                    detectCount = clustered.size();
                    for (int j = 0; j < clustered.size(); j++) {
                        DetectSignal signal = clustered.get(j);
                        if (signal == null) {
                            continue;
                        }
                        assigned.add(signal);
                        response.getDetections().add(rowFromSignal(batchId, view, target, signal));
                    }
                } else {
                    detectCount = emitFromSeries(batchId, view, target);
                }
                response.getBatches().add(summaryOf(batchId, view, target, detectCount));
            }
        }
        if (!includeUnassigned || bucketSignals == null || bucketSignals.isEmpty()) {
            return;
        }
        for (int i = 0; i < bucketSignals.size(); i++) {
            DetectSignal signal = bucketSignals.get(i);
            if (signal == null || assigned.contains(signal)) {
                continue;
            }
            unassignedCount++;
            response.getDetections().add(rowFromSignal(0, view, null, signal));
        }
    }

    public DetectionBatchResponse build(long elapsedMs) {
        response.setBatchCount(response.getBatches().size());
        response.setDetectionCount(response.getDetections().size());
        response.setUnassignedCount(unassignedCount);
        response.setElapsedMs(elapsedMs);
        return response;
    }

    private int emitFromSeries(int batchId, NetworkView view, TargetView target) {
        List<SeriesPoint> azSeries = target.getAzimuthSeries();
        List<SeriesPoint> freqSeries = target.getFreqSeries();
        if (azSeries == null || azSeries.isEmpty()) {
            azSeries = target.getRawAzimuthSeries();
        }
        if (azSeries == null || azSeries.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < azSeries.size(); i++) {
            SeriesPoint az = azSeries.get(i);
            if (az == null) {
                continue;
            }
            DetectionBatchRow row = baseRow(batchId, view, target);
            row.setDetectTimeMs(az.getT());
            row.setDetectTime(formatTime(az.getT(), null));
            row.setAzimuthDeg(az.getV());
            double freq = view.getFreq();
            if (freqSeries != null && i < freqSeries.size() && freqSeries.get(i) != null) {
                freq = freqSeries.get(i).getV();
            }
            row.setFreqMhz(freq);
            response.getDetections().add(row);
        }
        return azSeries.size();
    }

    private static DetectionBatchRow rowFromSignal(
            int batchId,
            NetworkView view,
            TargetView target,
            DetectSignal signal
    ) {
        DetectionBatchRow row = baseRow(batchId, view, target);
        row.setDetectTimeMs(signal.getDetectTimesss());
        row.setDetectTime(formatTime(signal.getDetectTimesss(), signal.getDetectTime()));
        row.setAzimuthDeg(signal.getAzimuth());
        row.setFreqMhz(signal.getFreq());
        row.setSignalId(signal.getId());
        return row;
    }

    private static DetectionBatchRow baseRow(int batchId, NetworkView view, TargetView target) {
        DetectionBatchRow row = new DetectionBatchRow();
        row.setBatchId(batchId);
        row.setNetworkId(view.getNetworkId());
        row.setChannel(view.getCommLinkChannel());
        row.setChannelLabel(view.getCommLinkChannelLabel());
        if (target != null) {
            row.setTargetId(target.getTargetId());
            row.setTargetType(target.getTargetType());
            row.setTargetTypeLabel(typeLabel(target.getTargetType()));
        }
        return row;
    }

    private static DetectionBatchSummary summaryOf(
            int batchId,
            NetworkView view,
            TargetView target,
            int detectCount
    ) {
        DetectionBatchSummary summary = new DetectionBatchSummary();
        summary.setBatchId(batchId);
        summary.setNetworkId(view.getNetworkId());
        summary.setChannel(view.getCommLinkChannel());
        summary.setChannelLabel(view.getCommLinkChannelLabel());
        summary.setFreqMhz(view.getFreq());
        summary.setDetectCount(detectCount);
        if (target != null) {
            summary.setTargetId(target.getTargetId());
            summary.setTargetType(target.getTargetType());
            summary.setTargetTypeLabel(typeLabel(target.getTargetType()));
            if (target.getCommFreqMhzList() != null && target.getCommFreqMhzList().size() == 1) {
                summary.setFreqMhz(target.getCommFreqMhzList().get(0).doubleValue());
            }
        }
        return summary;
    }

    static String typeLabel(String type) {
        if (type == null) {
            return null;
        }
        if ("GROUND".equals(type)) {
            return "地面站";
        }
        if ("AWACS".equals(type)) {
            return "预警机";
        }
        if ("AIR".equals(type)) {
            return "飞机";
        }
        return type;
    }

    static String formatTime(long epochMs, LocalDateTime detectTime) {
        if (detectTime != null) {
            return detectTime.format(TIME_FMT);
        }
        if (epochMs <= 0L) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(TIME_FMT);
    }
}
