package com.wjx.touhou_aifun.mixin;

import com.wjx.touhou_aifun.maid.gui.MaidGuiSlots;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = SlotItemHandler.class, remap = false)
public abstract class GuiForgeSlotMixin {
    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true, remap = true)
    private void touhouAIFun$disabled(ItemStack stack, CallbackInfoReturnable<Boolean> ci) {
        if (!MaidGuiSlots.enabled((Slot)(Object)this)) ci.setReturnValue(false);
    }
    @Inject(method = "getMaxStackSize()I", at = @At("HEAD"), cancellable = true, remap = true)
    private void touhouAIFun$capacity(CallbackInfoReturnable<Integer> ci) {
        if (!MaidGuiSlots.enabled((Slot)(Object)this)) ci.setReturnValue(0);
    }
}
