package com.wjx.touhou_aifun.chat.agent.build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One maid's build queue. Gson-friendly public fields only; every list is repaired defensively on
 * load because an old or interrupted checkpoint may contain nulls.
 */
public class BuildQueueState {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    public String ownerUUID = "";
    public String taskId = "";
    public long taskGeneration;
    public int goalVersion;
    public List<BuildDesign> designs = new ArrayList<>();
    public String activeDesignId = "";
    /** Step indices of the active design in execution order. */
    public List<Integer> order = new ArrayList<>();
    public List<BuildStepState> steps = new ArrayList<>();
    /** idle | running | paused | completed | blocked | cancelled */
    public String status = "idle";
    public String pausedReason = "";
    public long updatedTick;

    public static final Set<String> QUEUE_STATUSES =
            Set.of("idle", "running", "paused", "completed", "blocked", "cancelled");
    public static final Set<String> STEP_STATUSES = Set.of(
            BuildStepState.PENDING, BuildStepState.READY, BuildStepState.RUNNING,
            BuildStepState.DONE, BuildStepState.FAILED, BuildStepState.SKIPPED, BuildStepState.BLOCKED);

    public BuildQueueState() {
    }

    public BuildDesign design(String designId) {
        if (designId == null || designId.isBlank() || designs == null) return null;
        for (BuildDesign design : designs) {
            if (design != null && designId.equals(design.id)) return design;
        }
        return null;
    }

    public BuildDesign activeDesign() {
        return design(activeDesignId);
    }

    public BuildStepState stepState(int index) {
        if (steps == null) return null;
        for (BuildStepState state : steps) {
            if (state != null && state.index == index) return state;
        }
        return null;
    }

    public BuildStepState ensureStepState(int index) {
        if (steps == null) steps = new ArrayList<>();
        BuildStepState state = stepState(index);
        if (state == null) {
            state = new BuildStepState(index);
            steps.add(state);
        }
        return state;
    }

    /** Rebuilds step states/order so they exactly address the active design's current step list. */
    public void ensureStepStates() {
        if (designs == null) designs = new ArrayList<>();
        if (steps == null) steps = new ArrayList<>();
        if (order == null) order = new ArrayList<>();
        BuildDesign design = activeDesign();
        if (design == null) {
            steps.clear();
            order.clear();
            return;
        }
        if (design.steps == null) design.steps = new ArrayList<>();
        List<BuildStepState> next = new ArrayList<>(design.steps.size());
        for (int i = 0; i < design.steps.size(); i++) {
            BuildStepState existing = stepState(i);
            next.add(existing != null ? existing : new BuildStepState(i));
        }
        steps.clear();
        steps.addAll(next);
        List<Integer> repaired = new ArrayList<>(design.steps.size());
        Set<Integer> seen = new HashSet<>();
        for (Integer index : order) {
            if (index != null && index >= 0 && index < design.steps.size() && seen.add(index)) repaired.add(index);
        }
        if (repaired.size() != design.steps.size()) {
            repaired.clear();
            for (int i = 0; i < design.steps.size(); i++) repaired.add(i);
        }
        order.clear();
        order.addAll(repaired);
        for (BuildStepState state : steps) {
            if (state.status == null || !STEP_STATUSES.contains(state.status)) state.status = BuildStepState.PENDING;
            if (state.lastError == null) state.lastError = "";
            if (state.decision == null) state.decision = "";
            if (state.adjustment == null) state.adjustment = "";
        }
    }

    /** Resets unfinished steps to pending, keeping done/skipped progress. */
    public void resetPending() {
        if (steps == null) return;
        for (BuildStepState state : steps) {
            if (state == null || state.terminal()) continue;
            state.status = BuildStepState.PENDING;
            state.attempts = 0;
            state.lastError = "";
            state.decision = "";
            state.settledTick = 0;
            state.adjustment = "";
        }
    }

