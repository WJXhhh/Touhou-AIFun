package com.wjx.touhou_aifun.chat.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskStateGoalTest {
    @Test
    void unchangedTaskUsesTheOriginalGoal() {
        AgentTaskState task = new AgentTaskState("craft a brush");
        assertEquals("craft a brush", task.effectiveGoal());
        assertEquals("craft a brush", task.announcementGoal());
    }

    @Test
    void pendingAndAppliedCorrectionsBecomeTheEffectiveGoal() {
        AgentTaskState task = new AgentTaskState("合成软板的刷子");
        task.amend("其实是原版刷子（minecraft:brush），用羽毛和铜锭");
        assertTrue(task.effectiveGoal().contains("minecraft:brush"),
                "A pending amendment must already be visible to status/log wording");
        task.applyAmendment();

        assertTrue(task.effectiveGoal().startsWith("合成软板的刷子"));
        assertTrue(task.effectiveGoal().contains("minecraft:brush"));
        assertTrue(task.announcementGoal().startsWith("Latest owner correction"),
                "The correction must come first so the bounded phrasing prompt keeps it");
        assertTrue(task.announcementGoal().contains("minecraft:brush"));
        assertTrue(task.announcementGoal().contains("Original task: 合成软板的刷子"));
        assertEquals(2, task.goalVersion);
    }

    @Test
    void laterCorrectionSupersedesTheEarlierOneInAnnouncementText() {
        AgentTaskState task = new AgentTaskState("make a brush");
        task.amend("first correction: soft brush");
        task.applyAmendment();
        task.amend("second correction: vanilla minecraft:brush");
        task.applyAmendment();

        String announcement = task.announcementGoal();
        assertTrue(announcement.contains("second correction: vanilla minecraft:brush"));
        assertFalse(announcement.contains("first correction"),
                "The announcement prompt should lead with the newest correction only");
        assertTrue(task.effectiveGoal().contains("second correction"));
    }

    @Test
    void announcementGoalStaysBoundedForLongGoals() {
        AgentTaskState task = new AgentTaskState("x".repeat(900));
        task.amend("y".repeat(900));
        task.applyAmendment();
        assertTrue(task.announcementGoal().codePointCount(0, task.announcementGoal().length()) <= 512);
    }
}
