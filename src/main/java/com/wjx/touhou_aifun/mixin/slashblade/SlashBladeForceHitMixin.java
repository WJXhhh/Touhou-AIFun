package com.wjx.touhou_aifun.mixin.slashblade;

import com.wjx.touhou_aifun.compat.slashblade.SlashBladeCombat;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mods.flammpfeil.slashblade.entity.EntityAbstractSummonedSword", remap = false)
public abstract class SlashBladeForceHitMixin {
    @Inject(method = "doForceHitEntity", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$protectForcedHit(Entity target, CallbackInfo ci) {
        if (!SlashBladeCombat.allowHit((Entity) (Object) this, target)) ci.cancel();
    }
}
