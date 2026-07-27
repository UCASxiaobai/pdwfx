package com.pdwfx.signal.service;

import com.pdwfx.signal.model.DetectSignal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExcelImportServiceFilterTest {

    @Test
    void pdwCsvSkipsInvalidNSignalTimeRows(@TempDir Path dir) throws Exception {
        Path csv = dir.resolve("sample.csv");
        String content = ""
                + "pl,xhfw,zcsj,nSignalTime\n"
                + "439,180,2025-07-03 10:52:02,1321\n"
                + "439,181,2025-07-03 10:52:03,914\n"
                + "439,182,2025-07-03 10:52:04,1781\n";
        Files.write(csv, content.getBytes(StandardCharsets.UTF_8));

        ExcelImportService service = new ExcelImportService();
        List<DetectSignal> signals = service.parse(csv);

        assertEquals(2, signals.size());
        assertEquals(180.0, signals.get(0).getAzimuth(), 0.001);
        assertEquals(182.0, signals.get(1).getAzimuth(), 0.001);
    }
}
