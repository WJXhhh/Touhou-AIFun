package com.wjx.touhou_aifun.compat.ai.openai.request;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.ToolCall;
import com.google.gson.annotations.SerializedName;

import javax.annotation.Nullable;
import java.util.List;

public final class ReasoningChatMessage {
    @SerializedName("role")
    private final String role;

    @SerializedName("content")
    private final String content;

    @SerializedName("tool_calls")
    @Nullable
    private List<ToolCall> toolCalls = null;

    @SerializedName("tool_call_id")
    @Nullable
    private String toolCallId = null;

    @SerializedName("reasoning_content")
    @Nullable
    private String reasoningContent = null;

    private ReasoningChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public static ReasoningChatMessage systemChat(String content) {
        return new ReasoningChatMessage(Role.SYSTEM.getId(), content);
    }

    public static ReasoningChatMessage userChat(String content) {
        return new ReasoningChatMessage(Role.USER.getId(), content);
    }

    public static ReasoningChatMessage developerChat(String content) {
        return new ReasoningChatMessage(Role.DEVELOPER.getId(), content);
    }

    public static ReasoningChatMessage assistantChat(String content, @Nullable String reasoningContent) {
        ReasoningChatMessage chatMessage = new ReasoningChatMessage(Role.ASSISTANT.getId(), content);
        chatMessage.reasoningContent = reasoningContent;
        return chatMessage;
    }

    public static ReasoningChatMessage assistantChat(String content, @Nullable String reasoningContent, List<ToolCall> toolCalls) {
        ReasoningChatMessage chatMessage = assistantChat(content, reasoningContent);
        chatMessage.toolCalls = toolCalls;
        return chatMessage;
    }

    public static ReasoningChatMessage toolChat(String content, String toolCallId) {
        ReasoningChatMessage chatMessage = new ReasoningChatMessage(Role.TOOL.getId(), content);
        chatMessage.toolCallId = toolCallId;
        return chatMessage;
    }
}
