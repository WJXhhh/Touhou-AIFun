package com.wjx.touhou_aifun.compat.ai.qwen;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAISite;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Qwen LLM via the DashScope OpenAI-compatible endpoint.
 *
 * <p>This is a thin subclass of {@link ReasoningCompatOpenAISite}: the wire protocol is
 * plain OpenAI chat-completions, so the inherited {@link #client()} works unchanged and
 * switching between qwen text models never alters the request shape. The only reasons to
 * subclass are to claim the {@code "qwen"} API type and ship sensible defaults.
 */
public class QwenLLMSite extends ReasoningCompatOpenAISite {
    public static final String API_TYPE = QwenShared.API_TYPE;

    public QwenLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                       boolean hasThinkingField, Map<String, String> headers, Map<String, ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    public QwenLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                       boolean hasThinkingField, Map<String, String> headers, List<ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    public static final class Serializer implements SerializableSite<QwenLLMSite> {
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
                map -> new java.util.ArrayList<>(map.values())
        );

        private static final Codec<QwenLLMSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(QwenLLMSite::id),
                ResourceLocation.CODEC.fieldOf(Site.ICON).forGetter(LLMOpenAISite::icon),
                Codec.STRING.fieldOf(URL).forGetter(LLMOpenAISite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(LLMOpenAISite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(LLMOpenAISite::secretKey),
                Codec.BOOL.optionalFieldOf(HAS_THINKING_FIELD, false).forGetter(LLMOpenAISite::hasThinkingField),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(LLMOpenAISite::headers),
                MODELS_CODEC.fieldOf(MODELS).forGetter(LLMOpenAISite::modelEntries)
        ).apply(instance, QwenLLMSite::new));

        @Override
        public QwenLLMSite defaultSite() {
            return new QwenLLMSite(
                    API_TYPE,
                    SerializableSite.defaultIcon(API_TYPE),
                    QwenShared.COMPATIBLE_CHAT_URL,
                    false,
                    StringUtils.EMPTY,
                    false,
                    Map.of(),
                    List.of(
                            new ModelEntry("qwen-max"),
                            new ModelEntry("qwen-plus"),
                            new ModelEntry("qwen-flash"),
                            new ModelEntry("qwen-turbo"),
                            new ModelEntry("qwen3-max", true)
                    )
            );
        }

        @Override
        public Codec<QwenLLMSite> codec() {
            return CODEC;
        }
    }
}
