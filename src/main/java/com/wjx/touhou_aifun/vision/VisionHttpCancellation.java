package com.wjx.touhou_aifun.vision;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the raw provider HTTP future so a superseding chat turn stops billed visual work. */
public final class VisionHttpCancellation {
    private static final Map<UUID, CompletableFuture<?>> IN_FLIGHT = new ConcurrentHashMap<>();

    private VisionHttpCancellation() {
    }

    public static void register(UUID maid, CompletableFuture<?> transportFuture) {
        if (maid == null || transportFuture == null) return;
        CompletableFuture<?> previous = IN_FLIGHT.put(maid, transportFuture);
        if (previous != null && previous != transportFuture && !previous.isDone()) previous.cancel(true);
        transportFuture.whenComplete((ignored, throwable) -> IN_FLIGHT.remove(maid, transportFuture));
    }

    public static void cancelForMaid(UUID maid) {
        if (maid == null) return;
        CompletableFuture<?> future = IN_FLIGHT.remove(maid);
        if (future != null && !future.isDone()) future.cancel(true);
    }

    public static void cancelAll() {
        IN_FLIGHT.values().forEach(future -> {
            if (future != null && !future.isDone()) future.cancel(true);
        });
        IN_FLIGHT.clear();
    }
}
