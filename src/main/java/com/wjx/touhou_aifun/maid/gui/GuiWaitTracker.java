package com.wjx.touhou_aifun.maid.gui;

/** Deterministic wait state machine; no HTTP, rendering or wall-clock timers. */
public final class GuiWaitTracker {
    private final long started;
    private long lastProgress;
    private long lastSample;
    private GuiProcessState previous;
    private String blocked = "";
    private int blockedSamples;
    public GuiWaitTracker(long tick) { started = lastProgress = lastSample = tick; }

    public String sample(long tick, GuiProcessState state, GuiWaitBudget budget, boolean delivered) {
        budget.advance(tick - lastSample);
        lastSample = tick;
        if (delivered) return "completed";
        if (previous == null || state.progress() > previous.progress()
                || state.produced() > previous.produced()) lastProgress = tick;
        previous = state;
        if (state.status() == GuiProcessState.Status.BLOCKED && tick - started >= 60) {
            if (blocked.equals(state.blockedReason())) blockedSamples++;
            else { blocked = state.blockedReason(); blockedSamples = 1; }
            if (blockedSamples >= 2) return "blocked_" + blocked;
        } else { blockedSamples = 0; blocked = ""; }
        if (budget.exhausted()) return state.status() == GuiProcessState.Status.RUNNING
                || state.status() == GuiProcessState.Status.COMPLETED ? "still_processing" : "progress_unknown";
        if (state.reliable() && tick - lastProgress >= Math.max(300, state.updateIntervalTicks() * 2)) {
            return "stalled";
        }
        return "waiting";
    }
}
