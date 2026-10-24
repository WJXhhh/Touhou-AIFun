package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TaskGuidanceTest {
    private static JsonObject completion() {
        return JsonParser.parseString("{\"kind\":\"transfer\",\"dimension\":\"minecraft:overworld\","
                + "\"item\":\"minecraft:iron_ingot\",\"count\":1,\"to_maid\":true,\"position\":[1,2,3]}").getAsJsonObject();
    }

    @Test void guidanceIsAdvisoryAndNeverChangesTheContract() {
        var task = new AgentTaskState("put five iron away");
        task.defineCompletion(completion());
        var before = task.completion.deepCopy();
        task.guide("the north chest is behind the wall, walk around it");
        assertEquals(1, task.goalVersion);
        assertTrue(task.constraints.isEmpty());
        assertEquals(0, task.actionSteps.size());
        assertEquals(before, task.completion);
        assertFalse(task.effectiveGoal().contains("behind the wall"), task.effectiveGoal());
    }

    @Test void guidanceAccumulatesAndIsConsumedOnce() {
        var task = new AgentTaskState("sort");
        task.guide("first");
        task.guide("second");
        assertEquals("first\nsecond", task.takeGuidance());
        assertEquals("", task.takeGuidance());
        assertFalse(task.hasGuidance());
    }

    @Test void amendmentDoesNotSwallowExistingGuidance() {
        var task = new AgentTaskState("sort");
        task.guide("watch the lava");
        task.amend("use the north chest");
        task.applyAmendment();
        assertEquals(2, task.goalVersion);
        assertEquals("use the north chest", task.constraints.get(0));
        assertEquals("watch the lava", task.takeGuidance(), "guidance stays queued for the next checkpoint");
    }

    @Test void oldCheckpointWithoutGuidanceLoadsEmptyAndBoundsLegacyOversize() {
        var old = new Gson().fromJson("{\"goal\":\"sort\"}", AgentTaskState.class);
        assertEquals("", old.pendingGuidance);
        old.pendingGuidance = null;
        old.validatePhaseCheckpoint();
        assertEquals("", old.pendingGuidance);
        var tooLong = new AgentTaskState("sort");
        tooLong.pendingGuidance = "x".repeat(5000);
        tooLong.validatePhaseCheckpoint();
        assertEquals(AgentTaskState.MAX_GUIDANCE_CHARS, tooLong.pendingGuidance.length());
    }

    @Test void oversizedGuidanceIsRejectedAtomically() {
        var task = new AgentTaskState("sort");
        task.guide("keep this");
        assertThrows(IllegalArgumentException.class, () -> task.guide("y".repeat(2000)));
        assertEquals("keep this", task.pendingGuidance);
        assertThrows(IllegalArgumentException.class, () -> task.guide("  "));
    }

    @Test void guidanceSurvivesSerializationAfterNormalization() {
        var task = new AgentTaskState("sort");
        task.guide("use the north chest");
        var restored = new Gson().fromJson(new Gson().toJson(task), AgentTaskState.class);
        restored.validatePhaseCheckpoint();
        assertEquals("use the north chest", restored.takeGuidance());
    }
}
