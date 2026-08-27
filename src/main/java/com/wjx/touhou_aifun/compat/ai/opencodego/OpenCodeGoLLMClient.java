package com.wjx.touhou_aifun.compat.ai.opencodego;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.wjx.touhou_aifun.compat.ai.openai.AnthropicCompatLLMClient;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAIClient;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAISite;

import java.net.http.HttpClient;
import java.util.ArrayList;

/** Routes an OpenCode Go model to the wire protocol documented for that model. */
public final class OpenCodeGoLLMClient implements LLMClient {
    private final HttpClient httpClient;
    private final OpenCodeGoLLMSite site;

    public OpenCodeGoLLMClient(HttpClient httpClient, OpenCodeGoLLMSite site) {
        this.httpClient = httpClient;
        this.site = site;
    }

    @Override
    public void chat(LLMCallback callback) {
        String model = callback.getMaid().getAiChatManager().getLLMModel();
        if (OpenCodeGoShared.usesAnthropicMessages(model)) {
            new AnthropicCompatLLMClient(this.httpClient, this.copyForMessages()).chat(callback);
            return;
        }
        new ReasoningCompatOpenAIClient(this.httpClient, this.copyForChatCompletions()).chat(callback);
    }

    private LLMOpenAISite copyForChatCompletions() {
        return new ReasoningCompatOpenAISite(
                this.site.id(), this.site.icon(),
                OpenCodeGoShared.chatCompletionsEndpoint(this.site.url()),
                this.site.enabled(), this.site.secretKey(), false,
                this.site.headers(), new ArrayList<>(this.site.modelEntries().values()));
    }

    private LLMOpenAISite copyForMessages() {
        return new LLMOpenAISite(
                this.site.id(), this.site.icon(),
                OpenCodeGoShared.messagesEndpoint(this.site.url()),
                this.site.enabled(), this.site.secretKey(), false,
                this.site.headers(), new ArrayList<>(this.site.modelEntries().values()));
    }
}
