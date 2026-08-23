package com.will.quickrecord;

/** Camera priorities tuned for nearby moving vehicles and reflective license plates. */
final class CaptureProfile {
    enum Lighting {
        BRIGHT_DAY,
        NORMAL_LIGHT,
        LOW_LIGHT
    }

    static final float BRIGHT_LUX = 800f;
    static final float LOW_LUX = 100f;

    final Lighting lighting;
    final int targetFps;
    final boolean preferUhd;
    final boolean enableTorch;
    final float exposureEv;
    final String label;

    private CaptureProfile(
            Lighting lighting,
            int targetFps,
            boolean preferUhd,
            boolean enableTorch,
            float exposureEv,
            String label) {
        this.lighting = lighting;
        this.targetFps = targetFps;
        this.preferUhd = preferUhd;
        this.enableTorch = enableTorch;
        this.exposureEv = exposureEv;
        this.label = label;
    }

    static CaptureProfile choose(Float measuredLux, int localHour) {
        if (measuredLux != null) {
            if (measuredLux >= BRIGHT_LUX) {
                return brightDay();
            }
            if (measuredLux < LOW_LUX) {
                return lowLight();
            }
            return normalLight();
        }

        return localHour >= 7 && localHour < 18 ? normalLight() : lowLight();
    }

    private static CaptureProfile brightDay() {
        return new CaptureProfile(
                Lighting.BRIGHT_DAY, 30, true, false, -0.7f, "强光车牌");
    }

    private static CaptureProfile normalLight() {
        return new CaptureProfile(
                Lighting.NORMAL_LIGHT, 30, false, false, -0.7f, "日常车牌");
    }

    private static CaptureProfile lowLight() {
        return new CaptureProfile(
                Lighting.LOW_LIGHT, 30, false, true, -1.0f, "低光车牌");
    }
}
