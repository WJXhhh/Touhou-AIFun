package com.wjx.touhou_aifun.compat.slashblade;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SlashBladeTacticsTest {
    @Test void onlyKnownNativeArtsAreAutoCast() {
        for (String art : new String[]{"judgement_cut", "sakura_end", "void_slash", "circle_slash",
                "drive_horizontal", "drive_vertical", "wave_edge", "piercing"}) {
            assertTrue(SlashBladeTactics.knownArt("slashblade:" + art));
        }
        assertFalse(SlashBladeTactics.knownArt("addon:judgement_cut"));
        assertFalse(SlashBladeTactics.knownArt("slashblade:none"));
        assertFalse(SlashBladeTactics.knownArt(null));
    }
    @Test void powerIsRequiredForEverySwordAndNeverInferredFromSouls() {
        assertFalse(SlashBladeTactics.phantomAllowed(true, false, false, 0, 10000, 2));
        assertTrue(SlashBladeTactics.phantomAllowed(true, false, false, 1, 2, 2));
        assertFalse(SlashBladeTactics.phantomAllowed(true, true, false, 5, 10000, 2));
        assertFalse(SlashBladeTactics.phantomAllowed(true, false, true, 5, 10000, 2));
        assertFalse(SlashBladeTactics.phantomAllowed(false, false, false, 5, 10000, 2));
        assertFalse(SlashBladeTactics.phantomAllowed(true, false, false, 5, 19, 20));
    }
    @Test void selectionUsesTheCurrentArtAndCombatOpportunity() {
        assertTrue(SlashBladeTactics.wantsArt("slashblade:judgement_cut", 9, 4, 1, false));
        assertFalse(SlashBladeTactics.wantsArt("slashblade:circle_slash", 9, 4, 1, false));
        assertTrue(SlashBladeTactics.wantsArt("slashblade:circle_slash", 3, 4, 3, false));
        assertFalse(SlashBladeTactics.wantsArt("slashblade:circle_slash", 3, 4, 3, true));
    }
}
