package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.compat.slashblade.SlashBladeCompat;
import com.wjx.touhou_aifun.maid.combat.MaidSlashBladeCombat;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SlashBladeCompat.class, remap = false)
public abstract class SlashBladeSwingMixin {
    @Inject(method = "swingSlashBlade", at = @At("HEAD"), cancellable = true)
    private static void touhouAIFun$nativeComboOnly(EntityMaid maid, ItemStack stack, CallbackInfo ci) {
        if (MaidSlashBladeCombat.controls(maid)) ci.cancel();
    }
}
