package com.wjx.touhou_aifun.compat.ai.mimo.stt;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.STTSiteFormLayout;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.mimo.MimoShared;
import com.wjx.touhou_aifun.compat.ai.mimo.layout.MimoSTTFormLayout;

import java.util.Map;

public class MimoSTTSite implements STTSite {
    public static final String API_TYPE = MimoShared.API_TYPE;

    private final String id;
    private final ResourceLocation icon;

    private boolean enabled;
    private String url;
    private String secretKey;
    private String model;

    public MimoSTTSite(String id, ResourceLocation icon, boolean enabled, String url, String secretKey, String model) {
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
        return MimoShared.ICON;
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
        return new MimoSTTClient(STT_HTTP_CLIENT, this);
    }

    @Override
    public STTSiteFormLayout formLayout() {
        return new MimoSTTFormLayout(this);
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

    public static final class Serializer implements SerializableSite<MimoSTTSite> {
        private static final Codec<MimoSTTSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(MimoSTTSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(MimoSTTSite::icon),
                Codec.BOOL.fieldOf(ENABLED).forGetter(MimoSTTSite::enabled),
                Codec.STRING.fieldOf(URL).forGetter(MimoSTTSite::url),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(MimoSTTSite::getSecretKey),
                Codec.STRING.fieldOf("model").forGetter(MimoSTTSite::getModel)
        ).apply(instance, MimoSTTSite::new));

        @Override
        public Codec<MimoSTTSite> codec() {
            return CODEC;
        }

        @Override
        public MimoSTTSite defaultSite() {
            return new MimoSTTSite(
                    API_TYPE,
                    MimoShared.ICON,
                    false,
                    "https://api.xiaomimimo.com/v1/chat/completions",
                    StringUtils.EMPTY,
                    "mimo-v2.5-asr"
            );
        }
    }
}
