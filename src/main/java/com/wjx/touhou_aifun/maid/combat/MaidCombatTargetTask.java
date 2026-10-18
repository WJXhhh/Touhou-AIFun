package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;

/**
 * Keeps the brain target in sync with the reflex layers. This is a CORE behavior,
 * so it works in every schedule activity, not only Activity.WORK.
 */
public final class MaidCombatTargetTask extends Behavior<EntityMaid> {
    public MaidCombatTargetTask() {
        super(ImmutableMap.of(
                MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED));
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        return MaidCombatReflex.hasLayers(maid);
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        refresh(maid);
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        refresh(maid);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return MaidCombatReflex.hasLayers(maid);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        MaidCombatReflex.releaseIfOwned(maid);
    }

    private static void refresh(EntityMaid maid) {
        LivingEntity target = MaidCombatReflex.currentTarget(maid);
        if (target == null) {
            MaidCombatReflex.releaseIfOwned(maid);
            return;
        }
        boolean explicit = MaidCombatReflex.isCommandTarget(maid, target);
        if (MaidCombatPolicy.engagement(maid, explicit) == MaidCombatRules.Engagement.NONE) {
            return;
        }
        Brain<EntityMaid> brain = maid.getBrain();
        brain.setMemory(MemoryModuleType.ATTACK_TARGET, target);
        brain.setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
    }
}
