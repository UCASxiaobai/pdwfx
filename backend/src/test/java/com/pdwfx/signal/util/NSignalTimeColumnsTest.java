package com.pdwfx.signal.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NSignalTimeColumnsTest {

    @Test
    void distinguishesThreeSignalTimeColumns() {
        assertTrue(NSignalTimeColumns.isLegacyDwellColumn("nSingnalTime"));
        assertTrue(NSignalTimeColumns.isSignalStartColumn("nSignalStartTime"));
        assertTrue(NSignalTimeColumns.isCanonicalDwellColumn("nSignalTime"));
        assertFalse(NSignalTimeColumns.isSignalStartColumn("nSignalTime"));
    }

    @Test
    void readRaw10usPrefersCanonicalOverLegacy() throws Exception {
        String csv = "pl,nSingnalTime,nSignalStartTime,nSignalTime\n"
                + "100,800,100,900\n";
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csv))) {
            long raw = NSignalTimeColumns.readRaw10us(parser.iterator().next());
            assertEquals(900L, raw);
        }
    }

    @Test
    void readRaw10usFallsBackToLegacyWhenCanonicalEmpty() throws Exception {
        String csv = "pl,nSingnalTime,nSignalStartTime,nSignalTime\n"
                + "100,914,,\n";
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csv))) {
            long raw = NSignalTimeColumns.readRaw10us(parser.iterator().next());
            assertEquals(914L, raw);
        }
    }

    @Test
    void toDwellMsConverts10usUnits() {
        assertEquals(0d, NSignalTimeColumns.toDwellMs(0L));
        assertEquals(9.14d, NSignalTimeColumns.toDwellMs(914L), 0.001);
    }

    @Test
    void aliasIntoHeaderIndexUsesCanonicalWhenPresent() {
        Map<String, Integer> map = new HashMap<>();
        map.put("NSINGNALTIME", 7);
        map.put("NSIGNALSTARTTIME", 8);
        map.put("NSIGNALTIME", 9);
        NSignalTimeColumns.aliasIntoHeaderIndex(map);
        assertEquals(9, map.get("NSIGNALTIME").intValue());
    }

    @Test
    void aliasIntoHeaderIndexFallsBackToLegacy() {
        Map<String, Integer> map = new HashMap<>();
        map.put("NSINGNALTIME", 7);
        NSignalTimeColumns.aliasIntoHeaderIndex(map);
        assertEquals(7, map.get("NSIGNALTIME").intValue());
    }

    @Test
    void isValidSceneInputMatchesFfType2Windows() {
        assertFalse(NSignalTimeColumns.isValidSceneInput(1300L));
        assertFalse(NSignalTimeColumns.isValidSceneInput(7100L));
        assertFalse(NSignalTimeColumns.isValidSceneInput(914L));
        assertTrue(NSignalTimeColumns.isValidSceneInput(1321L));
        assertTrue(NSignalTimeColumns.isValidSceneInput(1781L));
        assertTrue(NSignalTimeColumns.isValidSceneInput(2834L));
        assertTrue(NSignalTimeColumns.isValidSceneInput(3789L));
        assertTrue(NSignalTimeColumns.isValidSceneInput(1803L));
    }

    @Test
    void isValidSceneInputFromCsvRecord() throws Exception {
        String csv = "pl,xhfw,zcsj,nSingnalTime,nSignalTime\n"
                + "100,90,2025-11-07T11:56:27.157,1803,\n"
                + "100,91,2025-11-07T11:56:27.157,914,\n";
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csv))) {
            java.util.Iterator<org.apache.commons.csv.CSVRecord> it = parser.iterator();
            assertTrue(NSignalTimeColumns.isValidSceneInput(it.next()));
            assertFalse(NSignalTimeColumns.isValidSceneInput(it.next()));
        }
    }
}
