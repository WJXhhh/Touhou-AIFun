package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.github.tartaricacid.touhoulittlemaid.client.input.STTChatKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

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
}
