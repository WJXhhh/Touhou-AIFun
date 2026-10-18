package com.wjx.touhou_aifun.maid.combat;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaidCombatSettingsTest {
    @Test
    void emptyParentReadsAsEmptyAndInvalidModesAreRejected() {
        assertTrue(MaidCombatSettings.read(new CompoundTag()).isEmpty());
        assertNull(MaidCombatSettings.normalizeMode("aggressive"));
        assertEquals("off", MaidCombatSettings.normalizeMode(" OFF "));
        assertEquals("defend", MaidCombatSettings.normalizeMode("defend"));
    }

    @Test
    void valuesRoundTripAndSanitizeNotes() {
        CompoundTag parent = new CompoundTag();
        MaidCombatSettings.write(parent, new MaidCombatSettings.Values(
                "defend", true, false, 24, "  keep <owner> safe\nplease  "));
        MaidCombatSettings.Values values = MaidCombatSettings.read(parent);
        assertEquals("defend", values.mode());
        assertEquals(true, values.protectOwner());
        assertEquals(false, values.attackPlayers());
        assertEquals(24, values.engageRadius());
        assertEquals("keep [owner] safe please", values.notes());
        assertTrue(parent.contains(MaidCombatSettings.ROOT_TAG));
    }

    @Test
    void emptyValuesRemoveTheStoredOverrides() {
        CompoundTag parent = new CompoundTag();
        MaidCombatSettings.write(parent, new MaidCombatSettings.Values("off", null, null, null, ""));
        assertTrue(parent.contains(MaidCombatSettings.ROOT_TAG));
        MaidCombatSettings.write(parent, MaidCombatSettings.Values.EMPTY);
        assertFalse(parent.contains(MaidCombatSettings.ROOT_TAG));
    }

    @Test
    void notesAreBoundedByCodePoints() {
        String longNotes = "好".repeat(MaidCombatSettings.MAX_NOTES_CODE_POINTS + 40);
        String sanitized = MaidCombatSettings.sanitizeNotes(longNotes);
        assertEquals(MaidCombatSettings.MAX_NOTES_CODE_POINTS,
                sanitized.codePointCount(0, sanitized.length()));
        assertEquals("", MaidCombatSettings.sanitizeNotes(null));
        assertEquals("a [b]", MaidCombatSettings.sanitizeNotes("a <b>"));
    }

    @Test
    void dataClampsAndParsesModes() {
        assertEquals(4, MaidCombatData.clampRadius(0));
        assertEquals(48, MaidCombatData.clampRadius(999));
        assertEquals(16, MaidCombatData.clampRadius(16));
        assertEquals(MaidCombatData.Mode.OFF, MaidCombatData.parseMode("off"));
        assertEquals(MaidCombatData.Mode.DEFEND, MaidCombatData.parseMode("defend"));
        assertEquals("off", MaidCombatData.modeName(MaidCombatData.Mode.OFF));
        assertEquals("defend", MaidCombatData.modeName(MaidCombatData.Mode.DEFEND));
    }
}
