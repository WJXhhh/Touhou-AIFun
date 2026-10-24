package com.wjx.touhou_aifun.chat.agent.build;

import com.google.gson.JsonObject;

/** Small shared JSON/summary helpers for build status consumers. */
public final class BuildQueueSummary {
    private BuildQueueSummary() {
    }

    public static JsonObject progress(BuildQueueState state) {
        return BuildQueueManager.progressJson(state);
    }

    /** The single task-mutation fact recorded for one successful run (never one per block). */
    public static JsonObject runFact(BuildDesign design, BuildQueueState state) {
        JsonObject fact = new JsonObject();
        fact.addProperty("kind", "build_run");
        fact.addProperty("success", true);
        fact.addProperty("design_id", design.id);
        fact.addProperty("structure_name", design.displayName());
        fact.addProperty("dimension", design.dimension);
        fact.addProperty("steps_total", design.steps.size());
        fact.addProperty("steps_done", state.countStatus(BuildStepState.DONE));
        fact.addProperty("steps_skipped", state.countStatus(BuildStepState.SKIPPED));
        fact.addProperty("steps_failed", state.countStatus(BuildStepState.FAILED));
        return fact;
    }

    /** Compact human-readable progress line used by logs/foreground diagnostics. */
    public static String text(BuildQueueState state) {
        if (state == null) return "";
        BuildDesign design = state.activeDesign();
        String name = design == null ? "structure" : design.displayName();
        int total = state.steps == null ? 0 : state.steps.size();
        return name + ": " + state.countStatus(BuildStepState.DONE) + "/" + total + " done, "
                + state.countStatus(BuildStepState.FAILED) + " failed, "
                + state.countStatus(BuildStepState.SKIPPED) + " skipped";
    }
}
