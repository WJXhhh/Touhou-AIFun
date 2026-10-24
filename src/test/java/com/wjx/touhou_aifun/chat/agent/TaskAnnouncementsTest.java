package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.chat.agent.TaskAnnouncements.Mode;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskAnnouncementsTest {
    private String notice(String json, boolean repeated, Mode mode) {
        return TaskAnnouncements.controlNotice(JsonParser.parseString(json).getAsJsonObject(), repeated, mode);
    }
    @Test void modesParseStrictlyAndFallBackToBrief() {
        assertEquals(Mode.detailed, TaskAnnouncements.parse("detailed"));
        assertEquals(Mode.detailed, TaskAnnouncements.parse("  DETAILED "));
        assertEquals(Mode.brief, TaskAnnouncements.parse("brief"));
        assertEquals(Mode.silent, TaskAnnouncements.parse("silent"));
        assertEquals(Mode.brief, TaskAnnouncements.parse("verbose"));
        assertEquals(Mode.brief, TaskAnnouncements.parse(null));
        assertEquals("brief", TouhouAIFunConfig.AGENT_TASK_ANNOUNCEMENTS.getDefault());
    }

    @Test void briefAcknowledgementIsModelPhrasedFromRealDispatchState() {
        String starting = notice("{\"controlled_task_dispatch\":\"starting_now\",\"controlled_task_goal\":\"put five iron away\"}", false, Mode.brief);
        assertTrue(starting.contains("dispatch=starting_now"), starting);
        assertTrue(starting.contains("put five iron away"), starting);
        assertTrue(starting.contains("STYLE HINTS, not scripts"), starting);
        assertTrue(starting.contains("好，我这就去"), starting);
        String waiting = notice("{\"controlled_task_dispatch\":\"waiting\",\"controlled_task_goal\":\"sort chests\",\"active_task_goal\":\"retrieve iron\"}", false, Mode.brief);
        assertTrue(waiting.contains("dispatch=waiting"), waiting);
        assertTrue(waiting.contains("retrieve iron"), waiting);
        assertTrue(waiting.contains("记下了，忙完就去"), waiting);
        String amend = notice("{\"controlled_task_dispatch\":\"amendment_next_boundary\",\"controlled_task_goal\":\"sort chests\",\"accepted_owner_amendment\":\"also move the beef\"}", false, Mode.brief);
        assertTrue(amend.contains("amendment_next_boundary"), amend);
        assertTrue(amend.contains("also move the beef"), amend);
        assertTrue(amend.contains("了解，改成"), amend);
    }

    @Test void briefAcknowledgementNeverUsesTheOldFixedTemplate() {
        String brief = notice("{\"controlled_task_dispatch\":\"starting_now\"}", false, Mode.brief);
        assertFalse(brief.contains("好嘞，记下啦"), brief);
        assertTrue(brief.contains("ONE short"), brief);
        assertTrue(brief.contains("A new owner message is required"), brief);
    }

    @Test void guidanceDispatchIsPhrasedAsAHintNotANewRequirement() {
        String applied = notice("{\"controlled_task_dispatch\":\"guidance_applied\","
                + "\"controlled_task_goal\":\"sort chests\",\"accepted_owner_guidance\":\"use the north chest\"}", false, Mode.brief);
        assertTrue(applied.contains("dispatch=guidance_applied"), applied);
        assertTrue(applied.contains("use the north chest"), applied);
        assertTrue(applied.contains("without restating the goal"), applied);
        String pending = notice("{\"controlled_task_dispatch\":\"guidance_pending\"}", false, Mode.silent);
        assertTrue(pending.contains("guidance_pending"), pending);
        assertTrue(pending.contains("when the task continues"), pending);
    }

    @Test void silentNoticeAllowsANaturalReplyButForbidsStatusBroadcasts() {
        String silent = notice("{\"controlled_task_dispatch\":\"waiting\",\"controlled_task_goal\":\"sort chests\"}", false, Mode.silent);
        assertTrue(silent.contains("dispatch=waiting"), silent);
        assertTrue(silent.contains("ONE short"), silent);
        assertTrue(silent.contains("do not report completion, failure, queue position"), silent);
        assertFalse(silent.contains("STYLE HINTS"), silent);
    }

    @Test void detailedNoticeKeepsTheLegacyInstruction() {
        String detailed = notice("{\"controlled_task_dispatch\":\"starting_now\"}", true, Mode.detailed);
        assertTrue(detailed.contains("Report the controlled_task_goal"), detailed);
        assertFalse(detailed.contains("STYLE HINTS"), detailed);
    }

    @Test void briefOutcomeIsShortAndHidesQueueFacts() {
        String done = TaskAnnouncements.outcome(true, false, "把五块铁放进北边箱子", "已根据现场和实际操作记录核验完成。", 2, Mode.brief);
        assertNotNull(done);
        assertFalse(done.contains("五块铁") || done.contains("北边箱子") || done.contains("2") || done.contains("核验"));
        assertEquals("做完啦～", done);
        assertEquals("I got stuck on this one; let's retry later~",
                TaskAnnouncements.outcome(false, true, "take iron", "final inventory unmet", 0, Mode.brief));
    }

    @Test void detailedOutcomeKeepsGoalOutcomeAndQueueCount() {
        String failed = TaskAnnouncements.outcome(false, false, "把五块铁放进北边箱子", "未通过完成验证或遇到阻塞：final_inventory_below_required_count", 2, Mode.detailed);
        assertTrue(failed.contains("把五块铁放进北边箱子"));
        assertTrue(failed.contains("final_inventory_below_required_count"));
        assertTrue(failed.contains("后续还有2个任务等待"));
        String done = TaskAnnouncements.outcome(true, true, "take iron", "Verified iron received.", 0, Mode.detailed);
        assertEquals("Task complete: Verified iron received.", done);
    }

    @Test void silentModeHasNoOwnerFacingOutcomeNotification() {
        assertNull(TaskAnnouncements.outcome(true, false, "goal", "outcome", 0, Mode.silent));
        assertNull(TaskAnnouncements.outcome(false, true, "goal", "outcome", 0, Mode.silent));
        assertNull(TaskAnnouncements.runtimePause(true, false, Mode.silent));
        assertNull(TaskAnnouncements.runtimePause(false, true, Mode.silent));
    }

    @Test void briefRuntimePauseKeepsTheAdviceOutOfChat() {
        String brief = TaskAnnouncements.runtimePause(true, false, Mode.brief);
        assertFalse(brief.contains("LLM") || brief.contains("凭据") || brief.contains("站点"));
        assertFalse(TaskAnnouncements.runtimePause(false, true, Mode.brief).contains("archive"));
        assertTrue(TaskAnnouncements.runtimePause(true, false, Mode.detailed).contains("后台执行模型不可用"));
        assertTrue(TaskAnnouncements.runtimePause(false, true, Mode.detailed).contains("archive is unavailable"));
    }

    @Test void loggedDetailDoesNotDependOnTheAnnouncementMode() {
        // Logging is side-effect only; the point of this test is that it is callable for every mode and
        // never throws, so the full facts are always available to the server operator.
        for (Mode mode : Mode.values()) {
            TaskAnnouncements.logControl("maid", "enqueue", "task", "queued", 2, "goal", false, "starting_now", mode);
            TaskAnnouncements.logOutcome("maid", "task", "goal", false, "final inventory unmet", 1, mode);
            TaskAnnouncements.logRuntimePause("maid", "task", "goal", "decision_model_unavailable", mode);
        }
    }
}
