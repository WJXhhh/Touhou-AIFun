package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.wjx.touhou_aifun.chat.ChatBubbleDisplayTime;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChatBubbleManager.class, remap = false)
public abstract class ChatBubbleManagerMixin {
    @Inject(method = "addLLMChatText", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$avoidDuplicateTtsText(String message, long waitingChatBubbleId, CallbackInfo ci) {
        ChatBubbleManager manager = (ChatBubbleManager) (Object) this;
        if (waitingChatBubbleId >= 0
                && !manager.getChatBubbleDataCollection().containsKey(waitingChatBubbleId)) {
            ci.cancel();
        }
    }

    /**
     * The reply bubble is built with a fixed 15 s lifetime, so a long reply scrolls away before it can
     * be read. Replace that fixed-duration bubble with one whose lifetime scales with the text length
     * (see {@link ChatBubbleDisplayTime}). {@code TextChatBubbleData} is a mod class and {@code Component}
     * a stable Minecraft class name, so this stays {@code remap = false} like the rest of this mixin.
     */
    @Redirect(method = "addLLMChatText",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/entity/chatbubble/implement/TextChatBubbleData;"
                            + "type2(Lnet/minecraft/network/chat/Component;)"
                            + "Lcom/github/tartaricacid/touhoulittlemaid/entity/chatbubble/implement/TextChatBubbleData;"))
    private TextChatBubbleData touhouAIFun$scaleExistTickByLength(Component text) {
        int existTick = ChatBubbleDisplayTime.existTickFor(text.getString());
        return TextChatBubbleData.create(existTick, text, IChatBubbleData.TYPE_2, IChatBubbleData.DEFAULT_PRIORITY);
    }
}
