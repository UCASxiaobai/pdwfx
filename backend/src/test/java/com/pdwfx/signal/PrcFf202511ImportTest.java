package com.pdwfx.signal;

import com.pdwfx.signal.imports.ImportFormat;
import com.pdwfx.signal.imports.ImportFormatDetector;
import com.pdwfx.signal.model.DetectSignal;
import com.pdwfx.signal.service.ExcelImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrcFf202511ImportTest {

    private static final Path SAMPLE = Paths.get("D:/Documents/Code/Java/data/PDWSrcData20251107115627/PrcFf1139.csv");

    static boolean sampleExists() {
        return Files.isRegularFile(SAMPLE);
    }

    @Test
    @EnabledIf("sampleExists")
    void detectsPdwTableFormat() throws Exception {
        ImportFormat format = ImportFormatDetector.detectFromPath(SAMPLE);
        assertEquals(ImportFormat.PDW_TABLE, format);
    }

    @Test
    @EnabledIf("sampleExists")
    void parsesDwellFromLegacyColumnWhenCanonicalEmpty() throws Exception {
        ExcelImportService service = new ExcelImportService();
        List<DetectSignal> signals = service.parse(SAMPLE);
        assertFalse(signals.isEmpty());
        DetectSignal withDwell = signals.stream()
                .filter(s -> s.getSignalDwellMs() > 0d)
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected at least one signal with dwell > 0"));
        assertTrue(withDwell.getSignalDwellMs() > 0d);
    }

    @Test
    @EnabledIf("sampleExists")
    void importRowCountAlignsWithWideBandSceneReader() throws Exception {
        ExcelImportService importService = new ExcelImportService();
        com.scenefinder.service.CsvDetectionReader reader = new com.scenefinder.service.CsvDetectionReader();
        int imported = importService.parse(SAMPLE).size();
        int screened = reader.read(SAMPLE, 0.0, 999_999.0).size();
        assertEquals(screened, imported);
    }
}
