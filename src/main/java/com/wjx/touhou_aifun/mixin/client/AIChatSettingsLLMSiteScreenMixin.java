package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsLLMSiteScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;

/**
 * Adds a global "LLM streaming output" toggle on the provider list screen, sitting on the footer
 * row just below the create-site buttons (the "新建 OpenAI" / 兼容openai button row). The flag
 * controls streaming (SSE) output for every vendor's LLM models.
 */
@Mixin(value = AIChatSettingsLLMSiteScreen.class, remap = false)
public abstract class AIChatSettingsLLMSiteScreenMixin {
    @Inject(method = "initContent", at = @At("TAIL"))
    private void touhouAIFun$addStreamingToggle(CallbackInfo ci) {
        AIChatSettingsLLMSiteScreen screen = (AIChatSettingsLLMSiteScreen) (Object) this;
        AIChatSettingsHubAccessor accessor = (AIChatSettingsHubAccessor) this;

        boolean enabled = TouhouAIFunConfig.LLM_STREAMING.get();
        int contentX = accessor.touhouAIFun$invokeGetContentX();
        int contentWidth = accessor.touhouAIFun$invokeGetContentWidth();
        // Leave room on the right for the existing 80px "Back" footer button (+4px gap).
        int width = contentWidth - 84;

        FlatColorButton button = new FlatColorButton(
                contentX, accessor.touhouAIFun$invokeGetFooterY(), width, 20,
                Component.translatable(enabled
                        ? "gui.touhou_aifun.llm_stream.on"
                        : "gui.touhou_aifun.llm_stream.off"), pressed -> {
            boolean next = !TouhouAIFunConfig.LLM_STREAMING.get();
            TouhouAIFunConfig.setLlmStreaming(next);
            AIFunNetwork.sendSettingsToServer(TouhouAIFunConfig.TTS_SENTENCE_STREAMING.get(), next,
                    TouhouAIFunConfig.TTS_EMOTION_CONTROL.get(), TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get());
            screen.init(screen.getMinecraft(), screen.width, screen.height);
        });
        button.setSelect(enabled);
        button.active = !accessor.touhouAIFun$hasInsufficientPermissions();
        screen.addRenderableWidget(button);
    }
}
