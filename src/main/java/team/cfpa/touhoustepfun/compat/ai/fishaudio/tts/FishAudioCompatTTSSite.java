package team.cfpa.touhoustepfun.compat.ai.fishaudio.tts;

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
import team.cfpa.touhoustepfun.compat.ai.fishaudio.layout.FishAudioTTSFormLayout;

import java.util.Map;

public final class FishAudioCompatTTSSite implements TTSSite, SupportModelSelect {
    public static final String API_TYPE = TTSApiType.FISH_AUDIO.getName();
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

    public FishAudioCompatTTSSite(String id, ResourceLocation icon, String url, boolean enabled,
                                  String secretKey, String customVoiceRefAudioPath, String customVoiceRefText,
                                  String customVoiceId, Map<String, String> headers, Map<String, String> models) {
        this.id = id;
        this.icon = icon;
        this.url = url;
        this.enabled = enabled;
        this.secretKey = secretKey;
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
        return new FishAudioCompatTTSClient(TTS_HTTP_CLIENT, this);
    }

    @Override
    public TTSSiteFormLayout formLayout() {
        return new FishAudioTTSFormLayout(this);
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

    public static class Serializer implements SerializableSite<FishAudioCompatTTSSite> {
        public static final Codec<FishAudioCompatTTSSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(FishAudioCompatTTSSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(FishAudioCompatTTSSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(FishAudioCompatTTSSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(FishAudioCompatTTSSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(FishAudioCompatTTSSite::secretKey),
                Codec.STRING.optionalFieldOf(CUSTOM_VOICE_REF_AUDIO_PATH, StringUtils.EMPTY).forGetter(FishAudioCompatTTSSite::customVoiceRefAudioPath),
                Codec.STRING.optionalFieldOf(CUSTOM_VOICE_REF_TEXT, StringUtils.EMPTY).forGetter(FishAudioCompatTTSSite::customVoiceRefText),
                Codec.STRING.optionalFieldOf(CUSTOM_VOICE_ID, StringUtils.EMPTY).forGetter(FishAudioCompatTTSSite::customVoiceId),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(FishAudioCompatTTSSite::headers),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(MODELS).forGetter(FishAudioCompatTTSSite::models)
        ).apply(instance, FishAudioCompatTTSSite::new));

        @Override
        public FishAudioCompatTTSSite defaultSite() {
            return new FishAudioCompatTTSSite(API_TYPE, SerializableSite.defaultIcon(API_TYPE),
                    "https://api.fish.audio/v1/tts", false, StringUtils.EMPTY,
                    StringUtils.EMPTY, StringUtils.EMPTY, StringUtils.EMPTY, Map.of(),
                    Map.of("b2b2d0fa88ee44d789da28ebbd97421e", "Neuro-sama (EN)",
                            "4858e0be678c4449bf3a7646186edd42", "Nahida (EN)",
                            "1aacaeb1b840436391b835fd5513f4c4", "Furina (CN)",
                            "ec4875ed4e154ed09d1b501a2214579a", "Baal (CN)",
                            "bcbb6d60721c44a489bc33dd59ce7cfc", "Firefly (CN)",
                            "bca87f0aa93f4e85aee1e132ca6bd254", "Hina (CN)",
                            "b85f3ec7e48b4abfaa723d95c1cdaff5", "Kusanagi Nene (JP)",
                            "ac7df666cedb48fda3820bf404691c88", "Asahina Mafuyu (JP)",
                            "0b808d6e6c4a47999e50ffbbc47172c3", "Anegasaki Nene (JP)"));
        }

        @Override
        public Codec<FishAudioCompatTTSSite> codec() {
            return CODEC;
        }
    }
}
