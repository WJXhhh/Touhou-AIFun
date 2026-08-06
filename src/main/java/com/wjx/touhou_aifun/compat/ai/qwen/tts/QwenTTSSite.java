package com.wjx.touhou_aifun.compat.ai.qwen.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SupportModelSelect;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.TTSSiteFormLayout;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.qwen.QwenShared;
import com.wjx.touhou_aifun.compat.ai.qwen.layout.QwenTTSFormLayout;

import java.util.LinkedHashMap;
import java.util.Map;

public final class QwenTTSSite implements TTSSite, SupportModelSelect {
    public static final String API_TYPE = QwenShared.API_TYPE;
    private static final String DEFAULT_TTS_MODEL = "qwen3-tts-flash";

    private final String id;
    private final ResourceLocation icon;
    private final Map<String, String> headers;
    private final Map<String, String> models;

    private String url;
    private boolean enabled;
    private String secretKey;

    public QwenTTSSite(String id, ResourceLocation icon, String url, boolean enabled,
                       String secretKey, Map<String, String> headers, Map<String, String> models) {
        this.id = id;
        this.icon = icon;
        this.url = url;
        this.enabled = enabled;
        this.secretKey = secretKey;
        this.headers = headers;
        this.models = models;
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

    @Override
    public boolean enabled() {
        return enabled;
    }

    public String secretKey() {
        return secretKey;
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
        return new QwenTTSClient(TTS_HTTP_CLIENT, this);
    }

    @Override
    public TTSSiteFormLayout formLayout() {
        return new QwenTTSFormLayout(this);
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

    public static final class Serializer implements SerializableSite<QwenTTSSite> {
        private static final Codec<QwenTTSSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(QwenTTSSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(QwenTTSSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(QwenTTSSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(QwenTTSSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(QwenTTSSite::secretKey),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(QwenTTSSite::headers),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(MODELS).forGetter(QwenTTSSite::models)
        ).apply(instance, QwenTTSSite::new));

        @Override
        public QwenTTSSite defaultSite() {
            Map<String, String> models = new LinkedHashMap<>();
            addVoice(models, "Cherry");
            addVoice(models, "Ethan");
            addVoice(models, "Serena");
            addVoice(models, "Chelsie");
            return new QwenTTSSite(
                    API_TYPE,
                    SerializableSite.defaultIcon(API_TYPE),
                    QwenShared.TTS_URL,
                    false,
                    StringUtils.EMPTY,
                    Map.of(),
                    models
            );
        }

        @Override
        public Codec<QwenTTSSite> codec() {
            return CODEC;
        }

        private static void addVoice(Map<String, String> models, String voice) {
            models.put(DEFAULT_TTS_MODEL + ":" + voice, DEFAULT_TTS_MODEL + " / " + voice);
        }
    }
}
