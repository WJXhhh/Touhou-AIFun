package com.wjx.touhou_aifun.compat.ai.mimo.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SupportModelSelect;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.TTSSiteFormLayout;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.mimo.MimoShared;
import com.wjx.touhou_aifun.compat.ai.mimo.layout.MimoTTSFormLayout;

import java.util.LinkedHashMap;
import java.util.Map;

public class MimoTTSSite implements TTSSite, SupportModelSelect {
    public static final String API_TYPE = MimoShared.API_TYPE;

    private final String id;
    private final ResourceLocation icon;
    private final Map<String, String> headers;
    private final Map<String, String> models;

    private String url;
    private boolean enabled;
    private String secretKey;
    private String voiceDesignPrompt;
    private String voiceCloneRefAudioPath;
    private String voiceCloneRefText;
    private String voiceCloneDataUrl;

    public MimoTTSSite(String id, ResourceLocation icon, String url, boolean enabled,
                       String secretKey, String voiceDesignPrompt, String voiceCloneRefAudioPath, String voiceCloneRefText,
                       String voiceCloneDataUrl, Map<String, String> headers, Map<String, String> models) {
        this.id = id;
        this.icon = icon;
        this.url = url;
        this.enabled = enabled;
        this.secretKey = secretKey;
        this.voiceDesignPrompt = voiceDesignPrompt;
        this.voiceCloneRefAudioPath = voiceCloneRefAudioPath;
        this.voiceCloneRefText = voiceCloneRefText;
        this.voiceCloneDataUrl = voiceCloneDataUrl;
        this.headers = headers;
        this.models = models;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ResourceLocation icon() {
        return MimoShared.ICON;
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    public String secretKey() {
        return secretKey;
    }

    public String voiceDesignPrompt() {
        return voiceDesignPrompt;
    }

    public String voiceCloneRefAudioPath() {
        return voiceCloneRefAudioPath;
    }

    public String voiceCloneRefText() {
        return voiceCloneRefText;
    }

    public String voiceCloneDataUrl() {
        return voiceCloneDataUrl;
    }

    @Override
    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public Map<String, String> models() {
        return models;
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    @Override
    public TTSClient client() {
        return new MimoTTSClient(TTS_HTTP_CLIENT, this);
    }

    @Override
    public TTSSiteFormLayout formLayout() {
        return new MimoTTSFormLayout(this);
    }

    public void setUrl(String url) {
        this.url = url;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public static final class Serializer implements SerializableSite<MimoTTSSite> {
        private static final String MODEL_MIMO_25_TTS = "mimo-v2.5-tts";
        private static final Codec<MimoTTSSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(MimoTTSSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(MimoTTSSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(MimoTTSSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(MimoTTSSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(MimoTTSSite::secretKey),
                Codec.STRING.optionalFieldOf("voice_design_prompt", StringUtils.EMPTY).forGetter(MimoTTSSite::voiceDesignPrompt),
                Codec.STRING.optionalFieldOf("voice_clone_ref_audio_path", StringUtils.EMPTY).forGetter(MimoTTSSite::voiceCloneRefAudioPath),
                Codec.STRING.optionalFieldOf("voice_clone_ref_text", StringUtils.EMPTY).forGetter(MimoTTSSite::voiceCloneRefText),
                Codec.STRING.optionalFieldOf("voice_clone_data_url", StringUtils.EMPTY).forGetter(MimoTTSSite::voiceCloneDataUrl),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(MimoTTSSite::headers),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(MODELS).forGetter(MimoTTSSite::models)
        ).apply(instance, MimoTTSSite::new));

        @Override
        public MimoTTSSite defaultSite() {
            Map<String, String> models = new LinkedHashMap<>();
            addVoice(models, "MiMo Default", "mimo_default", MODEL_MIMO_25_TTS);
            addVoice(models, "Default 中文", "default_zh", MODEL_MIMO_25_TTS);
            addVoice(models, "Default English", "default_en", MODEL_MIMO_25_TTS);
            addVoice(models, "冰糖", "冰糖", MODEL_MIMO_25_TTS);
            addVoice(models, "茉莉", "茉莉", MODEL_MIMO_25_TTS);
            addVoice(models, "苏打", "苏打", MODEL_MIMO_25_TTS);
            addVoice(models, "白桦", "白桦", MODEL_MIMO_25_TTS);
            addVoice(models, "Mia", "Mia", MODEL_MIMO_25_TTS);
            addVoice(models, "Chloe", "Chloe", MODEL_MIMO_25_TTS);
            addVoice(models, "Milo", "Milo", MODEL_MIMO_25_TTS);
            addVoice(models, "Dean", "Dean", MODEL_MIMO_25_TTS);

            return new MimoTTSSite(
                    API_TYPE,
                    MimoShared.ICON,
                    "https://api.xiaomimimo.com/v1/chat/completions",
                    false,
                    StringUtils.EMPTY,
                    StringUtils.EMPTY,
                    StringUtils.EMPTY,
                    StringUtils.EMPTY,
                    StringUtils.EMPTY,
                    Map.of(),
                    models
            );
        }

        @Override
        public Codec<MimoTTSSite> codec() {
            return CODEC;
        }

        private static void addVoice(Map<String, String> models, String displayName, String voiceId, String... supportedModels) {
            for (String model : supportedModels) {
                models.put(model + ":" + voiceId, model + " / " + displayName);
            }
        }
    }
}
