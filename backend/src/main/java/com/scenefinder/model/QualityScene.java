package com.scenefinder.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 算法输出的一个「优质场景」：特定时间窗 + 频段范围内的有价值信号模式。
 */
public class QualityScene {

    private final int rank;
    private final SceneType sceneType;
    private final Instant windowStart;
    private final Instant windowEnd;
    private final double freqCenterMhz;
    private final double freqMinMhz;
    private final double freqMaxMhz;
    private final int distinctDeviceCount;
    private final double score;
    private final int trackCount;
    private final double medianSeparationDeg;
    private final double averageSmoothness;
    private final List<Integer> trackIds;
    private final double pollingPeriodSec;
    private final int periodicBurstCount;
    private final double avgBearingsPerBurst;
    private final double periodicityScore;
    private final String annotation;
    /** 点名组询问机轨迹 ID；对称轮询无持续发信源时为 null */
    private final Integer interrogatorTrackId;
    /** 点名组询问机方位（度）；检测阶段提示，建轨后可仍保留 */
    private final Double interrogatorBearingDeg;
    /** 点名应答机 lane 数（不含询问机） */
    private final int responderLaneCount;
    /** 该点名组目标数（询问机 0/1 + 应答机），尚非 D 波道号 */
    private final int channelTargetCount;
    /** 轮询假设归一化质量 Qp（0–1）；连续场景为 0 */
    private final double hypothesisQuality;

    public QualityScene(
            int rank,
            SceneType sceneType,
            Instant windowStart,
            Instant windowEnd,
            double freqCenterMhz,
            double freqMinMhz,
            double freqMaxMhz,
            int distinctDeviceCount,
            double score,
            int trackCount,
            double medianSeparationDeg,
            double averageSmoothness,
            List<Integer> trackIds,
            double pollingPeriodSec,
            int periodicBurstCount,
            double avgBearingsPerBurst,
            double periodicityScore,
            String annotation
    ) {
        this(rank, sceneType, windowStart, windowEnd, freqCenterMhz, freqMinMhz, freqMaxMhz,
                distinctDeviceCount, score, trackCount, medianSeparationDeg, averageSmoothness,
                trackIds, pollingPeriodSec, periodicBurstCount, avgBearingsPerBurst, periodicityScore,
                annotation, null, null, 0, 0, 0.0);
    }

    public QualityScene(
            int rank,
            SceneType sceneType,
            Instant windowStart,
            Instant windowEnd,
            double freqCenterMhz,
            double freqMinMhz,
            double freqMaxMhz,
            int distinctDeviceCount,
            double score,
            int trackCount,
            double medianSeparationDeg,
            double averageSmoothness,
            List<Integer> trackIds,
            double pollingPeriodSec,
            int periodicBurstCount,
            double avgBearingsPerBurst,
            double periodicityScore,
            String annotation,
            Integer interrogatorTrackId,
            Double interrogatorBearingDeg,
            int responderLaneCount,
            int channelTargetCount
    ) {
        this(rank, sceneType, windowStart, windowEnd, freqCenterMhz, freqMinMhz, freqMaxMhz,
                distinctDeviceCount, score, trackCount, medianSeparationDeg, averageSmoothness,
                trackIds, pollingPeriodSec, periodicBurstCount, avgBearingsPerBurst, periodicityScore,
                annotation, interrogatorTrackId, interrogatorBearingDeg, responderLaneCount,
                channelTargetCount, 0.0);
    }

    public QualityScene(
            int rank,
            SceneType sceneType,
            Instant windowStart,
            Instant windowEnd,
            double freqCenterMhz,
            double freqMinMhz,
            double freqMaxMhz,
            int distinctDeviceCount,
            double score,
            int trackCount,
            double medianSeparationDeg,
            double averageSmoothness,
            List<Integer> trackIds,
            double pollingPeriodSec,
            int periodicBurstCount,
            double avgBearingsPerBurst,
            double periodicityScore,
            String annotation,
            Integer interrogatorTrackId,
            Double interrogatorBearingDeg,
            int responderLaneCount,
            int channelTargetCount,
            double hypothesisQuality
    ) {
        this.rank = rank;
        this.sceneType = sceneType;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.freqCenterMhz = freqCenterMhz;
        this.freqMinMhz = freqMinMhz;
        this.freqMaxMhz = freqMaxMhz;
        this.distinctDeviceCount = distinctDeviceCount;
        this.score = score;
        this.trackCount = trackCount;
        this.medianSeparationDeg = medianSeparationDeg;
        this.averageSmoothness = averageSmoothness;
        if (trackIds == null) {
            this.trackIds = Collections.<Integer>emptyList();
        } else {
            this.trackIds = trackIds;
        }
        this.pollingPeriodSec = pollingPeriodSec;
        this.periodicBurstCount = periodicBurstCount;
        this.avgBearingsPerBurst = avgBearingsPerBurst;
        this.periodicityScore = periodicityScore;
        this.annotation = annotation;
        this.interrogatorTrackId = interrogatorTrackId;
        this.interrogatorBearingDeg = interrogatorBearingDeg;
        this.responderLaneCount = responderLaneCount;
        this.channelTargetCount = channelTargetCount;
        this.hypothesisQuality = hypothesisQuality;
    }

