package com.wjx.touhou_aifun.compat.ai.mimo.response;

import com.google.gson.annotations.SerializedName;

public record MimoChoice(@SerializedName("message") MimoMessage message) {
}
