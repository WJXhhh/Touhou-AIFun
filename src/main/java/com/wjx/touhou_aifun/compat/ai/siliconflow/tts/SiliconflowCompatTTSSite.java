package com.wjx.touhou_aifun.compat.ai.siliconflow.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SupportModelSelect;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSApiType;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.TTSSiteFormLayout;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.siliconflow.layout.SiliconflowTTSFormLayout;

import java.util.Map;

public final class SiliconflowCompatTTSSite implements TTSSite, SupportModelSelect {
    public static final String API_TYPE = TTSApiType.SILICONFLOW.getName();
    public static final String VOICE_MODEL = "FunAudioLLM/CosyVoice2-0.5B";
    private static final String CUSTOM_VOICE_REF_AUDIO_PATH = "custom_voice_ref_audio_path";
    private static final String CUSTOM_VOICE_REF_TEXT = "custom_voice_ref_text";
    private static final String CUSTOM_VOICE_URI = "custom_voice_uri";

    private final String id;
    private final ResourceLocation icon;
    private final String customVoiceRefAudioPath;
    private final String customVoiceRefText;
    private final String customVoiceUri;
    private final Map<String, String> headers;
    private final Map<String, String> models;

    private String url;
    private boolean enabled;
    private String secretKey;

    public SiliconflowCompatTTSSite(String id, ResourceLocation icon, String url, boolean enabled,
                                    String secretKey, String customVoiceRefAudioPath, String customVoiceRefText,
                                    String customVoiceUri, Map<String, String> headers, Map<String, String> models) {
        this.id = id;
        this.icon = icon;
        this.url = url;
        this.enabled = enabled;
        this.secretKey = secretKey;
        this.customVoiceRefAudioPath = customVoiceRefAudioPath;
        this.customVoiceRefText = customVoiceRefText;
        this.customVoiceUri = customVoiceUri;
        this.headers = headers;
        this.models = models;
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    @Override
    public TTSClient client() {
        return new SiliconflowCompatTTSClient(TTS_HTTP_CLIENT, this);
    }

    @Override
    public TTSSiteFormLayout formLayout() {
        return new SiliconflowTTSFormLayout(this);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ResourceLocation icon() {
        return icon;
    }

    @Override
    public String url() {
        return url;
    }

    public String secretKey() {
        return secretKey;
    }

    public String customVoiceRefAudioPath() {
        return customVoiceRefAudioPath;
    }

    public String customVoiceRefText() {
        return customVoiceRefText;
    }

    public String customVoiceUri() {
        return customVoiceUri;
    }

    @Override
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public Map<String, String> models() {
        return this.models;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public static class Serializer implements SerializableSite<SiliconflowCompatTTSSite> {
        public static final Codec<SiliconflowCompatTTSSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(SiliconflowCompatTTSSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(SiliconflowCompatTTSSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(SiliconflowCompatTTSSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(SiliconflowCompatTTSSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(SiliconflowCompatTTSSite::secretKey),
                Codec.STRING.optionalFieldOf(CUSTOM_VOICE_REF_AUDIO_PATH, StringUtils.EMPTY).forGetter(SiliconflowCompatTTSSite::customVoiceRefAudioPath),
                Codec.STRING.optionalFieldOf(CUSTOM_VOICE_REF_TEXT, StringUtils.EMPTY).forGetter(SiliconflowCompatTTSSite::customVoiceRefText),
                Codec.STRING.optionalFieldOf(CUSTOM_VOICE_URI, StringUtils.EMPTY).forGetter(SiliconflowCompatTTSSite::customVoiceUri),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(SiliconflowCompatTTSSite::headers),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(MODELS).forGetter(SiliconflowCompatTTSSite::models)
        ).apply(instance, SiliconflowCompatTTSSite::new));

        @Override
        public SiliconflowCompatTTSSite defaultSite() {
            return new SiliconflowCompatTTSSite(API_TYPE, SerializableSite.defaultIcon(API_TYPE),
                    "https://api.siliconflow.cn/v1/audio/speech", false, StringUtils.EMPTY,
                    StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, Map.of(),
                    Map.of(VOICE_MODEL + ":anna", "anna",
                            VOICE_MODEL + ":bella", "bella",
                            VOICE_MODEL + ":claire", "claire",
                            VOICE_MODEL + ":diana", "diana"));
        }

        @Override
        public Codec<SiliconflowCompatTTSSite> codec() {
            return CODEC;
        }
    }
}
