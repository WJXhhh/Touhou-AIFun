package com.wjx.touhou_aifun.maid.combat;

import com.google.gson.JsonObject;

/** Compact, model-facing view of one maid's combat state. */
public record MaidCombatStatus(boolean enabled, String mode, boolean explicitMode,
                               boolean protectOwner, boolean attackPlayers, int engageRadius,
                               boolean publicMaid, boolean friendlyFireAllowed,
                               String playerGate, String engagement, String target,
                               String commandTarget, String suppressedTarget,
                               long reflexRemainingTicks, String notes) {
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("status", "ok");
        out.addProperty("enabled", enabled);
        out.addProperty("mode", mode);
        out.addProperty("explicit_mode", explicitMode);
        out.addProperty("protect_owner", protectOwner);
        out.addProperty("attack_players", attackPlayers);
        out.addProperty("engage_radius", engageRadius);
        out.addProperty("public_maid", publicMaid);
        out.addProperty("friendly_fire_allowed", friendlyFireAllowed);
        out.addProperty("player_retaliation", playerGate);
        out.addProperty("engagement", engagement);
        out.addProperty("target", target);
        out.addProperty("commanded_target", commandTarget);
        out.addProperty("suppressed_target", suppressedTarget);
        out.addProperty("reflex_remaining_ticks", reflexRemainingTicks);
        if (notes != null && !notes.isBlank()) {
            out.addProperty("notes", notes);
        }
        return out;
    }

    /** One short line for the system prompt; empty when combat is disabled. */
    public String toPromptLine() {
        if (!enabled) {
            return "";
        }
        StringBuilder line = new StringBuilder("Combat: stance=").append(mode)
                .append(", attack_players=").append(attackPlayers)
                .append(", player_retaliation=").append(playerGate)
                .append(", engagement=").append(engagement);
        if (!"none".equals(target)) {
            line.append(", target=").append(target);
        }
        if (!"none".equals(commandTarget)) {
            line.append(", commanded_target=").append(commandTarget);
        }
        if (!"none".equals(suppressedTarget)) {
            line.append(", suppressed=").append(suppressedTarget);
        }
        line.append('.');
        return line.toString();
    }
}
