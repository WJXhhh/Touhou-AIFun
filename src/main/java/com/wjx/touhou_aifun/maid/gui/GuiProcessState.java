package com.wjx.touhou_aifun.maid.gui;

/** All durations are simulation ticks. Fuel countdown is deliberately not a progress signal. */
public record GuiProcessState(Status status, String blockedReason, long progress, long total,
                              long produced, long remainingTicks, long updateIntervalTicks,
                              boolean reliable) {
    public enum Status { STARTING, RUNNING, COMPLETED, BLOCKED, UNKNOWN }
    public static GuiProcessState unknown() {
        return new GuiProcessState(Status.UNKNOWN, "", 0, 0, 0, -1, 20, false);
    }
}
