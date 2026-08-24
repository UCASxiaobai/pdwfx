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

    @Test
    void detectsWhenInvalidByteFollowsUtf8CharsetSample() throws Exception {
        Path tmp = Files.createTempFile("lq-malformed-", ".csv");
        try {
            String header = "zbxh,headers,detectTime,longitude,latitude,dwsx,mbnm,mbmc\n";
            String row = "1139,\"雷情导入截获时间经度纬度\",2025-07-03T08:43:18.070,107.77,22.21,x,MB1,n\n";
            StringBuilder text = new StringBuilder(header);
            while (text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 7000) {
                text.append(row);
            }
            byte[] prefix = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] tail = row.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] bytes = new byte[prefix.length + 2 + tail.length];
            System.arraycopy(prefix, 0, bytes, 0, prefix.length);
            bytes[prefix.length] = (byte) 0xFF;
            bytes[prefix.length + 1] = '\n';
            System.arraycopy(tail, 0, bytes, prefix.length + 2, tail.length);
            Files.write(tmp, bytes);

            ImportFormat format = ImportFormatDetector.detectFromPath(tmp);
            assertEquals(ImportFormat.EXTERNAL_TARGET_LOCATE, format);
            ExternalTargetLocateImportService service = new ExternalTargetLocateImportService();
            List<ExternalTargetFix> fixes = service.parse(tmp);
            assertFalse(fixes.isEmpty());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void parsesExcelSlashDateTimeFromLocateHeaders() throws Exception {
        Path tmp = Files.createTempFile("lq-excel-date-", ".csv");
        try {
            String csv = "zbxh,headers,drt,detectTime,longitude,latitude,dwsx,dtlxmc,mbnm,mbmc,jxh\n"
                    + "1139,,,2025/7/3 8:43,107.7793884,22.21476936,我,,MB_1,n,\n";
            Files.write(tmp, csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(ImportFormat.EXTERNAL_TARGET_LOCATE, ImportFormatDetector.detectFromPath(tmp));
            List<ExternalTargetFix> fixes = new ExternalTargetLocateImportService().parse(tmp);
            assertEquals(1, fixes.size());
            assertTrue(fixes.get(0).getDetectTimeMs() > 0);
            assertEquals(107.7793884, fixes.get(0).getLongitude(), 1e-6);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void detectsPrcFfAsPdwNotLocate() throws Exception {
        Path prc = Paths.get("D:/Documents/Code/Java/pdwfx/PrcFf1139.csv");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(prc));
        ImportFormat format = ImportFormatDetector.detectFromPath(prc);
        assertEquals(ImportFormat.PDW_TABLE, format, "PrcFf must not be classified as locate");
    }
}
