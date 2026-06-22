package com.wjx.touhou_aifun.mixin.client;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.editor.LLMSiteEditorScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wjx.touhou_aifun.compat.ai.mimo.MimoLLMSite;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = LLMSiteEditorScreen.class, remap = false)
public abstract class LLMSiteEditorScreenMixin {
    @Shadow
    private LLMSite sourceSite;

    @Shadow
    private boolean supportsReasoning;

    @Shadow
    private boolean createMode;

    @Inject(method = "buildSite", at = @At("RETURN"), cancellable = true)
    private void touhouAIFun$preserveSiteApiType(CallbackInfoReturnable<LLMSite> cir) {
        if (createMode) {
            return;
        }

        LLMSite result = cir.getReturnValue();
        if (result == null) {
            return;
        }
        if (!(result instanceof LLMOpenAISite openAISite)) {
            return;
        }

        // Determine the correct API type from the original source site
        String apiType = sourceSite.getApiType();

        // If the site was already corrupted (previously saved as "openai"),
        // fall back to checking the source site ID
        if ("openai".equals(apiType) && !"mimo".equals(sourceSite.id())) {
            return;
        }

        // Rebuild as MimoLLMSite to preserve the correct api_type,
        // so that MimoEndpointResolver can handle tp- key redirect
        List<LLMOpenAISite.ModelEntry> entries = new ArrayList<>(openAISite.modelEntries().values());
        cir.setReturnValue(new MimoLLMSite(
                openAISite.id(), openAISite.icon(), openAISite.url(),
                openAISite.enabled(), openAISite.secretKey(),
                supportsReasoning, openAISite.headers(), entries
        ));
    }
}
