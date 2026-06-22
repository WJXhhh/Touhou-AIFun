package team.cfpa.touhoustepfun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ChatBubbleManager.class, remap = false)
public abstract class ChatBubbleManagerMixin {
    @Inject(method = "addLLMChatText", at = @At("HEAD"), cancellable = true)
    private void touhouStepFun$avoidDuplicateTtsText(String message, long waitingChatBubbleId, CallbackInfo ci) {
        ChatBubbleManager manager = (ChatBubbleManager) (Object) this;
        if (waitingChatBubbleId >= 0
                && !manager.getChatBubbleDataCollection().containsKey(waitingChatBubbleId)) {
            ci.cancel();
        }
    }
}
