package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.github.tartaricacid.touhoulittlemaid.client.input.STTChatKey;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.client.sound.SpeechMute;
import com.wjx.touhou_aifun.client.voice.FreeChatController;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;

@Mixin(value = STTChatKey.class, remap = false)
public abstract class STTChatKeyMixin {
    @ModifyArg(method = {"sttStart", "sttStop"},
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/site/AvailableSites;getSTTSite(Ljava/lang/String;)Lcom/github/tartaricacid/touhoulittlemaid/ai/service/stt/STTSite;"),
            index = 0)
    private static String touhouAIFun$useSelectedEnabledSite(String legacySiteId) {
        String configured = TouhouAIFunConfig.STT_SELECTED_SITE.get();
        STTSite selected = AvailableSites.STT_SITES.get(configured);
        if (selected != null && selected.enabled()) {
            return configured;
        }
        return AvailableSites.STT_SITES.values().stream()
                .filter(STTSite::enabled)
                .findFirst()
                .map(STTSite::id)
                .orElse(legacySiteId);
    }

    /**
     * The player is about to start recording for this maid. Cut her speech immediately instead of
     * waiting for the transcribed text to open the next chat turn: otherwise she keeps talking over
     * the microphone and the voice input can pick up her TTS. The client drops local playback at
     * once; the server request stops the sentences still being synthesized/sent for the old reply.
     */
    @Inject(method = "tryToStart", at = @At("HEAD"))
    private static void touhouAIFun$stopSpeechBeforeRecording(EntityMaid maid, LocalPlayer player,
                                                               STTSite sttSite, CallbackInfo ci) {
        FreeChatController.suspendForPushToTalk();
        SpeechMute.beginListening();
        AIFunNetwork.sendMaidSpeechStop(maid.getId());
    }

    /** Recording stopped (STT key released): incoming maid audio and ambient capture may resume. */
    @Inject(method = "sttStop", at = @At("HEAD"))
    private static void touhouAIFun$endSpeechMute(EntityMaid maid, CallbackInfo ci) {
        SpeechMute.endListening();
        FreeChatController.resumeAfterPushToTalk();
    }
}
