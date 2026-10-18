package com.wjx.touhou_aifun.maid.combat;

import com.github.tartaricacid.touhoulittlemaid.api.entity.ai.IExtraMaidBrain;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;

import java.util.ArrayList;
import java.util.List;

/**
 * Registers the reflex behaviors through TLM's public extra-brain extension.
 * No mixin is needed for the brain itself, so task/schedule refreshes keep working.
 */
public final class MaidCombatBrain implements IExtraMaidBrain {
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> getCoreBehaviors() {
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> behaviors = new ArrayList<>();
        behaviors.add(behavior(1, new MaidCombatTargetTask()));
        behaviors.add(behavior(1, new MaidCombatMeleeTask()));
        // Late priority: keep the reflex walk target after follow/work/random-walk behaviors.
        behaviors.add(behavior(99, new MaidCombatChaseTask()));
        return behaviors;
    }

    private static Pair<Integer, BehaviorControl<? super EntityMaid>> behavior(
            int priority, BehaviorControl<? super EntityMaid> control) {
        return Pair.of(priority, control);
    }
}
