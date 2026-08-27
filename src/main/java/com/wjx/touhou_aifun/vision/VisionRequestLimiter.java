package com.wjx.touhou_aifun.vision;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;

/** Small global guard against a tool loop turning many maids into simultaneous paid HTTP calls. */
final class VisionRequestLimiter {
    static final int MAX_CONCURRENT = 4;
    private static final AtomicInteger ACTIVE = new AtomicInteger();

    private VisionRequestLimiter() {
    }

    static boolean tryAcquire() {
        while (true) {
            int current = ACTIVE.get();
            if (current >= MAX_CONCURRENT) return false;
            if (ACTIVE.compareAndSet(current, current + 1)) return true;
        }
    }

    static void release() {
        ACTIVE.updateAndGet(current -> Math.max(0, current - 1));
    }

    static <T> CompletableFuture<T> releaseWhenDone(CompletableFuture<T> future) {
        if (future == null) {
            release();
            return null;
        }
        future.whenComplete((ignored, throwable) -> release());
        return future;
    }

    static int activeRequests() {
        return ACTIVE.get();
    }
}
