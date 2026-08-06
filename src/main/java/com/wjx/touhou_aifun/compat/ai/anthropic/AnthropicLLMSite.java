package com.wjx.touhou_aifun.compat.ai.anthropic;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import com.wjx.touhou_aifun.compat.ai.openai.AnthropicCompatLLMClient;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * LLM site speaking the Anthropic Messages protocol ({@code POST /v1/messages}).
 *
 * <p>Wire protocol and behaviour live in {@link AnthropicCompatLLMClient}; this class only
 * claims the {@code "anthropic"} API type and ships DeepSeek's Anthropic-compatible endpoint
 * as the default. Because the protocol differs from OpenAI chat-completions, {@link #client()}
 * is overridden and must never fall back to the inherited OpenAI client.
 */
public class AnthropicLLMSite extends LLMOpenAISite {
    public static final String API_TYPE = AnthropicShared.API_TYPE;

    public AnthropicLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                            boolean hasThinkingField, Map<String, String> headers, Map<String, ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    public AnthropicLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                            boolean hasThinkingField, Map<String, String> headers, List<ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    @Override
    public ResourceLocation icon() {
        return AnthropicShared.ICON;
    }

    @Override
    public LLMClient client() {
        return new AnthropicCompatLLMClient(LLM_HTTP_CLIENT, this);
    }

    public static final class Serializer implements SerializableSite<AnthropicLLMSite> {
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

        private static final Codec<AnthropicLLMSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(AnthropicLLMSite::id),
                ResourceLocation.CODEC.fieldOf(Site.ICON).forGetter(AnthropicLLMSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(AnthropicLLMSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(AnthropicLLMSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(AnthropicLLMSite::secretKey),
                Codec.BOOL.optionalFieldOf(HAS_THINKING_FIELD, false).forGetter(AnthropicLLMSite::hasThinkingField),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(AnthropicLLMSite::headers),
                MODELS_CODEC.fieldOf(MODELS).forGetter(AnthropicLLMSite::modelEntries)
        ).apply(instance, AnthropicLLMSite::new));

        @Override
        public AnthropicLLMSite defaultSite() {
            return new AnthropicLLMSite(
                    AnthropicShared.DEFAULT_SITE_ID,
                    AnthropicShared.ICON,
                    AnthropicShared.DEFAULT_URL,
                    false,
                    StringUtils.EMPTY,
                    true,
                    Map.of(),
                    List.of(
                            new ModelEntry(AnthropicShared.DEFAULT_MODEL),
                            new ModelEntry(AnthropicShared.DEFAULT_MODEL_PRO, true)
                    )
            );
        }

        @Override
        public Codec<AnthropicLLMSite> codec() {
            return CODEC;
        }
    }
}
