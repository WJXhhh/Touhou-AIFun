package com.wjx.touhou_aifun.compat.ai.mimo.response;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

public final class MimoAudio {
    @SerializedName("data")
    @Nullable
    private String data;

    @Nullable
    public String getData() {
        return data;
    }
}
