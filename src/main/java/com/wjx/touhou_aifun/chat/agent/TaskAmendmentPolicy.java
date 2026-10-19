package com.wjx.touhou_aifun.chat.agent;

/**
 * Decides whether an owner amendment may also resume a task that the runtime paused.
 *
 * <p>A task paused because it failed, got stuck or could not verify completion is waiting for the
 * owner's correction; once that correction arrives, asking for a separate "continue" is redundant.
 * An explicit owner pause is never overridden, and the feature can be disabled in config.</p>
 */
public final class TaskAmendmentPolicy {
    public static final String OWNER_PAUSE_REASON = "paused_by_owner";

    private TaskAmendmentPolicy() {
    }

    public static boolean shouldAutoResume(boolean enabled, boolean queuePaused, AgentTaskState task) {
        return enabled && queuePaused && task != null
                && task.status == AgentTaskState.Status.paused
                && !OWNER_PAUSE_REASON.equals(task.outcome);
    }
}
