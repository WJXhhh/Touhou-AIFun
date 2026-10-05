package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.google.gson.*;
import com.wjx.touhou_aifun.compat.ai.openai.response.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PromptCacheTest {
    private static final Gson GSON = new Gson();
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static LLMMessage message(Role role, String text) { return new LLMMessage(role,text,0,null,null); }

    @Test void fiftyStepReplayKeepsPolicyPrefixAndLatestOwnerConstraints() {
        List<LLMMessage> history = new ArrayList<>(List.of(message(Role.SYSTEM,"Stable rules"), message(Role.USER,"Take 59 beef")));
        String prefix = String.join("\n\n",AnthropicPromptLayout.split(history).system());
        for (int step = 0; step < 50; step++) {
            history.removeIf(m -> m.role()==Role.SYSTEM && m.message().startsWith("checkpoint"));
            history.add(message(Role.ASSISTANT,"action " + step));
            history.add(new LLMMessage(Role.TOOL,"{\"moved\":"+step+"}",0,null,"c"+step));
            if (step==25) history.add(message(Role.USER,"Leave one beef; retain all other requirements"));
            history.add(message(Role.SYSTEM,"checkpoint: moved="+step+", goal_version="+(step<25?1:2)));
            var layout = AnthropicPromptLayout.split(history);
            assertEquals(prefix,String.join("\n\n",layout.system()));
            assertEquals(1,layout.updates().size());
            assertTrue(layout.updates().get(0).contains("moved="+step));
            assertTrue(history.stream().anyMatch(m->m.role()==Role.USER && m.message().equals("Take 59 beef")));
            if (step>=25) assertTrue(history.stream().anyMatch(m->m.message().contains("Leave one beef")));
            JsonArray messages=new JsonArray();JsonObject results=json("{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"c1\"},{\"type\":\"tool_result\",\"tool_use_id\":\"c2\"}]}");
            messages.add(results); AnthropicPromptLayout.appendUpdates(messages,layout.updates());
            assertEquals(results,messages.get(0)); assertEquals(2,messages.size());
            assertTrue(messages.get(1).toString().contains("moved="+step));
        }
    }

    @Test void anthropicPartialUsageRetainsCacheAndCountsTotalContextOnce() {
        var stream = new StreamAccumulator();
        stream.acceptAnthropicUsage(json("{\"input_tokens\":17,\"output_tokens\":0,\"cache_read_input_tokens\":2000,\"cache_creation_input_tokens\":64}"));
        stream.acceptAnthropicUsage(json("{\"output_tokens\":9}"));
        var response = stream.buildResponse(); var usage=response.getTokenUsage();
        assertEquals(2081,response.getUsage().getPromptTokens()); assertEquals(2090,response.getUsage().getTotalTokens());
        assertEquals(2000,usage.cached_input_tokens()); assertEquals(17,usage.uncached_input_tokens()); assertEquals(64,usage.cache_write_input_tokens());
        JsonObject roundTrip=GSON.toJsonTree(response).getAsJsonObject();
        assertEquals(usage,TokenUsage.read(TokenUsage.anthropic(roundTrip.getAsJsonObject("usage"))));
    }

    @Test void openAIStreamingPreservesDeepSeekCacheFieldsAndExplicitZero() {
        var stream = new StreamAccumulator();
        stream.accept(GSON.fromJson("{\"usage\":{\"prompt_tokens\":3000,\"completion_tokens\":10,\"total_tokens\":3010,\"prompt_cache_hit_tokens\":2500,\"prompt_cache_miss_tokens\":500}}",StreamChunk.class));
        assertEquals(2500,stream.buildResponse().getTokenUsage().cached_input_tokens());
        assertEquals(500,stream.buildResponse().getTokenUsage().uncached_input_tokens());
        assertEquals(0,TokenUsage.read(json("{\"prompt_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":0}}")).cached_input_tokens());
        assertNull(TokenUsage.read(json("{\"prompt_tokens\":10}")).cached_input_tokens());
        assertNull(TokenUsage.read(json("{\"prompt_tokens\":10}")).uncached_input_tokens());
    }

    @Test void responsesAndSubscriptionKeepCachedAndReasoningDetails() {
        String body="{\"output_text\":\"ok\",\"usage\":{\"input_tokens\":3000,\"output_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":2800},\"output_tokens_details\":{\"reasoning_tokens\":4}}}";
        var response=OpenAIResponsesCompatLLMClient.adaptResponse(body);
        assertEquals(3010,response.getUsage().getTotalTokens()); assertEquals(2800,response.getTokenUsage().cached_input_tokens());
        assertEquals(200,response.getTokenUsage().uncached_input_tokens()); assertEquals(4,response.getTokenUsage().reasoning_tokens());
        var events=new ChatGPTResponsesCodec.Events();JsonObject completed=json(body);completed.addProperty("status","completed");
        JsonObject event=json("{\"type\":\"response.completed\"}");event.add("response",completed);events.accept(event);
        assertEquals(response.getTokenUsage(),OpenAIResponsesCompatLLMClient.adaptResponse(events.completed().toString()).getTokenUsage());
    }
}
