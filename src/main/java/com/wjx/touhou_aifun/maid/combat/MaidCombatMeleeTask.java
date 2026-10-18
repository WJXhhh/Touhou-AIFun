package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

/**
 * Immediate melee reaction. Unlike TLM's MaidMeleeAttack this does not require
 * the target to be present in the 20-tick NEAREST_VISIBLE_LIVING_ENTITIES memory;
 * line of sight and melee reach are checked directly.
 */
public final class MaidCombatMeleeTask extends Behavior<EntityMaid> {
    private static final int BASE_COOLDOWN_TICKS = 20;

    public MaidCombatMeleeTask() {
        super(ImmutableMap.of(
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.ATTACK_COOLING_DOWN, MemoryStatus.REGISTERED));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return canFight(maid);
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        tryAttack(maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        tryAttack(maid);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return canFight(maid);
    }

    private static boolean canFight(EntityMaid maid) {
        LivingEntity target = MaidCombatReflex.currentTarget(maid);
        if (target == null) {
            return false;
        }
        boolean explicit = MaidCombatReflex.isCommandTarget(maid, target);
        return MaidCombatPolicy.engagement(maid, explicit) == MaidCombatRules.Engagement.MELEE;
    }

    private static void tryAttack(EntityMaid maid) {
        LivingEntity target = MaidCombatReflex.currentTarget(maid);
        if (target == null) {
            return;
        }
        boolean explicit = MaidCombatReflex.isCommandTarget(maid, target);
        if (MaidCombatPolicy.engagement(maid, explicit) != MaidCombatRules.Engagement.MELEE) {
            return;
        }
        Brain<EntityMaid> brain = maid.getBrain();
        if (brain.hasMemoryValue(MemoryModuleType.ATTACK_COOLING_DOWN)) {
            return;
        }
        if (MaidCombatReflex.isSuppressed(maid, target)) {
            return;
        }
        if (!maid.isWithinMeleeAttackRange(target) || !maid.hasLineOfSight(target)) {
            return;
        }
        brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
        maid.swing(InteractionHand.MAIN_HAND);
        maid.doHurtTarget(target);
        double attackSpeed = maid.getAttributeValue(Attributes.ATTACK_SPEED);
        long cooldown = attackSpeed > 0.0
                ? (long) (BASE_COOLDOWN_TICKS / attackSpeed) : BASE_COOLDOWN_TICKS;
        brain.setMemoryWithExpiry(MemoryModuleType.ATTACK_COOLING_DOWN, true,
                Math.max(1L, cooldown));
    }
}
