package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents TLM's blocking summary pass because AIFun owns the visible context and durable memory. */
@Mixin(value = HistorySummaryManager.class, remap = false)
public abstract class HistorySummaryManagerMixin {
    @Inject(method = "tryCompressBeforeChat", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$disableBaseSummary(Runnable afterSummary,
                                                CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(false);
    }
}
