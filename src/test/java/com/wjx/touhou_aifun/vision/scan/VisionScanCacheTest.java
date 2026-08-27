package com.wjx.touhou_aifun.vision.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionScanCacheTest {
    @Test
    void cacheIsReusableOnlyForTheFollowingTenTicks() {
        assertTrue(VisionScanCache.isFresh(100, 100));
        assertTrue(VisionScanCache.isFresh(110, 100));
        assertFalse(VisionScanCache.isFresh(111, 100));
    }

    @Test
    void tickRollbackNeverReusesAResultFromAnotherServerTimeline() {
        assertFalse(VisionScanCache.isFresh(5, 2_000));
    }
}
