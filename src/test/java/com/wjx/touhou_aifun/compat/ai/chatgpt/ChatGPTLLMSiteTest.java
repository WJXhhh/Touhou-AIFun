package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChatGPTLLMSiteTest {
    @Test void reasoningSettingsSurviveModelRefreshAndSerialization() {
        var site = new ChatGPTLLMSite(ChatGPTLLMSite.API_TYPE, true, Map.of("old", "Old"), false, "high", false);
        var refreshed = site.withModels(Map.of("gpt-6.1-sol", "GPT-6.1-Sol"));
        var codec = new ChatGPTLLMSite.Serializer().codec();
        var saved = codec.encodeStart(JsonOps.INSTANCE, refreshed).result().orElseThrow();
        var reloaded = codec.parse(JsonOps.INSTANCE, saved).result().orElseThrow();
        assertEquals(new ChatGPTReasoningSettings(false, "high"), reloaded.reasoningSettings());
        assertTrue(reloaded.enabled());
        assertFalse(reloaded.webSearch());
        assertFalse(reloaded.withModels(Map.of()).webSearch());
        assertEquals(refreshed.models(), reloaded.models());
        assertEquals(reloaded.reasoningSettings(), reloaded.withModels(Map.of()).reasoningSettings());
    }

    @Test void olderSiteFilesGetSummaryOnAndModelDefaultEffort() {
        var site = new ChatGPTLLMSite.Serializer().codec().parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"id\":\"chatgpt_subscription\",\"enabled\":true}")).result().orElseThrow();
        assertEquals(ChatGPTReasoningSettings.DEFAULT, site.reasoningSettings());
        assertTrue(site.webSearch());
    }

    @Test void syncedSiteContainsOnlyModelMetadataAndUsesFixedOfficialEndpoint() {
        var site = new ChatGPTLLMSite(ChatGPTLLMSite.API_TYPE, true, Map.of("model-slug", "Model label"));
        site.setSecretKey("must-not-be-sent");
        site.setUrl("https://untrusted.example");
        var serializer = new ChatGPTLLMSite.Serializer();
        JsonElement encoded = serializer.codec().encodeStart(JsonOps.INSTANCE, site).result().orElseThrow();
        assertFalse(encoded.toString().contains("must-not-be-sent"));
        assertFalse(encoded.toString().contains("secret"));
        assertFalse(encoded.toString().contains("untrusted"));
        var decoded = serializer.codec().parse(JsonOps.INSTANCE, encoded).result().orElseThrow();
        assertEquals("Model label", decoded.models().get("model-slug"));
        assertEquals("model-slug", decoded.modelEntries().get("model-slug").name());
        assertEquals(ChatGPTLLMSite.ENDPOINT, site.url());
        assertEquals("", site.secretKey());
    }
}
