package com.wjx.touhou_aifun.mixin;

import com.wjx.touhou_aifun.maid.gui.MaidGuiActor;
import com.wjx.touhou_aifun.maid.gui.MaidGuiInventoryBinding;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(Inventory.class)
public abstract class GuiActorInventoryMixin {
    @Shadow @Final @Mutable public NonNullList<ItemStack> items;
    @Shadow @Final public NonNullList<ItemStack> armor;
    @Shadow @Final @Mutable public NonNullList<ItemStack> offhand;
    @Shadow @Final @Mutable private List<NonNullList<ItemStack>> compartments;
    @Shadow @Final public Player player;
    @Inject(method = "<init>", at = @At("RETURN"))
    private void touhouAIFun$bindGuiInventory(Player player, CallbackInfo ci) {
        var maid = MaidGuiInventoryBinding.constructing();
        if (!(player instanceof MaidGuiActor) || maid == null) return;
        items = MaidGuiInventoryBinding.items(maid);
        offhand = MaidGuiInventoryBinding.offhand(maid);
        compartments = List.of(items, armor, offhand);
    }
    @Inject(method = "placeItemBackInInventory(Lnet/minecraft/world/item/ItemStack;Z)V", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$returnGuiStack(ItemStack stack, boolean sendPacket, CallbackInfo ci) {
        if (player instanceof MaidGuiActor actor) {
            actor.droppedCount += MaidGuiInventoryBinding.giveBack(actor.maid(), stack.copy());
            stack.setCount(0);
            ci.cancel();
        }
    }
    @Inject(method = "getFreeSlot", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$skipUnavailableInventory(CallbackInfoReturnable<Integer> ci) {
        if (!(player instanceof MaidGuiActor)) return;
        Inventory inventory = (Inventory) (Object) this;
        for (int i = 0; i < 36; i++) if (MaidGuiInventoryBinding.enabled(inventory, i) && items.get(i).isEmpty()) { ci.setReturnValue(i); return; }
        ci.setReturnValue(-1);
    }
}
