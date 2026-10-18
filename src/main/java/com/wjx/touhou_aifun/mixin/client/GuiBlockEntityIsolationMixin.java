package com.wjx.touhou_aifun.mixin.client;

import com.wjx.touhou_aifun.client.gui.automation.GuiClientRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom GUI handlers read/write a detached tile, preserving the owner's client world. */
@Mixin(Level.class)
public abstract class GuiBlockEntityIsolationMixin {
    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$guiTile(BlockPos position, CallbackInfoReturnable<BlockEntity> ci) {
        BlockEntity tile = GuiClientRuntime.detachedBlockEntity(position);
        if (tile != null) ci.setReturnValue(tile);
    }
}
