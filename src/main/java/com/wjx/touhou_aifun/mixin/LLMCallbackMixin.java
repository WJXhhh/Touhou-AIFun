package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LLMCallback.class, remap = false)
public abstract class LLMCallbackMixin {
    @Inject(method = "onSuccess", at = @At("HEAD"))
    private void touhouAIFun$showTextBeforeTts(ResponseChat responseChat, CallbackInfo ci) {
        String chatText = responseChat.getChatText();
        if (chatText.isBlank() || responseChat.getTtsText().isBlank()) {
            return;
        }

        LLMCallback callback = (LLMCallback) (Object) this;
        EntityMaid maid = callback.getMaid();
        if (maid.level() instanceof ServerLevel serverLevel) {
            long waitingBubbleId = callback.getWaitingChatBubbleId();
            serverLevel.getServer().submit(() ->
                    maid.getChatBubbleManager().addLLMChatText(chatText, waitingBubbleId));
        }
    }
}
