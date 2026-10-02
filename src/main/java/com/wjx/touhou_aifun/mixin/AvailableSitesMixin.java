package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.wjx.touhou_aifun.compat.ai.opencodego.OpenCodeGoLLMSite;
import com.wjx.touhou_aifun.compat.ai.anthropic.AnthropicShared;
import com.wjx.touhou_aifun.compat.ai.deepseek.DeepSeekModels;
import com.wjx.touhou_aifun.compat.ai.opencodego.OpenCodeGoShared;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunLLMSite;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunPlanLLMSite;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunShared;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mixin(value = AvailableSites.class, remap = false)
public abstract class AvailableSitesMixin {
    @Inject(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/manager/site/AvailableSites;saveSites()V"
            )
    )
    private static void touhouAIFun$repairLlmSites(CallbackInfo ci) {
        repairStepFunSite("stepfun", false);
        repairStepFunSite("stepfun_plan", true);
        repairOpenCodeGoSite();
        repairDeepSeekAnthropicSite();
        reorderStepFunPair(AvailableSites.LLM_SITES);
    }

    private static void repairDeepSeekAnthropicSite() {
        LLMSite site = AvailableSites.LLM_SITES.get(AnthropicShared.DEFAULT_SITE_ID);
        if (!(site instanceof LLMOpenAISite openAISite)) {
            return;
        }
        // The Anthropic site can also point at another vendor or a gateway.
        try {
            if ("api.deepseek.com".equalsIgnoreCase(java.net.URI.create(openAISite.url()).getHost())) {
                DeepSeekModels.updateSite(openAISite);
            }
        } catch (IllegalArgumentException ignored) {
            // Leave malformed user URLs for the normal site validation path.
        }
    }

    /**
     * Existing site JSON keeps its saved model list when the mod adds a new default model.
     * Preserve all user settings and append required built-in models during site loading.
     */
    private static void repairOpenCodeGoSite() {
        LLMSite site = AvailableSites.LLM_SITES.get(OpenCodeGoShared.API_TYPE);
        if (!(site instanceof LLMOpenAISite openAISite)) {
            return;
        }

        List<LLMOpenAISite.ModelEntry> entries = new ArrayList<>(openAISite.modelEntries().values());
        boolean hasMuse = entries.stream().anyMatch(entry ->
                OpenCodeGoShared.MUSE_SPARK_1_2_CONTRIBUTOR.equalsIgnoreCase(entry.name()));
        boolean hasCorrectType = site instanceof OpenCodeGoLLMSite;
        if (hasMuse && hasCorrectType) {
            return;
        }
        if (!hasMuse) {
            entries.add(new LLMOpenAISite.ModelEntry(OpenCodeGoShared.MUSE_SPARK_1_2_CONTRIBUTOR));
        }

        AvailableSites.LLM_SITES.put(OpenCodeGoShared.API_TYPE, new OpenCodeGoLLMSite(
                OpenCodeGoShared.API_TYPE,
                OpenCodeGoShared.ICON,
                openAISite.url(),
                openAISite.enabled(),
                openAISite.secretKey(),
                openAISite.hasThinkingField(),
                openAISite.headers(),
                entries
        ));
    }

    private static void repairStepFunSite(String id, boolean plan) {
        LLMSite site = AvailableSites.LLM_SITES.get(id);
        if (!(site instanceof LLMOpenAISite openAISite)) {
            return;
        }
        if ((!plan && site instanceof StepFunLLMSite && !(site instanceof StepFunPlanLLMSite))
                || (plan && site instanceof StepFunPlanLLMSite)) {
            return;
        }

        List<LLMOpenAISite.ModelEntry> entries = new ArrayList<>(openAISite.modelEntries().values());
        LLMSite repaired = plan
                ? new StepFunPlanLLMSite(
                        id, StepFunShared.ICON, openAISite.url(), openAISite.enabled(),
                        openAISite.secretKey(), openAISite.hasThinkingField(), openAISite.headers(), entries
                )
                : new StepFunLLMSite(
                        id, StepFunShared.ICON, openAISite.url(), openAISite.enabled(),
                        openAISite.secretKey(), openAISite.hasThinkingField(), openAISite.headers(), entries
                );
        AvailableSites.LLM_SITES.put(id, repaired);
    }

    private static void reorderStepFunPair(Map<String, LLMSite> sites) {
        if (!sites.containsKey("stepfun") || !sites.containsKey("stepfun_plan")) {
            return;
        }

        LinkedHashMap<String, LLMSite> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, LLMSite> entry : sites.entrySet()) {
            String key = entry.getKey();
            if ("stepfun_plan".equals(key)) {
                continue;
            }
            reordered.put(key, entry.getValue());
            if ("stepfun".equals(key)) {
                reordered.put("stepfun_plan", sites.get("stepfun_plan"));
            }
        }

        sites.clear();
        sites.putAll(reordered);
    }
}
