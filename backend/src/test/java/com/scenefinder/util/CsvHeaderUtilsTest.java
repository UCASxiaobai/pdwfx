package com.scenefinder.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvHeaderUtilsTest {

    @Test
    void normalizeHeaderRowInsertsThreeColumnBlockAfterTzys() {
        List<String> source = Arrays.asList(
                "zbxh", "pl", "xhdk", "tzys", "nSingnalTime", "nSignalStartTime", "nSignalTime",
                "xhfd", "xhfw", "zcsj"
        );
        List<String> normalized = CsvHeaderUtils.normalizeHeaderRow(source);
        int tzys = normalized.indexOf("tzys");
        assertEquals("nSingnalTime", normalized.get(tzys + 1));
        assertEquals("nSignalStartTime", normalized.get(tzys + 2));
        assertEquals("nSignalTime", normalized.get(tzys + 3));
        assertEquals("xhfd", normalized.get(tzys + 4));
    }

    @Test
    void mapRecordToRowAlignsValuesWithNormalizedHeader() throws Exception {
        String csv = "zbxh,pl,tzys,nSingnalTime,nSignalStartTime,nSignalTime,xhfd,xhfw,zcsj\n"
                + "1139,366.2,,914,,,79,102.7,2025-11-07T11:56:27.157\n";
        List<String> header = CsvHeaderUtils.normalizeHeaderRow(Arrays.asList(
                "zbxh", "pl", "tzys", "nSingnalTime", "nSignalStartTime", "nSignalTime",
                "xhfd", "xhfw", "zcsj"
        ));
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .build()
                .parse(new StringReader(csv))) {
            List<String> row = CsvHeaderUtils.mapRecordToRow(parser.iterator().next(), header);
            assertEquals(header.size(), row.size());
            assertEquals("914", row.get(header.indexOf("nSingnalTime")));
            assertEquals("", row.get(header.indexOf("nSignalStartTime")));
            assertEquals("", row.get(header.indexOf("nSignalTime")));
            assertEquals("79", row.get(header.indexOf("xhfd")));
            assertEquals("102.7", row.get(header.indexOf("xhfw")));
        }
    }

    @Test
    void mergeSourceHeadersPreservesBlockOnce() {
        List<String> a = Arrays.asList("pl", "tzys", "nSingnalTime", "xhfd");
        List<String> b = Arrays.asList("pl", "tzys", "nSignalTime", "xhfw");
        List<String> merged = CsvHeaderUtils.mergeSourceHeaders(Arrays.asList(a, b));
        assertTrue(merged.contains("nSingnalTime"));
        assertTrue(merged.contains("nSignalStartTime"));
        assertTrue(merged.contains("nSignalTime"));
        int firstBlock = merged.indexOf("nSingnalTime");
        assertEquals(-1, merged.subList(firstBlock + 1, merged.size()).indexOf("nSingnalTime"));
    }
}
