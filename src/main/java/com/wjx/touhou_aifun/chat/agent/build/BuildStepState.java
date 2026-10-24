package com.wjx.touhou_aifun.chat.agent.build;

/** Durable progress of one design step. Indices always address the active design's step list. */
public class BuildStepState {
    public static final String PENDING = "pending";
    public static final String READY = "ready";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";
    public static final String BLOCKED = "blocked";

    public int index;
    public String status = PENDING;
    public int attempts;
    public String lastError = "";
    public String decision = "";
    public long settledTick;
    public String adjustment = "";

    public BuildStepState() {
    }

    public BuildStepState(int index) {
        this.index = index;
    }

    public boolean terminal() {
        return DONE.equals(status) || SKIPPED.equals(status);
    }

    public boolean retryable() {
        return PENDING.equals(status) || READY.equals(status);
    }
}
