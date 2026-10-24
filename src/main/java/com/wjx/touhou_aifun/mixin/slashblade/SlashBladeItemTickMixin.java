package com.wjx.touhou_aifun.mixin.slashblade;

import com.wjx.touhou_aifun.compat.slashblade.SlashBladeCombat;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ItemSlashBlade.class, remap = false)
public abstract class SlashBladeItemTickMixin {
    @Inject(method = "inventoryTick", at = @At("HEAD"), cancellable = true, remap = true)
    private void touhouAIFun$singleMaidTick(ItemStack stack, Level level, Entity holder, int slot,
                                          boolean selected, CallbackInfo ci) {
        if (!SlashBladeCombat.allowInventoryTick(holder, stack, selected)) ci.cancel();
    }
}
