package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSystemServices;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.TTSSystemAudioToClientMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.compat.ai.tts.TTSProgressiveSynthesis;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.ChatSpeakerContext;
import com.wjx.touhou_aifun.network.AIFunNetwork;

import java.util.List;

@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class MaidAIChatManagerMixin {
    @Unique
    private ChatSpeakerContext.Snapshot touhouAIFun$currentSpeaker;

    @Inject(method = "chat", at = @At("HEAD"))
    private void touhouAIFun$rememberSpeaker(String message, ChatClientInfo clientInfo,
                                              ServerPlayer sender, CallbackInfo ci) {
        MaidAIChatManager manager = (MaidAIChatManager) (Object) this;
        ChatSpeakerContext.remember(manager.getMaid(), message, sender);
    }

    @Inject(method = "normalChat", at = @At("HEAD"))
    private void touhouAIFun$prepareTurn(String message, List<LLMMessage> messages,
                                         LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager manager = (MaidAIChatManager) (Object) this;
        this.touhouAIFun$currentSpeaker = ChatSpeakerContext.take(manager.getMaid(), message);
        com.wjx.touhou_aifun.chat.agent.AgentRuntime.speaker(manager.getMaid(), this.touhouAIFun$currentSpeaker, message);
        AIFunMemoryManager.beginTurn(manager, message);
        List<LLMMessage> original = List.copyOf(messages);
        messages.clear();
        messages.addAll(AIFunMemoryManager.rebuildVisibleMessages(manager, original, message));
    }

    @ModifyArg(method = "normalChat",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMMessage;"
                            + "userChat(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;"
                            + "Ljava/lang/String;)Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMMessage;"),
            index = 1)
    private String touhouAIFun$attachSpeakerIdentity(String messageWithContext) {
        return ChatSpeakerContext.attach(messageWithContext, this.touhouAIFun$currentSpeaker);
    }

    @Inject(method = "normalChat", at = @At("RETURN"))
    private void touhouAIFun$clearSpeaker(String message, List<LLMMessage> messages,
                                          LLMClient chatClient, CallbackInfo ci) {
        this.touhouAIFun$currentSpeaker = null;
    }

    @Redirect(method = "tts",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/tts/TTSClient;play(Ljava/lang/String;Lcom/github/tartaricacid/touhoulittlemaid/ai/service/tts/TTSConfig;Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/TTSCallback;)V"))
    private void touhouAIFun$playBySentence(TTSClient client, String message,
                                              TTSConfig config, TTSCallback callback) {
        TTSProgressiveSynthesis.play(client, message, config, callback);
    }

    /** System/local TTS has its own packet type, but follows the same public-maid audience rule. */
    @Inject(method = "onPlaySoundLocal", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$playSystemTtsForAudience(String siteName, String chatText, String ttsText,
                                                       TTSConfig config, TTSSystemServices services,
                                                       long waitingChatBubbleId, CallbackInfo ci) {
        EntityMaid maid = ((MaidAIChatManager) (Object) this).getMaid();
        if (maid.level() instanceof ServerLevel level) {
            level.getServer().execute(() -> {
                AIFunNetwork.sendMaidTtsAudio(maid,
                        new TTSSystemAudioToClientMessage(siteName, ttsText, config, services));
                maid.getChatBubbleManager().addLLMChatText(chatText, waitingChatBubbleId);
            });
        }
        ci.cancel();
    }
}
