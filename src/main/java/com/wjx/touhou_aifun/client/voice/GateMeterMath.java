package com.wjx.touhou_aifun.client.voice;

/**
 * Pure math for the inline free-chat gate meter. The visual scale is a square-root mapping of
 * 0..8000 RMS so quiet speech is visible while loud peaks still fit; the configured threshold can
 * be larger than the visual ceiling and is simply clamped at the right edge.
 */
public final class GateMeterMath {
    public static final int MAX_RMS = 8000;
    public static final int MIN_THRESHOLD = 50;
    public static final int MAX_THRESHOLD = 20000;
    public static final double TTS_THRESHOLD_FACTOR = 1.5D;

    private GateMeterMath() {
    }

    public static double normalize(int rms) {
        int clamped = Math.max(0, Math.min(MAX_RMS, rms));
        return Math.sqrt(clamped / (double) MAX_RMS);
    }

    public static int denormalize(double fraction) {
        double clamped = Math.max(0.0D, Math.min(1.0D, fraction));
        return (int) Math.round(clamped * clamped * MAX_RMS);
    }

    public static int thresholdFromMouseX(double mouseX, int widgetX, int widgetWidth) {
        if (widgetWidth <= 0) {
            return MIN_THRESHOLD;
        }
        return clampThreshold(denormalize((mouseX - widgetX) / widgetWidth));
    }

    public static int clampThreshold(int value) {
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, value));
    }

    public static int activeThreshold(int threshold, boolean ttsActive) {
        int clamped = clampThreshold(threshold);
        if (!ttsActive) {
            return clamped;
        }
        return Math.min(MAX_THRESHOLD, (int) Math.round(clamped * TTS_THRESHOLD_FACTOR));
    }

    public static boolean triggered(int level, int threshold) {
        return level >= Math.max(1, threshold);
    }
}
