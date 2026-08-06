package com.wjx.touhou_aifun.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;

import java.util.Map;
import java.util.Set;
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
 *       reply should still be shown/spoken. A superseded reply is discarded by the callback mixin.</li>
 *   <li>{@code ttsGeneration} is bumped whenever the latest reply takes over speaking; ongoing
 *       progressive synthesis from an older reply checks it and stops.</li>
 * </ul>
 */
public final class ChatFlowManager {
    private static final Map<UUID, Object> LATEST_REQUEST = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> TTS_GENERATION = new ConcurrentHashMap<>();
    private static final Map<UUID, CompletableFuture<?>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CURRENT_TURN = new ConcurrentHashMap<>();
    private static final Map<Object, Long> CALLBACK_TURNS = new ConcurrentHashMap<>();
    private static final Map<Object, Set<String>> REQUESTED_TOOLS = new ConcurrentHashMap<>();
    /**
     * The base callback writes its legacy assistant/tool history synchronously after the mixin's
     * HEAD hook.  Keeping the callback in a thread-local guard lets the data mixin re-check the
     * turn at that exact write point, closing the small A/B race between the HEAD hook and the
     * base method body.
     */
    private static final ThreadLocal<Object> HISTORY_GUARD = new ThreadLocal<>();
    private static final ThreadLocal<Object> ORIGINAL_SUCCESS_DISPATCH = new ThreadLocal<>();

    private ChatFlowManager() {
    }

    /**
     * Marks {@code callback} as the latest request for the maid (called when it is created), and
     * actively cancels the previous request's in-flight LLM HTTP call so the model stops generating.
     */
    public static void registerRequest(UUID maid, Object callback) {
        // TLM uses LLMCallback subclasses for settings generation and grounded knowledge. Those
        // side requests must not cancel or replace the ordinary maid conversation.
        if (!(callback instanceof LLMCallback) || callback.getClass() != LLMCallback.class) {
            return;
        }
        Object previous = LATEST_REQUEST.get(maid);
        cancelInFlight(maid);
        LATEST_REQUEST.put(maid, callback);
        if (previous != null && previous != callback) {
            CALLBACK_TURNS.remove(previous);
            REQUESTED_TOOLS.remove(previous);
        }
        CALLBACK_TURNS.put(callback, CURRENT_TURN.getOrDefault(maid, 0L));
        REQUESTED_TOOLS.putIfAbsent(callback, ConcurrentHashMap.newKeySet());
    }

    /** Starts a new ordinary user turn before the base manager appends the user history entry. */
    public static long beginTurn(UUID maid, long turnId) {
        CURRENT_TURN.put(maid, turnId);
        // Publish the new turn before cancelling the old future: cancellation may synchronously
        // invoke its completion handler, which must already observe the request as superseded.
        cancelInFlight(maid);
        return turnId;
    }

    /** Returns the durable turn bound to an ordinary callback, or zero for a side callback. */
    public static long currentTurnId(UUID maid, Object callback) {
        return CALLBACK_TURNS.getOrDefault(callback, 0L);
    }

    public static void requestToolSchema(UUID maid, Object callback, String toolId) {
        if (toolId == null || toolId.isBlank()) return;
        REQUESTED_TOOLS.computeIfAbsent(callback, ignored -> ConcurrentHashMap.newKeySet()).add(toolId);
    }

    public static Set<String> requestedToolIds(UUID maid, Object callback) {
        Set<String> ids = REQUESTED_TOOLS.get(callback);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    /** Releases loaded-tool state; the turn binding remains until the next request for late replies. */
    public static void finishRequest(UUID maid, Object callback) {
        REQUESTED_TOOLS.remove(callback);
    }

    public static void beginHistoryGuard(Object callback) {
        HISTORY_GUARD.set(callback);
    }

    public static void clearHistoryGuard(Object callback) {
        if (HISTORY_GUARD.get() == callback) {
            HISTORY_GUARD.remove();
        }
    }

    /** True when a legacy history write on the current callback thread is still allowed. */
    public static boolean allowHistoryWrite(UUID maid) {
        Object callback = HISTORY_GUARD.get();
        return callback == null || !isSuperseded(maid, callback);
    }

    public static boolean takeOriginalSuccessDispatch(Object callback) {
        if (ORIGINAL_SUCCESS_DISPATCH.get() == callback) {
            ORIGINAL_SUCCESS_DISPATCH.remove();
            return true;
        }
        return false;
    }

    /** Re-enter the base onSuccess body on the server thread without recursively scheduling it. */
    public static void dispatchOriginalSuccess(Object callback, Runnable invocation) {
        ORIGINAL_SUCCESS_DISPATCH.set(callback);
        try {
            invocation.run();
        } finally {
            ORIGINAL_SUCCESS_DISPATCH.remove();
        }
    }

    /** Remembers only an ordinary chat future; side callbacks must never be cancelled by chat B. */
    public static void setInFlight(UUID maid, Object callback, CompletableFuture<?> future) {
        if (callback instanceof LLMCallback && callback.getClass() == LLMCallback.class) {
            IN_FLIGHT.put(maid, future);
        }
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
        Long callbackTurn = CALLBACK_TURNS.get(callback);
        long currentTurn = CURRENT_TURN.getOrDefault(maid, 0L);
        if (callbackTurn != null && callbackTurn > 0 && currentTurn > callbackTurn) {
            return true;
        }
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
