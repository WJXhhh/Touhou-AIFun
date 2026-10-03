package com.wjx.touhou_aifun.mixin;

import com.wjx.touhou_aifun.maid.gui.MaidGuiInventoryBinding;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class GuiActorSlotMixin {
    @Shadow @Final public Container container;
    @Shadow @Final private int slot;
    @Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$disabledGuiSlot(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        if (container instanceof Inventory inventory && !MaidGuiInventoryBinding.enabled(inventory, slot)) cir.setReturnValue(false);
    }
    @Inject(method = "getMaxStackSize()I", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$disabledGuiCapacity(CallbackInfoReturnable<Integer> cir) {
        if (container instanceof Inventory inventory && !MaidGuiInventoryBinding.enabled(inventory, slot)) cir.setReturnValue(0);
    }
}