    public static QualityScene trackScene(
            int rank,
            Instant windowStart,
            Instant windowEnd,
            double freqCenterMhz,
            double freqMinMhz,
            double freqMaxMhz,
            int distinctDeviceCount,
            double score,
            int trackCount,
            double medianSeparationDeg,
            double averageSmoothness,
            List<Integer> trackIds
    ) {
        return new QualityScene(
                rank,
                SceneType.TRACK_CONTINUOUS,
                windowStart,
                windowEnd,
                freqCenterMhz,
                freqMinMhz,
                freqMaxMhz,
                distinctDeviceCount,
                score,
                trackCount,
                medianSeparationDeg,
                averageSmoothness,
                trackIds,
                0,
                0,
                0,
                0,
                "多条方位连续变化轨迹并存，适合观察目标运动与并行目标"
        );
    }

    public static QualityScene pollingScene(
            int rank,
            Instant windowStart,
            Instant windowEnd,
            double freqCenterMhz,
            double freqMinMhz,
            double freqMaxMhz,
            double score,
            int periodicBurstCount,
            double medianBearingSpanDeg,
            double pollingPeriodSec,
            double avgBearingsPerBurst,
            int typicalBearingsPerBurst,
            double periodicityScore
    ) {
        String note = String.format(
                Locale.CHINA,
                "多设备轮询通信：周期 %s，每轮 burst 内约 %d 台设备并发发信"
                        + "（短时窗内多测向点、近距方位不合并为单设备连发）；可通过轮询规律分辨",
                PeriodFormatUtils.formatSecMsUs(pollingPeriodSec),
                typicalBearingsPerBurst
        );
        return new QualityScene(
                rank,
                SceneType.MULTI_DEVICE_POLLING,
                windowStart,
                windowEnd,
                freqCenterMhz,
                freqMinMhz,
                freqMaxMhz,
                typicalBearingsPerBurst,
                score,
                periodicBurstCount,
                medianBearingSpanDeg,
                periodicityScore,
                Collections.<Integer>emptyList(),
                pollingPeriodSec,
                periodicBurstCount,
                avgBearingsPerBurst,
                periodicityScore,
                note
        );
    }

    public int getRank() {
        return rank;
    }

