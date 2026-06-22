package com.wjx.touhou_aifun.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks, per maid, which chat request is the latest one so that an in-flight request can be
 * superseded when the player sends a new one, and so an older reply's TTS can be cut off when a
 * newer reply starts speaking.
 *
 * <ul>
 *   <li>{@code latest} (identity of the current {@code LLMCallback}) decides whether a returning
 *       reply should still be shown/spoken. A superseded reply is kept in history but not output.</li>
 *   <li>{@code ttsGeneration} is bumped whenever the latest reply takes over speaking; ongoing
 *       progressive synthesis from an older reply checks it and stops.</li>
 * </ul>
 */
public final class ChatFlowManager {
    private static final Map<UUID, Object> LATEST_REQUEST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> TTS_GENERATION = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<?>> IN_FLIGHT = new ConcurrentHashMap<>();

    private ChatFlowManager() {
    }

    /**
     * Marks {@code callback} as the latest request for the maid (called when it is created), and
     * actively cancels the previous request's in-flight LLM HTTP call so the model stops generating.
     */
    public static void registerRequest(UUID maid, Object callback) {
        cancelInFlight(maid);
        LATEST_REQUEST.put(maid, callback);
    }

    /** Remembers the in-flight LLM request future so a later request can abort it. */
    public static void setInFlight(UUID maid, CompletableFuture<?> future) {
        IN_FLIGHT.put(maid, future);
    }

    private static void cancelInFlight(UUID maid) {
        CompletableFuture<?> previous = IN_FLIGHT.remove(maid);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }
    }

    /** True if a newer request has been issued for the maid since {@code callback} was created. */
    public static boolean isSuperseded(UUID maid, Object callback) {
        Object latest = LATEST_REQUEST.get(maid);
        return latest != null && latest != callback;
    }

    /** Bumps the TTS generation so any older reply's ongoing synthesis/playback is abandoned. */
    public static int beginTtsTakeover(UUID maid) {
        return TTS_GENERATION.merge(maid, 1, Integer::sum);
    }

    /** Current TTS generation for the maid (captured by a synthesis run to detect takeovers). */
    public static int ttsGeneration(UUID maid) {
        return TTS_GENERATION.getOrDefault(maid, 0);
    }
}
