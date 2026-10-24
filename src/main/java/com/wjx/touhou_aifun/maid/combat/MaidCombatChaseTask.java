package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

/**
 * Chase behavior registered at a very late priority so it wins over the owner
 * follow / work / random-walk walk targets while a reflex target is active.
 * Actual pathing may start on the next brain tick; the target acquisition itself
 * is immediate.
 */
public final class MaidCombatChaseTask extends Behavior<EntityMaid> {
    private static final float SPEED = 0.7f;
    private static final int STOP_DISTANCE = 1;

    public MaidCombatChaseTask() {
        super(ImmutableMap.of(
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return shouldChase(maid);
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        chase(maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        chase(maid);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return shouldChase(maid);
    }

    private static boolean shouldChase(EntityMaid maid) {
        if (MaidSlashBladeCombat.controls(maid)) return false;
        LivingEntity target = MaidCombatReflex.currentTarget(maid);
        if (target == null || !maid.canBrainMoving()) {
            return false;
        }
        boolean explicit = MaidCombatReflex.isCommandTarget(maid, target);
        if (MaidCombatPolicy.engagement(maid, explicit) != MaidCombatRules.Engagement.MELEE) {
            return false;
        }
        if (maid.isWithinMeleeAttackRange(target)) {
            return false;
        }
        return !maid.hasRestriction() || maid.isWithinRestriction(target.blockPosition());
    }

    private static void chase(EntityMaid maid) {
        LivingEntity target = MaidCombatReflex.currentTarget(maid);
        if (target != null) {
            BehaviorUtils.setWalkAndLookTargetMemories(maid, target, SPEED, STOP_DISTANCE);
        }
    }
}
