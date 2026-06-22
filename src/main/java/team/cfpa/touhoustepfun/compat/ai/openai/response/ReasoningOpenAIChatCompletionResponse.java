package team.cfpa.touhoustepfun.compat.ai.openai.response;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.response.Usage;
import com.google.gson.annotations.SerializedName;

import javax.annotation.Nullable;

public final class ReasoningOpenAIChatCompletionResponse {
    @SerializedName("id")
    private String id;

    @SerializedName("object")
    private String object;

    @SerializedName("created")
    private long created;

    @SerializedName("model")
    private String model;

    @SerializedName("system_fingerprint")
    private String systemFingerprint;

    @SerializedName("choices")
    private ReasoningOpenAIChoice[] choices;

    @SerializedName("service_tier")
    private String serviceTier;

    @SerializedName("usage")
    private Usage usage;

    @Nullable
    public ReasoningOpenAIMessage getFirstChoice() {
        if (choices != null && choices.length > 0) {
            return choices[0].getMessage();
        }
        return null;
    }

    public Usage getUsage() {
        return usage;
    }
}
