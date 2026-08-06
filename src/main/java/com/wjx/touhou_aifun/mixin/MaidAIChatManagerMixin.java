package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.summary.HistorySummaryManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.compat.ai.tts.TTSProgressiveSynthesis;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;

import java.util.List;

@Mixin(value = MaidAIChatManager.class, remap = false)
public abstract class MaidAIChatManagerMixin {
    @Redirect(method = "chat",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/summary/HistorySummaryManager;tryCompressBeforeChat(Ljava/lang/Runnable;)Z"))
    private boolean touhouAIFun$disableBlockingBaseSummary(HistorySummaryManager ignored, Runnable afterSummary) {
        // AIFun keeps its own asynchronous, structured memory. The base summary blocks the player
        // request and would also put a lossy duplicate summary back into the visible prompt.
        return false;
    }

    @Inject(method = "normalChat", at = @At("HEAD"))
    private void touhouAIFun$prepareTurn(String message, List<LLMMessage> messages,
                                         LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager manager = (MaidAIChatManager) (Object) this;
        AIFunMemoryManager.beginTurn(manager, message);
        List<LLMMessage> original = List.copyOf(messages);
        messages.clear();
        messages.addAll(AIFunMemoryManager.rebuildVisibleMessages(manager, original, message));
    }

    @Redirect(method = "tts",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/tts/TTSClient;play(Ljava/lang/String;Lcom/github/tartaricacid/touhoulittlemaid/ai/service/tts/TTSConfig;Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/entity/TTSCallback;)V"))
    private void touhouAIFun$playBySentence(TTSClient client, String message,
                                              TTSConfig config, TTSCallback callback) {
        TTSProgressiveSynthesis.play(client, message, config, callback);
    }
}
