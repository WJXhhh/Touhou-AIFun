package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsTTSSiteScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.util.Rectangle;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;

@Mixin(value = AIChatSettingsTTSSiteScreen.class, remap = false)
public abstract class AIChatSettingsTTSSiteScreenMixin {
    private static final int ROW_HEIGHT = 24;
    // Reserve space for two stacked toggles (sentence streaming + emotion control).
    private static final int TOGGLE_SPACE = 4 + ROW_HEIGHT * 2;

    @ModifyArg(method = "initContent",
            at = @At(value = "INVOKE", target = "Lcom/github/tartaricacid/touhoulittlemaid/util/Rectangle;<init>(DDDD)V"),
            index = 3)
    private double touhouAIFun$reserveToggleSpace(double height) {
        return height - TOGGLE_SPACE;
    }

    @Inject(method = "initContent", at = @At("TAIL"))
    private void touhouAIFun$addToggles(CallbackInfo ci) {
        AIChatSettingsTTSSiteScreen screen = (AIChatSettingsTTSSiteScreen) (Object) this;
        AIChatSettingsHubAccessor accessor = (AIChatSettingsHubAccessor) this;
        Rectangle listArea = accessor.touhouAIFun$getListArea();
        int x = accessor.touhouAIFun$invokeGetContentX();
        int width = accessor.touhouAIFun$invokeGetContentWidth();
        int top = (int) listArea.bottom() + 4;
        boolean enabled = !accessor.touhouAIFun$hasInsufficientPermissions();

        boolean sentenceStreaming = TouhouAIFunConfig.TTS_SENTENCE_STREAMING.get();
        FlatColorButton streamingButton = new FlatColorButton(x, top, width, 20,
                Component.translatable(sentenceStreaming
                        ? "gui.touhou_aifun.tts_sentence_stream.on"
                        : "gui.touhou_aifun.tts_sentence_stream.off"), pressed -> {
            boolean next = !TouhouAIFunConfig.TTS_SENTENCE_STREAMING.get();
            TouhouAIFunConfig.setSentenceStreaming(next);
            sendSettings();
            screen.init(screen.getMinecraft(), screen.width, screen.height);
        });
        streamingButton.setSelect(sentenceStreaming);
        streamingButton.active = enabled;
        screen.addRenderableWidget(streamingButton);

        // Emotion control cycles three modes: off → on (brackets hidden) → on (brackets in text).
        boolean emotionControl = TouhouAIFunConfig.TTS_EMOTION_CONTROL.get();
        boolean emotionInText = TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get();
        String emotionKey;
        if (!emotionControl) {
            emotionKey = "gui.touhou_aifun.tts_emotion.off";
        } else if (!emotionInText) {
            emotionKey = "gui.touhou_aifun.tts_emotion.hidden";
        } else {
            emotionKey = "gui.touhou_aifun.tts_emotion.visible";
        }
        FlatColorButton emotionButton = new FlatColorButton(x, top + ROW_HEIGHT, width, 20,
                Component.translatable(emotionKey), pressed -> {
            cycleEmotionMode();
            sendSettings();
            screen.init(screen.getMinecraft(), screen.width, screen.height);
        });
        emotionButton.setSelect(emotionControl);
        emotionButton.active = enabled;
        screen.addRenderableWidget(emotionButton);
    }

    /** off → on/hidden → on/visible → off */
    private static void cycleEmotionMode() {
        boolean control = TouhouAIFunConfig.TTS_EMOTION_CONTROL.get();
        boolean inText = TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get();
        if (!control) {
            TouhouAIFunConfig.setEmotionControl(true);
            TouhouAIFunConfig.setEmotionInText(false);
        } else if (!inText) {
            TouhouAIFunConfig.setEmotionInText(true);
        } else {
            TouhouAIFunConfig.setEmotionControl(false);
            TouhouAIFunConfig.setEmotionInText(false);
        }
    }

    private static void sendSettings() {
        AIFunNetwork.sendSettingsToServer(
                TouhouAIFunConfig.TTS_SENTENCE_STREAMING.get(),
                TouhouAIFunConfig.LLM_STREAMING.get(),
                TouhouAIFunConfig.TTS_EMOTION_CONTROL.get(),
                TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get());
    }
}
