package com.wjx.touhou_aifun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.google.gson.Gson;
import com.wjx.touhou_aifun.compat.ai.openai.response.ReasoningOpenAIMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReasoningOpenAIMessageTest {
    @Test
    void responseDtoHasNoCrossModSuperclassAndConvertsOnlyAtToolBoundary() {
        ReasoningOpenAIMessage response = new Gson().fromJson("""
                {
                  "role":"assistant",
                  "content":"visible",
                  "reasoning_content":"private reasoning",
                  "tool_calls":[{
                    "id":"call-1",
                    "type":"function",
                    "function":{"name":"scan_surroundings","arguments":"{}"}
                  }]
                }
                """, ReasoningOpenAIMessage.class);

        assertSame(Object.class, ReasoningOpenAIMessage.class.getSuperclass());
        assertTrue(response.hasToolCall());

        Message base = response.toBaseMessage();
        ReasoningContentCodec.DecodedContent decoded = ReasoningContentCodec.decode(base.getContent());
        assertEquals("visible", decoded.content());
        assertEquals("private reasoning", decoded.reasoningContent());
        assertEquals("call-1", base.getToolCalls().get(0).getId());
        assertEquals("scan_surroundings", base.getToolCalls().get(0).getFunction().getName());
    }
}
