package com.wjx.touhou_aifun.chat.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskAmendmentPolicyTest {
    private static AgentTaskState paused(String outcome) {
        AgentTaskState task = new AgentTaskState("craft the brush");
        task.status = AgentTaskState.Status.paused;
        task.outcome = outcome;
        return task;
    }

    @Test
    void runtimePauseResumesWhenTheOwnerAmendsIt() {
        assertTrue(TaskAmendmentPolicy.shouldAutoResume(true, true,
                paused("未通过完成验证或遇到阻塞：missing completion")));
    }

    @Test
    void explicitOwnerPauseIsNeverOverridden() {
        assertFalse(TaskAmendmentPolicy.shouldAutoResume(true, true,
                paused(TaskAmendmentPolicy.OWNER_PAUSE_REASON)));
    }

    @Test
    void disabledConfigKeepsTheOldPendingBehaviour() {
        assertFalse(TaskAmendmentPolicy.shouldAutoResume(false, true,
                paused("blocked during verification")));
    }

    @Test
    void queueMustActuallyBePaused() {
        assertFalse(TaskAmendmentPolicy.shouldAutoResume(true, false,
                paused("blocked during verification")));
    }

    @Test
    void runningOrMissingTasksAreNotResumedByAnAmendment() {
        AgentTaskState running = new AgentTaskState("craft");
        running.status = AgentTaskState.Status.running;
        assertFalse(TaskAmendmentPolicy.shouldAutoResume(true, true, running));
        assertFalse(TaskAmendmentPolicy.shouldAutoResume(true, true, null));
    }
}
