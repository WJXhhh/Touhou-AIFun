package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsHubScreen;
import com.wjx.touhou_aifun.client.gui.VisionSettingsScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds an addon-owned visual tab without modifying the base mod's fixed ServiceType enum. */
@Mixin(value = AIChatSettingsHubScreen.class, remap = false)
public abstract class AIChatSettingsHubVisionMixin {
    @Shadow protected int startX;
    @Shadow protected int startY;

    // init is a vanilla Screen override. It must be remapped even though the target class belongs
    // to TLM, otherwise the client mixin only matches the development (Mojang-named) runtime.
    @Inject(method = "init", at = @At("TAIL"), remap = true)
    private void touhouAIFun$addVisionTab(CallbackInfo ci) {
        AIChatSettingsHubScreen screen = (AIChatSettingsHubScreen) (Object) this;
        int x = startX;
        // TLM's STT site button occupies startY + 115 through +135.
        int y = startY + 145;
        screen.addRenderableWidget(new FlatColorButton(x, y, 95, 20,
                Component.translatable("gui.touhou_aifun.vision"),
                button -> screen.getMinecraft().setScreen(new VisionSettingsScreen(screen))));
    }
}
