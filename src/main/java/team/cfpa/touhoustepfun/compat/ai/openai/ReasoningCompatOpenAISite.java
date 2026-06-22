package team.cfpa.touhoustepfun.compat.ai.openai;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.SupportModelSelect;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMApiType;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.google.common.collect.Lists;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import team.cfpa.touhoustepfun.compat.ai.stepfun.StepFunLLMClient;
import team.cfpa.touhoustepfun.compat.ai.stepfun.StepFunShared;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ReasoningCompatOpenAISite extends LLMOpenAISite implements SupportModelSelect {
    public static final String API_TYPE = LLMApiType.OPENAI.getName();

    public ReasoningCompatOpenAISite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                                     boolean hasThinkingField, Map<String, String> headers, Map<String, ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    public ReasoningCompatOpenAISite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                                     boolean hasThinkingField, Map<String, String> headers, List<ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    @Override
    public LLMClient client() {
        // Keep old user-created StepFun entries working after the dedicated API type was introduced.
        if (StepFunShared.isStepFunApi(this.url())) {
            return new StepFunLLMClient(LLM_HTTP_CLIENT, this);
        }
        return new ReasoningCompatOpenAIClient(LLM_HTTP_CLIENT, this);
    }

    public static final class Serializer implements SerializableSite<LLMOpenAISite> {
        private static final Codec<ModelEntry> MODEL_ENTRY_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(ModelEntry::name),
                Codec.BOOL.fieldOf("reasoning").forGetter(ModelEntry::isReasoning)
        ).apply(instance, ModelEntry::new));

        private static final Codec<ModelEntry> SINGLE_MODEL_CODEC = Codec.either(Codec.STRING, MODEL_ENTRY_CODEC).xmap(
                either -> either.map(ModelEntry::new, Function.identity()),
                entry -> entry.isReasoning() ? Either.right(entry) : Either.left(entry.name())
        );

        private static final Codec<Map<String, ModelEntry>> MODELS_CODEC = Codec.list(SINGLE_MODEL_CODEC).xmap(
                list -> list.stream().collect(Collectors.toMap(ModelEntry::name, Function.identity())),
                map -> Lists.newArrayList(map.values())
        );

        private static final Codec<LLMOpenAISite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(LLMOpenAISite::id),
                ResourceLocation.CODEC.fieldOf(ICON).forGetter(LLMOpenAISite::icon),
                Codec.STRING.fieldOf(URL).forGetter(LLMOpenAISite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(LLMOpenAISite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(LLMOpenAISite::secretKey),
                Codec.BOOL.optionalFieldOf(HAS_THINKING_FIELD, false).forGetter(LLMOpenAISite::hasThinkingField),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(LLMOpenAISite::headers),
                MODELS_CODEC.fieldOf(MODELS).forGetter(LLMOpenAISite::modelEntries)
        ).apply(instance, ReasoningCompatOpenAISite::new));

        @Override
        public LLMOpenAISite defaultSite() {
            return new ReasoningCompatOpenAISite(API_TYPE, SerializableSite.defaultIcon(API_TYPE),
                    "https://api.openai.com/v1/chat/completions", false,
                    StringUtils.EMPTY, false, Map.of(),
                    List.of(
                            new ModelEntry("gpt-4o"),
                            new ModelEntry("gpt-4.1"),
                            new ModelEntry("gpt-5-mini", true),
                            new ModelEntry("gpt-5.1", true),
                            new ModelEntry("gpt-5.2", true),
                            new ModelEntry("gpt-5.4", true)
                    ));
        }

        @Override
        public Codec<LLMOpenAISite> codec() {
            return CODEC;
        }
    }
}
