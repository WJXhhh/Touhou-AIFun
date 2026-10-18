package com.wjx.touhou_aifun.maid.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidCombatPromptsTest {
    @Test
    void guidanceStatesTheServerHardRules() {
        String guidance = MaidCombatPrompts.guidance().toLowerCase(java.util.Locale.ROOT);
        assertTrue(guidance.contains("never the actual owner"));
        assertTrue(guidance.contains("public maid"));
        assertTrue(guidance.contains("friendly fire"));
        assertTrue(guidance.contains("attack_players"));
        assertTrue(guidance.contains("combat_target"));
        assertTrue(guidance.contains("combat_control"));
        assertTrue(guidance.contains("only the actual owner"));
    }
}
