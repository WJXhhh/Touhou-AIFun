package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.ContextBudgetPlanner;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector;

import java.net.http.HttpRequest;
import java.util.UUID;

@Mixin(value = LLMCallback.class, remap = false)
public abstract class LLMCallbackMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void touhouAIFun$registerRequest(CallbackInfo ci) {
        EntityMaid maid = ((LLMCallback) (Object) this).getMaid();
        if (maid != null && ((Object) this).getClass() == LLMCallback.class) {
            ChatFlowManager.registerRequest(maid.getUUID(), this);
            LLMCallback callback = (LLMCallback) (Object) this;
            if (callback.getMessages().stream().noneMatch(message -> message.role()
                    == com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role.TOOL)) {
                double factor = AIFunMemoryManager.calibratedEstimate(callback.getChatManager(), callback.getMessages())
                        / (double) Math.max(1, ContextTokenEstimator.estimate(callback.getMessages()));
                var planned = ContextBudgetPlanner.trim(callback.getMessages(),
                        com.wjx.touhou_aifun.config.TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get(),
                        ToolContextSelector.schemaBudget(callback.getMaid(), callback), factor);
                callback.getMessages().clear();
                callback.getMessages().addAll(planned);
            }
        }
    }

    @Inject(method = "onFailure", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$suppressSupersededFailure(HttpRequest request, Throwable throwable,
                                                       int errorCode, CallbackInfo ci) {
        if (((Object) this).getClass() != LLMCallback.class) {
            return;
        }
        EntityMaid maid = ((LLMCallback) (Object) this).getMaid();
        // The previous request was cancelled (or finished late) because a newer one took over:
        // swallow its failure so no error is shown to the player.
        if (maid != null && ChatFlowManager.isSuperseded(maid.getUUID(), this)) {
            ChatFlowManager.finishRequest(maid.getUUID(), this);
            ci.cancel();
        }
    }

    @Inject(method = "onFailure", at = @At("RETURN"))
    private void touhouAIFun$releaseFailedRequest(HttpRequest request, Throwable throwable,
                                                   int errorCode, CallbackInfo ci) {
        if (((Object) this).getClass() != LLMCallback.class) return;
        LLMCallback self = (LLMCallback) (Object) this;
        ChatFlowManager.finishRequest(self.getMaid().getUUID(), this);
    }

    @Inject(method = "onSuccess(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/response/ResponseChat;)V",
            at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$flowControl(ResponseChat responseChat, CallbackInfo ci) {
        LLMCallback self = (LLMCallback) (Object) this;
        if (((Object) this).getClass() != LLMCallback.class) {
            return;
        }
        EntityMaid maid = self.getMaid();
        if (maid == null) {
            return;
        }
        UUID maidId = maid.getUUID();

        // TLM's base onSuccess writes history immediately but may also enqueue its bubble/TTS
        // work. Re-enter it on the server thread so a new B turn cannot interleave between the
        // supersede check and those writes. The thread-local marker prevents an infinite re-entry.
        boolean dispatched = ChatFlowManager.takeOriginalSuccessDispatch(this);
        if (!dispatched && maid.level() instanceof ServerLevel serverLevel
                && !serverLevel.getServer().isSameThread()) {
            self.runOnServerThread(() -> {
                if (ChatFlowManager.isSuperseded(maidId, this)) {
                    AIFunMemoryManager.interruptCallback(self);
                    ChatFlowManager.finishRequest(maidId, this);
                } else {
                    ChatFlowManager.dispatchOriginalSuccess(this, () -> self.onSuccess(responseChat));
                }
            });
            ci.cancel();
            return;
        }

        // A newer request arrived while this one was thinking: discard the late reply entirely so
        // it cannot create a user-B -> assistant-A history inversion.
        if (ChatFlowManager.isSuperseded(maidId, this)) {
            AIFunMemoryManager.interruptCallback(self);
            ChatFlowManager.finishRequest(maidId, this);
            ci.cancel();
            return;
        }

        // Arm a same-thread guard for the base method's legacy history write. The return hook
        // records the durable turn only after that write has either happened or been rejected by
        // the guard, so a concurrent B request cannot leave an assistant-A orphan in AIFun memory.
        ChatFlowManager.beginHistoryGuard(this);

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
                        {
                            if (!ChatFlowManager.isSuperseded(maidId, this)) {
                                maid.getChatBubbleManager().addLLMChatText(chatText, waitingBubbleId);
                            }
                        });
            }
        }
    }

    @Inject(method = "onSuccess(Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/response/ResponseChat;)V",
            at = @At("RETURN"))
    private void touhouAIFun$finishAcceptedTurn(ResponseChat responseChat, CallbackInfo ci) {
        if (((Object) this).getClass() != LLMCallback.class) return;
        LLMCallback self = (LLMCallback) (Object) this;
        UUID maidId = self.getMaid().getUUID();
        try {
            if (ChatFlowManager.isSuperseded(maidId, this)) {
                AIFunMemoryManager.interruptCallback(self);
            } else if (!responseChat.getChatText().isBlank() && !responseChat.getTtsText().isBlank()) {
                AIFunMemoryManager.completeCallback(self, responseChat.toString());
            }
        } finally {
            ChatFlowManager.finishRequest(maidId, this);
            ChatFlowManager.clearHistoryGuard(this);
        }
    }

    @Inject(method = "onFunctionCall", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$dropSupersededToolCall(Message choice, LLMClient client, CallbackInfo ci) {
        if (((Object) this).getClass() != LLMCallback.class) return;
        LLMCallback self = (LLMCallback) (Object) this;
        if (ChatFlowManager.isSuperseded(self.getMaid().getUUID(), this)) {
            AIFunMemoryManager.interruptCallback(self);
            ChatFlowManager.finishRequest(self.getMaid().getUUID(), this);
            ci.cancel();
        } else {
            ChatFlowManager.beginHistoryGuard(this);
        }
    }

    @Inject(method = "onFunctionCall", at = @At("RETURN"))
    private void touhouAIFun$releaseToolHistoryGuard(Message choice, LLMClient client, CallbackInfo ci) {
        if (((Object) this).getClass() == LLMCallback.class) {
            ChatFlowManager.clearHistoryGuard(this);
        }
    }

    @Inject(method = "addToolResult", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$dropSupersededToolResult(String result, String toolId,
                                                       CallbackInfoReturnable<LLMCallback> cir) {
        if (((Object) this).getClass() != LLMCallback.class) return;
        LLMCallback self = (LLMCallback) (Object) this;
        if (ChatFlowManager.isSuperseded(self.getMaid().getUUID(), this)) {
            AIFunMemoryManager.interruptCallback(self);
            ChatFlowManager.finishRequest(self.getMaid().getUUID(), this);
            cir.setReturnValue(self);
        }
    }

    @Inject(method = "addToolResult", at = @At("RETURN"))
    private void touhouAIFun$recordCompactToolResult(String result, String toolId,
                                                     CallbackInfoReturnable<LLMCallback> cir) {
        if (((Object) this).getClass() != LLMCallback.class) return;
        AIFunMemoryManager.addToolOutcome((LLMCallback) (Object) this, result);
    }
}
