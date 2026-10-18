package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;

import javax.annotation.Nullable;

/**
 * Per-maid combat rules. Stored in Forge persistent data (server side only) so
 * no new EntityData/network protocol is required. The hot path is a single small
 * NBT read per decision; the values are tiny and the config fallbacks are live.
 */
public final class MaidCombatData {
    public static final int DEFAULT_ENGAGE_RADIUS = 16;
    public static final int MIN_ENGAGE_RADIUS = 4;
    public static final int MAX_ENGAGE_RADIUS = 48;

    private MaidCombatData() {
    }

    public enum Mode {
        OFF,
        DEFEND
    }

    public record Snapshot(boolean enabled, Mode mode, boolean explicitMode, boolean protectOwner,
                           boolean attackPlayers, int engageRadius, String notes,
                           boolean publicMaid, boolean friendlyFireAllowed) {
    }

    public static Snapshot load(EntityMaid maid) {
        boolean loaded = TouhouAIFunConfig.SPEC.isLoaded();
        boolean enabled = loaded && TouhouAIFunConfig.COMBAT_ENABLED.get();
        MaidCombatSettings.Values values = MaidCombatSettings.read(maid.getPersistentData());

        boolean explicitMode = values.mode() != null;
        Mode mode;
        if (values.mode() != null) {
            mode = parseMode(values.mode());
        } else if (maid.getTask() instanceof IAttackTask) {
            mode = Mode.DEFEND;
        } else if (loaded && TouhouAIFunConfig.COMBAT_DEFEND_IN_ALL_TASKS.get()) {
            mode = Mode.DEFEND;
        } else {
            mode = Mode.OFF;
        }

        boolean protectOwner = values.protectOwner() != null
                ? values.protectOwner()
                : (!loaded || TouhouAIFunConfig.COMBAT_PROTECT_OWNER.get());
        boolean attackPlayers = values.attackPlayers() != null
                ? values.attackPlayers()
                : (!loaded || TouhouAIFunConfig.COMBAT_ATTACK_PLAYERS.get());
        int configuredRadius = loaded ? TouhouAIFunConfig.COMBAT_ENGAGE_RADIUS.get() : DEFAULT_ENGAGE_RADIUS;
        int engageRadius = clampRadius(values.engageRadius() != null ? values.engageRadius() : configuredRadius);
        String notes = MaidCombatSettings.sanitizeNotes(values.notes());

        boolean publicMaid = false;
        boolean friendlyFireAllowed = true;
        try {
            publicMaid = PublicMaidAccess.isPublic(maid);
            friendlyFireAllowed = PublicMaidAccess.isFriendlyFireAllowed(maid);
        } catch (RuntimeException ignored) {
            // An entity without the addon data mixin behaves like an ordinary private maid.
        }
        return new Snapshot(enabled, mode, explicitMode, protectOwner, attackPlayers,
                engageRadius, notes, publicMaid, friendlyFireAllowed);
    }

    /**
     * Apply a partial update. Null arguments keep the existing stored value;
     * reset removes all stored overrides first. Values are clamped/sanitized.
     */
    public static void store(EntityMaid maid, @Nullable Mode mode, @Nullable Boolean protectOwner,
                             @Nullable Boolean attackPlayers, @Nullable Integer engageRadius,
                             @Nullable String notes, boolean reset) {
        MaidCombatSettings.Values current = reset ? MaidCombatSettings.Values.EMPTY
                : MaidCombatSettings.read(maid.getPersistentData());
        String nextMode = mode != null ? modeName(mode) : current.mode();
        Boolean nextProtect = protectOwner != null ? protectOwner : current.protectOwner();
        Boolean nextAttack = attackPlayers != null ? attackPlayers : current.attackPlayers();
        Integer nextRadius = engageRadius != null
                ? Integer.valueOf(clampRadius(engageRadius)) : current.engageRadius();
        String nextNotes = notes != null
                ? MaidCombatSettings.sanitizeNotes(notes) : current.notes();
        MaidCombatSettings.write(maid.getPersistentData(),
                new MaidCombatSettings.Values(nextMode, nextProtect, nextAttack, nextRadius, nextNotes));
    }

    public static void reset(EntityMaid maid) {
        maid.getPersistentData().remove(MaidCombatSettings.ROOT_TAG);
    }

    public static int clampRadius(int radius) {
        return Math.max(MIN_ENGAGE_RADIUS, Math.min(MAX_ENGAGE_RADIUS, radius));
    }

    public static Mode parseMode(@Nullable String mode) {
        return "off".equals(MaidCombatSettings.normalizeMode(mode)) ? Mode.OFF : Mode.DEFEND;
    }

    public static String modeName(Mode mode) {
        return mode == Mode.OFF ? "off" : "defend";
    }
}
