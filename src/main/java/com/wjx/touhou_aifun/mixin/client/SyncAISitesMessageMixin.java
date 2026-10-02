package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.network.message.ai.SyncAISitesMessage;
import com.wjx.touhou_aifun.client.gui.ChatGPTSubscriptionScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the settings parent when a site refresh arrives while the standalone OAuth screen is open. */
@Mixin(value = SyncAISitesMessage.class, remap = false)
public abstract class SyncAISitesMessageMixin {
    @Inject(method = "onHandle", at = @At("HEAD"), cancellable = true)
    private static void touhouAIFun$syncSubscriptionScreen(SyncAISitesMessage message, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof ChatGPTSubscriptionScreen screen) {
            screen.onSitesSynced(message);
            ci.cancel();
        }
    }
}
