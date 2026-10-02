package com.wjx.touhou_aifun.compat.ai.deepseek;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekModelsTest {
    @Test
    void migratesOldCatalogWithoutLosingCustomModelsOrReasoningSettings() {
        LLMOpenAISite site = site(List.of(
                new LLMOpenAISite.ModelEntry("deepseek-chat"),
                new LLMOpenAISite.ModelEntry("deepseek-reasoner"),
                new LLMOpenAISite.ModelEntry("deepseek-v4-flash", true),
                new LLMOpenAISite.ModelEntry("deepseek-v4-flash-vision-exp"),
                new LLMOpenAISite.ModelEntry(DeepSeekModels.PRO, true),
                new LLMOpenAISite.ModelEntry("custom-model", true)));

        DeepSeekModels.updateSite(site);
        DeepSeekModels.updateSite(site);

        assertEquals(java.util.Set.of(DeepSeekModels.FLASH, DeepSeekModels.PRO, "custom-model"),
                site.models().keySet());
        assertEquals(site.models().keySet(), site.modelEntries().keySet());
        assertTrue(site.isReasoningModel(DeepSeekModels.FLASH));
        assertTrue(site.isReasoningModel(DeepSeekModels.PRO));
        assertTrue(site.isReasoningModel("custom-model"));
        assertEquals("test-key", site.secretKey());
        assertEquals(Map.of("X-Custom", "value"), site.headers());
        assertFalse(site.enabled());
    }

    @Test
    void preservesExplicitNewFlashSettingWhenBothNamesExist() {
        LLMOpenAISite site = site(List.of(
                new LLMOpenAISite.ModelEntry(DeepSeekModels.FLASH, false),
                new LLMOpenAISite.ModelEntry("deepseek-v4-flash", true)));
        DeepSeekModels.updateSite(site);
        assertFalse(site.isReasoningModel(DeepSeekModels.FLASH));
        assertTrue(site.models().containsKey(DeepSeekModels.PRO));
    }

    private static LLMOpenAISite site(List<LLMOpenAISite.ModelEntry> models) {
        return new LLMOpenAISite("deepseek", null, "https://api.deepseek.com/chat/completions",
                false, "test-key", true, Map.of("X-Custom", "value"), models);
    }
}
