package team.cfpa.touhoustepfun.compat.ai.stepfun.stt;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import team.cfpa.touhoustepfun.compat.ai.stepfun.StepFunShared;

public final class StepFunPlanSTTSite extends StepFunSTTSite {
    public static final String API_TYPE = "stepfun_plan";

    public StepFunPlanSTTSite(String id, ResourceLocation icon, boolean enabled, String url, String secretKey, String model) {
        super(id, icon, enabled, url, secretKey, model);
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    public static final class Serializer implements SerializableSite<StepFunPlanSTTSite> {
        private static final Codec<StepFunPlanSTTSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(StepFunPlanSTTSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(StepFunPlanSTTSite::icon),
                Codec.BOOL.fieldOf(ENABLED).forGetter(StepFunPlanSTTSite::enabled),
                Codec.STRING.fieldOf(URL).forGetter(StepFunPlanSTTSite::url),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(StepFunPlanSTTSite::getSecretKey),
                Codec.STRING.fieldOf("model").forGetter(StepFunPlanSTTSite::getModel)
        ).apply(instance, StepFunPlanSTTSite::new));

        @Override
        public Codec<StepFunPlanSTTSite> codec() {
            return CODEC;
        }

        @Override
        public StepFunPlanSTTSite defaultSite() {
            return new StepFunPlanSTTSite(
                    API_TYPE,
                    StepFunShared.ICON,
                    false,
                    "https://api.stepfun.com/step_plan/v1/audio/asr/sse",
                    StringUtils.EMPTY,
                    "stepaudio-2.5-asr"
            );
        }
    }
}
