package com.will.quickrecord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class PlateRecognizerTest {
    @Test
    public void extractsMultipleStandardAndNewEnergyPlatesFromNoisyText() {
        List<String> plates = PlateRecognizer.extractCandidates(
                "车辆 粤B·12345 通过，后车 沪A D12345");

        assertEquals(Arrays.asList("粤B12345", "沪AD12345"), plates);
    }

    @Test
    public void removesDuplicateReadingsFromOneFrame() {
        List<String> plates = PlateRecognizer.extractCandidates(
                "京A12345 京A-12345");

        assertEquals(Arrays.asList("京A12345"), plates);
    }

    @Test
    public void leavesNonPlateTextUnrecognized() {
        assertTrue(PlateRecognizer.extractCandidates("订单 A12345，共两辆车").isEmpty());
    }
}
