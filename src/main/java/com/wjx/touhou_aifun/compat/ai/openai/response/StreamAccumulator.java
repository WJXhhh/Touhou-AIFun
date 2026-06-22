package com.wjx.touhou_aifun.compat.ai.openai.response;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Usage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.util.List;
import java.util.TreeMap;

/**
 * Accumulates streaming (SSE) chat-completion deltas into the same shape as a non-streaming
 * response. Once the stream ends, {@link #buildResponse()} produces a
 * {@link ReasoningOpenAIChatCompletionResponse} identical to what a non-streaming request would
 * have returned, so all downstream handling (token accounting, tool/agent calls, reasoning
 * content) is reused unchanged.
 */
public final class StreamAccumulator {
    private static final Gson GSON = new Gson();

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final TreeMap<Integer, ToolCallBuilder> toolCalls = new TreeMap<>();

    @Nullable
    private Usage usage;
    @Nullable
    private String finishReason;

    public void accept(StreamChunk chunk) {
        if (chunk.getUsage() != null) {
            this.usage = chunk.getUsage();
        }
        StreamChunk.StreamChoice choice = chunk.getFirstChoice();
        if (choice == null) {
            return;
        }
        if (choice.getFinishReason() != null) {
            this.finishReason = choice.getFinishReason();
        }
        StreamChunk.StreamDelta delta = choice.getDelta();
        if (delta == null) {
            return;
        }
        if (delta.getContent() != null) {
            this.content.append(delta.getContent());
        }
        if (delta.getReasoningContent() != null) {
            this.reasoning.append(delta.getReasoningContent());
        }
        List<StreamChunk.StreamToolCall> deltaToolCalls = delta.getToolCalls();
        if (deltaToolCalls != null) {
            for (StreamChunk.StreamToolCall tc : deltaToolCalls) {
                ToolCallBuilder builder = this.toolCalls.computeIfAbsent(tc.getIndex(), i -> new ToolCallBuilder());
                if (tc.getId() != null) {
                    builder.id = tc.getId();
                }
                StreamChunk.StreamFunction function = tc.getFunction();
                if (function != null) {
                    if (function.getName() != null) {
                        builder.name = function.getName();
                    }
                    if (function.getArguments() != null) {
                        builder.arguments.append(function.getArguments());
                    }
                }
            }
        }
    }

    /** Raw assistant content accumulated so far (visible answer, may include partial text). */
    public String currentContent() {
        return this.content.toString();
    }

    /** Reasoning ("deep thinking") content accumulated so far; empty if the model emits none. */
    public String currentReasoning() {
        return this.reasoning.toString();
    }

    public boolean hasToolCalls() {
        return !this.toolCalls.isEmpty();
    }

    /** Rebuilds the equivalent non-streaming response object. */
    public ReasoningOpenAIChatCompletionResponse buildResponse() {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", this.content.toString());
        if (this.reasoning.length() > 0) {
            message.addProperty("reasoning_content", this.reasoning.toString());
        }
        if (!this.toolCalls.isEmpty()) {
            JsonArray toolCallsJson = new JsonArray();
            for (ToolCallBuilder builder : this.toolCalls.values()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", builder.name);
                function.addProperty("arguments", builder.arguments.toString());

                JsonObject toolCall = new JsonObject();
                toolCall.addProperty("id", builder.id);
                toolCall.addProperty("type", "function");
                toolCall.add("function", function);
                toolCallsJson.add(toolCall);
            }
            message.add("tool_calls", toolCallsJson);
        }

        JsonObject choice = new JsonObject();
        choice.addProperty("index", 0);
        if (this.finishReason != null) {
            choice.addProperty("finish_reason", this.finishReason);
        }
        choice.add("message", message);

        JsonArray choices = new JsonArray();
        choices.add(choice);

        JsonObject root = new JsonObject();
        root.add("choices", choices);
        if (this.usage != null) {
            root.add("usage", GSON.toJsonTree(this.usage));
        }

        return GSON.fromJson(root, ReasoningOpenAIChatCompletionResponse.class);
    }

    private static final class ToolCallBuilder {
        @Nullable
        private String id;
        @Nullable
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
