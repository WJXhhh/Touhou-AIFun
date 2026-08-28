package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import com.wjx.touhou_aifun.maid.PublicMaidData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityMaid.class, remap = false)
public abstract class EntityMaidPublicDataMixin implements PublicMaidData {
    @Unique
    private static final String touhouAIFun$PUBLIC_MAID_TAG = "TouhouAIFunPublicMaid";
    @Unique
    private static final String touhouAIFun$FRIENDLY_FIRE_TAG = "TouhouAIFunFriendlyFire";
    @Unique
    private static final EntityDataAccessor<Boolean> touhouAIFun$PUBLIC_MAID =
            SynchedEntityData.defineId(EntityMaid.class, EntityDataSerializers.BOOLEAN);
    @Unique
    private static final EntityDataAccessor<Boolean> touhouAIFun$FRIENDLY_FIRE =
            SynchedEntityData.defineId(EntityMaid.class, EntityDataSerializers.BOOLEAN);

    @Inject(method = "defineSynchedData", at = @At("TAIL"), remap = true)
    private void touhouAIFun$defineAccessData(CallbackInfo ci) {
        EntityMaid maid = (EntityMaid) (Object) this;
        maid.getEntityData().define(touhouAIFun$PUBLIC_MAID, false);
        // Keep the base mod's current reduced-owner-damage behavior unless the owner opts out.
        maid.getEntityData().define(touhouAIFun$FRIENDLY_FIRE, true);
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"), remap = true)
    private void touhouAIFun$saveAccessData(CompoundTag tag, CallbackInfo ci) {
        tag.putBoolean(touhouAIFun$PUBLIC_MAID_TAG, touhouAIFun$isPublicMaid());
        tag.putBoolean(touhouAIFun$FRIENDLY_FIRE_TAG, touhouAIFun$isFriendlyFireAllowed());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"), remap = true)
    private void touhouAIFun$loadAccessData(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(touhouAIFun$PUBLIC_MAID_TAG, Tag.TAG_BYTE)) {
            touhouAIFun$setPublicMaid(tag.getBoolean(touhouAIFun$PUBLIC_MAID_TAG));
        }
        if (tag.contains(touhouAIFun$FRIENDLY_FIRE_TAG, Tag.TAG_BYTE)) {
            touhouAIFun$setFriendlyFireAllowed(tag.getBoolean(touhouAIFun$FRIENDLY_FIRE_TAG));
        }
    }

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true, remap = true)
    private void touhouAIFun$applyFriendlyFireSetting(DamageSource source, float amount,
                                                       CallbackInfoReturnable<Boolean> cir) {
        EntityMaid maid = (EntityMaid) (Object) this;
        if (!touhouAIFun$isFriendlyFireAllowed()
                && source.getEntity() instanceof Player player
                // Sneaking is an explicit owner override: protection prevents accidents, not disposal.
                && !(PublicMaidAccess.isActualOwner(maid, player) && player.isShiftKeyDown())
                && maid.isAlliedTo(player)) {
            cir.setReturnValue(false);
        }
    }

    @Override
    public boolean touhouAIFun$isPublicMaid() {
        return ((EntityMaid) (Object) this).getEntityData().get(touhouAIFun$PUBLIC_MAID);
    }

    @Override
    public void touhouAIFun$setPublicMaid(boolean publicMaid) {
        ((EntityMaid) (Object) this).getEntityData().set(touhouAIFun$PUBLIC_MAID, publicMaid);
    }

    @Override
    public boolean touhouAIFun$isFriendlyFireAllowed() {
        return ((EntityMaid) (Object) this).getEntityData().get(touhouAIFun$FRIENDLY_FIRE);
    }

    @Override
    public void touhouAIFun$setFriendlyFireAllowed(boolean allowed) {
        ((EntityMaid) (Object) this).getEntityData().set(touhouAIFun$FRIENDLY_FIRE, allowed);
    }
}
