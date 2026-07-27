package com.scenefinder;

import com.scenefinder.config.SceneFinderProperties;
import com.scenefinder.model.BearingTrack;
import com.scenefinder.model.DetectionPoint;
import com.scenefinder.model.FrequencyBandUtils;
import com.scenefinder.model.QualityScene;
import com.scenefinder.model.SceneType;
import com.scenefinder.service.AnalyzeOptions;
import com.scenefinder.service.CsvDetectionReader;
import com.scenefinder.service.MultiDevicePollingDetectorService;
import com.scenefinder.service.SceneFusionService;
import com.scenefinder.service.SceneScorerService;
import com.scenefinder.service.TrackBuilderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = com.pdwfx.signal.SignalAnalysisApplication.class)
class SceneRank240DiagnosticTest {

    private static final Path CSV =
            Paths.get("d:/Documents/Code/Java/data/PDWSrcData20251107115627/PrcFf1139.csv");

    private static final double TARGET_FREQ = 240.55;
    private static final double FREQ_TOL = 0.15;

    @Autowired
    private CsvDetectionReader csvDetectionReader;
    @Autowired
    private TrackBuilderService trackBuilderService;
    @Autowired
    private SceneScorerService sceneScorerService;
    @Autowired
    private SceneFusionService sceneFusionService;
    @Autowired
    private MultiDevicePollingDetectorService pollingDetectorService;
    @Autowired
    private SceneFinderProperties properties;

    @Test
    void printRankFor240MhzWideBand() throws Exception {
        assertTrue(Files.exists(CSV), "CSV not found: " + CSV);

        SceneFinderProperties props = new SceneFinderProperties();
        props.copyFrom(properties);
        props.setFreqMin(200);
        props.setFreqMax(600);
        props.setWindowSeconds(120);
        props.setWindowStepSeconds(30);
        props.setMinTracksInScene(1);
        props.setTopKTrackScenes(40);
        props.setTopKPollingScenes(20);
        props.setFreqClusterGapMhz(0.01);
        props.setSceneFreqBandGapMhz(0.01);

        List<DetectionPoint> points = csvDetectionReader.read(CSV, props.getFreqMin(), props.getFreqMax());
        List<BearingTrack> tracks = trackBuilderService.buildTracks(points, props);
        List<QualityScene> trackScenes = sceneScorerService.findTopScenes(tracks, props);
        List<QualityScene> pollingScenes = pollingDetectorService.findPollingScenes(points, props);
        List<QualityScene> fused = sceneFusionService.fuse(trackScenes, pollingScenes, props);

        List<BearingTrack> near240 = tracks.stream()
                .filter(t -> Math.abs(FrequencyBandUtils.dominantFrequencyMhz(t) - TARGET_FREQ) <= FREQ_TOL)
                .sorted(Comparator.comparingInt(BearingTrack::getId))
                .collect(Collectors.toList());

        System.out.println("=== 数据概览 (200-600 MHz) ===");
        System.out.println("检测点: " + points.size());
        System.out.println("确认轨迹: " + tracks.size());
        System.out.println("连续轨迹候选窗: " + trackScenes.size());
        System.out.println("240.55±" + FREQ_TOL + " MHz 轨迹数: " + near240.size());
        for (BearingTrack t : near240) {
            System.out.printf(
                    "  track#%d domFreq=%.3f pts=%d dur=%.1fs smooth=%.3f%n",
                    t.getId(),
                    FrequencyBandUtils.dominantFrequencyMhz(t),
                    t.getObservations().size(),
                    t.durationSeconds(),
                    t.smoothnessScore()
            );
        }

        printFreqRank("全部连续轨迹候选（按 score 排序）", trackScenes, 15);
        printTargetRank("全部连续轨迹候选", trackScenes);

        List<QualityScene> trackOnly = trackScenes.stream()
                .filter(s -> s.getSceneType() == SceneType.TRACK_CONTINUOUS)
                .collect(Collectors.toList());
        printTargetRank("连续轨迹候选（fuse 前）", trackOnly);
        printPure240Rank(trackScenes);

        System.out.println("\n=== 轮询候选: " + pollingScenes.size() + " 条 ===");

        System.out.println("\n=== 最终 fuse 输出（轮询+连续，全类型排序）共 " + fused.size() + " 条 ===");
        for (QualityScene s : fused) {
            String mark = isNear240(s) || containsTrack140(s) ? " <-- 含240.55" : "";
            System.out.printf(
                    "  rank#%d [%s] score=%.3f freq=%.3f MHz tracks=%d%s%n",
                    s.getRank(),
                    s.getSceneType(),
                    s.getScore(),
                    s.getFreqCenterMhz(),
                    s.getTrackCount(),
                    mark
            );
        }

        List<QualityScene> fusedTracks = fused.stream()
                .filter(s -> s.getSceneType() == SceneType.TRACK_CONTINUOUS)
                .collect(Collectors.toList());
        System.out.println("\n=== fuse 后连续轨迹共 " + fusedTracks.size() + " 条 ===");
        for (QualityScene s : fusedTracks) {
            System.out.printf(
                    "  rank#%d score=%.3f freq=%.3f MHz tracks=%d %s — %s%n",
                    s.getRank(),
                    s.getScore(),
                    s.getFreqCenterMhz(),
                    s.getTrackCount(),
                    s.getWindowStart(),
                    s.getWindowEnd()
            );
        }
        printTargetRank("fuse 后连续轨迹", fusedTracks);

        // 窄频段对照
        SceneFinderProperties narrow = new SceneFinderProperties();
        narrow.copyFrom(props);
        narrow.setFreqMin(TARGET_FREQ);
        narrow.setFreqMax(TARGET_FREQ);
        List<DetectionPoint> narrowPts = csvDetectionReader.read(CSV, narrow.getFreqMin(), narrow.getFreqMax());
        List<BearingTrack> narrowTracks = trackBuilderService.buildTracks(narrowPts, narrow);
        List<QualityScene> narrowScenes = sceneScorerService.findTopScenes(narrowTracks, narrow);
        System.out.println("\n=== 对照：仅 240.55 MHz ===");
        System.out.println("检测点: " + narrowPts.size() + " 轨迹: " + narrowTracks.size()
                + " 候选窗: " + narrowScenes.size());
        for (int i = 0; i < Math.min(5, narrowScenes.size()); i++) {
            QualityScene s = narrowScenes.get(i);
            System.out.printf(
                    "  #%d score=%.3f freq=%.3f tracks=%d%n",
                    i + 1, s.getScore(), s.getFreqCenterMhz(), s.getTrackCount()
            );
        }
    }

