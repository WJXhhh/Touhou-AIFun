package team.cfpa.touhoustepfun.compat.ai.mimo.response;

import com.google.gson.annotations.SerializedName;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

public final class MimoMessage {
    @SerializedName("content")
    @Nullable
    private String content;

    @SerializedName("audio")
    @Nullable
    private MimoAudio audio;

    public String getContent() {
        return StringUtils.defaultString(content);
    }

    @Nullable
    public MimoAudio getAudio() {
        return audio;
    }
}
