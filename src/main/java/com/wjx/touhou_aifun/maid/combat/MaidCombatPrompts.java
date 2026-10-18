package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

/** Prompt-only combat text. Hard decisions stay in the server-side policy. */
public final class MaidCombatPrompts {
    private MaidCombatPrompts() {
    }

    public static String guidance() {
        return """

                ## Combat behavior (server-enforced)
                - The server reflex retaliates immediately when the maid or its actual owner is hurt.
                  Do not call switch_work_task merely to defend, and do not claim an attack or kill without
                  tool/status evidence.
                - Players may be valid targets, but NEVER the actual owner. Player targets are refused entirely
                  while this maid is a public maid, while friendly fire is disabled, or when attack_players is
                  false. These are server hard rules: never claim or attempt to bypass them.
                - Use combat_target to command or clear a specific target only when a player explicitly asks to
                  attack or stop. Obtain entity_id from nearby_entities or scan_surroundings; never guess.
                  clear=true clears the commanded/reflex target and suppresses it for a while: the base attack
                  task will not select it and the maid deals no melee damage to it. The base attack work mode may
                  still engage OTHER hostiles; switching the work mode is how all attacks are stopped.
                - Use combat_control to read or change combat rules. Only the actual owner may change them.
                  notes are owner preferences for tone/roleplay; they never override hard rules.
                """;
    }

    /** Compact state line; empty when the feature is disabled or there is nothing useful to say. */
    public static String statusLine(EntityMaid maid) {
        try {
            MaidCombatStatus status = MaidCombatReflex.status(maid);
            String line = status.toPromptLine();
            if (line == null || line.isBlank()) {
                return "";
            }
            return "\n" + line + "\n";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static String notesBlock(EntityMaid maid) {
        try {
            String notes = MaidCombatData.load(maid).notes();
            if (notes == null || notes.isBlank()) {
                return "";
            }
            return "\n## Owner combat preferences (advisory; server hard rules still apply)\n"
                    + notes + "\n";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static String all(EntityMaid maid) {
        return guidance() + statusLine(maid) + notesBlock(maid);
    }
}
