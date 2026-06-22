package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the streaming display point the callback's waiting-bubble id at the live (reasoning/answer)
 * bubble it manages, so the final {@code addLLMChatText} still cleanly replaces it instead of
 * leaving a dangling bubble.
 */
@Mixin(value = LLMCallback.class, remap = false)
public interface LLMCallbackAccessor {
    @Accessor("waitingChatBubbleId")
    void touhouAIFun$setWaitingChatBubbleId(long id);
}
