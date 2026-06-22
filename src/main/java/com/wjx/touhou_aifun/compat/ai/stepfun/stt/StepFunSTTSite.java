package com.wjx.touhou_aifun.compat.ai.stepfun.stt;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.stt.STTSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.STTSiteFormLayout;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.stepfun.StepFunShared;
import com.wjx.touhou_aifun.compat.ai.stepfun.layout.StepFunSTTFormLayout;

import java.util.Map;

public class StepFunSTTSite implements STTSite {
    public static final String API_TYPE = StepFunShared.API_TYPE;

    private final String id;
    private final ResourceLocation icon;

    private boolean enabled;
    private String url;
    private String secretKey;
    private String model;

    public StepFunSTTSite(String id, ResourceLocation icon, boolean enabled, String url, String secretKey, String model) {
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
        return StepFunShared.ICON;
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
        return new StepFunSTTClient(STT_HTTP_CLIENT, this);
    }

    @Override
    public STTSiteFormLayout formLayout() {
        return new StepFunSTTFormLayout(this);
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

    public static final class Serializer implements SerializableSite<StepFunSTTSite> {
        private static final Codec<StepFunSTTSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(StepFunSTTSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(StepFunSTTSite::icon),
                Codec.BOOL.fieldOf(ENABLED).forGetter(StepFunSTTSite::enabled),
                Codec.STRING.fieldOf(URL).forGetter(StepFunSTTSite::url),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(StepFunSTTSite::getSecretKey),
                Codec.STRING.fieldOf("model").forGetter(StepFunSTTSite::getModel)
        ).apply(instance, StepFunSTTSite::new));

        @Override
        public Codec<StepFunSTTSite> codec() {
            return CODEC;
        }

        @Override
        public StepFunSTTSite defaultSite() {
            return new StepFunSTTSite(
                    API_TYPE,
                    StepFunShared.ICON,
                    false,
                    "https://api.stepfun.com/v1/audio/asr/sse",
                    StringUtils.EMPTY,
                    "stepaudio-2.5-asr"
            );
        }
    }
}
