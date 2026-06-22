package team.cfpa.touhoustepfun.compat.ai.mimo.response;

import com.google.gson.annotations.SerializedName;

public record MimoChoice(@SerializedName("message") MimoMessage message) {
}
