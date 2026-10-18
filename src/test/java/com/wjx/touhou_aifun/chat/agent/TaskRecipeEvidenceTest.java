package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskRecipeEvidenceTest {
    @Test void retainedDefinitionKeepsIngredientsAndUnknownDataButNeverHistoricalInventoryAvailability() {
        var original=JsonParser.parseString("{\"recipe_id\":\"pack:machine\",\"recipe_version\":\"v1\",\"output\":{\"item\":\"pack:machine\",\"count\":2,\"fingerprint\":\"nbt\"},\"can_fill_one_craft\":true,\"crafts_for_requested_count\":3,\"ingredients\":[{\"grid_slot\":1,\"definition\":{\"tag\":\"minecraft:planks\"},\"accepted_examples\":[\"minecraft:oak_planks\"],\"examples_truncated\":true,\"matching_backpack_count\":64}],\"custom_recipe_rule\":{\"required\":true}}").getAsJsonObject();
        var retained=TaskRecipeEvidence.definition(original);
        assertFalse(retained.has("can_fill_one_craft"));assertFalse(retained.has("crafts_for_requested_count"));
        assertFalse(retained.getAsJsonArray("ingredients").get(0).getAsJsonObject().has("matching_backpack_count"));
        assertEquals(original.get("output"),retained.get("output"));assertEquals(original.get("custom_recipe_rule"),retained.get("custom_recipe_rule"));
        assertTrue(retained.getAsJsonArray("ingredients").get(0).getAsJsonObject().get("examples_truncated").getAsBoolean());
        assertTrue(original.has("can_fill_one_craft"));assertEquals(64,original.getAsJsonArray("ingredients").get(0).getAsJsonObject().get("matching_backpack_count").getAsInt());
    }
}
