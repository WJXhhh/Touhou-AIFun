package com.wjx.touhou_aifun.vision;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionRequestLimiterTest {
    @AfterEach
    void drain() {
        while (VisionRequestLimiter.activeRequests() > 0) VisionRequestLimiter.release();
    }

    @Test
    void capsConcurrentPaidVisualRequestsAndReleasesCapacity() {
        for (int index = 0; index < VisionRequestLimiter.MAX_CONCURRENT; index++) {
            assertTrue(VisionRequestLimiter.tryAcquire());
        }
        assertEquals(VisionRequestLimiter.MAX_CONCURRENT, VisionRequestLimiter.activeRequests());
        assertFalse(VisionRequestLimiter.tryAcquire());

        VisionRequestLimiter.release();
        assertTrue(VisionRequestLimiter.tryAcquire());
    }

    @Test
    void cancellingReturnedObservationImmediatelyReleasesCapacity() {
        assertTrue(VisionRequestLimiter.tryAcquire());
        CompletableFuture<String> observation = new CompletableFuture<>();
        assertEquals(observation, VisionRequestLimiter.releaseWhenDone(observation));

        observation.cancel(false);

        assertEquals(0, VisionRequestLimiter.activeRequests());
    }
}
