package com.wjx.touhou_aifun.compat.ai.qwen.stt;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.STTSiteFormLayout;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.qwen.QwenShared;
import com.wjx.touhou_aifun.compat.ai.qwen.layout.QwenSTTFormLayout;

import java.util.Map;

public class QwenSTTSite implements STTSite {
    public static final String API_TYPE = QwenShared.API_TYPE;

    private final String id;
    private final ResourceLocation icon;

    private boolean enabled;
    private String url;
    private String secretKey;
    private String model;

    public QwenSTTSite(String id, ResourceLocation icon, boolean enabled, String url, String secretKey, String model) {
        this.id = id;
        this.icon = icon;
        this.enabled = enabled;
        this.url = url;
        this.secretKey = secretKey;
        this.model = model;
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
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public Map<String, String> headers() {
        return Map.of();
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getModel() {
        return model;
    }

    @Override
    public STTClient client() {
        return new QwenSTTClient(STT_HTTP_CLIENT, this);
    }

    @Override
    public STTSiteFormLayout formLayout() {
        return new QwenSTTFormLayout(this);
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public static final class Serializer implements SerializableSite<QwenSTTSite> {
        private static final Codec<QwenSTTSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(QwenSTTSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(QwenSTTSite::icon),
                Codec.BOOL.fieldOf(ENABLED).forGetter(QwenSTTSite::enabled),
                Codec.STRING.fieldOf(URL).forGetter(QwenSTTSite::url),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(QwenSTTSite::getSecretKey),
                Codec.STRING.fieldOf("model").forGetter(QwenSTTSite::getModel)
        ).apply(instance, QwenSTTSite::new));

        @Override
        public Codec<QwenSTTSite> codec() {
            return CODEC;
        }

        @Override
        public QwenSTTSite defaultSite() {
            return new QwenSTTSite(
                    API_TYPE,
                    SerializableSite.defaultIcon(API_TYPE),
                    false,
                    QwenShared.COMPATIBLE_CHAT_URL,
                    StringUtils.EMPTY,
                    "qwen3-asr-flash"
            );
        }
    }
}
