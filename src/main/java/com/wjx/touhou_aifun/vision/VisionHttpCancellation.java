package com.wjx.touhou_aifun.vision;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the raw provider HTTP future so a superseding chat turn stops billed visual work. */
public final class VisionHttpCancellation {
    private static final Map<UUID, CompletableFuture<?>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> TICKETS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong NEXT_TICKET = new java.util.concurrent.atomic.AtomicLong();

    private VisionHttpCancellation() {
    }

    public static void register(UUID maid, CompletableFuture<?> transportFuture) {
        register(maid, transportFuture, ticket(maid));
    }

    public static long ticket(UUID maid) {
        return maid == null ? 0 : TICKETS.computeIfAbsent(maid, ignored -> NEXT_TICKET.incrementAndGet());
    }

    public static boolean isCurrent(UUID maid, long ticket) {
        return maid == null || java.util.Objects.equals(TICKETS.get(maid), ticket);
    }

    /** A delayed retry or OAuth refresh from an old turn must not replace a new turn's future. */
    public static synchronized void register(UUID maid, CompletableFuture<?> transportFuture, long ticket) {
        if (maid == null || transportFuture == null) return;
        if (!isCurrent(maid, ticket)) { transportFuture.cancel(true); return; }
        CompletableFuture<?> previous = IN_FLIGHT.put(maid, transportFuture);
        if (previous != null && previous != transportFuture && !previous.isDone()) previous.cancel(true);
        transportFuture.whenComplete((ignored, throwable) -> IN_FLIGHT.remove(maid, transportFuture));
    }

    public static synchronized void cancelForMaid(UUID maid) {
        if (maid == null) return;
        TICKETS.put(maid, NEXT_TICKET.incrementAndGet());
        CompletableFuture<?> future = IN_FLIGHT.remove(maid);
        if (future != null && !future.isDone()) future.cancel(true);
    }

    public static synchronized void cancelAll() {
        TICKETS.clear();
        IN_FLIGHT.values().forEach(future -> {
            if (future != null && !future.isDone()) future.cancel(true);
        });
        IN_FLIGHT.clear();
    }
}
