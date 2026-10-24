package com.wjx.touhou_aifun.chat.agent.build;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildMaterialPolicyTest {
    private static BuildDesign design() {
        BuildDesign design = new BuildDesign();
        design.structureName = "wall";
        design.dimension = "minecraft:overworld";
        BuildStep a = new BuildStep(0, "place", 0, 64, 0);
        a.item = "minecraft:cobblestone";
        BuildStep b = new BuildStep(1, "place", 1, 64, 0);
        b.item = "minecraft:cobblestone";
        BuildStep c = new BuildStep(2, "interact", 1, 65, 0);
        c.item = "minecraft:lever";
        BuildStep optional = new BuildStep(3, "place", 2, 64, 0);
        optional.item = "minecraft:torch";
        optional.required = false;
        design.steps.add(a);
        design.steps.add(b);
        design.steps.add(c);
        design.steps.add(optional);
        design.reindex();
        return design;
    }

    @Test void requiredCountsSkipDoneStepsAndOptionalItems() {
        BuildDesign design = design();
        BuildQueueState state = new BuildQueueState();
        state.designs.add(design);
        state.activeDesignId = design.id;
        state.ensureStepStates();
        state.stepState(0).status = BuildStepState.DONE;
        Map<String, Integer> counts = BuildMaterialPolicy.requiredCounts(design, state);
        assertEquals(Map.of("minecraft:cobblestone", 1, "minecraft:lever", 1), counts);
        state.stepState(1).status = BuildStepState.SKIPPED;
        counts = BuildMaterialPolicy.requiredCounts(design, state);
        assertEquals(Map.of("minecraft:lever", 1), counts);
    }

    @Test void explicitItemWinsOverBlockDerivationAndMissingInfoIsStable() {
        BuildStep step = new BuildStep(0, "place", 0, 0, 0);
        step.block = "minecraft:oak_planks";
        step.item = "minecraft:birch_planks";
        assertEquals("minecraft:birch_planks", BuildMaterialPolicy.requiredItem(step));
        assertEquals("", BuildMaterialPolicy.requiredItem(new BuildStep(0, "place", 0, 0, 0)));
    }

    @Test void missingJsonIsBoundedAndIncludesDisplayFields() {
        var missing = java.util.List.of(new BuildMaterialPolicy.Missing("minecraft:stone", "Stone", 8, 2, 3));
        var json = BuildMaterialPolicy.missingJson(missing);
        assertEquals(1, json.size());
        assertEquals("Stone", json.get(0).getAsJsonObject().get("display_name").getAsString());
        assertEquals(6, json.get(0).getAsJsonObject().get("missing").getAsInt());
    }
}
