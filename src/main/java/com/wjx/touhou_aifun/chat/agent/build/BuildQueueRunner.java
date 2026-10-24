package com.wjx.touhou_aifun.chat.agent.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.agent.TaskCallback;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionManager;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionRequest;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-tick driver for one build run. It holds no world lease: each step is one
 * {@link MaidBlockActionManager} call, so other physical actions can interrupt between steps.
 * Verification is deterministic; ambiguous or failed states go to {@link #JUDGE} when wired,
 * otherwise the run pauses with a needs_llm result for the execution LLM to resolve.
 */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BuildQueueRunner {
    /** Wired by the lead's native decision integration; null means "ask the executor LLM". */
    public static volatile BuildStateJudge JUDGE = null;

    private static final Map<UUID, ActiveRun> RUNS = new HashMap<>();
    private static final long RETRY_DELAY_TICKS = 4;
    private static final int MAX_JUDGE_RETRIES = 4;
    private static final int MAX_STEP_ITERATIONS_PER_TICK = 8;
    private static final int DEFAULT_MAX_ATTEMPTS = 2;

    private BuildQueueRunner() {
    }

    public static boolean running(UUID maidId) {
        return maidId != null && RUNS.containsKey(maidId);
    }

    public static CompletableFuture<JsonObject> start(EntityMaid maid, Object callback, BuildDesign design,
                                                      BuildQueueState queue, int maxSteps, int maxSeconds,
                                                      boolean retryFailed) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        if (running(maid.getUUID())) {
            future.complete(BuildQueueManager.failure("design_in_use"));
            return future;
        }
        ActiveRun run = new ActiveRun(maid, callback, design, queue, future, maxSteps,
                Math.max(1, maxSeconds) * 20L, retryFailed);
        RUNS.put(maid.getUUID(), run);
        try {
            run.tick();
        } catch (Throwable failed) {
            TouhouAIFun.LOGGER.error("AIFun build run failed to start for maid {}", maid.getUUID(), failed);
            run.failInternal();
        }
        return future;
    }

    public static void cancelForMaid(UUID maidId, String reason) {
        ActiveRun run = RUNS.remove(maidId);
        if (run == null) return;
        if (run.actionFuture != null && !run.actionFuture.isDone()) {
            MaidBlockActionManager.cancelForMaid(maidId,
                    reason == null || reason.isBlank() ? "build_run_cancelled" : reason);
        }
        JsonObject out = new JsonObject();
        out.addProperty("success", false);
        out.addProperty("status", "cancelled");
        out.addProperty("error", BuildQueueManager.bounded(reason == null || reason.isBlank() ? "cancelled" : reason, 160));
        out.addProperty("design_id", run.design.id);
        run.future.complete(out);
    }

    public static void cancelAll() {
        for (UUID maidId : List.copyOf(RUNS.keySet())) cancelForMaid(maidId, "server_stopping");
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || RUNS.isEmpty()) return;
        for (ActiveRun run : List.copyOf(RUNS.values())) {
            if (run.future.isDone()) {
                RUNS.remove(run.maid.getUUID(), run);
                continue;
            }
            try {
                run.tick();
            } catch (Throwable failed) {
                TouhouAIFun.LOGGER.error("AIFun build run tick failed for maid {}", run.maid.getUUID(), failed);
                run.failInternal();
            }
        }
    }

    private enum Phase { IDLE, WAITING_ACTION, WAITING_JUDGE }

    private static final class ActiveRun {
        private final EntityMaid maid;
        private final Object callback;
        private final BuildDesign design;
        private final BuildQueueState queue;
        private final CompletableFuture<JsonObject> future;
        private final long startTick;
        private final long deadlineTick;
        private final int maxSteps;
        private final boolean retryFailed;
        private int startedSteps;
        private Phase phase = Phase.IDLE;
        @Nullable
        private CompletableFuture<MaidBlockActionResult> actionFuture;
        @Nullable
        private BuildStep activeStep;
        @Nullable
        private BuildStepState activeState;
        @Nullable
        private CompletableFuture<JsonObject> judgeFuture;
        private JsonObject judgeActual = new JsonObject();
        private long retryAtTick;

        ActiveRun(EntityMaid maid, Object callback, BuildDesign design, BuildQueueState queue,
                  CompletableFuture<JsonObject> future, int maxSteps, long maxTicks, boolean retryFailed) {
            this.maid = maid;
            this.callback = callback;
            this.design = design;
            this.queue = queue;
            this.future = future;
            this.maxSteps = maxSteps;
            this.retryFailed = retryFailed;
            this.startTick = levelTime();
            this.deadlineTick = startTick + maxTicks;
        }

        void tick() {
            if (future.isDone()) return;
            if (!(maid.level() instanceof ServerLevel) || maid.isRemoved() || !maid.isAlive()) {
                pauseWith("maid_unavailable", false);
                return;
            }
            long now = levelTime();
            if (ChatFlowManager.isSuperseded(maid.getUUID(), callback)) {
                cancelled("superseded");
                return;
            }
            if (callback instanceof TaskCallback task && task.stopped()) {
                cancelled("task_stopped");
                return;
            }
            if (!design.dimension.equals(BuildQueueManager.dimension(maid))) {
                pauseWith("dimension_mismatch", false);
                return;
            }
            if (now >= deadlineTick) {
                pauseWith("max_seconds", true);
                return;
            }
            if (!"running".equals(queue.status)) {
                cancelled("run_not_active");
                return;
            }
            int guard = 0;
            while (!future.isDone()) {
                if (phase == Phase.WAITING_ACTION) {
                    if (actionFuture == null) {
                        phase = Phase.IDLE;
                        continue;
                    }
                    if (!actionFuture.isDone()) return;
                    processAction(now);
                } else if (phase == Phase.WAITING_JUDGE) {
                    if (judgeFuture == null) {
                        phase = Phase.IDLE;
                        continue;
                    }
                    if (!judgeFuture.isDone()) return;
                    processVerdict(now);
                } else {
                    if (now < retryAtTick) return;
                    startNext(now);
                }
                if (future.isDone()) return;
                if (++guard >= MAX_STEP_ITERATIONS_PER_TICK) return;
            }
        }

        private void startNext(long now) {
            int index = pickNextIndex();
            if (index < 0) {
                int remaining = queue.countStatus(BuildStepState.FAILED) + queue.countStatus(BuildStepState.BLOCKED);
                if (remaining > 0) completeFailedSteps(now);
                else completeRun(now);
                return;
            }
            if (startedSteps >= maxSteps) {
                pauseWith("max_steps", true);
                return;
            }
            BuildStep step = design.steps.get(index);
            BuildStepState stepState = queue.ensureStepState(index);
            List<String> missing = BuildQueueManager.missingForStep(maid, step);
            if (!missing.isEmpty()) {
                if (!step.required) {
                    // Optional decoration must never block required structural work.
                    stepState.status = BuildStepState.SKIPPED;
                    stepState.decision = "skipped_missing_optional";
                    stepState.lastError = "missing_items";
                    stepState.settledTick = now;
                    persist(now);
                    return;
                }
                activeStep = step;
                activeState = stepState;
                blockMissing(now, "missing_for_step");
                return;
            }
            startedSteps++;
            stepState.attempts++;
            stepState.status = BuildStepState.RUNNING;
            stepState.lastError = "";
            stepState.decision = "";
            stepState.settledTick = 0;
            queue.updatedTick = now;
            activeStep = step;
            activeState = stepState;
            phase = Phase.WAITING_ACTION;
            persist(now);
            TouhouAIFun.LOGGER.debug("AIFun build run: maid={} step={} kind={} requested", maid.getUUID(), index, step.kind);
            MaidBlockActionRequest request;
            try {
                request = requestFor(step);
            } catch (RuntimeException invalid) {
                handleFailure(now, actionError(step, "invalid_request", invalid), "invalid_request");
                return;
            }
            try {
                actionFuture = MaidBlockActionManager.start(maid, callback, request);
            } catch (RuntimeException failed) {
                actionFuture = null;
                handleFailure(now, actionError(step, "action_start_failed", failed), "action_start_failed");
            }
        }

        private int pickNextIndex() {
            for (Integer index : queue.order) {
                BuildStepState stepState = queue.stepState(index);
                if (stepState == null) continue;
                if (BuildStepState.PENDING.equals(stepState.status) || BuildStepState.READY.equals(stepState.status))
                    return index;
                if (retryFailed && BuildStepState.FAILED.equals(stepState.status)) return index;
            }
            return -1;
        }

        private void processAction(long now) {
            CompletableFuture<MaidBlockActionResult> completed = actionFuture;
            actionFuture = null;
            if (completed == null) {
                phase = Phase.IDLE;
                return;
            }
            MaidBlockActionResult result = null;
            Throwable thrown = null;
            try {
                result = completed.getNow(null);
            } catch (Throwable failure) {
                thrown = failure;
            }
            BuildStep step = activeStep;
            if (result == null) {
                handleFailure(now, actionError(step, "action_failed", thrown), "action_failed");
                return;
            }
            if (result.success()) {
                JsonObject actual = BuildStepVerifier.verify(maid, design, step, false);
                if (actual.get("verified").getAsBoolean()) {
                    markDone(now, step, activeState, "verified");
                    return;
                }
                handleFailure(now, withActionError(actual, result), mismatchReason(result, actual));
                return;
            }
            String error = result.error().isBlank() ? "block_action_failed" : result.error();
            if ("superseded".equals(error) || "cancelled".equals(error)) {
                cancelled(error);
                return;
            }
            if ("missing_item".equals(error) || "missing_tool".equals(error)) {
                blockMissing(now, error);
                return;
            }
            JsonObject actual = BuildStepVerifier.verify(maid, design, step, true);
            handleFailure(now, withActionError(actual, result), error);
        }

        private void handleFailure(long now, JsonObject actual, String reason) {
            BuildStepState stepState = activeState;
            if (stepState == null) {
                phase = Phase.IDLE;
                return;
            }
            stepState.attempts = Math.max(stepState.attempts, 1);
            stepState.lastError = BuildQueueManager.bounded(reason, 160);
            stepState.settledTick = now;
            stepState.status = BuildStepState.FAILED;
            phase = Phase.IDLE;
            if (stepState.attempts < actionMaxAttempts()) {
                // Cheap deterministic retry: re-read the live state and try the same physical action again.
                stepState.status = BuildStepState.READY;
                stepState.decision = "retry";
                retryAtTick = now + RETRY_DELAY_TICKS;
                persist(now);
                return;
            }
            BuildStateJudge judge = JUDGE;
            if (judge == null) {
                stateNeedsLlm(now, actual, reason == null || reason.isBlank() ? "no_judge" : reason);
                return;
            }
            stepState.status = BuildStepState.RUNNING;
            judgeActual = actual == null ? new JsonObject() : actual.deepCopy();
            phase = Phase.WAITING_JUDGE;
            try {
                judgeFuture = judge.judge(maid, callback, design, activeStep, judgeActual, stepState.attempts);
            } catch (RuntimeException failed) {
                judgeFuture = null;
                stateNeedsLlm(now, judgeActual, "judge_failed");
                return;
            }
            if (judgeFuture == null) stateNeedsLlm(now, judgeActual, "judge_unavailable");
            persist(now);
        }

        private void processVerdict(long now) {
            CompletableFuture<JsonObject> completed = judgeFuture;
            judgeFuture = null;
            phase = Phase.IDLE;
            BuildStepState stepState = activeState;
            if (completed == null || stepState == null) return;
            JsonObject verdict = null;
            try {
                verdict = completed.getNow(null);
            } catch (Throwable ignored) {
            }
            if (verdict == null) {
                stateNeedsLlm(now, judgeActual, "judge_failed");
                return;
            }
            boolean confident = verdict.has("confident") && verdict.get("confident").isJsonPrimitive()
                    && verdict.get("confident").getAsBoolean();
            String choice = verdict.has("choice") && verdict.get("choice").isJsonPrimitive()
                    ? verdict.get("choice").getAsString().toLowerCase(Locale.ROOT) : "";
            String reason = verdict.has("reason") && verdict.get("reason").isJsonPrimitive()
                    ? verdict.get("reason").getAsString() : "";
            if (!confident) {
                stateNeedsLlm(now, judgeActual, reason.isBlank() ? "judge_uncertain" : reason);
                return;
            }
            switch (choice) {
                case "satisfied" -> markDone(now, activeStep, stepState, "satisfied");
                case "retry" -> {
                    if (stepState.attempts >= MAX_JUDGE_RETRIES) {
                        stateBlocked(now, reason.isBlank() ? "retry_limit" : reason);
                        return;
                    }
                    stepState.status = BuildStepState.READY;
                    stepState.decision = "retry";
                    stepState.settledTick = now;
                    retryAtTick = now + RETRY_DELAY_TICKS;
                    persist(now);
                }
                case "adjust" -> {
                    JsonObject adjustment = verdict.has("adjustment") && verdict.get("adjustment").isJsonObject()
                            ? verdict.getAsJsonObject("adjustment") : null;
                    if (!applyAdjustment(activeStep, adjustment)) {
                        stateNeedsLlm(now, judgeActual, "invalid_adjustment");
                        return;
                    }
                    stepState.status = BuildStepState.PENDING;
                    stepState.attempts = 0;
                    stepState.decision = "adjust";
                    stepState.adjustment = BuildQueueManager.bounded(adjustment == null ? "" : adjustment.toString(), 512);
                    stepState.lastError = BuildQueueManager.bounded(reason, 160);
                    stepState.settledTick = now;
                    retryAtTick = now;
                    persist(now);
                }
                case "skip" -> {
                    stepState.status = BuildStepState.SKIPPED;
                    stepState.decision = "skipped";
                    stepState.lastError = BuildQueueManager.bounded(reason, 160);
                    stepState.settledTick = now;
                    clearActive();
                    persist(now);
                }
                case "blocked" -> stateBlocked(now, reason.isBlank() ? "step_blocked" : reason);
                default -> stateNeedsLlm(now, judgeActual, reason.isBlank() ? "llm" : reason);
            }
        }

        private boolean applyAdjustment(@Nullable BuildStep step, @Nullable JsonObject adjustment) {
            if (step == null || adjustment == null) return false;
            BuildStep candidate = BuildQueueManager.copyStep(step, step.index);
            try {
                if (adjustment.has("block")) candidate.block = BuildQueueManager.bounded(adjustment.get("block").getAsString(), 256);
                if (adjustment.has("item")) candidate.item = BuildQueueManager.bounded(adjustment.get("item").getAsString(), 128);
                if (adjustment.has("tool")) candidate.tool = BuildQueueManager.bounded(adjustment.get("tool").getAsString(), 128);
                if (adjustment.has("face")) candidate.face = BuildQueueManager.bounded(adjustment.get("face").getAsString(), 16);
                if (adjustment.has("note")) candidate.note = BuildQueueManager.bounded(adjustment.get("note").getAsString(), 256);
                if (adjustment.has("x")) candidate.x = adjustment.get("x").getAsInt();
                if (adjustment.has("y")) candidate.y = adjustment.get("y").getAsInt();
                if (adjustment.has("z")) candidate.z = adjustment.get("z").getAsInt();
                if (adjustment.has("strict")) candidate.strict = adjustment.get("strict").getAsBoolean();
                BuildQueueManager.validateStep(candidate);
            } catch (RuntimeException invalid) {
                return false;
            }
            step.block = candidate.block;
            step.item = candidate.item;
            step.tool = candidate.tool;
            step.face = candidate.face;
            step.note = candidate.note;
            step.x = candidate.x;
            step.y = candidate.y;
            step.z = candidate.z;
            step.strict = candidate.strict;
            return true;
        }

        private void markDone(long now, @Nullable BuildStep step, @Nullable BuildStepState stepState, String decision) {
            if (stepState != null) {
                stepState.status = BuildStepState.DONE;
                stepState.decision = decision;
                stepState.lastError = "";
                stepState.settledTick = now;
            }
            clearActive();
            persist(now);
        }

        private void stateNeedsLlm(long now, JsonObject actual, String reason) {
            BuildStepState stepState = activeState;
            if (stepState != null) {
                stepState.status = BuildStepState.FAILED;
                stepState.decision = "needs_llm";
                stepState.lastError = BuildQueueManager.bounded(reason, 160);
                stepState.settledTick = now;
            }
            queue.status = "paused";
            queue.pausedReason = "needs_llm";
            queue.updatedTick = now;
            persist(now);
            JsonObject out = new JsonObject();
            out.addProperty("success", false);
            out.addProperty("status", "needs_llm");
            out.addProperty("error", "needs_llm");
            out.addProperty("design_id", design.id);
            out.addProperty("structure_name", design.displayName());
            out.addProperty("attempts", stepState == null ? 0 : stepState.attempts);
            out.addProperty("reason", BuildQueueManager.bounded(reason, 256));
            if (activeStep != null) out.add("step", BuildQueueManager.stepRow(activeStep, stepState));
            out.add("actual", actual == null ? new JsonObject() : actual.deepCopy());
            finish(out);
        }

        private void stateBlocked(long now, String reason) {
            BuildStepState stepState = activeState;
            String text = BuildQueueManager.bounded(reason == null || reason.isBlank() ? "step_blocked" : reason, 160);
            if (stepState != null) {
                stepState.status = BuildStepState.BLOCKED;
                stepState.decision = "blocked";
                stepState.lastError = text;
                stepState.settledTick = now;
            }
            queue.status = "blocked";
            queue.pausedReason = text;
            queue.updatedTick = now;
            persist(now);
            JsonObject out = new JsonObject();
            out.addProperty("success", false);
            out.addProperty("status", "blocked");
            out.addProperty("error", "step_blocked");
            out.addProperty("design_id", design.id);
            out.addProperty("reason", text);
            if (activeStep != null) out.add("blocked_step", BuildQueueManager.stepRow(activeStep, stepState));
            finish(out);
        }

        private void blockMissing(long now, String reason) {
            BuildStepState stepState = activeState;
            if (stepState != null) {
                stepState.status = BuildStepState.BLOCKED;
                stepState.decision = "blocked";
                stepState.lastError = "missing_items";
                stepState.settledTick = now;
            }
            LinkedHashMap<String, Integer> missing = BuildQueueManager.missingFromMaid(maid, design);
            for (String item : BuildQueueManager.missingForStep(maid, activeStep)) {
                missing.putIfAbsent(item, 1);
            }
            queue.status = "paused";
            queue.pausedReason = "missing_items";
            queue.updatedTick = now;
            persist(now);
            JsonObject out = new JsonObject();
            out.addProperty("success", false);
            out.addProperty("status", "failed");
            out.addProperty("error", "missing_items");
            out.addProperty("design_id", design.id);
            out.addProperty("structure_name", design.displayName());
            out.add("missing_items", BuildQueueManager.stringArray(missing.keySet()));
            out.add("missing_from_maid", BuildQueueManager.itemCountsJson(missing));
            if (activeStep != null) out.add("blocked_step", BuildQueueManager.stepRow(activeStep, stepState));
            if (reason != null && !reason.isBlank()) out.addProperty("reason", reason);
            finish(out);
        }

        private void completeRun(long now) {
            queue.status = "completed";
            queue.pausedReason = "";
            queue.updatedTick = now;
            persist(now);
            recordMutation();
            JsonObject out = new JsonObject();
            out.addProperty("success", true);
            out.addProperty("status", "completed");
            out.addProperty("design_id", design.id);
            out.addProperty("structure_name", design.displayName());
            out.addProperty("dimension", design.dimension);
            out.add("progress", BuildQueueManager.progressJson(queue));
            out.addProperty("started_steps", startedSteps);
            out.addProperty("elapsed_ticks", now - startTick);
            finish(out);
        }

        private void completeFailedSteps(long now) {
            queue.status = "blocked";
            queue.pausedReason = "steps_failed";
            queue.updatedTick = now;
            persist(now);
            JsonObject out = new JsonObject();
            out.addProperty("success", false);
            out.addProperty("status", "failed");
            out.addProperty("error", "steps_failed");
            out.addProperty("design_id", design.id);
            out.add("progress", BuildQueueManager.progressJson(queue));
            for (Integer index : queue.order) {
                BuildStepState stepState = queue.stepState(index);
                if (stepState == null) continue;
                if (BuildStepState.FAILED.equals(stepState.status) || BuildStepState.BLOCKED.equals(stepState.status)) {
                    out.add("blocked_step", BuildQueueManager.stepRow(design.steps.get(index), stepState));
                    break;
                }
            }
            finish(out);
        }

        private void pauseWith(String reason, boolean success) {
            long now = levelTime();
            queue.status = "paused";
            queue.pausedReason = reason;
            queue.updatedTick = now;
            persist(now);
            JsonObject out = new JsonObject();
            out.addProperty("success", success);
            out.addProperty("status", success ? "paused" : "failed");
            if (success) out.addProperty("paused_reason", reason);
            else out.addProperty("error", reason);
            out.addProperty("design_id", design.id);
            out.add("progress", BuildQueueManager.progressJson(queue));
            finish(out);
        }

        private void cancelled(String reason) {
            long now = levelTime();
            queue.status = "paused";
            queue.pausedReason = reason;
            queue.updatedTick = now;
            persist(now);
            JsonObject out = new JsonObject();
            out.addProperty("success", false);
            out.addProperty("status", "cancelled");
            out.addProperty("error", reason);
            out.addProperty("design_id", design.id);
            finish(out);
        }

        private void failInternal() {
            try {
                queue.status = "paused";
                queue.pausedReason = "internal_error";
                queue.updatedTick = levelTime();
                BuildQueueData.save(maid);
            } catch (RuntimeException ignored) {
            }
            JsonObject out = new JsonObject();
            out.addProperty("success", false);
            out.addProperty("status", "failed");
            out.addProperty("error", "internal_error");
            out.addProperty("design_id", design.id);
            finish(out);
        }

        private void finish(JsonObject result) {
            RUNS.remove(maid.getUUID(), this);
            future.complete(result);
        }

        private void clearActive() {
            activeStep = null;
            activeState = null;
            phase = Phase.IDLE;
        }

        private void persist(long now) {
            queue.updatedTick = now;
            BuildQueueData.save(maid);
        }

        private JsonObject withActionError(JsonObject actual, MaidBlockActionResult result) {
            JsonObject out = actual == null ? new JsonObject() : actual.deepCopy();
            if (result != null) {
                if (!result.status().isBlank()) out.addProperty("action_status", result.status());
                if (!result.error().isBlank()) out.addProperty("action_error", result.error());
                if (!result.detail().isBlank()) out.addProperty("action_detail", BuildQueueManager.bounded(result.detail(), 256));
            }
            return out;
        }

        private String mismatchReason(MaidBlockActionResult result, JsonObject actual) {
            if (result != null && !result.error().isBlank()) return result.error();
            if (result != null && !result.status().isBlank()) return result.status();
            String reason = actual != null && actual.has("reason") ? actual.get("reason").getAsString() : "";
            return reason.isBlank() ? "verification_mismatch" : reason;
        }

        private JsonObject actionError(@Nullable BuildStep step, String error, @Nullable Throwable thrown) {
            JsonObject actual = new JsonObject();
            actual.addProperty("verified", false);
            actual.addProperty("expected", step == null ? "" : ("mine".equals(step.kindOrPlace()) ? "minecraft:air" : step.block));
            actual.addProperty("actual", "");
            actual.addProperty("reason", error);
            if (thrown != null) actual.addProperty("detail", BuildQueueManager.message(thrown, ""));
            if (step != null) actual.add("position", BuildQueueManager.positionJson(step.x, step.y, step.z));
            return actual;
        }

        private void recordMutation() {
            if (!(callback instanceof TaskCallback task)) return;
            try {
                task.recordMutation(BuildQueueSummary.runFact(design, queue));
            } catch (RuntimeException failed) {
                TouhouAIFun.LOGGER.warn("AIFun build run mutation summary not recorded: {}", failed.getMessage());
            }
        }

        private MaidBlockActionRequest requestFor(BuildStep step) {
            MaidBlockActionRequest.Action action = switch (step.kindOrPlace()) {
                case "mine" -> MaidBlockActionRequest.Action.MINE;
                case "interact" -> MaidBlockActionRequest.Action.INTERACT;
                default -> MaidBlockActionRequest.Action.PLACE;
            };
            Direction face = null;
            if (step.face != null && !step.face.isBlank()) face = Direction.byName(step.face);
            String expected;
            if (action == MaidBlockActionRequest.Action.MINE) expected = null;
            else expected = step.block == null || step.block.isBlank() ? null : step.block;
            String item = step.item == null || step.item.isBlank() ? null : step.item;
            String tool = step.tool == null || step.tool.isBlank() ? null : step.tool;
            BlockPos target = new BlockPos(step.x, step.y, step.z).immutable();
            return new MaidBlockActionRequest(action, target, face, null, item, tool, expected, step.strict,
                    actionTimeoutTicks());
        }

        private long levelTime() {
            try {
                return maid.level().getGameTime();
            } catch (RuntimeException unavailable) {
                return 0L;
            }
        }
    }

    private static int actionMaxAttempts() {
        try {
            if (TouhouAIFunConfig.SPEC.isLoaded()) {
                return BuildQueueManager.clamp(TouhouAIFunConfig.BLOCK_ACTION_MAX_ATTEMPTS.get(), 1, 5);
            }
        } catch (RuntimeException ignored) {
        }
        return DEFAULT_MAX_ATTEMPTS;
    }

    private static int actionTimeoutTicks() {
        try {
            if (TouhouAIFunConfig.SPEC.isLoaded()) {
                return BuildQueueManager.clamp(TouhouAIFunConfig.BLOCK_ACTION_TIMEOUT_SECONDS.get(), 1, 120) * 20;
            }
        } catch (RuntimeException ignored) {
        }
        return MaidBlockActionRequest.DEFAULT_TIMEOUT_TICKS;
    }
}
