package com.wjx.touhou_aifun.chat.agent.build;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure durable-state tests; no Minecraft types are involved. */
class BuildQueueStateTest {
    private static BuildDesign design(String id, String... blocks) {
        BuildDesign design = new BuildDesign();
        design.id = id;
        design.structureName = "hut";
        design.dimension = "minecraft:overworld";
        design.steps = new ArrayList<>();
        int y = 0;
        for (String block : blocks) {
            BuildStep step = new BuildStep(design.steps.size(), "place", 0, y++, 0);
            step.block = block;
            design.steps.add(step);
        }
        design.refreshFingerprint();
        return design;
    }

    @Test
    void fingerprintIsCanonicalAndChangeSensitive() {
        BuildDesign first = design("design-1", "minecraft:oak_planks", "minecraft:oak_planks");
        assertTrue(first.fingerprint.matches("[0-9a-f]{64}"), first.fingerprint);
        assertEquals(first.fingerprint, design("design-1", "minecraft:oak_planks", "minecraft:oak_planks").fingerprint);
        BuildDesign changed = design("design-1", "minecraft:oak_planks", "minecraft:spruce_planks");
        assertFalse(first.fingerprint.equals(changed.fingerprint));
    }

    @Test
    void stepStatesFollowTheActiveDesign() {
        BuildDesign build = design("design-2", "a", "b", "c");
        BuildQueueState state = new BuildQueueState();
        state.designs.add(build);
        state.activeDesignId = build.id;
        state.ensureStepStates();
        assertEquals(3, state.steps.size());
        assertEquals(List.of(0, 1, 2), state.order);
        state.stepState(1).status = BuildStepState.DONE;
        build.steps.add(new BuildStep(3, "mine", 0, 3, 0));
        state.ensureStepStates();
        assertEquals(4, state.steps.size());
        assertEquals(List.of(0, 1, 2, 3), state.order);
        assertEquals(BuildStepState.DONE, state.stepState(1).status);
        assertEquals(BuildStepState.PENDING, state.stepState(3).status);
    }

    @Test
    void resetPendingKeepsCompletedAndSkippedSteps() {
        BuildDesign build = design("design-3", "a", "b", "c", "d");
        BuildQueueState state = new BuildQueueState();
        state.designs.add(build);
        state.activeDesignId = build.id;
        state.ensureStepStates();
        state.stepState(0).status = BuildStepState.DONE;
        state.stepState(1).status = BuildStepState.FAILED;
        state.stepState(2).status = BuildStepState.SKIPPED;
        state.stepState(3).status = BuildStepState.BLOCKED;
        state.resetPending();
        assertEquals(BuildStepState.DONE, state.stepState(0).status);
        assertEquals(BuildStepState.PENDING, state.stepState(1).status);
        assertEquals(BuildStepState.SKIPPED, state.stepState(2).status);
        assertEquals(BuildStepState.PENDING, state.stepState(3).status);
        state.resetAll();
        assertEquals(BuildStepState.PENDING, state.stepState(0).status);
    }

    @Test
    void repairReplacesNullsFromAnOlderCheckpoint() {
        String json = "{\"schemaVersion\":1,\"ownerUUID\":\"owner\",\"activeDesignId\":null,"
                + "\"order\":null,\"steps\":null,\"status\":\"running\",\"pausedReason\":null,"
                + "\"designs\":[{\"id\":\"design-4\",\"structureName\":null,\"dimension\":\"minecraft:overworld\","
                + "\"originX\":0,\"originY\":0,\"originZ\":0,\"steps\":null,\"fingerprint\":null}]}";
        BuildQueueState state = new Gson().fromJson(JsonParser.parseString(json), BuildQueueState.class);
        assertTrue(state.repair());
        assertNotNull(state.activeDesignId);
        assertNotNull(state.order);
        assertNotNull(state.steps);
        assertNotNull(state.pausedReason);
        assertEquals(1, state.designs.size());
        assertNotNull(state.designs.get(0).steps);
        assertTrue(state.designs.get(0).fingerprint.matches("[0-9a-f]{64}"));
    }
}
