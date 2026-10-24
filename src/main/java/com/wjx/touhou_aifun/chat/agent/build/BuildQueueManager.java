package com.wjx.touhou_aifun.chat.agent.build;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wjx.touhou_aifun.chat.agent.TaskCallback;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionManager;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionRequest;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockStateMatcher;
import com.wjx.touhou_aifun.maid.action.block.MaidItemSelection;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-thread owner of the build queue: design persistence, read-only status/verification and the
 * run entry point. It never edits the world directly; every block change goes through
 * {@link MaidBlockActionManager}.
 */
public final class BuildQueueManager {
    public static final String MATERIAL_POLICY_ASK_OWNER = "ask_owner";
    private static final int MAX_DESIGNS_FALLBACK = 8;
    private static final int MAX_STEPS_PER_DESIGN_FALLBACK = 4096;
    private static final int MAX_STEPS_PER_CALL_FALLBACK = 256;
    private static final int RUN_MAX_SECONDS_FALLBACK = 600;

    private BuildQueueManager() {
    }

    // ------------------------------------------------------------------ state / design

    public static BuildQueueState state(EntityMaid maid) {
        requireServerThread(maid);
        return BuildQueueData.get(maid);
    }

    @Nullable
    public static BuildDesign design(EntityMaid maid, String designId) {
        requireServerThread(maid);
        BuildQueueState state = BuildQueueData.get(maid);
        if (designId == null || designId.isBlank()) return state.activeDesign();
        return state.design(designId);
    }

    /** Creates a design or appends steps to an existing one. Validation errors are JSON, not throws. */
    public static JsonObject design(EntityMaid maid, JsonObject args) {
        requireServerThread(maid);
        BuildQueueState state = BuildQueueData.get(maid);
        try {
            return createOrAppend(maid, state, args == null ? new JsonObject() : args);
        } catch (IllegalArgumentException invalid) {
            return failure(message(invalid, "invalid_arguments"));
        } catch (IllegalStateException tooLarge) {
            return failure(message(tooLarge, "build_queue_state_too_large"));
        }
    }

    private static JsonObject createOrAppend(EntityMaid maid, BuildQueueState state, JsonObject args) {
        if (BuildQueueRunner.running(maid.getUUID())) throw new IllegalArgumentException("design_in_use");
        String requestedId = stringArg(args, "design_id", "").trim();
        boolean append = boolArg(args, "append", false);
        BuildDesign previous = null;
        if (append) {
            if (requestedId.isBlank()) throw new IllegalArgumentException("design_id_required_for_append");
            previous = state.design(requestedId);
            if (previous == null) throw new IllegalArgumentException("unknown_design");
        } else if (!requestedId.isBlank()) {
            if (state.design(requestedId) != null) throw new IllegalArgumentException("design_id_exists");
        }
        String structureName = stringArg(args, "structure_name", previous == null ? "structure" : previous.displayName()).trim();
        if (structureName.isBlank()) structureName = "structure";
        if (structureName.length() > 128) structureName = structureName.substring(0, 128);
        String dimension = resolveDimension(maid, args, previous);
        int[] origin = resolveOrigin(maid, args);
        String order = stringArg(args, "order", "bottom_up").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("bottom_up", "as_given", "layers").contains(order)) throw new IllegalArgumentException("invalid_order");
        JsonArray rawSteps = args.has("steps") && args.get("steps").isJsonArray()
                ? args.getAsJsonArray("steps") : new JsonArray();
        int maxPerCall = maxStepsPerCall();
        if (rawSteps.isEmpty()) throw new IllegalArgumentException("steps_required");
        if (rawSteps.size() > maxPerCall) throw new IllegalArgumentException("too_many_steps");

        BuildDesign candidate = new BuildDesign();
        candidate.id = append ? previous.id : (requestedId.isBlank() ? UUID.randomUUID().toString() : requestedId);
        if (candidate.id.length() > 128) throw new IllegalArgumentException("invalid_design_id");
        candidate.structureName = structureName;
        candidate.dimension = dimension;
        candidate.originX = origin[0];
        candidate.originY = origin[1];
        candidate.originZ = origin[2];
        candidate.createdTick = append ? previous.createdTick : levelGameTime(maid);
        candidate.steps = new ArrayList<>();
        if (append) {
            for (BuildStep step : previous.steps) candidate.steps.add(copyStep(step, step.index));
        } else if (state.designs.size() >= maxDesigns()) {
            throw new IllegalArgumentException("design_limit");
        }
        Set<String> placePositions = new HashSet<>();
        Set<String> minePositions = new HashSet<>();
        for (BuildStep step : candidate.steps) {
            if ("place".equals(step.kindOrPlace())) placePositions.add(step.x + "," + step.y + "," + step.z);
            else if ("mine".equals(step.kindOrPlace())) minePositions.add(step.x + "," + step.y + "," + step.z);
        }
        for (JsonElement element : rawSteps) {
            if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("invalid_step");
            BuildStep step = parseStep(element.getAsJsonObject(), origin[0], origin[1], origin[2], candidate.steps.size());
            String position = step.x + "," + step.y + "," + step.z;
            if ("place".equals(step.kindOrPlace()) && !placePositions.add(position))
                throw new IllegalArgumentException("duplicate_place_position");
            if ("mine".equals(step.kindOrPlace())) minePositions.add(position);
            candidate.steps.add(step);
        }
        for (String position : placePositions) {
            if (minePositions.contains(position)) throw new IllegalArgumentException("conflicting_position");
        }
        if (candidate.steps.size() > maxStepsPerDesign()) throw new IllegalArgumentException("design_step_limit");
        sortSteps(candidate, order);
        candidate.refreshFingerprint();

