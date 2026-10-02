package com.wjx.touhou_aifun.compat.ai.deepseek;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;

/** Official DeepSeek model IDs; third-party gateways maintain their own catalog. */
public final class DeepSeekModels {
    public static final String FLASH = "deepseek-flash";
    public static final String PRO = "deepseek-v4-pro";

    private DeepSeekModels() {
    }

    public static void updateSite(LLMOpenAISite site) {
        // V4.1 Flash replaces both old Flash variants (official update: 2026-09-10).
        boolean reasoning = site.isReasoningModel("deepseek-v4-flash")
                || site.isReasoningModel("deepseek-v4-flash-vision-exp");
        site.removeModel("deepseek-chat");
        site.removeModel("deepseek-reasoner");
        site.removeModel("deepseek-v4-flash");
        site.removeModel("deepseek-v4-flash-vision-exp");
        if (!site.modelEntries().containsKey(FLASH)) {
            site.addModel(FLASH);
            site.modelEntries().put(FLASH, new LLMOpenAISite.ModelEntry(FLASH, reasoning));
        }
        if (!site.modelEntries().containsKey(PRO)) {
            site.addModel(PRO);
        }
    }
}
