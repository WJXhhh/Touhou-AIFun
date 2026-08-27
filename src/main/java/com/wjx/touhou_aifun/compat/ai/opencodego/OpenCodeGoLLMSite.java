package com.wjx.touhou_aifun.compat.ai.opencodego;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** OpenCode Go site with automatic OpenAI Chat Completions / Anthropic Messages routing. */
public final class OpenCodeGoLLMSite extends LLMOpenAISite {
    public static final String API_TYPE = OpenCodeGoShared.API_TYPE;

    public OpenCodeGoLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                             boolean hasThinkingField, Map<String, String> headers,
                             Map<String, ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    public OpenCodeGoLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                             boolean hasThinkingField, Map<String, String> headers,
                             List<ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    @Override
    public ResourceLocation icon() {
        return OpenCodeGoShared.ICON;
    }

    @Override
    public LLMClient client() {
        return new OpenCodeGoLLMClient(LLM_HTTP_CLIENT, this);
    }

    public static final class Serializer implements SerializableSite<OpenCodeGoLLMSite> {
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
                map -> new ArrayList<>(map.values())
        );

        private static final Codec<OpenCodeGoLLMSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(OpenCodeGoLLMSite::id),
                ResourceLocation.CODEC.fieldOf(Site.ICON).forGetter(OpenCodeGoLLMSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(OpenCodeGoLLMSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(OpenCodeGoLLMSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(OpenCodeGoLLMSite::secretKey),
                Codec.BOOL.optionalFieldOf(HAS_THINKING_FIELD, false).forGetter(OpenCodeGoLLMSite::hasThinkingField),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(OpenCodeGoLLMSite::headers),
                MODELS_CODEC.fieldOf(MODELS).forGetter(OpenCodeGoLLMSite::modelEntries)
        ).apply(instance, OpenCodeGoLLMSite::new));

        @Override
        public OpenCodeGoLLMSite defaultSite() {
            return new OpenCodeGoLLMSite(
                    API_TYPE, OpenCodeGoShared.ICON, OpenCodeGoShared.DEFAULT_URL, false,
                    StringUtils.EMPTY, false, Map.of(), List.of(
                    new ModelEntry("glm-5.3-flash"),
                    new ModelEntry("glm-5.3"),
                    new ModelEntry("glm-5.2"),
                    new ModelEntry("glm-5.1"),
                    new ModelEntry("kimi-k3"),
                    new ModelEntry("kimi-k2.7-code"),
                    new ModelEntry("kimi-k2.6"),
                    new ModelEntry("longcat-2.0"),
                    new ModelEntry("deepseek-v4-pro"),
                    new ModelEntry("deepseek-v4-flash"),
                    new ModelEntry("deepseek-v4-flash-vision-exp"),
                    new ModelEntry("mimo-v2.5-pro"),
                    new ModelEntry("mimo-v2.5"),
                    new ModelEntry("minimax-m3"),
                    new ModelEntry("minimax-m2.7"),
                    new ModelEntry("minimax-m2.5"),
                    new ModelEntry("qwen3.8-max"),
                    new ModelEntry("qwen3.7-max"),
                    new ModelEntry("qwen3.7-plus"),
                    new ModelEntry("qwen3.6-plus"),
                    new ModelEntry("hy3")
            ));
        }

        @Override
        public Codec<OpenCodeGoLLMSite> codec() {
            return CODEC;
        }
    }
}
