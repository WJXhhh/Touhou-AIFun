package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.response.ResponseChat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.chat.ChatFavorability;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.ContextBudgetPlanner;
import com.wjx.touhou_aifun.chat.context.ContextTokenEstimator;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.compat.ai.openai.ToolContextSelector;

import java.net.http.HttpRequest;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mixin(value = LLMCallback.class, remap = false)
public abstract class LLMCallbackMixin {
    @Unique
    private boolean touhouAIFun$requestRegistered;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void touhouAIFun$registerRequest(CallbackInfo ci) {
        // LLMCallback's two-argument constructor delegates to the three-argument one. A constructor
        // injection therefore observes both RETURN points for the same object; only register and
        // trim once, after the first fully-initialised return.
        if (touhouAIFun$requestRegistered) {
            return;
        }
        touhouAIFun$requestRegistered = true;

        EntityMaid maid = ((LLMCallback) (Object) this).getMaid();
        if (maid != null && ((Object) this).getClass() == LLMCallback.class) {
            ChatFlowManager.registerRequest(maid.getUUID(), this);
            LLMCallback callback = (LLMCallback) (Object) this;
            if (!ToolContextSelector.usesAIFunClient(callback.getMaid())) {
                int schemaBudget = ToolContextSelector.requestSchemaBudget(callback.getMaid(), callback);
                ChatFlowManager.rememberSchemaBudget(callback, schemaBudget);
                double factor = AIFunMemoryManager.calibratedEstimate(callback.getChatManager(), callback.getMessages())
                        / (double) Math.max(1, ContextTokenEstimator.estimate(callback.getMessages()));
                var planned = ContextBudgetPlanner.trim(callback.getMessages(),
                        com.wjx.touhou_aifun.config.TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get(),
                        schemaBudget, factor);
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
                // Durable cross-provider memory keeps only the player-visible answer. The TTS
                // translation and provider reasoning envelope remain in legacy/current tool-chain
                // messages but must not double the next ordinary request.
                AIFunMemoryManager.completeCallback(self, responseChat.getChatText());
                ChatFavorability.awardCompletedChat(self.getMaid());
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
        EntityMaid maid = self.getMaid();
        UUID maidId = maid.getUUID();

        // Tool-call history and batch scheduling must be ordered with normalChat on the server
        // thread, just like accepted text replies. Otherwise user B can slip between the A check
        // and the base method's assistant tool-call history write.
        boolean dispatched = ChatFlowManager.takeOriginalFunctionDispatch(this);
        if (!dispatched && maid.level() instanceof ServerLevel serverLevel
                && !serverLevel.getServer().isSameThread()) {
            self.runOnServerThread(() -> {
                if (ChatFlowManager.isSuperseded(maidId, this)) {
                    AIFunMemoryManager.interruptCallback(self);
                    ChatFlowManager.finishRequest(maidId, this);
                } else {
                    ChatFlowManager.dispatchOriginalFunction(this, () -> self.onFunctionCall(choice, client));
                }
            });
            ci.cancel();
            return;
        }
        if (ChatFlowManager.isSuperseded(maidId, this)) {
            AIFunMemoryManager.interruptCallback(self);
            ChatFlowManager.finishRequest(maidId, this);
            ci.cancel();
        } else {
            ChatFlowManager.beginHistoryGuard(this);
        }
    }

    /** Stop not-yet-started members of an old multi-tool batch after a newer user turn takes over. */
    @Inject(method = "onSingleCall", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$stopSupersededToolBatch(ToolCall toolCall, LLMCallback callback,
                                                      LLMClient client,
                                                      CallbackInfoReturnable<CompletableFuture<LLMCallback>> cir) {
        if (((Object) this).getClass() != LLMCallback.class) return;
        LLMCallback self = (LLMCallback) (Object) this;
        if (ChatFlowManager.isSuperseded(self.getMaid().getUUID(), this)) {
            AIFunMemoryManager.interruptCallback(self);
            cir.setReturnValue(CompletableFuture.completedFuture(callback));
        }
    }

    @Inject(method = "onFunctionCall", at = @At("RETURN"))
    private void touhouAIFun$releaseToolHistoryGuard(Message choice, LLMClient client, CallbackInfo ci) {
        if (((Object) this).getClass() == LLMCallback.class) {
            ChatFlowManager.clearHistoryGuard(this);
        }
    }

    /** The base single-sub-agent branch writes a placeholder directly, bypassing addToolResult. */
    // javac lowers the completion handler (including this direct history write) into the
    // synthetic BiFunction body rather than executeSingleToolCall itself. Target the actual
    // bytecode owner; pointing at the outer method compiles but fails Mixin's runtime require=1
    // check during ToolRegister initialization.
    @Redirect(method = "lambda$executeSingleToolCall$5",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/"
                            + "MaidAIChatManager;addToolHistory(Ljava/lang/String;Ljava/lang/String;)V"))
    private void touhouAIFun$guardDirectSideToolHistory(MaidAIChatManager manager,
                                                        String text, String toolCallId) {
        LLMCallback self = (LLMCallback) (Object) this;
        if (!ChatFlowManager.isSuperseded(self.getMaid().getUUID(), this)) {
            manager.addToolHistory(text, toolCallId);
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
        LLMCallback callback = (LLMCallback) (Object) this;
        AIFunMemoryManager.addToolOutcome(callback, result);
        if (!ToolContextSelector.usesAIFunClient(callback.getMaid())
                && !ChatFlowManager.isSuperseded(callback.getMaid().getUUID(), callback)) {
            int schemaBudget = ChatFlowManager.rememberedSchemaBudget(callback);
            if (schemaBudget <= 0) {
                schemaBudget = ToolContextSelector.requestSchemaBudget(callback.getMaid(), callback);
                ChatFlowManager.rememberSchemaBudget(callback, schemaBudget);
            }
            double factor = AIFunMemoryManager.calibratedEstimate(
                    callback.getChatManager(), callback.getMessages())
                    / (double) Math.max(1, ContextTokenEstimator.estimate(callback.getMessages()));
            var planned = ContextBudgetPlanner.trim(callback.getMessages(),
                    com.wjx.touhou_aifun.config.TouhouAIFunConfig.CONTEXT_INPUT_BUDGET_TOKENS.get(),
                    schemaBudget, factor);
            callback.getMessages().clear();
            callback.getMessages().addAll(planned);
        }
    }
}
