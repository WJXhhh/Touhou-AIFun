package com.wjx.touhou_aifun.compat.ai.openai.request;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.ResponseFormat;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.Thinking;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.request.Tool;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.google.common.collect.Lists;
import com.google.gson.annotations.SerializedName;

import javax.annotation.Nullable;
import java.util.List;

public final class ReasoningChatCompletion {
    @SerializedName("model")
    private String model = "";

    @SerializedName("messages")
    private List<ReasoningChatMessage> messages = Lists.newArrayList();

    @SerializedName("tools")
    @Nullable
    private List<Tool> tools = null;

    @SerializedName("response_format")
    private ResponseFormat responseFormat = ResponseFormat.text();

    @SerializedName("thinking")
    @Nullable
    private Thinking thinking = null;

    @SerializedName("reasoning_effort")
    @Nullable
    private String reasoningEffort = null;

    @SerializedName("stream")
    @Nullable
    private Boolean stream = null;

    @SerializedName("stream_options")
    @Nullable
    private StreamOptions streamOptions = null;

    public static ReasoningChatCompletion create() {
        return new ReasoningChatCompletion();
    }

    /**
     * Enables streaming (SSE) output and asks the server to include the final usage chunk so token
     * accounting keeps working exactly like the non-streaming path.
     */
    public ReasoningChatCompletion enableStream() {
        this.stream = Boolean.TRUE;
        this.streamOptions = new StreamOptions();
        return this;
    }

    private static final class StreamOptions {
        @SerializedName("include_usage")
        private final boolean includeUsage = true;
    }

    public ReasoningChatCompletion model(String model) {
        this.model = model;
        return this;
    }

    public ReasoningChatCompletion systemChat(String message) {
        this.messages.add(ReasoningChatMessage.systemChat(message));
        return this;
    }

    public ReasoningChatCompletion userChat(String message) {
        this.messages.add(ReasoningChatMessage.userChat(message));
        return this;
    }

    public ReasoningChatCompletion assistantChat(String message, @Nullable String reasoningContent) {
        this.messages.add(ReasoningChatMessage.assistantChat(message, reasoningContent));
        return this;
    }

    public ReasoningChatCompletion assistantChat(String message, @Nullable String reasoningContent, List<ToolCall> toolCalls) {
        this.messages.add(ReasoningChatMessage.assistantChat(message, reasoningContent, toolCalls));
        return this;
    }

    public ReasoningChatCompletion toolChat(String message, String toolCallId) {
        this.messages.add(ReasoningChatMessage.toolChat(message, toolCallId));
        return this;
    }

    public ReasoningChatCompletion developerChat(String message) {
        this.messages.add(ReasoningChatMessage.developerChat(message));
        return this;
    }

    public ReasoningChatCompletion addTool(Tool tool) {
        if (this.tools == null) {
            this.tools = Lists.newArrayList();
        }
        this.tools.add(tool);
        return this;
    }

    public ReasoningChatCompletion disableThinking() {
        this.thinking = Thinking.disabled();
        return this;
    }

    public ReasoningChatCompletion reasoningEffort(String reasoningEffort) {
        this.reasoningEffort = reasoningEffort;
        return this;
    }

    public ReasoningChatCompletion mergeSystemMessages() {
        boolean continuous = true;
        List<ReasoningChatMessage> copy = Lists.newArrayList();
        for (ReasoningChatMessage message : this.messages) {
            if (continuous && message.getRole().equals(Role.SYSTEM.getId())) {
                if (copy.isEmpty()) {
                    copy.add(message);
                } else {
                    ReasoningChatMessage first = copy.get(0);
                    copy.set(0, ReasoningChatMessage.systemChat(first.getContent() + "\n" + message.getContent()));
                }
            } else {
                continuous = false;
                copy.add(message);
            }
        }
        this.messages = copy;
        return this;
    }

    public ReasoningChatCompletion setResponseFormat(ResponseFormat responseFormat) {
        this.responseFormat = responseFormat;
        return this;
    }
}
