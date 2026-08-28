package com.wjx.touhou_aifun.client.gui;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsHubScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.settings.AIChatSettingsSTTConfigScreen;
import com.wjx.touhou_aifun.mixin.client.AIChatSettingsHubAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.util.Map;

/** Opens TLM's client-local STT configuration directly from the maid management screen. */
public final class MaidManagementSttSettings {
    private MaidManagementSttSettings() {
    }

    public static Screen create(Screen parent) {
        boolean insufficientPermissions = Minecraft.getInstance().player == null
                || !Minecraft.getInstance().player.hasPermissions(2);
        AIChatSettingsHubScreen seed = AIChatSettingsHubScreen.openDefault(parent, Map.of(), Map.of(),
                insufficientPermissions);
        AIChatSettingsHubScreen.SharedState state =
                ((AIChatSettingsHubAccessor) seed).touhouAIFun$getState();
        return new AIChatSettingsSTTConfigScreen(parent, state, insufficientPermissions);
    }
}
