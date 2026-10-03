package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.gui.*;
import net.minecraft.nbt.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = EntityMaid.class, remap = false)
public abstract class GuiTemporaryItemsMixin {
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"), remap = true)
    private void touhouAIFun$saveGuiItems(CompoundTag tag, CallbackInfo ci) {
        tag.put("TouhouAIFunGuiItems", MaidGuiSessionManager.saveTemporary((EntityMaid) (Object) this));
    }
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"), remap = true)
    private void touhouAIFun$restoreGuiItems(CompoundTag tag, CallbackInfo ci) {
        ListTag items = tag.getList("TouhouAIFunGuiItems", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) MaidGuiInventoryBinding.giveBack((EntityMaid) (Object) this, ItemStack.of(items.getCompound(i)));
        tag.remove("TouhouAIFunGuiItems");
    }
}
