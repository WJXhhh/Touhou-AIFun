package com.wjx.touhou_aifun.compat.ai.openai.response;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningContentCodec;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Local response DTO. Do not extend the base mod's Message here: Forge 47.4's EventBus transformer
 * may first load streamed response classes on a common-pool thread whose AppClassLoader cannot
 * resolve cross-mod parent classes. Conversion to the base Message happens only at the callback
 * boundary where an agent tool call actually requires it.
 */
public final class ReasoningOpenAIMessage {
    private static final Gson GSON = new Gson();

    @SerializedName("role")
    private String role;

    @SerializedName("content")
    private String content;

    @SerializedName("tool_calls")
    private List<ToolCall> toolCalls;

    @SerializedName("reasoning_content")
    @Nullable
    private String reasoningContent;

    public String getContent() {
        return ReasoningContentCodec.encode(getVisibleContent(), reasoningContent);
    }

    public String getVisibleContent() {
        if (content != null && content.startsWith("<think>")) {
            return content.replaceAll("<think>[\\s\\S]*?</think>", "").trim();
        }
        return StringUtils.defaultString(content);
    }

    public String getRole() {
        return role;
    }

    public boolean hasToolCall() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /** Preserve encoded reasoning in assistant history while crossing into the base agent API. */
    public Message toBaseMessage() {
        JsonObject message = new JsonObject();
        message.addProperty("role", StringUtils.defaultString(role, "assistant"));
        message.addProperty("content", getContent());
        if (toolCalls != null) message.add("tool_calls", GSON.toJsonTree(toolCalls));
        return GSON.fromJson(message, Message.class);
    }

    @Nullable
    public String getReasoningContent() {
        return reasoningContent;
    }
}
