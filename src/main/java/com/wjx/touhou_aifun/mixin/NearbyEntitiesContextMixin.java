package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.context.tools.NearbyEntityMaidContexts;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanRequest;
import com.wjx.touhou_aifun.vision.scan.ScanDirection;
import com.wjx.touhou_aifun.vision.scan.ScanMode;
import com.wjx.touhou_aifun.vision.scan.ShallowEnvironmentScanner;
import com.wjx.touhou_aifun.vision.scan.VisionScanCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the legacy nearby_entities id while upgrading its data and radius semantics. */
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.ai.agent.context.tools.NearbyEntityMaidContexts$NearbyEntitiesContext", remap = false)
public abstract class NearbyEntitiesContextMixin {
    @Inject(method = "getValue", at = @At("HEAD"), cancellable = true, remap = false)
    private void touhouAIFun$enhancedScan(EntityMaid maid, CallbackInfoReturnable<String> cir) {
        try {
            EnvironmentScanRequest request = new EnvironmentScanRequest(
                    ScanMode.ENTITIES, ScanDirection.ALL, 20, "");
            cir.setReturnValue(VisionScanCache.scan(maid, request).toJson());
        } catch (Throwable ignored) {
            // If another mod supplies an incompatible entity implementation, leave the base context intact.
        }
    }
}
