package com.wjx.touhou_aifun.compat.ai.opencodego;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenCodeGoSharedTest {
    @Test
    void routesPublishedAnthropicModelsToMessages() {
        assertTrue(OpenCodeGoShared.usesAnthropicMessages("minimax-m3"));
        assertTrue(OpenCodeGoShared.usesAnthropicMessages("qwen3.8-max"));
        assertFalse(OpenCodeGoShared.usesAnthropicMessages("deepseek-v4-flash"));
        assertFalse(OpenCodeGoShared.usesAnthropicMessages("kimi-k3"));
        assertTrue(OpenCodeGoShared.usesResponses("muse-spark-1.2-contributor"));
        assertFalse(OpenCodeGoShared.usesResponses("deepseek-v4-flash"));
    }

    @Test
    void acceptsEitherBaseOrFullEndpointInConfiguration() {
        assertEquals("https://opencode.ai/zen/go/v1/chat/completions",
                OpenCodeGoShared.chatCompletionsEndpoint("https://opencode.ai/zen/go"));
        assertEquals("https://opencode.ai/zen/go/v1/messages",
                OpenCodeGoShared.messagesEndpoint("https://opencode.ai/zen/go/v1/chat/completions"));
        assertEquals("https://opencode.ai/zen/go/v1/chat/completions",
                OpenCodeGoShared.chatCompletionsEndpoint("https://opencode.ai/zen/go/v1/messages/"));
        assertEquals("https://opencode.ai/zen/go/v1/responses",
                OpenCodeGoShared.responsesEndpoint("https://opencode.ai/zen/go/v1/messages/"));
    }
}
