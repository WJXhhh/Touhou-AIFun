package com.wjx.touhou_aifun.compat.ai.mimo.response;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class MimoChatCompletionResponse {
    @SerializedName("choices")
    @Nullable
    private List<MimoChoice> choices;

    @Nullable
    public MimoMessage getFirstMessage() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        MimoChoice choice = choices.get(0);
        return choice == null ? null : choice.message();
    }
}
