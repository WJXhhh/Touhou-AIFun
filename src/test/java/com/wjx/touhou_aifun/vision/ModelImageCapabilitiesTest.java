package com.wjx.touhou_aifun.vision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModelImageCapabilitiesTest {
    @Test void onlyExplicitProviderInputModalitiesEstablishCapability() {
        assertNull(ModelImageCapabilities.providerImages(com.google.gson.JsonParser.parseString("{\"name\":\"vision-pro\"}").getAsJsonObject()));
        assertFalse(ModelImageCapabilities.providerImages(com.google.gson.JsonParser.parseString("{\"input_modalities\":[\"text\"]}").getAsJsonObject()));
        assertTrue(ModelImageCapabilities.providerImages(com.google.gson.JsonParser.parseString("{\"modalities\":{\"input\":[\"text\",\"image\"]}}").getAsJsonObject()));
        assertFalse(ModelImageCapabilities.knownSupported("openai", "https://api.openai.com/not-a-chat-endpoint", "gpt-4o"));
        assertFalse(ModelImageCapabilities.knownSupported("openai", "https://api.anthropic.com/v1/messages", "claude-sonnet-4-20250514"));
        assertTrue(ModelImageCapabilities.knownSupported("anthropic", "https://api.anthropic.com", "claude-sonnet-4-20250514"));
    }
    @Test void usesEndpointAndConfirmedModelRatherThanProOrFlashSuffixes() {
        assertTrue(ModelImageCapabilities.knownSupported("stepfun_plan", "https://api.stepfun.com/step_plan/v1/chat/completions", "step-3.7-flash"));
        assertFalse(ModelImageCapabilities.knownSupported("stepfun", "https://api.stepfun.com/v1/chat/completions", "step-3.5-flash"));
        assertTrue(ModelImageCapabilities.knownSupported("mimo", "https://token-plan-cn.xiaomimimo.com/v1/chat/completions", "mimo-v2.5"));
        assertFalse(ModelImageCapabilities.knownSupported("mimo", "https://token-plan-cn.xiaomimimo.com/v1/chat/completions", "mimo-v2.5-pro"));
        assertFalse(ModelImageCapabilities.knownSupported("mimo", "https://api.xiaomimimo.com/v1/chat/completions", "mimo-v2-pro"));
        assertTrue(ModelImageCapabilities.knownSupported("aliyun", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3-vl-flash"));
        assertFalse(ModelImageCapabilities.knownSupported("aliyun", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "qwen3-max"));
        assertFalse(ModelImageCapabilities.knownSupported("openai", "https://unknown.invalid/v1/chat/completions", "gpt-4o"));
        assertFalse(ModelImageCapabilities.knownSupported("opencode_go", "https://opencode.ai/zen/go", "mimo-v2.5"));
    }
    @Test void manualOverrideWinsOverProviderMetadataAndProviderWinsOverBuiltins() {
        var providerNo = new ModelMetadataStore.Metadata(VisionCapabilityMode.AUTO, false, "", false, "");
        assertFalse(ModelImageCapabilities.supports(providerNo, "openai", "https://api.openai.com/v1/chat/completions", "gpt-4o"));
        assertTrue(ModelImageCapabilities.supports(providerNo.withCapability(VisionCapabilityMode.SUPPORTED), "openai", "https://unknown.invalid", "custom"));
        var providerYes = new ModelMetadataStore.Metadata(VisionCapabilityMode.AUTO, true, "", false, "");
        assertTrue(ModelImageCapabilities.supports(providerYes, "openai", "https://unknown.invalid", "custom"));
        assertFalse(ModelImageCapabilities.supports(providerYes.withCapability(VisionCapabilityMode.UNSUPPORTED), "openai", "https://api.openai.com/v1/chat/completions", "gpt-4o"));
    }
}
