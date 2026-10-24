package com.wjx.touhou_aifun.mixin.slashblade;

import com.wjx.touhou_aifun.compat.slashblade.SlashBladeCombat;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mods.flammpfeil.slashblade.ability.ArrowReflector", remap = false)
public abstract class SlashBladeReflectMixin {
    @Inject(method = "doReflect", at = @At("HEAD"), cancellable = true)
    private static void touhouAIFun$friendlyProjectile(Entity projectile, Entity attacker, CallbackInfo ci) {
        if (projectile != null && !projectile.level().isClientSide && !SlashBladeCombat.mayReflect(projectile, attacker)) ci.cancel();
    }
    @Inject(method = "doReflect", at = @At("RETURN"))
    private static void touhouAIFun$trackReflection(Entity projectile, Entity attacker, CallbackInfo ci) {
        SlashBladeCombat.reflected(projectile, attacker);
    }
}
