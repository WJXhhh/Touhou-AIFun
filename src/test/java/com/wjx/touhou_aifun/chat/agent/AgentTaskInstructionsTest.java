package com.wjx.touhou_aifun.chat.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskInstructionsTest {
    @Test void executionNarrationPolicyRequiresPerRoundStatusAndToolResultSummaries() {
        String policy = AgentTaskInstructions.NARRATION_POLICY;
        assertTrue(policy.contains("Every response MUST open with a short factual status paragraph"), policy);
        assertTrue(policy.contains("BEFORE any tool call"), policy);
        assertTrue(policy.contains("When the previous turn returned tool results, first summarize"), policy);
        assertTrue(policy.contains("exact counts"), policy);
        assertTrue(policy.contains("FINAL response must be a factual execution report"), policy);
        assertTrue(policy.contains("never spoken"), policy);
        assertTrue(policy.contains("never changes completion verification"), policy);
    }

    @Test void missingNarrationReminderRepeatsTheRequirementAndPointsAtTheLatestToolResults() {
        String reminder = AgentTaskInstructions.NARRATION_REMINDER;
        assertTrue(reminder.contains("status narration"), reminder);
        assertTrue(reminder.contains("summarizes the tool results above"), reminder);
        assertTrue(reminder.contains("before any tool call"), reminder);
    }

    @Test void foregroundPromptPointsTheMainModelAtTheNarrationChannel() {
        String foreground = AgentPrompts.FOREGROUND;
        assertTrue(foreground.contains("result_ref=\"task_narration\""), foreground);
        assertTrue(foreground.contains("intermediate narration"), foreground);
    }
}
