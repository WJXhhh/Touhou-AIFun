package com.wjx.touhou_aifun.client.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GateMeterMathTest {
    @Test
    void squareRootScaleMapsBothEndsExactly() {
        assertEquals(0.0D, GateMeterMath.normalize(0), 0.0001D);
        assertEquals(1.0D, GateMeterMath.normalize(8000), 0.0001D);
        assertEquals(2000, GateMeterMath.denormalize(0.5D));
        assertEquals(8000, GateMeterMath.denormalize(2.0D));
    }

    @Test
    void draggingAcrossTheBarMapsToTheConfiguredRange() {
        assertEquals(500, GateMeterMath.thresholdFromMouseX(150, 100, 200));
        assertEquals(2000, GateMeterMath.thresholdFromMouseX(200, 100, 200));
        assertEquals(50, GateMeterMath.thresholdFromMouseX(0, 100, 200));
        assertEquals(8000, GateMeterMath.thresholdFromMouseX(400, 100, 200));
    }

    @Test
    void ttsRaisesTheActiveThresholdByOnePointFive() {
        assertEquals(700, GateMeterMath.activeThreshold(700, false));
        assertEquals(1050, GateMeterMath.activeThreshold(700, true));
        assertEquals(20000, GateMeterMath.activeThreshold(15000, true));
        assertEquals(20000, GateMeterMath.clampThreshold(99999));
        assertEquals(50, GateMeterMath.clampThreshold(-10));
    }

    @Test
    void triggerIsInclusiveAtTheThreshold() {
        assertTrue(GateMeterMath.triggered(700, 700));
        assertFalse(GateMeterMath.triggered(699, 700));
    }
}
