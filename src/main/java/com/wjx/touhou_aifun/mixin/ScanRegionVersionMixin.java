package com.wjx.touhou_aifun.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.vision.scan.ScanRegionVersions;

/** Vanilla target uses the generated refmap on both development and distribution Forge. */
@Mixin(ServerLevel.class)
public abstract class ScanRegionVersionMixin {
    @Inject(method="sendBlockUpdated",at=@At("RETURN"))
    private void touhouAIFun$scanRegionChanged(BlockPos pos,BlockState oldState,BlockState newState,int flags,CallbackInfo ci) {
        ScanRegionVersions.changed((ServerLevel)(Object)this,pos);
    }
}