        List<BuildDesign> designsBackup = new ArrayList<>(state.designs);
        String activeBackup = state.activeDesignId;
        String statusBackup = state.status;
        String pausedBackup = state.pausedReason;
        long updatedBackup = state.updatedTick;
        List<BuildStepState> stepsBackup = new ArrayList<>(state.steps);
        List<Integer> orderBackup = new ArrayList<>(state.order);
        installDesign(maid, state, candidate, previous);
        try {
            BuildQueueData.ensureWithinLimit(maid, state);
        } catch (IllegalStateException tooLarge) {
            state.designs = designsBackup;
            state.activeDesignId = activeBackup;
            state.status = statusBackup;
            state.pausedReason = pausedBackup;
            state.updatedTick = updatedBackup;
            state.steps = stepsBackup;
            state.order = orderBackup;
            throw tooLarge;
        }
        BuildQueueData.save(maid);
        return designSavedJson(maid, state, candidate, append);
    }

    private static JsonObject designSavedJson(EntityMaid maid, BuildQueueState state, BuildDesign design, boolean append) {
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", "design_saved");
        out.addProperty("design_id", design.id);
        out.addProperty("structure_name", design.displayName());
        out.addProperty("dimension", design.dimension);
        out.addProperty("steps", design.steps.size());
        out.addProperty("total_designs", state.designs.size());
        out.addProperty("append", append);
        out.addProperty("active_design_id", state.activeDesignId);
        out.addProperty("fingerprint", design.fingerprint);
        out.add("origin", positionJson(design.originX, design.originY, design.originZ));
        out.add("required_item_counts", itemCountsJson(requiredItemCounts(maid, design)));
        out.add("missing_from_maid", itemCountsJson(missingFromMaid(maid, design)));
        out.add("missing_items", stringArray(missingFromMaid(maid, design).keySet()));
        return out;
    }

    @Nullable
    private static BuildDesign resolveDesign(EntityMaid maid, BuildQueueState state, JsonObject args) {
        String designId = stringArg(args, "design_id", "");
        if (!designId.isBlank()) return state.design(designId);
        return state.activeDesign();
    }

    /** Installs a candidate design, remapping existing step states by absolute position. */
    private static void installDesign(EntityMaid maid, BuildQueueState state, BuildDesign candidate,
                                      @Nullable BuildDesign previous) {
        Map<String, BuildStepState> oldStates = new HashMap<>();
        if (previous != null) {
            for (int i = 0; i < previous.steps.size(); i++) {
                BuildStep step = previous.steps.get(i);
                BuildStepState old = state.stepState(i);
                if (step != null && old != null) oldStates.put(step.positionKey(), old);
            }
        }
        int at = previous == null ? -1 : state.designs.indexOf(previous);
        if (at >= 0) state.designs.set(at, candidate);
        else state.designs.add(candidate);
        state.activeDesignId = candidate.id;
        List<BuildStepState> rebuilt = new ArrayList<>(candidate.steps.size());
        for (int i = 0; i < candidate.steps.size(); i++) {
            BuildStep step = candidate.steps.get(i);
            step.index = i;
            BuildStepState stepState = oldStates.remove(step.positionKey());
            if (stepState == null) stepState = new BuildStepState(i);
            stepState.index = i;
            rebuilt.add(stepState);
        }
        state.steps = rebuilt;
        state.order = new ArrayList<>(candidate.steps.size());
        for (int i = 0; i < candidate.steps.size(); i++) state.order.add(i);
        state.updatedTick = levelGameTime(maid);
        if (previous == null) {
            state.status = "idle";
            state.pausedReason = "";
        }
        if (state.steps.size() != candidate.steps.size()) state.ensureStepStates();
    }

    // ------------------------------------------------------------------ status / view

    public static JsonObject status(EntityMaid maid, JsonObject args, boolean foreground) {
        requireServerThread(maid);
        BuildQueueState state = BuildQueueData.get(maid);
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        BuildDesign design = resolveDesign(maid, state, safeArgs);
        if (design == null) {
            JsonObject out = new JsonObject();
            out.addProperty("success", true);
            out.addProperty("status", state.status);
            out.addProperty("design_count", state.designs.size());
            out.addProperty("material_policy", MATERIAL_POLICY_ASK_OWNER);
            out.add("queue", queueSummary(state));
            return out;
        }
        state.ensureStepStates();
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", state.status);
        out.add("design", designSummary(design));
        out.add("queue", queueSummary(state));
        out.add("progress", progressJson(state));
        int offset = Math.max(0, intArg(safeArgs, "offset", 0));
        int limit = clamp(intArg(safeArgs, "limit", foreground ? 4 : 12), 1, 64);
        boolean includeDone = boolArg(safeArgs, "include_done", false);
        JsonArray page = new JsonArray();
        List<Integer> visible = new ArrayList<>();
        for (Integer index : state.order) {
            BuildStepState stepState = state.stepState(index);
            if (!includeDone && stepState != null && BuildStepState.DONE.equals(stepState.status)) continue;
            visible.add(index);
        }
        int start = Math.min(offset, visible.size());
        int end = Math.min(start + limit, visible.size());
        for (int i = start; i < end; i++) page.add(stepRow(design.steps.get(visible.get(i)), state.stepState(visible.get(i))));
        out.add("steps", page);
        JsonObject pagination = new JsonObject();
        pagination.addProperty("offset", offset);
        pagination.addProperty("limit", limit);
        pagination.addProperty("returned", page.size());
        pagination.addProperty("next_offset", end < visible.size() ? end : -1);
        out.add("pagination", pagination);
        out.add("required_item_counts", itemCountsJson(requiredItemCounts(maid, design)));
        LinkedHashMap<String, Integer> missing = missingFromMaid(maid, design);
        out.add("missing_from_maid", itemCountsJson(missing));
        out.add("missing_items", stringArray(missing.keySet()));
        JsonObject lastDecision = lastDecisionJson(state);
        if (lastDecision != null) out.add("last_decision", lastDecision);
        out.addProperty("material_policy", MATERIAL_POLICY_ASK_OWNER);
        return out;
    }

    public static JsonObject verify(EntityMaid maid, JsonObject args) {
        requireServerThread(maid);
        BuildQueueState state = BuildQueueData.get(maid);
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        BuildDesign design = resolveDesign(maid, state, safeArgs);
        if (design == null) return failure("no_design");
        boolean fullScan = boolArg(safeArgs, "full_scan", false);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("design_id", design.id);
        if (safeArgs.has("step") || safeArgs.has("position")) {
            BuildStep step = findStep(design, safeArgs);
            if (step == null) return failure("unknown_step");
            JsonObject result = BuildStepVerifier.verify(maid, design, step, fullScan);
            result.addProperty("index", step.index);
            out.addProperty("verified", result.get("verified").getAsBoolean());
            out.add("step", result);
            return out;
        }
        JsonArray rows = BuildStepVerifier.mismatches(maid, design, true);
        out.add("mismatches", rows);
        out.addProperty("checked", design.steps.size());
        out.addProperty("mismatch_count", rows.size());
        out.addProperty("verified", rows.isEmpty());
        return out;
    }

    /** Read-only foreground summary (<= 2 KB); null when the maid has no design at all. */
    @Nullable
    public static JsonObject foregroundView(EntityMaid maid) {
        requireServerThread(maid);
        BuildQueueState state = BuildQueueData.get(maid);
        BuildDesign design = state.activeDesign();
        if (design == null || design.steps.isEmpty()) return null;
        state.ensureStepStates();
        JsonObject out = new JsonObject();
        out.addProperty("design_id", design.id);
        out.addProperty("structure_name", bounded(design.displayName(), 64));
        out.addProperty("dimension", bounded(design.dimension, 64));
        out.addProperty("status", bounded(state.status, 16));
        out.addProperty("total", design.steps.size());
        out.addProperty("done", state.countStatus(BuildStepState.DONE));
        out.addProperty("pending", state.countStatus(BuildStepState.PENDING) + state.countStatus(BuildStepState.READY));
        out.addProperty("failed", state.countStatus(BuildStepState.FAILED));
        out.addProperty("skipped", state.countStatus(BuildStepState.SKIPPED));
        BuildStepState current = currentStepState(state);
        if (current != null && current.index >= 0 && current.index < design.steps.size()) {
            out.add("current_step", currentStepJson(design.steps.get(current.index), current));
        }
        BuildStepState blocked = firstStatus(state, BuildStepState.BLOCKED, BuildStepState.FAILED);
        if (blocked != null && blocked.index >= 0 && blocked.index < design.steps.size()) {
            out.add("blocked_step", currentStepJson(design.steps.get(blocked.index), blocked));
        }
        LinkedHashMap<String, Integer> missing = missingFromMaid(maid, design);
        List<String> ids = new ArrayList<>(missing.keySet());
        out.add("missing_items", stringArray(ids.size() > 12 ? ids.subList(0, 12) : ids));
        JsonObject lastDecision = lastDecisionJson(state);
        if (lastDecision != null) out.add("last_decision", lastDecision);
        out.addProperty("updated_tick", state.updatedTick);
        return out;
    }

    // ------------------------------------------------------------------ queue operations

    public static JsonObject reorder(EntityMaid maid, JsonObject args) {
        requireServerThread(maid);
        if (BuildQueueRunner.running(maid.getUUID())) return failure("build_running");
        BuildQueueState state = BuildQueueData.get(maid);
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        BuildDesign design = resolveDesign(maid, state, safeArgs);
        if (design == null) return failure("no_design");
        state.ensureStepStates();
        Map<String, BuildStepState> oldStates = new HashMap<>();
        for (int i = 0; i < design.steps.size(); i++) {
            BuildStep step = design.steps.get(i);
            BuildStepState stepState = state.stepState(i);
            if (step != null && stepState != null) oldStates.put(step.positionKey(), stepState);
        }
        if (safeArgs.has("indices") && safeArgs.get("indices").isJsonArray()) {
            JsonArray indices = safeArgs.getAsJsonArray("indices");
            if (indices.size() != design.steps.size()) return failure("invalid_order");
            Set<Integer> seen = new HashSet<>();
            List<BuildStep> reordered = new ArrayList<>(design.steps.size());
            for (JsonElement element : indices) {
                int index = element.getAsInt();
                if (index < 0 || index >= design.steps.size() || !seen.add(index)) return failure("invalid_order");
                reordered.add(design.steps.get(index));
            }
            design.steps = reordered;
        } else {
            String order = stringArg(safeArgs, "order", "bottom_up").trim().toLowerCase(Locale.ROOT);
            if (!Set.of("bottom_up", "as_given", "layers").contains(order)) return failure("invalid_order");
            sortSteps(design, order);
        }
        design.reindex();
        List<BuildStepState> rebuilt = new ArrayList<>(design.steps.size());
        for (int i = 0; i < design.steps.size(); i++) {
            BuildStep step = design.steps.get(i);
            BuildStepState stepState = oldStates.remove(step.positionKey());
            if (stepState == null) stepState = new BuildStepState(i);
            stepState.index = i;
            rebuilt.add(stepState);
        }
        state.steps = rebuilt;
        state.order = new ArrayList<>(design.steps.size());
        for (int i = 0; i < design.steps.size(); i++) state.order.add(i);
        design.refreshFingerprint();
        state.updatedTick = levelGameTime(maid);
        BuildQueueData.save(maid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", "reordered");
        out.addProperty("design_id", design.id);
        out.add("order", intArray(state.order));
        return out;
    }

    public static JsonObject skip(EntityMaid maid, JsonObject args) {
        requireServerThread(maid);
        if (BuildQueueRunner.running(maid.getUUID())) return failure("build_running");
        BuildQueueState state = BuildQueueData.get(maid);
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        BuildDesign design = resolveDesign(maid, state, safeArgs);
        if (design == null) return failure("no_design");
        state.ensureStepStates();
        long now = levelGameTime(maid);
        int skipped = 0;
        if (boolArg(safeArgs, "all_failed", false)) {
            for (BuildStepState stepState : state.steps) {
                if (stepState == null) continue;
                if (BuildStepState.FAILED.equals(stepState.status) || BuildStepState.BLOCKED.equals(stepState.status)) {
                    stepState.status = BuildStepState.SKIPPED;
                    stepState.decision = "skipped";
                    stepState.settledTick = now;
                    skipped++;
                }
            }
        } else {
            BuildStep step = findStep(design, safeArgs);
            if (step == null) return failure("unknown_step");
            BuildStepState stepState = state.ensureStepState(step.index);
            stepState.status = BuildStepState.SKIPPED;
            stepState.decision = "skipped";
            stepState.settledTick = now;
            skipped = 1;
        }
        state.updatedTick = now;
        BuildQueueData.save(maid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", "skipped");
        out.addProperty("design_id", design.id);
        out.addProperty("skipped", skipped);
        out.add("progress", progressJson(state));
        return out;
    }

    public static JsonObject reset(EntityMaid maid, JsonObject args) {
        requireServerThread(maid);
        if (BuildQueueRunner.running(maid.getUUID())) return failure("build_running");
        BuildQueueState state = BuildQueueData.get(maid);
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        BuildDesign design = resolveDesign(maid, state, safeArgs);
        if (design == null) return failure("no_design");
        state.ensureStepStates();
        boolean failedOnly = boolArg(safeArgs, "failed_only", false);
        int reset = 0;
        if (safeArgs.has("step") || safeArgs.has("position")) {
            BuildStep step = findStep(design, safeArgs);
            if (step == null) return failure("unknown_step");
            resetState(state.ensureStepState(step.index), true);
            reset = 1;
        } else {
            for (BuildStepState stepState : state.steps) {
                if (stepState == null) continue;
                if (failedOnly && !BuildStepState.FAILED.equals(stepState.status)
                        && !BuildStepState.BLOCKED.equals(stepState.status)) continue;
                resetState(stepState, true);
                reset++;
            }
        }
        state.status = "idle";
        state.pausedReason = "";
        state.updatedTick = levelGameTime(maid);
        BuildQueueData.save(maid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", "reset");
        out.addProperty("design_id", design.id);
        out.addProperty("reset", reset);
        return out;
    }

    public static JsonObject pause(EntityMaid maid, String reason) {
        requireServerThread(maid);
        BuildQueueState state = BuildQueueData.get(maid);
        String value = bounded(reason == null || reason.isBlank() ? "paused_by_owner" : reason.trim(), 128);
        BuildQueueRunner.cancelForMaid(maid.getUUID(), value);
        state.status = "paused";
        state.pausedReason = value;
        state.updatedTick = levelGameTime(maid);
        BuildQueueData.save(maid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", "paused");
        out.addProperty("paused_reason", value);
        out.addProperty("design_id", state.activeDesignId);
        return out;
    }

    /** Extra public helper for the tool's resume action; resets failed/blocked steps for another run. */
    public static JsonObject resume(EntityMaid maid, JsonObject args) {
        requireServerThread(maid);
        if (BuildQueueRunner.running(maid.getUUID())) {
            JsonObject out = new JsonObject();
            out.addProperty("success", true);
            out.addProperty("status", "running");
            return out;
        }
        BuildQueueState state = BuildQueueData.get(maid);
        if (state.activeDesign() == null && state.designs.isEmpty()) return failure("no_design");
        state.ensureStepStates();
        boolean retryFailed = boolArg(args == null ? new JsonObject() : args, "retry_failed", true);
        if (retryFailed) {
            for (BuildStepState stepState : state.steps) {
                if (stepState == null) continue;
                if (BuildStepState.FAILED.equals(stepState.status) || BuildStepState.BLOCKED.equals(stepState.status)
                        || BuildStepState.RUNNING.equals(stepState.status) || BuildStepState.READY.equals(stepState.status))
                    resetState(stepState, true);
            }
        }
        state.status = "idle";
        state.pausedReason = "";
        state.updatedTick = levelGameTime(maid);
        BuildQueueData.save(maid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", "idle");
        out.addProperty("design_id", state.activeDesignId);
        return out;
    }

    public static JsonObject clear(EntityMaid maid, JsonObject args) {
        requireServerThread(maid);
        if (BuildQueueRunner.running(maid.getUUID())) return failure("build_running");
        BuildQueueState state = BuildQueueData.get(maid);
        state.ensureStepStates();
        int cleared = state.steps.size();
        state.clearExecutionState();
        state.updatedTick = levelGameTime(maid);
        BuildQueueData.save(maid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", "idle");
        out.addProperty("steps_cleared", cleared);
        out.addProperty("designs_kept", state.designs.size());
        out.addProperty("active_design_id", state.activeDesignId);
        return out;
    }

    public static JsonObject deleteDesign(EntityMaid maid, JsonObject args) {
        requireServerThread(maid);
        if (BuildQueueRunner.running(maid.getUUID())) return failure("build_running");
        BuildQueueState state = BuildQueueData.get(maid);
        String designId = stringArg(args == null ? new JsonObject() : args, "design_id", "");
        if (designId.isBlank()) return failure("design_id_required");
        BuildDesign design = state.design(designId);
        if (design == null) return failure("unknown_design");
        // AgentRuntime exposes no public task list, so referenced-by-completion cannot be enumerated.
        // Conservative rule: the active design stays while the queue is paused/blocked on it.
        if (designId.equals(state.activeDesignId) && !Set.of("idle", "completed", "cancelled").contains(state.status))
            return failure("design_in_use");
        state.designs.remove(design);
        if (designId.equals(state.activeDesignId)) {
            state.activeDesignId = "";
            state.steps = new ArrayList<>();
            state.order = new ArrayList<>();
        }
        state.updatedTick = levelGameTime(maid);
        BuildQueueData.save(maid);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("status", "deleted");
        out.addProperty("design_id", designId);
        out.addProperty("remaining_designs", state.designs.size());
        out.addProperty("active_design_id", state.activeDesignId);
        return out;
    }

    // ------------------------------------------------------------------ materials

    public static JsonObject requestMaterials(EntityMaid maid, JsonObject args) {
        requireServerThread(maid);
        BuildQueueState state = BuildQueueData.get(maid);
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        BuildDesign design = resolveDesign(maid, state, safeArgs);
        if (design == null) return failure("no_design");
        LinkedHashMap<String, Integer> required = requiredItemCounts(maid, design);
        LinkedHashMap<String, Integer> missing = missingFromMaid(maid, design);
        JsonObject out = new JsonObject();
        out.addProperty("success", true);
        out.addProperty("design_id", design.id);
        if (missing.isEmpty()) {
            out.addProperty("status", "nothing_missing");
            return out;
        }
        out.addProperty("status", "materials_required");
        out.add("missing_items", stringArray(missing.keySet()));
        out.add("required_item_counts", itemCountsJson(required));
        out.add("missing_from_maid", itemCountsJson(missing));
        return out;
    }

    public static Set<String> missingItemIds(EntityMaid maid) {
        requireServerThread(maid);
        BuildQueueState state = BuildQueueData.get(maid);
        BuildDesign design = state.activeDesign();
        if (design == null) return Set.of();
        return missingFromMaid(maid, design).keySet();
    }

    // ------------------------------------------------------------------ run

    public static CompletableFuture<JsonObject> run(EntityMaid maid, Object callback, JsonObject args) {
        requireServerThread(maid);
        BuildQueueState state = BuildQueueData.get(maid);
        JsonObject safeArgs = args == null ? new JsonObject() : args;
        if (!(callback instanceof TaskCallback task)) return completed(failure("task_execution_only"));
        if (task.stopped()) return completed(failure("task_stopped"));
        String designId = stringArg(safeArgs, "design_id", "");
        BuildDesign design = designId.isBlank() ? state.activeDesign() : state.design(designId);
        if (design == null) return completed(failure("no_design"));
        if (state.ownerUUID != null && !state.ownerUUID.isBlank() && !state.ownerUUID.equals(ownerOf(maid)))
            return completed(failure("owner_changed"));
        if (task.task.completion == null) return completed(failure("completion_required_before_build"));
        if (!completionReferencesStructure(task.task.completion, design.id))
            return completed(failure("design_not_in_completion"));
        if (!design.dimension.equals(dimension(maid))) return completed(failure("dimension_mismatch"));
        if (MaidGuiSessionManager.session(maid.getUUID()) != null) return completed(failure("gui_open_close_first"));
        if (MaidBlockActionManager.busy(maid.getUUID())) return completed(failure("action_busy"));
        if (BuildQueueRunner.running(maid.getUUID())) return completed(failure("design_in_use"));
        int maxSteps;
        int maxSeconds;
        try {
            maxSteps = clamp(intArgRequired(safeArgs, "max_steps", 64), 1, 256);
            maxSeconds = clamp(intArgRequired(safeArgs, "max_seconds", 600), 1, runMaxSeconds());
        } catch (IllegalArgumentException invalid) {
            return completed(failure(message(invalid, "invalid_arguments")));
        }
        boolean retryFailed = boolArg(safeArgs, "retry_failed", false);
        boolean repair = boolArg(safeArgs, "repair", false);
        state.activeDesignId = design.id;
        state.taskId = task.task.id;
        state.taskGeneration = task.task.generation;
        state.goalVersion = task.task.goalVersion;
        state.ensureStepStates();
        if (retryFailed) {
            for (BuildStepState stepState : state.steps) {
                if (stepState != null && BuildStepState.FAILED.equals(stepState.status)) resetState(stepState, true);
            }
        }
        if (repair) repairDoneSteps(maid, design, state);
        state.status = "running";
        state.pausedReason = "";
        state.updatedTick = levelGameTime(maid);
        BuildQueueData.save(maid);
        return BuildQueueRunner.start(maid, callback, design, state, maxSteps, maxSeconds, retryFailed);
    }

    private static void repairDoneSteps(EntityMaid maid, BuildDesign design, BuildQueueState state) {
        for (BuildStepState stepState : state.steps) {
            if (stepState == null || !BuildStepState.DONE.equals(stepState.status)) continue;
            if (stepState.index < 0 || stepState.index >= design.steps.size()) continue;
            JsonObject result = BuildStepVerifier.verify(maid, design, design.steps.get(stepState.index), false);
            if (!result.get("verified").getAsBoolean()) {
                resetState(stepState, true);
                stepState.decision = "repair";
            }
        }
    }

    private static boolean completionReferencesStructure(JsonObject completion, String designId) {
        return scanStructureReference(completion, designId);
    }

    private static boolean scanStructureReference(JsonElement element, String designId) {
        if (element == null || element.isJsonNull()) return false;
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("kind") && object.get("kind").isJsonPrimitive()
                    && "structure".equals(object.get("kind").getAsString())
                    && object.has("structure_id") && object.get("structure_id").isJsonPrimitive()
                    && designId.equals(object.get("structure_id").getAsString())) return true;
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (scanStructureReference(entry.getValue(), designId)) return true;
            }
            return false;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (scanStructureReference(child, designId)) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ lifecycle

    public static void cancelForMaid(UUID maidId, String reason) {
        BuildQueueRunner.cancelForMaid(maidId, reason);
        // No EntityMaid is available here, so update the cached state only; the next save persists it.
        BuildQueueState cached = BuildQueueData.cached(maidId);
        if (cached != null) {
            cached.status = "paused";
            cached.pausedReason = bounded(reason == null || reason.isBlank() ? "cancelled" : reason, 128);
        }
    }

    public static void clearAll() {
        BuildQueueRunner.cancelAll();
        BuildQueueData.clearAll();
    }

    // ------------------------------------------------------------------ item aggregation (runner/tool)

    static LinkedHashMap<String, Integer> requiredItemCounts(EntityMaid maid, BuildDesign design) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        if (design == null || design.steps == null) return counts;
        for (BuildStep step : design.steps) {
            if (step == null) continue;
            String kind = step.kindOrPlace();
            if ("place".equals(kind)) {
                String item = placeItemId(step);
                if (!item.isBlank()) counts.merge(item, 1, Integer::sum);
            } else if ("interact".equals(kind) && step.required && !step.item.isBlank()) {
                counts.merge(step.item, 1, Integer::sum);
            }
        }
        return counts;
    }

    static LinkedHashMap<String, Integer> missingFromMaid(EntityMaid maid, BuildDesign design) {
        LinkedHashMap<String, Integer> missing = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : requiredItemCounts(maid, design).entrySet()) {
            int available = MaidItemSelection.count(maid, entry.getKey());
            int shortage = entry.getValue() - available;
            if (shortage > 0) missing.put(entry.getKey(), shortage);
        }
        return missing;
    }

    /** Missing item/tool ids for a single step (empty when the step can start). */
    static List<String> missingForStep(EntityMaid maid, BuildStep step) {
        List<String> missing = new ArrayList<>();
        if (step == null) return missing;
        switch (step.kindOrPlace()) {
            case "place" -> {
                String item = placeItemId(step);
                if (!item.isBlank() && MaidItemSelection.count(maid, item) <= 0) missing.add(item);
            }
            case "interact" -> {
                if (!step.item.isBlank() && MaidItemSelection.count(maid, step.item) <= 0) missing.add(step.item);
            }
            case "mine" -> {
                if (!step.tool.isBlank() && MaidItemSelection.count(maid, step.tool) <= 0) missing.add(step.tool);
            }
            default -> {
            }
        }
        return missing;
    }

    static String placeItemId(BuildStep step) {
        if (step == null) return "";
        if (step.item != null && !step.item.isBlank()) return step.item.trim();
        if (step.block == null || step.block.isBlank()) return "";
        try {
            Block block = MaidBlockStateMatcher.parse(step.block).block();
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            if (key == null) return "";
            Item item = BuiltInRegistries.ITEM.get(key);
            return item == Items.AIR ? "" : key.toString();
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    // ------------------------------------------------------------------ parsing / validation

    private static BuildStep parseStep(JsonObject raw, int originX, int originY, int originZ, int index) {
        BuildStep step = new BuildStep();
        step.index = index;
        String kind = stringArg(raw, "kind", "place").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("place", "mine", "interact").contains(kind)) throw new IllegalArgumentException("invalid_kind");
        step.kind = kind;
        int relativeX = requiredInt(raw, "x");
        int relativeY = requiredInt(raw, "y");
        int relativeZ = requiredInt(raw, "z");
        step.x = safeAdd(originX, relativeX);
        step.y = safeAdd(originY, relativeY);
        step.z = safeAdd(originZ, relativeZ);
        step.block = bounded(stringArg(raw, "block", "").trim(), 256);
        step.item = bounded(stringArg(raw, "item", "").trim(), 128);
        step.tool = bounded(stringArg(raw, "tool", "").trim(), 128);
        step.face = bounded(stringArg(raw, "face", "").trim().toLowerCase(Locale.ROOT), 16);
        step.strict = boolArg(raw, "strict", false);
        step.required = boolArg(raw, "required", true);
        step.note = bounded(stringArg(raw, "note", "").trim(), 256);
        validateStep(step);
        return step;
    }

    static void validateStep(BuildStep step) {
        if ("place".equals(step.kindOrPlace())) {
            if (step.block.isBlank()) throw new IllegalArgumentException("place_block_required");
            MaidBlockStateMatcher.Expected expected = MaidBlockStateMatcher.parse(step.block);
            if (expected.air()) throw new IllegalArgumentException("place_block_air");
            String itemId = placeItemId(step);
            if (itemId.isBlank()) throw new IllegalArgumentException("place_block_has_no_item:" + step.block);
            Item item = MaidBlockActionRequest.requireItem(itemId);
            if (!(item instanceof BlockItem)) throw new IllegalArgumentException("place_item_is_not_a_block:" + itemId);
            step.item = itemId;
        } else if (!step.block.isBlank()) {
            MaidBlockStateMatcher.parse(step.block);
        }
        if (!step.item.isBlank()) MaidBlockActionRequest.requireItem(step.item);
        if (!step.tool.isBlank()) MaidBlockActionRequest.requireItem(step.tool);
        if (!step.face.isBlank()) {
            Direction face = Direction.byName(step.face);
            if (face == null) throw new IllegalArgumentException("invalid_face");
            step.face = face.getName();
        }
    }

    private static int[] resolveOrigin(EntityMaid maid, JsonObject args) {
        String space = stringArg(args, "coordinate_space", "world").trim().toLowerCase(Locale.ROOT);
        if (!Set.of("world", "maid_relative").contains(space)) throw new IllegalArgumentException("invalid_coordinate_space");
        boolean relative = space.equals("maid_relative");
        BlockPos maidPos = maid.blockPosition();
        JsonObject origin = args.has("origin") && args.get("origin").isJsonObject()
                ? args.getAsJsonObject("origin") : null;
        if (origin == null) return new int[]{maidPos.getX(), maidPos.getY(), maidPos.getZ()};
        boolean any = origin.has("x") || origin.has("y") || origin.has("z");
        if (!any) return new int[]{maidPos.getX(), maidPos.getY(), maidPos.getZ()};
        int x = requiredInt(origin, "x");
        int y = requiredInt(origin, "y");
        int z = requiredInt(origin, "z");
        if (relative) return new int[]{safeAdd(maidPos.getX(), x), safeAdd(maidPos.getY(), y), safeAdd(maidPos.getZ(), z)};
        return new int[]{x, y, z};
    }

    private static String resolveDimension(EntityMaid maid, JsonObject args, @Nullable BuildDesign previous) {
        String raw = stringArg(args, "dimension", "").trim();
        if (raw.isBlank()) {
            return previous == null || previous.dimension.isBlank() ? dimension(maid) : previous.dimension;
        }
        ResourceLocation key = ResourceLocation.tryParse(raw);
        if (key == null) throw new IllegalArgumentException("invalid_dimension");
        String normalized = key.toString();
        if (previous != null && !previous.dimension.isBlank() && !previous.dimension.equals(normalized))
            throw new IllegalArgumentException("dimension_mismatch");
        return normalized;
    }

    static void sortSteps(BuildDesign design, String order) {
        if (design == null || design.steps == null) return;
        String value = order == null ? "bottom_up" : order.trim().toLowerCase(Locale.ROOT);
        if ("as_given".equals(value)) {
            design.reindex();
            return;
        }
        Comparator<BuildStep> comparator = Comparator.comparingInt((BuildStep step) -> step.y);
        if (!"layers".equals(value)) {
            comparator = comparator
                    .thenComparingLong((BuildStep step) -> horizontalDistanceSquared(step, design.originX, design.originZ))
                    .thenComparingInt((BuildStep step) -> step.x)
                    .thenComparingInt((BuildStep step) -> step.z);
        }
        design.steps.sort(comparator);
        design.reindex();
    }

    private static long horizontalDistanceSquared(BuildStep step, int originX, int originZ) {
        long dx = (long) step.x - originX;
        long dz = (long) step.z - originZ;
        return dx * dx + dz * dz;
    }

    @Nullable
    private static BuildStep findStep(BuildDesign design, JsonObject args) {
        if (args.has("step")) {
            int index = intArg(args, "step", -1);
            for (BuildStep step : design.steps) if (step.index == index) return step;
            if (index >= 0 && index < design.steps.size()) return design.steps.get(index);
            return null;
        }
        if (args.has("position") && args.get("position").isJsonArray()) {
            JsonArray position = args.getAsJsonArray("position");
            if (position.size() != 3) return null;
            int x = position.get(0).getAsInt();
            int y = position.get(1).getAsInt();
            int z = position.get(2).getAsInt();
            for (BuildStep step : design.steps) if (step.x == x && step.y == y && step.z == z) return step;
        }
        return null;
    }

    // ------------------------------------------------------------------ JSON helpers

    static JsonObject failure(String error) {
        JsonObject out = new JsonObject();
        out.addProperty("success", false);
        out.addProperty("status", "failed");
        out.addProperty("error", error == null || error.isBlank() ? "build_failed" : error);
        return out;
    }

    static JsonObject designSummary(BuildDesign design) {
        JsonObject out = new JsonObject();
        out.addProperty("design_id", design.id);
        out.addProperty("structure_name", bounded(design.displayName(), 128));
        out.addProperty("dimension", design.dimension);
        out.addProperty("steps", design.steps.size());
        out.add("origin", positionJson(design.originX, design.originY, design.originZ));
        return out;
    }

    static JsonObject queueSummary(BuildQueueState state) {
        JsonObject out = new JsonObject();
        out.addProperty("status", state.status);
        out.addProperty("paused_reason", bounded(state.pausedReason, 160));
        out.addProperty("active_design_id", state.activeDesignId);
        out.addProperty("design_count", state.designs.size());
        out.addProperty("task_id", state.taskId);
        out.addProperty("task_generation", state.taskGeneration);
        out.addProperty("goal_version", state.goalVersion);
        out.addProperty("updated_tick", state.updatedTick);
        return out;
    }

    static JsonObject progressJson(BuildQueueState state) {
        JsonObject out = new JsonObject();
        out.addProperty("total", state.steps.size());
        out.addProperty("done", state.countStatus(BuildStepState.DONE));
        out.addProperty("pending", state.countStatus(BuildStepState.PENDING) + state.countStatus(BuildStepState.READY));
        out.addProperty("running", state.countStatus(BuildStepState.RUNNING));
        out.addProperty("failed", state.countStatus(BuildStepState.FAILED));
        out.addProperty("blocked", state.countStatus(BuildStepState.BLOCKED));
        out.addProperty("skipped", state.countStatus(BuildStepState.SKIPPED));
        return out;
    }

    static JsonObject stepRow(BuildStep step, @Nullable BuildStepState stepState) {
        JsonObject out = new JsonObject();
        out.addProperty("index", step.index);
        out.addProperty("kind", step.kindOrPlace());
        out.addProperty("x", step.x);
        out.addProperty("y", step.y);
        out.addProperty("z", step.z);
        out.addProperty("block", bounded(step.block, 256));
        if (!step.item.isBlank()) out.addProperty("item", step.item);
        if (!step.tool.isBlank()) out.addProperty("tool", step.tool);
        if (!step.face.isBlank()) out.addProperty("face", step.face);
        out.addProperty("strict", step.strict);
        out.addProperty("required", step.required);
        if (!step.note.isBlank()) out.addProperty("note", bounded(step.note, 128));
        if (stepState != null) {
            out.addProperty("status", stepState.status);
            out.addProperty("attempts", stepState.attempts);
            if (!stepState.lastError.isBlank()) out.addProperty("last_error", bounded(stepState.lastError, 160));
            if (!stepState.decision.isBlank()) out.addProperty("decision", bounded(stepState.decision, 64));
            if (!stepState.adjustment.isBlank()) out.addProperty("adjustment", bounded(stepState.adjustment, 256));
            out.addProperty("settled_tick", stepState.settledTick);
        }
        return out;
    }

    private static JsonObject currentStepJson(BuildStep step, BuildStepState stepState) {
        JsonObject out = new JsonObject();
        out.addProperty("index", step.index);
        out.addProperty("kind", step.kindOrPlace());
        out.add("position", positionJson(step.x, step.y, step.z));
        out.addProperty("block", bounded(step.block, 128));
        out.addProperty("status", stepState.status);
        if (!stepState.lastError.isBlank()) out.addProperty("last_error", bounded(stepState.lastError, 96));
        return out;
    }

    @Nullable
    private static JsonObject lastDecisionJson(BuildQueueState state) {
        BuildStepState found = null;
        for (BuildStepState stepState : state.steps) {
            if (stepState == null || stepState.decision.isBlank()) continue;
            if (found == null || stepState.settledTick >= found.settledTick) found = stepState;
        }
        if (found == null) return null;
        JsonObject out = new JsonObject();
        out.addProperty("index", found.index);
        out.addProperty("decision", bounded(found.decision, 64));
        out.addProperty("status", found.status);
        if (!found.lastError.isBlank()) out.addProperty("reason", bounded(found.lastError, 160));
        out.addProperty("settled_tick", found.settledTick);
        return out;
    }

    @Nullable
    static BuildStepState currentStepState(BuildQueueState state) {
        for (Integer index : state.order) {
            BuildStepState stepState = state.stepState(index);
            if (stepState != null && BuildStepState.RUNNING.equals(stepState.status)) return stepState;
        }
        for (Integer index : state.order) {
            BuildStepState stepState = state.stepState(index);
            if (stepState != null && (BuildStepState.PENDING.equals(stepState.status)
                    || BuildStepState.READY.equals(stepState.status))) return stepState;
        }
        return null;
    }

    @Nullable
    private static BuildStepState firstStatus(BuildQueueState state, String... statuses) {
        Set<String> wanted = Set.of(statuses);
        for (Integer index : state.order) {
            BuildStepState stepState = state.stepState(index);
            if (stepState != null && wanted.contains(stepState.status)) return stepState;
        }
        return null;
    }

    static JsonObject itemCountsJson(Map<String, Integer> counts) {
        JsonObject out = new JsonObject();
        if (counts != null) for (Map.Entry<String, Integer> entry : counts.entrySet()) out.addProperty(entry.getKey(), entry.getValue());
        return out;
    }

    static JsonArray stringArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) for (String value : values) array.add(value);
        return array;
    }

    static JsonArray intArray(List<Integer> values) {
        JsonArray array = new JsonArray();
        if (values != null) for (Integer value : values) if (value != null) array.add(value);
        return array;
    }

    static JsonArray positionJson(int x, int y, int z) {
        JsonArray array = new JsonArray();
        array.add(x);
        array.add(y);
        array.add(z);
        return array;
    }

    static void resetState(BuildStepState stepState, boolean clearAdjustment) {
        if (stepState == null) return;
        stepState.status = BuildStepState.PENDING;
        stepState.attempts = 0;
        stepState.lastError = "";
        stepState.decision = "";
        stepState.settledTick = 0;
        if (clearAdjustment) stepState.adjustment = "";
    }

    static BuildStep copyStep(BuildStep source, int index) {
        BuildStep out = new BuildStep();
        out.index = index;
        out.kind = source.kind;
        out.x = source.x;
        out.y = source.y;
        out.z = source.z;
        out.block = source.block;
        out.item = source.item;
        out.tool = source.tool;
        out.face = source.face;
        out.strict = source.strict;
        out.required = source.required;
        out.note = source.note;
        return out;
    }

    // ------------------------------------------------------------------ primitive parsing

    static String stringArg(JsonObject args, String key, String fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonPrimitive()) return fallback;
        return args.get(key).getAsString();
    }

    static boolean boolArg(JsonObject args, String key, boolean fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonPrimitive()) return fallback;
        JsonElement value = args.get(key);
        try {
            return value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : fallback;
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    static int intArg(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull() || !args.get(key).isJsonPrimitive()) return fallback;
        try {
            double value = args.get(key).getAsDouble();
            if (!Double.isFinite(value) || value != Math.floor(value)) return fallback;
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    static int intArgRequired(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) return fallback;
        JsonElement value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("invalid_" + key);
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.floor(number))
            throw new IllegalArgumentException("invalid_" + key);
        return (int) number;
    }

    private static int requiredInt(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull())
            throw new IllegalArgumentException(key + "_required");
        JsonElement value = args.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("invalid_" + key);
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.floor(number)
                || number < Integer.MIN_VALUE || number > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid_" + key);
        return (int) number;
    }

    private static int safeAdd(int left, int right) {
        long sum = (long) left + right;
        if (sum < Integer.MIN_VALUE || sum > Integer.MAX_VALUE) throw new IllegalArgumentException("invalid_coordinate");
        return (int) sum;
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static String bounded(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max));
    }

    static String message(Throwable throwable, String fallback) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) return fallback;
        return bounded(throwable.getMessage(), 512);
    }

    // ------------------------------------------------------------------ server / config helpers

    static void requireServerThread(EntityMaid maid) {
        if (maid == null) throw new IllegalStateException("server_thread_required");
        if (!(maid.level() instanceof ServerLevel level)) throw new IllegalStateException("server_thread_required");
        if (level.getServer() == null || !level.getServer().isSameThread())
            throw new IllegalStateException("server_thread_required");
    }

    static String dimension(EntityMaid maid) {
        return maid.level().dimension().location().toString();
    }

    static String ownerOf(EntityMaid maid) {
        UUID owner = maid.getOwnerUUID();
        return owner == null ? "" : owner.toString();
    }

    static long levelGameTime(EntityMaid maid) {
        try {
            return maid.level().getGameTime();
        } catch (RuntimeException unavailable) {
            return 0L;
        }
    }

    private static int maxDesigns() {
        try {
            if (TouhouAIFunConfig.SPEC.isLoaded()) return clamp(TouhouAIFunConfig.BUILD_QUEUE_MAX_DESIGNS.get(), 1, 16);
        } catch (RuntimeException ignored) {
        }
        return MAX_DESIGNS_FALLBACK;
    }

    private static int maxStepsPerDesign() {
        try {
            if (TouhouAIFunConfig.SPEC.isLoaded()) return clamp(TouhouAIFunConfig.BUILD_QUEUE_MAX_STEPS_PER_DESIGN.get(), 1, 8192);
        } catch (RuntimeException ignored) {
        }
        return MAX_STEPS_PER_DESIGN_FALLBACK;
    }

    private static int maxStepsPerCall() {
        try {
            if (TouhouAIFunConfig.SPEC.isLoaded()) return clamp(TouhouAIFunConfig.BUILD_QUEUE_MAX_STEPS_PER_CALL.get(), 1, 256);
        } catch (RuntimeException ignored) {
        }
        return MAX_STEPS_PER_CALL_FALLBACK;
    }

    static int runMaxSeconds() {
        try {
            if (TouhouAIFunConfig.SPEC.isLoaded()) return clamp(TouhouAIFunConfig.BUILD_QUEUE_RUN_MAX_SECONDS.get(), 30, 3600);
        } catch (RuntimeException ignored) {
        }
        return RUN_MAX_SECONDS_FALLBACK;
    }

    private static CompletableFuture<JsonObject> completed(JsonObject value) {
        return CompletableFuture.completedFuture(value);
    }
}
