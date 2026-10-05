package com.wjx.touhou_aifun.chat;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.network.AIFunNetwork;

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
    private static final Map<Object, com.wjx.touhou_aifun.chat.agent.AgentOperations> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> CURRENT_TURN = new ConcurrentHashMap<>();
    private static final Map<Object, Long> CALLBACK_TURNS = new ConcurrentHashMap<>();
    private static final Map<Object, Set<String>> REQUESTED_TOOLS = new ConcurrentHashMap<>();
    private static final Map<Object, Integer> REQUEST_SCHEMA_BUDGET = new ConcurrentHashMap<>();
    private static final Set<Object> ACTIVE_REQUESTS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> RETIRED_MAIDS = ConcurrentHashMap.newKeySet();
    /**
     * The base callback writes its legacy assistant/tool history synchronously after the mixin's
     * HEAD hook.  Keeping the callback in a thread-local guard lets the data mixin re-check the
     * turn at that exact write point, closing the small A/B race between the HEAD hook and the
     * base method body.
     */
    private static final ThreadLocal<Object> HISTORY_GUARD = new ThreadLocal<>();
    private static final ThreadLocal<Object> ORIGINAL_SUCCESS_DISPATCH = new ThreadLocal<>();
    private static final ThreadLocal<Object> ORIGINAL_FUNCTION_DISPATCH = new ThreadLocal<>();

    private ChatFlowManager() {
    }

    /** Invalidates every ordinary-chat side effect when the player explicitly clears memory. */
    public static void clearMaid(EntityMaid maidEntity) {
        com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager.cancel(maidEntity.getUUID(), "memory_cleared");
        UUID maid = maidEntity.getUUID();
        Object previous = LATEST_REQUEST.put(maid, new Object());
        CURRENT_TURN.compute(maid, (ignored, value) -> value == null ? 1L : value + 1L);
        cancelInFlight(maid);
        beginTtsTakeover(maid);
        AIFunNetwork.sendInterruptTts(maidEntity);
        if (previous instanceof LLMCallback callback) {
            maidEntity.getChatBubbleManager().removeChatBubble(callback.getWaitingChatBubbleId());
            ACTIVE_REQUESTS.remove(previous);
            REQUESTED_TOOLS.remove(previous);
            REQUEST_SCHEMA_BUDGET.remove(previous);
            CALLBACK_TURNS.remove(previous);
            com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.clearSnapshot(previous);
            if (previous instanceof LLMCallback llm) com.wjx.touhou_aifun.vision.MultimodalTurnContext.clear(llm);
        }
        try {
            com.wjx.touhou_aifun.vision.VisionObservationManager.clearMaid(maid);
        } catch (Throwable ignored) {
            // Optional integration remains harmless during bootstrap.
        }
    }

    /**
     * Marks {@code callback} as the latest request for the maid (called when it is created), and
     * actively cancels the previous request's in-flight LLM HTTP call so the model stops generating.
     */
    public static void registerRequest(UUID maid, Object callback) {
        // TLM uses LLMCallback subclasses for settings generation and grounded knowledge. Those
        // side requests must not cancel or replace the ordinary maid conversation.
        if (!(callback instanceof LLMCallback) || !com.wjx.touhou_aifun.chat.agent.AgentExecution.foreground(callback)) {
            return;
        }
        com.wjx.touhou_aifun.chat.agent.AgentRuntime.bind((LLMCallback) callback);
        RETIRED_MAIDS.remove(maid);
        Object previous = LATEST_REQUEST.get(maid);
        cancelInFlight(maid);
        LATEST_REQUEST.put(maid, callback);
        ACTIVE_REQUESTS.add(callback);
        if (previous != null && previous != callback) {
            CALLBACK_TURNS.remove(previous);
            REQUESTED_TOOLS.remove(previous);
            REQUEST_SCHEMA_BUDGET.remove(previous);
            com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.clearSnapshot(previous);
            if (previous instanceof LLMCallback llm) com.wjx.touhou_aifun.vision.MultimodalTurnContext.clear(llm);
        }
        CALLBACK_TURNS.put(callback, CURRENT_TURN.getOrDefault(maid, 0L));
        com.wjx.touhou_aifun.chat.agent.AgentExecution.bind((LLMCallback)callback,CURRENT_TURN.getOrDefault(maid,0L),null,0,com.wjx.touhou_aifun.chat.agent.AgentExecution.Purpose.CHAT);
        REQUESTED_TOOLS.putIfAbsent(callback, ConcurrentHashMap.newKeySet());
    }

    /** Starts a new ordinary user turn before the base manager appends the user history entry. */
    public static long beginTurn(EntityMaid maidEntity, long turnId) {
        if (!com.wjx.touhou_aifun.chat.agent.AgentRuntime.enabled(maidEntity)) com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager.cancel(maidEntity.getUUID(), "new_instruction");
        UUID maid = maidEntity.getUUID();
        RETIRED_MAIDS.remove(maid);
        Object previous = LATEST_REQUEST.get(maid);
        CURRENT_TURN.put(maid, turnId);
        // A new user instruction takes control immediately. Do not wait for the new model reply to
        // arrive before silencing audio synthesized for the old turn.
        beginTtsTakeover(maid);
        AIFunNetwork.sendInterruptTts(maidEntity);
        if (previous instanceof LLMCallback callback && ACTIVE_REQUESTS.contains(previous)) {
            maidEntity.getChatBubbleManager().removeChatBubble(callback.getWaitingChatBubbleId());
        }
        // Visual captures are tied to the old turn too; discard their in-memory request table so a
        // late six-face upload cannot be grounded into the new conversation.
        try {
            if (!com.wjx.touhou_aifun.chat.agent.AgentRuntime.enabled(maidEntity)) com.wjx.touhou_aifun.vision.VisionObservationManager.cancelForMaid(maid);
        } catch (Throwable ignored) {
            // Optional integration remains harmless during bootstrap.
        }
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
        if (callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) { if (java.util.Arrays.asList(com.wjx.touhou_aifun.compat.ai.action.GuiTool.IDS).contains(toolId)) task.task.loadedTools.addAll(java.util.Arrays.asList(com.wjx.touhou_aifun.compat.ai.action.GuiTool.IDS)); else task.task.loadedTools.add(toolId); return; }
        if (toolId == null || toolId.isBlank()) return;
        REQUESTED_TOOLS.computeIfAbsent(callback, ignored -> ConcurrentHashMap.newKeySet()).add(toolId);
    }

    public static Set<String> requestedToolIds(UUID maid, Object callback) {
        if (callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) return Set.copyOf(task.task.loadedTools);
        Set<String> ids = REQUESTED_TOOLS.get(callback);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    public static void rememberSchemaBudget(Object callback, int tokens) {
        if (callback != null && tokens > 0) REQUEST_SCHEMA_BUDGET.put(callback, tokens);
    }

    public static int rememberedSchemaBudget(Object callback) {
        return REQUEST_SCHEMA_BUDGET.getOrDefault(callback, 0);
    }

    /** Releases loaded-tool state; the turn binding remains until the next request for late replies. */
    public static void finishRequest(UUID maid, Object callback) {
        if (callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) {
            if (!task.stopped()) com.wjx.touhou_aifun.chat.agent.AgentRuntime.finished(task, "request_ended_before_task_completion", false);
            return;
        }
        if (callback instanceof LLMCallback llm) llm.runOnServerThread(() -> com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager.finish(callback));
        if (callback instanceof LLMCallback llm) com.wjx.touhou_aifun.vision.MultimodalTurnContext.clear(llm);
        REQUESTED_TOOLS.remove(callback);
        REQUEST_SCHEMA_BUDGET.remove(callback);
        ACTIVE_REQUESTS.remove(callback);
        var operations = IN_FLIGHT.remove(callback); if (operations != null) operations.cancel();
        com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.clearSnapshot(callback);
        com.wjx.touhou_aifun.chat.context.AIFunMemoryManager.pumpExtractionQueue();
    }

    public static boolean hasActiveRequest(UUID maid) { Object callback = LATEST_REQUEST.get(maid); return callback != null && ACTIVE_REQUESTS.contains(callback); }

    public static boolean hasActiveOrdinaryRequests() {
        return !ACTIVE_REQUESTS.isEmpty();
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

    public static boolean takeOriginalFunctionDispatch(Object callback) {
        if (ORIGINAL_FUNCTION_DISPATCH.get() == callback) {
            ORIGINAL_FUNCTION_DISPATCH.remove();
            return true;
        }
        return false;
    }

    /** Re-enter the base onFunctionCall body on the server thread without recursively scheduling it. */
    public static void dispatchOriginalFunction(Object callback, Runnable invocation) {
        ORIGINAL_FUNCTION_DISPATCH.set(callback);
        try {
            invocation.run();
        } finally {
            ORIGINAL_FUNCTION_DISPATCH.remove();
        }
    }

    public static void setModelInFlight(UUID maid, Object callback, CompletableFuture<?> future) {
        long started = System.nanoTime();
        future.whenComplete((value,error) -> com.wjx.touhou_aifun.chat.agent.AgentTelemetry.stage("model_http_ready",started,0));
        setInFlight(maid,callback,future);
    }
    /** Track all purposes, while ordinary chat takeover cancels only ordinary chat operations. */
    public static void setInFlight(UUID maid, Object callback, CompletableFuture<?> future) {
        var parent = com.wjx.touhou_aifun.chat.agent.KnowledgeSummaryCache.parent(callback);
        if (parent != null) { com.wjx.touhou_aifun.chat.agent.KnowledgeSummaryCache.track(callback,future); setInFlight(maid, parent, future); return; }
        long started = System.nanoTime();
        future.whenComplete((value, error) -> com.wjx.touhou_aifun.chat.agent.AgentTelemetry.stage("async_operation", started, 0));
        if (callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) { task.operations.add(future); return; }
        if (callback instanceof LLMCallback) {
            var operations = IN_FLIGHT.compute(callback, (key, current) -> {
                var owned = current == null ? new com.wjx.touhou_aifun.chat.agent.AgentOperations() : current;
                owned.add(future); return owned;
            });
            future.whenComplete((value,error) -> IN_FLIGHT.computeIfPresent(callback,
                    (key,current) -> current == operations && current.empty() ? null : current));
            if (isSuperseded(maid, callback)) { operations.cancel(); IN_FLIGHT.remove(callback, operations); }
        }
    }

    private static void cancelInFlight(UUID maid) {
        cancelInFlight(maid, false);
    }
    private static void cancelInFlight(UUID maid, boolean allPurposes) {
        IN_FLIGHT.forEach((callback, operations) -> {
            if (callback instanceof LLMCallback llm && llm.getMaid().getUUID().equals(maid)
                    && (allPurposes || com.wjx.touhou_aifun.chat.agent.AgentExecution.foreground(callback))
                    && IN_FLIGHT.remove(callback, operations)) operations.cancel();
        });
    }

    /** True if a newer request has been issued for the maid since {@code callback} was created. */
    public static boolean isSuperseded(UUID maid, Object callback) {
        if (callback instanceof com.wjx.touhou_aifun.chat.agent.TaskCallback task) return task.stopped();
        if (RETIRED_MAIDS.contains(maid)) return true;
        var parent = com.wjx.touhou_aifun.chat.agent.KnowledgeSummaryCache.parent(callback);
        if (parent != null) return isSuperseded(maid, parent);
        if (!com.wjx.touhou_aifun.chat.agent.AgentExecution.foreground(callback)) return false;
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

    /** Releases strong runtime references when a maid leaves a level while rejecting late callbacks. */
    public static void forgetMaid(UUID maid) {
        RETIRED_MAIDS.add(maid);
        Object callback = LATEST_REQUEST.remove(maid);
        CURRENT_TURN.remove(maid);
        TTS_GENERATION.remove(maid);
        cancelInFlight(maid, true);
        if (callback != null) {
            CALLBACK_TURNS.remove(callback);
            REQUESTED_TOOLS.remove(callback);
            REQUEST_SCHEMA_BUDGET.remove(callback);
            ACTIVE_REQUESTS.remove(callback);
            com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.clearSnapshot(callback);
        }
        com.wjx.touhou_aifun.chat.context.AIFunMemoryManager.cancelQueuedExtraction(maid);
        try {
            com.wjx.touhou_aifun.vision.VisionObservationManager.clearMaid(maid);
        } catch (Throwable ignored) {
        }
        com.wjx.touhou_aifun.chat.context.AIFunMemoryManager.pumpExtractionQueue();
    }

    public static void clearAllRuntimeState() {
        IN_FLIGHT.values().forEach(future -> {
            if (future != null) future.cancel();
        });
        LATEST_REQUEST.clear();
        TTS_GENERATION.clear();
        IN_FLIGHT.clear();
        CURRENT_TURN.clear();
        CALLBACK_TURNS.clear();
        REQUESTED_TOOLS.clear();
        REQUEST_SCHEMA_BUDGET.clear();
        ACTIVE_REQUESTS.clear();
        RETIRED_MAIDS.clear();
        HISTORY_GUARD.remove();
        ORIGINAL_SUCCESS_DISPATCH.remove();
        ORIGINAL_FUNCTION_DISPATCH.remove();
        com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector.clearAllSnapshots();
        com.wjx.touhou_aifun.vision.VisionObservationManager.clearAll();
        com.wjx.touhou_aifun.chat.context.AIFunMemoryManager.clearExtractionRuntime();
    }
}