    public SceneType getSceneType() {
        return sceneType;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public double getFreqCenterMhz() {
        return freqCenterMhz;
    }

    public double getFreqMinMhz() {
        return freqMinMhz;
    }

    public double getFreqMaxMhz() {
        return freqMaxMhz;
    }

    public int getDistinctDeviceCount() {
        return distinctDeviceCount;
    }

    public double getScore() {
        return score;
    }

    public int getTrackCount() {
        return trackCount;
    }

    public double getMedianSeparationDeg() {
        return medianSeparationDeg;
    }

    public double getAverageSmoothness() {
        return averageSmoothness;
    }

    public List<Integer> getTrackIds() {
        return trackIds;
    }

    public double getPollingPeriodSec() {
        return pollingPeriodSec;
    }

    public int getPeriodicBurstCount() {
        return periodicBurstCount;
    }

    public double getAvgBearingsPerBurst() {
        return avgBearingsPerBurst;
    }

    public double getPeriodicityScore() {
        return periodicityScore;
    }

    public String getAnnotation() {
        return annotation;
    }

    public Integer getInterrogatorTrackId() {
        return interrogatorTrackId;
    }

    public Double getInterrogatorBearingDeg() {
        return interrogatorBearingDeg;
    }

    public int getResponderLaneCount() {
        return responderLaneCount;
    }

    public int getChannelTargetCount() {
        return channelTargetCount;
    }

    public double getHypothesisQuality() {
        return hypothesisQuality;
    }

    public QualityScene withHypothesisQuality(double quality) {
        return new QualityScene(
                rank,
                sceneType,
                windowStart,
                windowEnd,
                freqCenterMhz,
                freqMinMhz,
                freqMaxMhz,
                distinctDeviceCount,
                score,
                trackCount,
                medianSeparationDeg,
                averageSmoothness,
                trackIds,
                pollingPeriodSec,
                periodicBurstCount,
                avgBearingsPerBurst,
                periodicityScore,
                annotation,
                interrogatorTrackId,
                interrogatorBearingDeg,
                responderLaneCount,
                channelTargetCount,
                quality
        );
    }

    public QualityScene withRank(int newRank) {
        return copy(newRank, sceneType, windowStart, windowEnd, trackIds, annotation,
                interrogatorTrackId, interrogatorBearingDeg, responderLaneCount, channelTargetCount);
    }

    /** 复制本场景并替换时间窗（全段窗模式下对齐整批检测起止）。 */
    public QualityScene withWindow(Instant newStart, Instant newEnd) {
        Instant start = newStart != null ? newStart : windowStart;
        Instant end = newEnd != null ? newEnd : windowEnd;
        return copy(rank, sceneType, start, end, trackIds, annotation,
                interrogatorTrackId, interrogatorBearingDeg, responderLaneCount, channelTargetCount);
    }

    /** 复制本场景并替换 lane / 轨迹 ID 列表（轮询建轨后写入）。 */
    public QualityScene withTrackIds(List<Integer> trackIds) {
        return copy(rank, sceneType, windowStart, windowEnd, trackIds, annotation,
                interrogatorTrackId, interrogatorBearingDeg, responderLaneCount, channelTargetCount);
    }

    /** 轮询场景附带 lane 轨迹 ID 与更新后的说明。 */
    public QualityScene withPollingLaneTracks(List<Integer> laneTrackIds, int laneCount, int alignedRounds) {
        String note = annotation;
        if (laneTrackIds != null && !laneTrackIds.isEmpty()) {
            note = String.format(
                    Locale.CHINA,
                    "多设备轮询通信：周期 %s，每轮约 %d 台设备；已按轮询槽位建轨 %d 条"
                            + "（对齐 %d 轮，仅相邻轮次同槽位相连，避免近距并发误判为单目标运动）",
                    PeriodFormatUtils.formatSecMsUs(pollingPeriodSec),
                    distinctDeviceCount,
                    laneCount,
                    alignedRounds
            );
        }
        return copy(
                rank,
                sceneType,
                windowStart,
                windowEnd,
                laneTrackIds,
                note,
                interrogatorTrackId,
                interrogatorBearingDeg,
                responderLaneCount,
                laneCount > 0 ? laneCount : distinctDeviceCount
        ).withDeviceCount(laneCount > 0 ? laneCount : distinctDeviceCount);
    }

    public QualityScene withCallsignMeta(
            Integer newInterrogatorTrackId,
            Double newInterrogatorBearingDeg,
            int newResponderLaneCount,
            int newChannelTargetCount,
            String note
    ) {
        String text = note != null ? note : annotation;
        return copy(rank, sceneType, windowStart, windowEnd, trackIds, text,
                newInterrogatorTrackId, newInterrogatorBearingDeg, newResponderLaneCount, newChannelTargetCount)
                .withDeviceCount(newChannelTargetCount > 0 ? newChannelTargetCount : distinctDeviceCount);
    }

    private QualityScene withDeviceCount(int devices) {
        return new QualityScene(
                rank,
                sceneType,
                windowStart,
                windowEnd,
                freqCenterMhz,
                freqMinMhz,
                freqMaxMhz,
                devices,
                score,
                trackCount,
                medianSeparationDeg,
                averageSmoothness,
                trackIds,
                pollingPeriodSec,
                periodicBurstCount,
                avgBearingsPerBurst,
                periodicityScore,
                annotation,
                interrogatorTrackId,
                interrogatorBearingDeg,
                responderLaneCount,
                channelTargetCount > 0 ? channelTargetCount : devices,
                hypothesisQuality
        );
    }

    private QualityScene copy(
            int newRank,
            SceneType newType,
            Instant start,
            Instant end,
            List<Integer> newTrackIds,
            String note,
            Integer newInterrogatorTrackId,
            Double newInterrogatorBearingDeg,
            int newResponderLaneCount,
            int newChannelTargetCount
    ) {
        return new QualityScene(
                newRank,
                newType,
                start,
                end,
                freqCenterMhz,
                freqMinMhz,
                freqMaxMhz,
                distinctDeviceCount,
                score,
                trackCount,
                medianSeparationDeg,
                averageSmoothness,
                newTrackIds,
                pollingPeriodSec,
                periodicBurstCount,
                avgBearingsPerBurst,
                periodicityScore,
                note,
                newInterrogatorTrackId,
                newInterrogatorBearingDeg,
                newResponderLaneCount,
                newChannelTargetCount,
                hypothesisQuality
        );
    }
}
