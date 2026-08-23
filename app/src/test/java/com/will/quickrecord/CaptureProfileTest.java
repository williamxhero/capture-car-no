package com.will.quickrecord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CaptureProfileTest {
    @Test
    public void brightReadingChoosesUhdRecording() {
        CaptureProfile profile = CaptureProfile.choose(800f, 12);

        assertEquals(CaptureProfile.Lighting.BRIGHT_DAY, profile.lighting);
        assertTrue(profile.preferUhd);
        assertFalse(profile.enableTorch);
        assertEquals(30, profile.targetFps);
    }

    @Test
    public void indoorReadingChoosesFhdPriority() {
        CaptureProfile profile = CaptureProfile.choose(250f, 12);

        assertEquals(CaptureProfile.Lighting.NORMAL_LIGHT, profile.lighting);
        assertFalse(profile.preferUhd);
        assertFalse(profile.enableTorch);
        assertEquals(30, profile.targetFps);
    }

    @Test
    public void darkReadingOverridesDaytimeClock() {
        CaptureProfile profile = CaptureProfile.choose(20f, 12);

        assertEquals(CaptureProfile.Lighting.LOW_LIGHT, profile.lighting);
        assertTrue(profile.enableTorch);
        assertEquals(-1.0f, profile.exposureEv, 0.001f);
    }

    @Test
    public void clockIsOnlyFallbackWhenLightSensorIsUnavailable() {
        assertEquals(
                CaptureProfile.Lighting.NORMAL_LIGHT,
                CaptureProfile.choose(null, 12).lighting);
        assertEquals(
                CaptureProfile.Lighting.LOW_LIGHT,
                CaptureProfile.choose(null, 22).lighting);
        assertTrue(CaptureProfile.choose(null, 22).enableTorch);
    }
}
