package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IRangedAttackTask;
import com.github.tartaricacid.touhoulittlemaid.entity.item.AbstractEntityFromItem;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.Npc;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraftforge.common.util.FakePlayer;

import javax.annotation.Nullable;

/**
 * Minecraft-facing combat policy. The actual boolean matrix lives in
 * {@link MaidCombatRules}; this class only maps entities and config values.
 */
public final class MaidCombatPolicy {
    private MaidCombatPolicy() {
    }

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "ok");
        }

        public static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    public static boolean enabled(EntityMaid maid) {
        return MaidCombatData.load(maid).enabled();
    }

    @Nullable
    public static LivingEntity attacker(DamageSource source) {
        if (source == null) {
            return null;
        }
        Entity entity = source.getEntity();
        if (entity instanceof LivingEntity living) {
            return living;
        }
        Entity direct = source.getDirectEntity();
        return direct instanceof LivingEntity living ? living : null;
    }

    /**
     * Validate one automatic reflex threat or one explicit command target.
     * The owner threat is evaluated with protectOwner/mode; explicit commands
     * bypass the stance mode but never the hard target rules.
     */
    public static Decision decision(EntityMaid maid, @Nullable LivingEntity target,
                                    boolean ownerThreat, boolean explicit) {
        MaidCombatData.Snapshot snapshot = MaidCombatData.load(maid);
        if (!snapshot.enabled()) {
            return Decision.deny("combat_disabled");
        }
        if (ownerThreat) {
            if (!snapshot.protectOwner()) {
                return Decision.deny("owner_protection_disabled");
            }
            if (snapshot.mode() != MaidCombatData.Mode.DEFEND) {
                return Decision.deny("combat_mode_off");
            }
        } else if (!explicit && snapshot.mode() != MaidCombatData.Mode.DEFEND) {
            return Decision.deny("combat_mode_off");
        }
        if (target == null || target == maid || !target.isAlive() || target.isRemoved()
                || target.level() != maid.level()) {
            return Decision.deny("target_unavailable");
        }
        if (!maid.canBrainMoving()) {
            return Decision.deny("maid_cannot_move");
        }
        if (maid.hasRestriction() && !maid.isWithinRestriction(target.blockPosition())) {
            return Decision.deny("target_outside_home");
        }

        if (target instanceof Player player) {
            MaidCombatRules.PlayerBlock block = playerBlock(maid, player);
            if (block != MaidCombatRules.PlayerBlock.ALLOWED) {
                return Decision.deny(MaidCombatRules.playerBlockName(block));
            }
        } else {
            String customName = target.getCustomName() == null ? "" : target.getCustomName().getString();
            boolean basic = MaidCombatRules.basicTargetAllowed(false, target.isAlive(), target.isRemoved(),
                    target.level() == maid.level(), maid.isAlliedTo(target),
                    target instanceof TamableAnimal, target instanceof Npc,
                    target instanceof ArmorStand, target instanceof AbstractEntityFromItem,
                    customName.startsWith(IAttackTask.MAID_NO_ATTACK_TAG));
            if (!basic) {
                return Decision.deny("target_excluded");
            }
            boolean attackTask = maid.getTask() instanceof IAttackTask;
            if (!MaidCombatRules.creeperAllowed(target instanceof Creeper, fleeCreepers(),
                    attackTask, explicit)) {
                return Decision.deny("creeper_flee");
            }
        }

        boolean within = MaidCombatRules.withinRadiusSqr(maid.distanceToSqr(target),
                snapshot.engageRadius());
        if (!within && ownerThreat) {
            LivingEntity owner = maid.getOwner();
            if (owner != null && owner.level() == target.level()) {
                within = MaidCombatRules.withinRadiusSqr(owner.distanceToSqr(target),
                        snapshot.engageRadius());
            }
        }
        if (!within) {
            return Decision.deny("target_out_of_range");
        }
        return Decision.allow();
    }

    public static MaidCombatRules.PlayerBlock playerBlock(EntityMaid maid, Player player) {
        MaidCombatData.Snapshot snapshot = MaidCombatData.load(maid);
        boolean owner;
        try {
            owner = PublicMaidAccess.isActualOwner(maid, player);
        } catch (RuntimeException ignored) {
            owner = maid.getOwnerUUID() != null && maid.getOwnerUUID().equals(player.getUUID());
        }
        return MaidCombatRules.playerTarget(true, owner, snapshot.publicMaid(),
                snapshot.friendlyFireAllowed(), snapshot.attackPlayers(),
                player instanceof FakePlayer, player.isCreative(), player.isSpectator());
    }

    /** Human/model readable hard-gate summary used by the tools and prompt. */
    public static String playerGate(EntityMaid maid) {
        MaidCombatData.Snapshot snapshot = MaidCombatData.load(maid);
        if (snapshot.publicMaid()) {
            return "blocked_public_maid";
        }
        if (!snapshot.friendlyFireAllowed()) {
            return "blocked_friendly_fire";
        }
        if (!snapshot.attackPlayers()) {
            return "attack_players_disabled";
        }
        return "allowed";
    }

    public static MaidCombatRules.Engagement engagement(EntityMaid maid, boolean explicit) {
        MaidCombatData.Snapshot snapshot = MaidCombatData.load(maid);
        IMaidTask task = maid.getTask();
        boolean attackTask = task instanceof IAttackTask;
        boolean rangedTask = task instanceof IRangedAttackTask;
        boolean weapon = false;
        if (attackTask) {
            try {
                weapon = ((IAttackTask) task).isWeapon(maid, maid.getMainHandItem());
            } catch (RuntimeException ignored) {
                weapon = false;
            }
        }
        return MaidCombatRules.engagement(snapshot.enabled(), attackTask, rangedTask, weapon,
                snapshot.mode() == MaidCombatData.Mode.DEFEND, holdingProjectileWeapon(maid), explicit);
    }

    public static boolean holdingProjectileWeapon(EntityMaid maid) {
        return maid.isHolding(stack -> {
            var item = stack.getItem();
            return item instanceof ProjectileWeaponItem projectile
                    && maid.canFireProjectileWeapon(projectile);
        });
    }

    public static String entityName(Entity entity) {
        if (entity == null) {
            return "none";
        }
        String name = entity.getName().getString();
        return name == null || name.isBlank() ? entity.getType().toString() : name;
    }

    public static String entityType(Entity entity) {
        return entity == null ? "none" : entity.getType().toString();
    }

    public static void debug(EntityMaid maid, String message) {
        if (TouhouAIFunConfig.SPEC.isLoaded() && TouhouAIFunConfig.COMBAT_DEBUG.get()) {
            TouhouAIFun.LOGGER.debug("[combat] maid={} {}", maid.getUUID(), message);
        }
    }

    private static boolean fleeCreepers() {
        return !TouhouAIFunConfig.SPEC.isLoaded() || TouhouAIFunConfig.COMBAT_FLEE_CREEPERS.get();
    }
}
