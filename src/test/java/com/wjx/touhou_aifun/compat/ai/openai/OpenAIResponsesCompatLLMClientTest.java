package com.wjx.touhou_aifun.compat.ai.openai;

import com.wjx.touhou_aifun.compat.ai.openai.response.ReasoningOpenAIChatCompletionResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIResponsesCompatLLMClientTest {
    @Test
    void adaptsTextReasoningToolsAndUsage() {
        ReasoningOpenAIChatCompletionResponse response = OpenAIResponsesCompatLLMClient.adaptResponse("""
                {"id":"resp_1","output":[
                  {"type":"reasoning","summary":[{"type":"summary_text","text":"checked"}]},
                  {"type":"message","content":[{"type":"output_text","text":"hello"}]},
                  {"type":"function_call","id":"fc_1","call_id":"call_1","name":"web_search",
                   "arguments":"{\\"query\\":\\"news\\"}"}
                ],"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}
                """);

        assertEquals(15, response.getUsage().getTotalTokens());
        assertEquals("hello", response.getFirstChoice().getVisibleContent());
        assertEquals("checked", response.getFirstChoice().getReasoningContent());
        assertTrue(response.getFirstChoice().hasToolCall());
        assertEquals("call_1", response.getFirstChoice().getToolCalls().get(0).getId());
        assertEquals("web_search", response.getFirstChoice().getToolCalls().get(0).getFunction().getName());
    }
}
