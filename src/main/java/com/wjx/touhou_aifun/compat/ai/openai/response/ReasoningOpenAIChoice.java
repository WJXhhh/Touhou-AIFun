package com.wjx.touhou_aifun.compat.ai.openai.response;

import com.google.gson.annotations.SerializedName;

public final class ReasoningOpenAIChoice {
    @SerializedName("index")
    private int index;

    @SerializedName("message")
    private ReasoningOpenAIMessage message;

    @SerializedName("finish_reason")
    private String finishReason;

    public int getIndex() {
        return index;
    }

    public ReasoningOpenAIMessage getMessage() {
        return message;
    }

    public String getFinishReason() {
        return finishReason;
    }
}
