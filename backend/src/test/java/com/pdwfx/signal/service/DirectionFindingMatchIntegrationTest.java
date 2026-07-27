package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.model.DirectionFindingMatchResponse;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.scenefinder.service.DirectionFindingMatcher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用 PrcFf1139 + Lq1139_df_match_test 验证测向-定位关联。
 * 数据目录：{@code ../../data/PDWSrcData20250703105202}（相对 backend 模块）。
 */
class DirectionFindingMatchIntegrationTest {

    private static Path dataDir;
    private static ExcelImportService excelImportService;
    private static ExternalTargetLocateImportService externalImportService;
    private static DirectionFindingMatchService matchService;

    @BeforeAll
    static void init() {
        dataDir = Paths.get("..", "..", "data", "PDWSrcData20250703105202").normalize();
        excelImportService = new ExcelImportService();
        externalImportService = new ExternalTargetLocateImportService();
        matchService = new DirectionFindingMatchService(excelImportService, externalImportService);
    }

    static boolean dataFilesPresent() {
        Path dir = Paths.get("..", "..", "data", "PDWSrcData20250703105202").normalize();
        return Files.isRegularFile(dir.resolve("PrcFf1139.csv"))
                && Files.isRegularFile(dir.resolve("Lq1139_df_match_test.csv"));
    }

    @Test
    @EnabledIf("dataFilesPresent")
    void matchTestDataLocksExpectedTrajectories() throws IOException {
        List<DetectSignal> signals = excelImportService.parse(dataDir.resolve("PrcFf1139.csv"));
        List<ExternalTargetFix> fixes =
                externalImportService.parse(dataDir.resolve("Lq1139_df_match_test.csv"));

        DirectionFindingMatchResponse response = matchService.match(signals, fixes, null);
        Map<String, String> batchToDevice = response.getBatchToDevice();

        assertTrue(
                batchToDevice.containsKey("1139@589.0000"),
                "589MHz batch should lock; batches=" + batchToDevice.keySet()
        );
        assertEquals("匹配验证-589MHz-149度", batchToDevice.get("1139@589.0000"));
        assertFalse(
                "干扰-589-持续不足10秒".equals(batchToDevice.get("1139@589.0000")),
                "589MHz batch must not lock to short sustain decoy"
        );

        assertTrue(
                batchToDevice.containsKey("1139@439.0500"),
                "439.05MHz batch should lock; batches=" + batchToDevice.keySet()
        );
        assertEquals("匹配验证-439MHz-181度", batchToDevice.get("1139@439.0500"));
        assertFalse(
                "干扰-439_05-超距520km".equals(batchToDevice.get("1139@439.0500")),
                "439MHz batch must not lock to over-distance decoy"
        );

        assertEquals("匹配验证-450MHz", batchToDevice.get("1139@450.9250"));
        assertEquals("匹配验证-287MHz", batchToDevice.get("1139@287.6250"));
        assertEquals("匹配验证-240MHz", batchToDevice.get("1139@240.0000"));

        assertFalse(
                batchToDevice.containsValue("干扰-589-持续不足10秒"),
                "No batch should lock to short-sustain decoy"
        );
        assertFalse(
                batchToDevice.containsValue("背景-全时段不可匹配"),
                "No batch should lock to background noise"
        );

        assertTrue(response.getMatchedPointCount() > 0, "Should have matched DF points in time window");
    }

    @Test
    @EnabledIf("dataFilesPresent")
    void bridgeBuildsFreqBatchIds() throws IOException {
        List<DetectSignal> signals = excelImportService.parse(dataDir.resolve("PrcFf1139.csv"));
        List<DirectionFindingMatcher.Measurement> ms = DirectionFindingMatchBridge.buildMeasurements(signals);
        long count589 = ms.stream().filter(m -> "1139@589.0000".equals(m.resolvedBatchId())).count();
        assertTrue(count589 >= 30, "PrcFf should contain enough 589MHz measurements");
    }
}
