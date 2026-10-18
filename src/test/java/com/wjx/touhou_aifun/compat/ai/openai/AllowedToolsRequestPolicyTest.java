package com.wjx.touhou_aifun.compat.ai.openai;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllowedToolsRequestPolicyTest {
    private static JsonObject body(boolean responses) {
        return JsonParser.parseString(responses?"{\"tools\":[{\"type\":\"function\",\"name\":\"inspect_gui\",\"parameters\":{}}]}":
                "{\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"inspect_gui\",\"parameters\":{}}}]}").getAsJsonObject();
    }
    @Test void chatAndResponsesUseTheirDistinctAllowedToolsWireShapesWithoutChangingSchemas() {
        for(boolean responses:new boolean[]{false,true}) {
            var body=body(responses);var tools=body.get("tools").deepCopy();
            assertTrue(AllowedToolsRequestPolicy.apply(body,"https://api.openai.com/v1/"+(responses?"responses":"chat/completions"),responses));
            assertEquals(tools,body.get("tools"));
            var choice=body.getAsJsonObject("tool_choice");assertEquals("allowed_tools",choice.get("type").getAsString());
            var allowed=responses?choice:choice.getAsJsonObject("allowed_tools");assertEquals("auto",allowed.get("mode").getAsString());
            var tool=allowed.getAsJsonArray("tools").get(0).getAsJsonObject();
            assertEquals("inspect_gui",(responses?tool:tool.getAsJsonObject("function")).get("name").getAsString());
            assertTrue(AllowedToolsRequestPolicy.reserve(java.util.List.of("inspect_gui"))>=com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(choice.toString()));
        }
    }
    @Test void compatibleSubscriptionAndLookalikeEndpointsNeverReceiveUnsupportedFields() {
        for(String url:java.util.List.of("https://api.deepseek.com/chat/completions","https://api.openai.com.evil.test/v1/responses",
                "https://chatgpt.com/backend-api/codex/responses","https://api.xiaomimimo.com/v1/chat/completions","http://api.openai.com/v1/responses","not a URL")) {
            var body=body(false);var original=body.deepCopy();assertFalse(AllowedToolsRequestPolicy.apply(body,url,false));assertEquals(original,body);
        }
    }
    @Test void existingChoicesEmptyToolsAndHostedToolsRemainUntouched() {
        var existing=body(true);existing.addProperty("tool_choice","required");assertFalse(AllowedToolsRequestPolicy.apply(existing,"https://api.openai.com/v1/responses",true));
        for(String json:java.util.List.of("{}","{\"tools\":[]}","{\"tools\":[{\"type\":\"web_search\"}]}","{\"tools\":[{\"type\":\"function\"}]}")) {
            var body=JsonParser.parseString(json).getAsJsonObject();var original=body.deepCopy();assertFalse(AllowedToolsRequestPolicy.apply(body,"https://api.openai.com/v1/responses",true));assertEquals(original,body);
        }
    }
}
