package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TamableAnimal.class)
public abstract class TamableAnimalPublicAccessMixin {
    @Inject(method = "setOwnerUUID", at = @At("HEAD"))
    private void touhouAIFun$clearPreviousOwnerImages(java.util.UUID owner, CallbackInfo ci) {
        if ((Object) this instanceof EntityMaid maid && !maid.level().isClientSide()
                && !java.util.Objects.equals(maid.getOwnerUUID(), owner)) {
            com.wjx.touhou_aifun.chat.ChatFlowManager.clearMaid(maid);
        }
    }

    /**
     * Keep the real owner UUID untouched while allowing every real player through existing
     * owner-gated interaction paths (GUI, T chat, X voice, packets, leashing, and held items).
     */
    @Inject(method = "isOwnedBy", at = @At("RETURN"), cancellable = true)
    private void touhouAIFun$grantPublicAccess(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValue()
                && (Object) this instanceof EntityMaid maid
                && entity instanceof Player player
                && PublicMaidAccess.isPublicPlayer(maid, player)) {
            cir.setReturnValue(true);
        }
    }

}
