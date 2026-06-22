package com.wjx.touhou_aifun.compat.ai.minimax.tts;

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
import com.wjx.touhou_aifun.compat.ai.minimax.layout.MiniMaxTTSFormLayout;

import java.util.Map;

public final class MiniMaxCompatTTSSite implements TTSSite, SupportModelSelect {
    public static final String API_TYPE = TTSApiType.MINIMAX.getName();
    private static final String CUSTOM_VOICE_REF_AUDIO_PATH = "custom_voice_ref_audio_path";
    private static final String CUSTOM_VOICE_REF_TEXT = "custom_voice_ref_text";
    private static final String CUSTOM_VOICE_ID = "custom_voice_id";

    private final String id;
    private final ResourceLocation icon;
    private final String customVoiceRefAudioPath;
    private final String customVoiceRefText;
    private final String customVoiceId;
    private final Map<String, String> headers;
    private final Map<String, String> models;

    private String url;
    private boolean enabled;
    private String secretKey;
    private String siteModel;

    public MiniMaxCompatTTSSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                                String siteModel, String customVoiceRefAudioPath, String customVoiceRefText,
                                String customVoiceId, Map<String, String> headers, Map<String, String> models) {
        this.id = id;
        this.icon = icon;
        this.url = url;
        this.enabled = enabled;
        this.secretKey = secretKey;
        this.siteModel = siteModel;
        this.customVoiceRefAudioPath = customVoiceRefAudioPath;
        this.customVoiceRefText = customVoiceRefText;
        this.customVoiceId = customVoiceId;
        this.headers = headers;
        this.models = models;
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    @Override
    public TTSClient client() {
        return new MiniMaxCompatTTSClient(TTS_HTTP_CLIENT, this);
    }

    @Override
    public TTSSiteFormLayout formLayout() {
        return new MiniMaxTTSFormLayout(this);
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

    public String siteModel() {
        return siteModel;
    }

    public String customVoiceRefAudioPath() {
        return customVoiceRefAudioPath;
    }

    public String customVoiceRefText() {
        return customVoiceRefText;
    }

    public String customVoiceId() {
        return customVoiceId;
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

    public static class Serializer implements SerializableSite<MiniMaxCompatTTSSite> {
        public static final Codec<MiniMaxCompatTTSSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(MiniMaxCompatTTSSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(MiniMaxCompatTTSSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(MiniMaxCompatTTSSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(MiniMaxCompatTTSSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(MiniMaxCompatTTSSite::secretKey),
                Codec.STRING.fieldOf(SITE_MODEL).forGetter(MiniMaxCompatTTSSite::siteModel),
                Codec.STRING.optionalFieldOf(CUSTOM_VOICE_REF_AUDIO_PATH, StringUtils.EMPTY).forGetter(MiniMaxCompatTTSSite::customVoiceRefAudioPath),
                Codec.STRING.optionalFieldOf(CUSTOM_VOICE_REF_TEXT, StringUtils.EMPTY).forGetter(MiniMaxCompatTTSSite::customVoiceRefText),
                Codec.STRING.optionalFieldOf(CUSTOM_VOICE_ID, StringUtils.EMPTY).forGetter(MiniMaxCompatTTSSite::customVoiceId),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(MiniMaxCompatTTSSite::headers),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(MODELS).forGetter(MiniMaxCompatTTSSite::models)
        ).apply(instance, MiniMaxCompatTTSSite::new));

        @Override
        public MiniMaxCompatTTSSite defaultSite() {
            return new MiniMaxCompatTTSSite(API_TYPE, SerializableSite.defaultIcon(API_TYPE),
                    "https://api.minimaxi.com/v1/t2a_v2", false, StringUtils.EMPTY,
                    "speech-2.8-turbo", StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, Map.of(),
                    Map.of("Chinese (Mandarin)_Mature_Woman", "Mature (CN)",
                            "Chinese (Mandarin)_Warm_Girl", "Warm (CN)",
                            "Chinese (Mandarin)_BashfulGirl", "Bashful (CN)",
                            "English_PlayfulGirl", "Playful (EN)",
                            "English_Soft-spokenGirl", "Soft (EN)",
                            "Japanese_DecisivePrincess", "Decisive (JP)",
                            "Japanese_DependableWoman", "Dependable (JP)",
                            "Japanese_obedient_girl_vv1", "Obedient (JP)"));
        }

        @Override
        public Codec<MiniMaxCompatTTSSite> codec() {
            return CODEC;
        }
    }
}
