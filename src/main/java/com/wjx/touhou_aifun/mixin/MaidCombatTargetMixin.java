package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.combat.MaidCombatReflex;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes "clear aggro" meaningful inside an attack work mode: the base
 * StartAttacking/IRangedAttackTask target acquisition consults
 * EntityMaid#canAttack, so a suppressed target is no longer selected there.
 * <p>
 * EntityMaid is a mod class, but canAttack overrides the vanilla
 * LivingEntity#canAttack method, so the injector needs remap = true (see
 * AGENTS.md's "mod class + vanilla override" rule).
 */
@Mixin(value = EntityMaid.class, remap = false)
public abstract class MaidCombatTargetMixin {
    @Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z",
            at = @At("HEAD"), cancellable = true, remap = true)
    private void touhouAIFun$suppressedTarget(LivingEntity target,
                                               CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        if (target != null && MaidCombatReflex.isSuppressed(maid, target)) {
            cir.setReturnValue(false);
        }
    }
}