    private void printTargetRank(String label, List<QualityScene> scenes) {
        int rank = 0;
        int bestRank = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        QualityScene best = null;
        for (QualityScene s : scenes) {
            rank++;
            if (!isNear240(s)) {
                continue;
            }
            if (bestRank < 0) {
                bestRank = rank;
                bestScore = s.getScore();
                best = s;
            }
        }
        System.out.println("\n--- " + label + " 中 240.55 MHz 场景 ---");
        if (bestRank < 0) {
            System.out.println("  未找到 freqCenter 在 " + TARGET_FREQ + "±" + FREQ_TOL + " MHz 的候选");
            return;
        }
        System.out.printf(
                "  最佳排名: 第 %d / %d 名, score=%.3f, freq=%.3f MHz, tracks=%d, sep=%.2f°%n",
                bestRank,
                scenes.size(),
                bestScore,
                best.getFreqCenterMhz(),
                best.getTrackCount(),
                best.getMedianSeparationDeg()
        );
        if (best != null) {
            System.out.printf(
                    "  时间窗: %s — %s, trackIds=%s%n",
                    best.getWindowStart(),
                    best.getWindowEnd(),
                    best.getTrackIds()
            );
        }
        int count = 0;
        for (QualityScene s : scenes) {
            if (isNear240(s)) {
                count++;
            }
        }
        System.out.println("  240.55 附近候选总数: " + count);
    }

    private void printFreqRank(String label, List<QualityScene> scenes, int topN) {
        System.out.println("\n=== " + label + "（前 " + topN + "）===");
        for (int i = 0; i < Math.min(topN, scenes.size()); i++) {
            QualityScene s = scenes.get(i);
            String mark = isNear240(s) ? " <-- 240.55" : "";
            System.out.printf(
                    "  #%d score=%.3f freq=%.3f MHz tracks=%d%s%n",
                    i + 1,
                    s.getScore(),
                    s.getFreqCenterMhz(),
                    s.getTrackCount(),
                    mark
            );
        }
    }

    private boolean isNear240(QualityScene s) {
        return Math.abs(s.getFreqCenterMhz() - TARGET_FREQ) <= FREQ_TOL
                || (s.getFreqMinMhz() <= TARGET_FREQ + FREQ_TOL && s.getFreqMaxMhz() >= TARGET_FREQ - FREQ_TOL);
    }

    private boolean containsTrack140(QualityScene s) {
        return s.getTrackIds() != null && s.getTrackIds().contains(140);
    }

    /** 仅含 track#140/#142、中心频点 240.55 的「纯」场景排名 */
    private void printPure240Rank(List<QualityScene> scenes) {
        int rank = 0;
        int bestPure = -1;
        QualityScene best = null;
        for (QualityScene s : scenes) {
            rank++;
            if (Math.abs(s.getFreqCenterMhz() - TARGET_FREQ) > 0.02) {
                continue;
            }
            List<Integer> ids = s.getTrackIds();
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            boolean only140142 = ids.stream().allMatch(id -> id == 140 || id == 142);
            if (!only140142) {
                continue;
            }
            if (bestPure < 0) {
                bestPure = rank;
                best = s;
            }
        }
        System.out.println("\n--- 纯 240.55 MHz 场景（仅 track#140/#142）---");
        if (bestPure < 0) {
            System.out.println("  无此类候选（宽频段下同窗混入其他频段轨迹）");
            return;
        }
        System.out.printf(
                "  最佳排名: 第 %d / %d, score=%.3f, tracks=%s%n",
                bestPure,
                scenes.size(),
                best.getScore(),
                best.getTrackIds()
        );
    }
}
