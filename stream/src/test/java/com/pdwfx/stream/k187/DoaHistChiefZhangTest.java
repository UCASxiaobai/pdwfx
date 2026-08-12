package com.pdwfx.stream.k187;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoaHistChiefZhangTest {

    @Test
    void fuseClusterAroundPeak() {
        List<Short> doas = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            doas.add((short) (1200 + (i % 5)));
        }
        DoaHistChiefZhang.Result r = DoaHistChiefZhang.fuse(doas, 300000L, 5);
        assertEquals(0, r.code);
        assertTrue(r.doaMean > 1190 && r.doaMean < 1210);
    }

    @Test
    void trueAzimuthWraps() {
        short az = DoaHistChiefZhang.toTrueAzimuth((short) 3500, 20000);
        assertTrue(az >= 0 && az < 3600);
    }
}
