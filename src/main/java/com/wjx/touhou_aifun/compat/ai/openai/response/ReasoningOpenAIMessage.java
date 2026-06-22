package com.wjx.touhou_aifun.compat.ai.openai.response;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Message;
import com.google.gson.annotations.SerializedName;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningContentCodec;

import javax.annotation.Nullable;

public final class ReasoningOpenAIMessage extends Message {
    @SerializedName("reasoning_content")
    @Nullable
    private String reasoningContent;

    @Override
    public String getContent() {
        return ReasoningContentCodec.encode(getVisibleContent(), reasoningContent);
    }

    public String getVisibleContent() {
        String content = super.getContent();
        if (content != null && content.startsWith("<think>")) {
            return content.replaceAll("<think>[\\s\\S]*?</think>", "").trim();
        }
        return StringUtils.defaultString(content);
    }

    @Nullable
    public String getReasoningContent() {
        return reasoningContent;
    }
}
