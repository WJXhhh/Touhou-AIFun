package com.wjx.touhou_aifun.maid.combat;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidCombatStatusTest {
    @Test
    void disabledStatusProducesNoPromptLineButStillSerializes() {
        MaidCombatStatus status = new MaidCombatStatus(false, "off", false, true, true, 16,
                false, true, "allowed", "none", "none", "none", "none", 0, "note");
        assertEquals("", status.toPromptLine());
        JsonObject json = status.toJson();
        assertEquals(false, json.get("enabled").getAsBoolean());
        assertEquals("off", json.get("mode").getAsString());
        assertEquals("note", json.get("notes").getAsString());
    }

    @Test
    void enabledStatusDescribesTargetsAndGate() {
        MaidCombatStatus status = new MaidCombatStatus(true, "defend", false, true, true, 16,
                true, true, "blocked_public_maid", "melee",
                "Zombie [minecraft:zombie, id=7, d=2.0]", "Skeleton [minecraft:skeleton, id=9, d=3.0]",
                "Creeper", 120, "");
        String line = status.toPromptLine();
        assertTrue(line.contains("stance=defend"));
        assertTrue(line.contains("blocked_public_maid"));
        assertTrue(line.contains("target=Zombie"));
        assertTrue(line.contains("commanded_target=Skeleton"));
        assertTrue(line.contains("suppressed=Creeper"));
        assertFalse(line.contains("notes"));
        assertEquals("melee", status.toJson().get("engagement").getAsString());
    }
}
