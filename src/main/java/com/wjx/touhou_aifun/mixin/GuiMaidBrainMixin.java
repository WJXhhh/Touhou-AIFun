package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Navigation continues in Mob's tick; unrelated work/follow goals yield to the GUI task. */
@Mixin(value = EntityMaid.class, remap = false)
public abstract class GuiMaidBrainMixin {
    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true, remap = true)
    private void touhouAIFun$yieldToGuiTask(CallbackInfo ci) {
        if (MaidGuiSessionManager.session(((EntityMaid) (Object) this).getUUID()) != null) ci.cancel();
    }
}