    /** Resets every step, including completed ones. */
    public void resetAll() {
        if (steps == null) steps = new ArrayList<>();
        for (BuildStepState state : steps) {
            if (state == null) continue;
            state.status = BuildStepState.PENDING;
            state.attempts = 0;
            state.lastError = "";
            state.decision = "";
            state.settledTick = 0;
            state.adjustment = "";
        }
    }

    /** Clears execution state/order but keeps the saved designs. */
    public void clearExecutionState() {
        if (steps == null) steps = new ArrayList<>();
        for (BuildStepState state : steps) {
            if (state == null) continue;
            state.status = BuildStepState.PENDING;
            state.attempts = 0;
            state.lastError = "";
            state.decision = "";
            state.settledTick = 0;
            state.adjustment = "";
        }
        if (order == null) order = new ArrayList<>();
        order.clear();
        status = "idle";
        pausedReason = "";
    }

    /** Null-repairs a deserialized checkpoint; returns true when anything was repaired. */
    public boolean repair() {
        boolean changed = false;
        if (schemaVersion != SCHEMA_VERSION) {
            schemaVersion = SCHEMA_VERSION;
            changed = true;
        }
        if (ownerUUID == null) {
            ownerUUID = "";
            changed = true;
        }
        if (taskId == null) {
            taskId = "";
            changed = true;
        }
        if (designs == null) {
            designs = new ArrayList<>();
            changed = true;
        }
        if (activeDesignId == null) {
            activeDesignId = "";
            changed = true;
        }
        if (order == null) {
            order = new ArrayList<>();
            changed = true;
        }
        if (steps == null) {
            steps = new ArrayList<>();
            changed = true;
        }
        if (pausedReason == null) {
            pausedReason = "";
            changed = true;
        }
        if (status == null || !QUEUE_STATUSES.contains(status)) {
            status = "idle";
            changed = true;
        }
        List<BuildDesign> keptDesigns = new ArrayList<>(designs.size());
        for (BuildDesign design : designs) {
            if (design == null) {
                changed = true;
                continue;
            }
            if (design.id == null || design.id.isBlank()) {
                design.id = java.util.UUID.randomUUID().toString();
                changed = true;
            }
            if (design.structureName == null) {
                design.structureName = "";
                changed = true;
            }
            if (design.dimension == null) {
                design.dimension = "";
                changed = true;
            }
            if (design.fingerprint == null) design.fingerprint = "";
            if (design.steps == null) {
                design.steps = new ArrayList<>();
                changed = true;
            }
            List<BuildStep> keptSteps = new ArrayList<>(design.steps.size());
            for (BuildStep step : design.steps) {
                if (step == null) {
                    changed = true;
                    continue;
                }
                if (step.kind == null || step.kind.isBlank()) step.kind = "place";
                if (step.block == null) step.block = "";
                if (step.item == null) step.item = "";
                if (step.tool == null) step.tool = "";
                if (step.face == null) step.face = "";
                if (step.note == null) step.note = "";
                keptSteps.add(step);
            }
            design.steps = keptSteps;
            design.reindex();
            if (design.fingerprint.isBlank()) design.refreshFingerprint();
            keptDesigns.add(design);
        }
        if (keptDesigns.size() != designs.size()) changed = true;
        designs = keptDesigns;
        List<BuildStepState> keptStates = new ArrayList<>(steps.size());
        for (BuildStepState state : steps) {
            if (state == null) {
                changed = true;
                continue;
            }
            if (state.status == null || !STEP_STATUSES.contains(state.status)) {
                state.status = BuildStepState.PENDING;
                changed = true;
            }
            keptStates.add(state);
        }
        steps = keptStates;
        return changed;
    }

    public int countStatus(String value) {
        int count = 0;
        if (steps != null) {
            for (BuildStepState state : steps) {
                if (state != null && value.equals(state.status)) count++;
            }
        }
        return count;
    }
}
