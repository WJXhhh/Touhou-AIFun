package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the base TTS callback's chat text and waiting bubble id. Streaming synthesis plays its own
 * audio instead of calling {@code TTSCallback.onSuccess}, but the chat bubble that normally appears
 * there must still be shown; this accessor lets the streamer replicate that one side effect.
 */
@Mixin(value = TTSCallback.class, remap = false)
public interface TTSCallbackAccessor {
    @Accessor("chatText")
    String touhouAIFun$chatText();

    @Accessor("waitingChatBubbleId")
    long touhouAIFun$waitingChatBubbleId();
}
