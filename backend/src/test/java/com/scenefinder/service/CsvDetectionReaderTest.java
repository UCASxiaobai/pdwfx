package com.scenefinder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvDetectionReaderTest {

    private static final Path SAMPLE =
            Paths.get("D:/Documents/Code/Java/data/PDWSrcData20251107115627/PrcFf1139.csv");

    static boolean sampleExists() {
        return Files.isRegularFile(SAMPLE);
    }

    @Test
    @EnabledIf("sampleExists")
    void readFiltersByValidNSignalTime() throws Exception {
        CsvDetectionReader reader = new CsvDetectionReader();
        List<com.scenefinder.model.DetectionPoint> points = reader.read(SAMPLE, 200.0, 600.0);
        assertFalse(points.isEmpty());
        assertTrue(points.size() < 154_947);
        assertTrue(points.size() > 30_000);
    }
}
