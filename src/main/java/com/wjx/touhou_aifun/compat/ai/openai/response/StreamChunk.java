package com.wjx.touhou_aifun.compat.ai.openai.response;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Usage;
import com.google.gson.annotations.SerializedName;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A single {@code data:} event of an OpenAI-compatible streaming (SSE) chat completion response.
 * Only the fields we accumulate are mapped; everything else is ignored by Gson.
 */
public final class StreamChunk {
    @SerializedName("choices")
    @Nullable
    private List<StreamChoice> choices;

    @SerializedName("usage")
    @Nullable
    private Usage usage;

    @Nullable
    public StreamChoice getFirstChoice() {
        if (choices != null && !choices.isEmpty()) {
            return choices.get(0);
        }
        return null;
    }

    @Nullable
    public Usage getUsage() {
        return usage;
    }

    public static final class StreamChoice {
        @SerializedName("index")
        private int index;

        @SerializedName("delta")
        @Nullable
        private StreamDelta delta;

        @SerializedName("finish_reason")
        @Nullable
        private String finishReason;

        @Nullable
        public StreamDelta getDelta() {
            return delta;
        }

        @Nullable
        public String getFinishReason() {
            return finishReason;
        }
    }

    public static final class StreamDelta {
        @SerializedName("content")
        @Nullable
        private String content;

        @SerializedName("reasoning_content")
        @Nullable
        private String reasoningContent;

        @SerializedName("tool_calls")
        @Nullable
        private List<StreamToolCall> toolCalls;

        @Nullable
        public String getContent() {
            return content;
        }

        @Nullable
        public String getReasoningContent() {
            return reasoningContent;
        }

        @Nullable
        public List<StreamToolCall> getToolCalls() {
            return toolCalls;
        }
    }

    public static final class StreamToolCall {
        @SerializedName("index")
        private int index;

        @SerializedName("id")
        @Nullable
        private String id;

        @SerializedName("function")
        @Nullable
        private StreamFunction function;

        public int getIndex() {
            return index;
        }

        @Nullable
        public String getId() {
            return id;
        }

        @Nullable
        public StreamFunction getFunction() {
            return function;
        }
    }

    public static final class StreamFunction {
        @SerializedName("name")
        @Nullable
        private String name;

        @SerializedName("arguments")
        @Nullable
        private String arguments;

        @Nullable
        public String getName() {
            return name;
        }

        @Nullable
        public String getArguments() {
            return arguments;
        }
    }
}
