package com.wjx.touhou_aifun.maid.combat;

/**
 * Pure combat decision rules, intentionally free of Minecraft entity types so the
 * player-target and engagement matrix can be unit tested without a running world.
 */
public final class MaidCombatRules {
    private MaidCombatRules() {
    }

    public enum Engagement {
        NONE,
        TARGET_ONLY,
        MELEE
    }

    public enum PlayerBlock {
        ALLOWED,
        OWNER,
        PUBLIC_MAID,
        FRIENDLY_FIRE_DISABLED,
        ATTACK_PLAYERS_DISABLED,
        FAKE_PLAYER,
        CREATIVE,
        SPECTATOR,
        NOT_PLAYER
    }

    /**
     * The hard player-target matrix requested by the owner:
     * owner is never valid; public maid and disabled friendly fire block every
     * player target; the per-maid attack_players flag can only restrict further.
     */
    public static PlayerBlock playerTarget(boolean player, boolean owner, boolean publicMaid,
                                           boolean friendlyFireAllowed, boolean attackPlayers,
                                           boolean fakePlayer, boolean creative, boolean spectator) {
        if (!player) {
            return PlayerBlock.NOT_PLAYER;
        }
        if (owner) {
            return PlayerBlock.OWNER;
        }
        if (publicMaid) {
            return PlayerBlock.PUBLIC_MAID;
        }
        if (!friendlyFireAllowed) {
            return PlayerBlock.FRIENDLY_FIRE_DISABLED;
        }
        if (!attackPlayers) {
            return PlayerBlock.ATTACK_PLAYERS_DISABLED;
        }
        if (fakePlayer) {
            return PlayerBlock.FAKE_PLAYER;
        }
        if (creative) {
            return PlayerBlock.CREATIVE;
        }
        if (spectator) {
            return PlayerBlock.SPECTATOR;
        }
        return PlayerBlock.ALLOWED;
    }

    /**
     * How the maid can participate in a fight.
     * <p>
     * Attack work modes keep the base weapon requirement. Ranged/danmaku tasks only
     * lock a target and let the base task perform its own ranged attack; they are
     * never forced into melee. Non-attack work modes only fight when the per-maid
     * defend mode (or the global defendInAllTasks default) enables it, or when the
     * owner explicitly commands a target.
     */
    public static Engagement engagement(boolean enabled, boolean attackTask, boolean rangedTask,
                                        boolean weapon, boolean defendMode,
                                        boolean holdingProjectileWeapon, boolean explicit) {
        if (!enabled) {
            return Engagement.NONE;
        }
        if (attackTask) {
            // The per-maid stance can still turn the addon reflex off inside an attack
            // work task. An explicit command is allowed to override that stance.
            if (!explicit && !defendMode) {
                return Engagement.NONE;
            }
            if (rangedTask) {
                return weapon || explicit ? Engagement.TARGET_ONLY : Engagement.NONE;
            }
            return weapon || explicit ? Engagement.MELEE : Engagement.NONE;
        }
        if (holdingProjectileWeapon) {
            return Engagement.NONE;
        }
        return explicit || defendMode ? Engagement.MELEE : Engagement.NONE;
    }

    public static boolean withinRadiusSqr(double distanceSqr, int radius) {
        double value = Math.max(0, radius);
        return distanceSqr <= value * value;
    }

    /** Basic non-player target filtering; player targets are handled separately. */
    public static boolean basicTargetAllowed(boolean sameEntity, boolean alive, boolean removed,
                                             boolean sameLevel, boolean allied, boolean tamable,
                                             boolean npc, boolean armorStand, boolean itemEntity,
                                             boolean noAttackName) {
        if (sameEntity || !alive || removed || !sameLevel) {
            return false;
        }
        return !allied && !tamable && !npc && !armorStand && !itemEntity && !noAttackName;
    }

    /**
     * Creepers are left to the base panic/flee behavior outside attack work modes
     * unless the owner explicitly commands them. Attack work modes keep the base
     * creeper rules (they are hostile and normally targeted there).
     */
    public static boolean creeperAllowed(boolean creeper, boolean fleeCreepers,
                                         boolean attackTask, boolean explicit) {
        if (!creeper || explicit || attackTask) {
            return true;
        }
        return !fleeCreepers;
    }

    public static String playerBlockName(PlayerBlock block) {
        return switch (block) {
            case ALLOWED -> "allowed";
            case OWNER -> "blocked_owner";
            case PUBLIC_MAID -> "blocked_public_maid";
            case FRIENDLY_FIRE_DISABLED -> "blocked_friendly_fire";
            case ATTACK_PLAYERS_DISABLED -> "blocked_attack_players_disabled";
            case FAKE_PLAYER -> "blocked_fake_player";
            case CREATIVE -> "blocked_creative_player";
            case SPECTATOR -> "blocked_spectator";
            case NOT_PLAYER -> "not_a_player";
        };
    }
}
