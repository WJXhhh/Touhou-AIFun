package team.cfpa.touhoustepfun.compat.ai.stepfun;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;

public final class StepFunPlanLLMSite extends StepFunLLMSite {
    public static final String API_TYPE = "stepfun_plan";

    public StepFunPlanLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                              boolean hasThinkingField, Map<String, String> headers, Map<String, ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    public StepFunPlanLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                              boolean hasThinkingField, Map<String, String> headers, List<ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    public static final class Serializer implements SerializableSite<StepFunPlanLLMSite> {
        private static final Codec<ModelEntry> MODEL_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(ModelEntry::name),
                Codec.BOOL.fieldOf("reasoning").forGetter(ModelEntry::isReasoning)
        ).apply(instance, ModelEntry::new));

        private static final Codec<ModelEntry> SINGLE_MODEL_CODEC = Codec.either(Codec.STRING, MODEL_ENTRY_CODEC).xmap(
                either -> either.map(ModelEntry::new, java.util.function.Function.identity()),
                entry -> entry.isReasoning()
                        ? com.mojang.datafixers.util.Either.right(entry)
                        : com.mojang.datafixers.util.Either.left(entry.name())
        );

        private static final Codec<Map<String, ModelEntry>> MODELS_CODEC = Codec.list(SINGLE_MODEL_CODEC).xmap(
                list -> list.stream().collect(java.util.stream.Collectors.toMap(ModelEntry::name, java.util.function.Function.identity())),
                map -> new java.util.ArrayList<>(map.values())
        );

        private static final Codec<StepFunPlanLLMSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(StepFunPlanLLMSite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(StepFunPlanLLMSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(StepFunPlanLLMSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(StepFunPlanLLMSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(StepFunPlanLLMSite::secretKey),
                Codec.BOOL.optionalFieldOf(HAS_THINKING_FIELD, false).forGetter(StepFunPlanLLMSite::hasThinkingField),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(StepFunPlanLLMSite::headers),
                MODELS_CODEC.fieldOf(MODELS).forGetter(StepFunPlanLLMSite::modelEntries)
        ).apply(instance, StepFunPlanLLMSite::new));

        @Override
        public StepFunPlanLLMSite defaultSite() {
            return new StepFunPlanLLMSite(
                    API_TYPE,
                    StepFunShared.ICON,
                    "https://api.stepfun.com/step_plan/v1/chat/completions",
                    false,
                    StringUtils.EMPTY,
                    false,
                    Map.of(),
                    List.of(
                            new ModelEntry("step-3.7-flash", true),
                            new ModelEntry("step-3.5-flash-2603", true),
                            new ModelEntry("step-3.5-flash", false),
                            new ModelEntry("step-router-v1", false)
                    )
            );
        }

        @Override
        public Codec<StepFunPlanLLMSite> codec() {
            return CODEC;
        }
    }
}
