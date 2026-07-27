package com.pdwfx.signal;

import com.pdwfx.signal.imports.ImportFormat;
import com.pdwfx.signal.imports.ImportFormatDetector;
import com.pdwfx.signal.model.ExternalTargetFix;
import com.pdwfx.signal.service.ExternalTargetLocateImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalTargetLocateImportTest {

    private static final Path SAMPLE = Paths.get("D:/Documents/Code/Java/data/Lq1139_20250703.csv");

    static boolean sampleExists() {
        return Files.isRegularFile(SAMPLE);
    }

    @Test
    @EnabledIf("sampleExists")
    void detectsLeiqingFormat() throws Exception {
        ImportFormat format = ImportFormatDetector.detectFromPath(SAMPLE);
        assertEquals(ImportFormat.EXTERNAL_TARGET_LOCATE, format);
    }

    @Test
    @EnabledIf("sampleExists")
    void parsesLeiqingSample() throws Exception {
        ExternalTargetLocateImportService service = new ExternalTargetLocateImportService();
        List<ExternalTargetFix> fixes = service.parse(SAMPLE);
        assertFalse(fixes.isEmpty());
        ExternalTargetFix first = fixes.get(0);
        assertTrue(first.getLongitude() > 100 && first.getLongitude() < 120);
        assertTrue(first.getLatitude() > 10 && first.getLatitude() < 30);
        assertTrue(first.getDetectTimeMs() > 0);
    }
}
