package team.cfpa.touhoustepfun.compat.ai.mimo;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.Site;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;
import team.cfpa.touhoustepfun.compat.ai.mimo.MimoShared;
import team.cfpa.touhoustepfun.compat.ai.openai.ReasoningCompatOpenAIClient;
import team.cfpa.touhoustepfun.compat.ai.openai.ReasoningCompatOpenAISite;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

public class MimoLLMSite extends ReasoningCompatOpenAISite {
    public static final String API_TYPE = MimoShared.API_TYPE;

    public MimoLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                       boolean hasThinkingField, Map<String, String> headers, Map<String, ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    public MimoLLMSite(String id, ResourceLocation icon, String url, boolean enabled, String secretKey,
                       boolean hasThinkingField, Map<String, String> headers, List<ModelEntry> modelEntries) {
        super(id, icon, url, enabled, secretKey, hasThinkingField, headers, modelEntries);
    }

    @Override
    public String getApiType() {
        return API_TYPE;
    }

    @Override
    public LLMClient client() {
        return new ReasoningCompatOpenAIClient(LLM_HTTP_CLIENT, this) {
            @Override
            public void chat(com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback callback) {
                String resolvedUrl = MimoEndpointResolver.resolve(MimoLLMSite.this.url(), MimoLLMSite.this.secretKey()).toString();
                String originalUrl = MimoLLMSite.this.url;
                try {
                    MimoLLMSite.this.url = resolvedUrl;
                    String apiKey = MimoShared.resolveSecretKey(MimoLLMSite.this.secretKey);
                    if (apiKey == null) {
                        callback.onFailure(null, new IllegalArgumentException("MiMo API key is empty"), com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode.REQUEST_SENDING_ERROR);
                        return;
                    }
                    MimoLLMSite.this.secretKey = apiKey;
                    super.chat(callback);
                } finally {
                    MimoLLMSite.this.url = originalUrl;
                }
            }

            @Override
            protected void handle(com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback callback,
                                  HttpResponse<String> response, Throwable throwable, HttpRequest request) {
                if (response != null && response.statusCode() == 401) {
                    String body = response.body();
                    String detail = body != null && body.length() > 200 ? body.substring(0, 200) : body;
                    callback.onFailure(request,
                            new IllegalArgumentException("MiMo API authentication failed (HTTP 401). Please check your API key. Detail: " + detail),
                            com.github.tartaricacid.touhoulittlemaid.ai.service.ErrorCode.REQUEST_RECEIVED_ERROR);
                    return;
                }
                super.handle(callback, response, throwable, request);
            }
        };
    }

    public static final class Serializer implements SerializableSite<MimoLLMSite> {
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

        private static final Codec<MimoLLMSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(MimoLLMSite::id),
                ResourceLocation.CODEC.fieldOf(Site.ICON).forGetter(MimoLLMSite::icon),
                Codec.STRING.fieldOf(URL).forGetter(MimoLLMSite::url),
                Codec.BOOL.fieldOf(ENABLED).forGetter(MimoLLMSite::enabled),
                Codec.STRING.fieldOf(SECRET_KEY).forGetter(MimoLLMSite::secretKey),
                Codec.BOOL.optionalFieldOf(HAS_THINKING_FIELD, false).forGetter(MimoLLMSite::hasThinkingField),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).fieldOf(HEADERS).forGetter(MimoLLMSite::headers),
                MODELS_CODEC.fieldOf(MODELS).forGetter(MimoLLMSite::modelEntries)
        ).apply(instance, MimoLLMSite::new));

        @Override
        public MimoLLMSite defaultSite() {
            return new MimoLLMSite(
                    API_TYPE,
                    MimoShared.ICON,
                    "https://api.xiaomimimo.com/v1/chat/completions",
                    false,
                    StringUtils.EMPTY,
                    true,
                    Map.of(),
                    List.of(
                            new ModelEntry("mimo-v2.5-pro", true),
                            new ModelEntry("mimo-v2.5", true)
                    )
            );
        }

        @Override
        public Codec<MimoLLMSite> codec() {
            return CODEC;
        }
    }
}
