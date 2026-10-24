package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.combat.MaidSlashBladeCombat;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityMaid.class, remap = false)
public abstract class SlashBladeMaidMixin {
    // Both methods override vanilla methods, despite the unremapped mod target.
    @Inject(method = "aiStep", at = @At("TAIL"), remap = true)
    private void touhouAIFun$bladeTick(CallbackInfo ci) {
        MaidSlashBladeCombat.tick((EntityMaid) (Object) this);
    }

    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true, remap = true)
    private void touhouAIFun$bladeOwnsAttack(Entity target, CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        if (!maid.level().isClientSide && MaidSlashBladeCombat.controls(maid)) cir.setReturnValue(false);
    }
}
