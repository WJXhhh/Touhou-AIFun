package com.wjx.touhou_aifun.maid.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidCombatRulesTest {
    @Test
    void ownerAndPublicMaidBlockEveryPlayerTarget() {
        assertEquals(MaidCombatRules.PlayerBlock.OWNER, target(true, true, false, true, true));
        assertEquals(MaidCombatRules.PlayerBlock.PUBLIC_MAID, target(true, false, true, true, true));
        assertEquals(MaidCombatRules.PlayerBlock.FRIENDLY_FIRE_DISABLED,
                target(true, false, false, false, true));
        assertEquals(MaidCombatRules.PlayerBlock.ATTACK_PLAYERS_DISABLED,
                target(true, false, false, true, false));
    }

    @Test
    void ordinaryPlayerIsAllowedAndSpecialPlayersAreRejected() {
        assertEquals(MaidCombatRules.PlayerBlock.ALLOWED, target(true, false, false, true, true));
        assertEquals(MaidCombatRules.PlayerBlock.FAKE_PLAYER,
                MaidCombatRules.playerTarget(true, false, false, true, true, true, false, false));
        assertEquals(MaidCombatRules.PlayerBlock.CREATIVE,
                MaidCombatRules.playerTarget(true, false, false, true, true, false, true, false));
        assertEquals(MaidCombatRules.PlayerBlock.SPECTATOR,
                MaidCombatRules.playerTarget(true, false, false, true, true, false, false, true));
        assertEquals(MaidCombatRules.PlayerBlock.NOT_PLAYER,
                MaidCombatRules.playerTarget(false, false, true, false, false, true, true, true));
    }

    @Test
    void attackTaskRequiresAWeaponButExplicitCommandsDoNot() {
        assertEquals(MaidCombatRules.Engagement.MELEE,
                MaidCombatRules.engagement(true, true, false, true, true, false, false));
        assertEquals(MaidCombatRules.Engagement.NONE,
                MaidCombatRules.engagement(true, true, false, false, true, false, false));
        assertEquals(MaidCombatRules.Engagement.MELEE,
                MaidCombatRules.engagement(true, true, false, false, false, false, true));
        assertEquals(MaidCombatRules.Engagement.NONE,
                MaidCombatRules.engagement(true, true, false, true, false, false, false),
                "mode=off disables automatic reflex even in an attack task");
        assertEquals(MaidCombatRules.Engagement.MELEE,
                MaidCombatRules.engagement(true, true, false, true, false, false, true),
                "an explicit command overrides the off stance");
    }

    @Test
    void rangedTasksOnlyLockATargetAndNeverMelee() {
        assertEquals(MaidCombatRules.Engagement.TARGET_ONLY,
                MaidCombatRules.engagement(true, true, true, true, true, false, false));
        assertEquals(MaidCombatRules.Engagement.NONE,
                MaidCombatRules.engagement(true, true, true, false, true, false, false));
        assertEquals(MaidCombatRules.Engagement.TARGET_ONLY,
                MaidCombatRules.engagement(true, true, true, false, false, false, true));
    }

    @Test
    void nonAttackTasksOnlyFightWhenDefendOrExplicit() {
        assertEquals(MaidCombatRules.Engagement.NONE,
                MaidCombatRules.engagement(true, false, false, false, false, false, false));
        assertEquals(MaidCombatRules.Engagement.MELEE,
                MaidCombatRules.engagement(true, false, false, false, true, false, false));
        assertEquals(MaidCombatRules.Engagement.MELEE,
                MaidCombatRules.engagement(true, false, false, false, false, false, true));
        assertEquals(MaidCombatRules.Engagement.NONE,
                MaidCombatRules.engagement(true, false, false, true, true, true, true),
                "holding a ranged weapon does not make a non-attack task melee");
        assertEquals(MaidCombatRules.Engagement.NONE,
                MaidCombatRules.engagement(false, false, false, false, true, false, true));
    }

    @Test
    void creeperAndBasicTargetRules() {
        assertTrue(MaidCombatRules.creeperAllowed(true, true, true, false));
        assertTrue(MaidCombatRules.creeperAllowed(true, true, false, true));
        assertFalse(MaidCombatRules.creeperAllowed(true, true, false, false));
        assertTrue(MaidCombatRules.creeperAllowed(true, false, false, false));
        assertTrue(MaidCombatRules.basicTargetAllowed(false, true, false, true,
                false, false, false, false, false, false));
        assertFalse(MaidCombatRules.basicTargetAllowed(false, true, false, true,
                true, false, false, false, false, false));
        assertFalse(MaidCombatRules.basicTargetAllowed(false, true, false, true,
                false, true, false, false, false, false));
        assertFalse(MaidCombatRules.basicTargetAllowed(false, true, false, true,
                false, false, false, false, false, true));
        assertTrue(MaidCombatRules.withinRadiusSqr(16, 4));
        assertFalse(MaidCombatRules.withinRadiusSqr(17, 4));
    }

    private static MaidCombatRules.PlayerBlock target(boolean player, boolean owner, boolean publicMaid,
                                                      boolean friendlyFire, boolean attackPlayers) {
        return MaidCombatRules.playerTarget(player, owner, publicMaid, friendlyFire, attackPlayers,
                false, false, false);
    }
}
