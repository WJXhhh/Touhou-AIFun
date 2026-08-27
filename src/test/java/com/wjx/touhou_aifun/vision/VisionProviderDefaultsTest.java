package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionProviderDefaultsTest {
    @Test
    void launchProvidersKeepTheRequestedEndpointsAndModels() {
        Map<String, VisionSite> sites = AvailableVisionSites.defaultSites().stream()
                .collect(Collectors.toMap(VisionSite::id, Function.identity()));

        assertSite(sites, "tencent_tokenhub", "https://tokenhub.tencentmaas.com/v1/chat/completions",
                "youtu-vita");
        assertSite(sites, "sensenova", "https://token.sensenova.cn/v1/chat/completions",
                "sensenova-6.7-flash-lite");
        assertSite(sites, "stepfun", "https://api.stepfun.com/v1/chat/completions", "step-3.7-flash");
        assertSite(sites, "stepfun_plan", "https://api.stepfun.com/step_plan/v1/chat/completions",
                "step-3.7-flash");
        assertSite(sites, "zhipu", "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                "glm-4.6v-flash");
        assertSite(sites, "qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "qwen3-vl-flash");
    }

    @Test
    void providerSpecificThinkingIsDisabledInTheCorrectWireShape() {
        VisionRequest request = new VisionRequest(null, null, "", "", Map.of(), -1);

        JsonObject qwen = new OpenAICompatibleVisionClient(site("qwen")).requestBody(request);
        assertFalse(qwen.get("enable_thinking").getAsBoolean());
        assertFalse(qwen.has("thinking"));

        JsonObject zhipu = new OpenAICompatibleVisionClient(site("zhipu")).requestBody(request);
        assertEquals("disabled", zhipu.getAsJsonObject("thinking").get("type").getAsString());
        assertFalse(zhipu.has("enable_thinking"));

        JsonObject stepfun = new OpenAICompatibleVisionClient(site("stepfun")).requestBody(request);
        assertFalse(stepfun.has("thinking"));
        assertFalse(stepfun.has("enable_thinking"));
        assertFalse(stepfun.has("max_tokens"));
        assertEquals("low", stepfun.get("reasoning_effort").getAsString());
        assertEquals("deepseek-style", stepfun.get("reasoning_format").getAsString());
        assertEquals("json_object", stepfun.getAsJsonObject("response_format").get("type").getAsString());

        JsonObject stepfunPlan = new OpenAICompatibleVisionClient(site("stepfun_plan")).requestBody(request);
        assertEquals("low", stepfunPlan.get("reasoning_effort").getAsString());

        JsonObject generic = new OpenAICompatibleVisionClient(site("custom")).requestBody(request);
        assertFalse(generic.has("max_tokens"));
        assertFalse(generic.has("reasoning_effort"));
        assertFalse(generic.has("response_format"));
    }

    @Test
    void separatesFinalContentFromUnfinishedStepFunReasoning() {
        JsonObject root = com.google.gson.JsonParser.parseString("""
                {"choices":[{"message":{"content":"","reasoning_content":"still inspecting"},
                "finish_reason":"length"}]}
                """).getAsJsonObject();

        OpenAICompatibleVisionClient.ProviderText result = OpenAICompatibleVisionClient.extractText(root);

        assertTrue(result.content().isBlank());
        assertEquals("still inspecting", result.reasoning());
        assertEquals("length", result.finishReason());
    }

    private static VisionSite site(String provider) {
        return new VisionSite(provider, provider, provider, "https://example.invalid/v1", "model", "key", false);
    }

    private static void assertSite(Map<String, VisionSite> sites, String id, String endpoint, String model) {
        VisionSite site = sites.get(id);
        assertEquals(endpoint, site.endpoint());
        assertEquals(model, site.model());
    }
}
