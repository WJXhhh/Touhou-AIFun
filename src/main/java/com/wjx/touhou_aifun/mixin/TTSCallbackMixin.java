package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.TTSAudioToClientMessage;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces TLM's hard-coded owner-only network TTS delivery with AIFun's public-maid audience. */
@Mixin(value = TTSCallback.class, remap = false)
public abstract class TTSCallbackMixin {
    @Shadow
    @Final
    private EntityMaid maid;

    @Shadow
    @Final
    private String chatText;

    @Shadow
    @Final
    private long waitingChatBubbleId;

    @Inject(method = "onSuccess([B)V", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$sendToMaidAudience(byte[] data, CallbackInfo ci) {
        if (this.maid.level() instanceof ServerLevel level) {
            level.getServer().execute(() -> {
                AIFunNetwork.sendMaidTtsAudio(this.maid,
                        new TTSAudioToClientMessage(this.maid.getId(), data));
                // A negative id is an announcement (or an empty streaming chunk): the announcement
                // text and bubble were already added before TTS started. Re-adding them here was the
                // +17..25 s duplicate chat line with no audio. Positive ids keep TLM's reply flow.
                if (this.waitingChatBubbleId >= 0) {
                    this.maid.getChatBubbleManager().addLLMChatText(this.chatText, this.waitingChatBubbleId);
                }
            });
        }
        ci.cancel();
    }
}
