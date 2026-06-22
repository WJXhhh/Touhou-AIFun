package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.network.AIFunNetwork;

import java.util.UUID;

@Mixin(value = LLMCallback.class, remap = false)
public abstract class LLMCallbackMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void touhouAIFun$registerRequest(CallbackInfo ci) {
        EntityMaid maid = ((LLMCallback) (Object) this).getMaid();
        if (maid != null) {
            ChatFlowManager.registerRequest(maid.getUUID(), this);
        }
    }

    @Inject(method = "onSuccess(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/response/ResponseChat;)V",
            at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$flowControl(ResponseChat responseChat, CallbackInfo ci) {
        LLMCallback self = (LLMCallback) (Object) this;
        EntityMaid maid = self.getMaid();
        if (maid == null) {
            return;
        }
        UUID maidId = maid.getUUID();

        // A newer request arrived while this one was thinking: keep its reply in history for context,
        // but do not show it or speak it.
        if (ChatFlowManager.isSuperseded(maidId, this)) {
            self.getChatManager().addAssistantHistory(responseChat.toString());
            ci.cancel();
            return;
        }

        // This is the latest reply: cut off any TTS still playing from the previous reply, then let
        // the original onSuccess proceed (history, TTS, chat bubble).
        if (maid.level() instanceof ServerLevel serverLevel) {
            ChatFlowManager.beginTtsTakeover(maidId);
            AIFunNetwork.sendInterruptTts(maid);

            // Show the chat text before TTS starts (kept from the original behaviour).
            String chatText = responseChat.getChatText();
            if (!chatText.isBlank() && !responseChat.getTtsText().isBlank()) {
                long waitingBubbleId = self.getWaitingChatBubbleId();
                serverLevel.getServer().submit(() ->
                        maid.getChatBubbleManager().addLLMChatText(chatText, waitingBubbleId));
            }
        }
    }
}
