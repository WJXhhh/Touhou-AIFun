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
    private static final int TOGGLE_SPACE = 28;

    @ModifyArg(method = "initContent",
            at = @At(value = "INVOKE", target = "Lcom/github/tartaricacid/touhoulittlemaid/util/Rectangle;<init>(DDDD)V"),
            index = 3)
    private double touhouAIFun$reserveToggleSpace(double height) {
        return height - TOGGLE_SPACE;
    }

    @Inject(method = "initContent", at = @At("TAIL"))
    private void touhouAIFun$addSentenceStreamingToggle(CallbackInfo ci) {
        AIChatSettingsTTSSiteScreen screen = (AIChatSettingsTTSSiteScreen) (Object) this;
        AIChatSettingsHubAccessor accessor = (AIChatSettingsHubAccessor) this;
        Rectangle listArea = accessor.touhouAIFun$getListArea();
        boolean enabled = TouhouAIFunConfig.TTS_SENTENCE_STREAMING.get();
        FlatColorButton button = new FlatColorButton(
                accessor.touhouAIFun$invokeGetContentX(), (int) listArea.bottom() + 4,
                accessor.touhouAIFun$invokeGetContentWidth(), 20,
                Component.translatable(enabled
                        ? "gui.touhou_aifun.tts_sentence_stream.on"
                        : "gui.touhou_aifun.tts_sentence_stream.off"), pressed -> {
            boolean next = !TouhouAIFunConfig.TTS_SENTENCE_STREAMING.get();
            TouhouAIFunConfig.setSentenceStreaming(next);
            AIFunNetwork.sendSettingsToServer(next);
            screen.init(screen.getMinecraft(), screen.width, screen.height);
        });
        button.setSelect(enabled);
        button.active = !accessor.touhouAIFun$hasInsufficientPermissions();
        screen.addRenderableWidget(button);
    }
}
