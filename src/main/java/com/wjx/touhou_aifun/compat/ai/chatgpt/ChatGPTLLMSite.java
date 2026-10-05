package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wjx.touhou_aifun.compat.ai.openai.ChatGPTResponsesClient;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public site metadata only. OAuth credentials never enter site JSON or network packets. */
public final class ChatGPTLLMSite extends LLMOpenAISite {
    public static final String API_TYPE = "chatgpt_subscription";
    public static final String ENDPOINT = "https://api.openai.com/v1/responses";
    public static final ResourceLocation ICON = SerializableSite.defaultIcon("openai");
    private final Map<String, String> catalog;
    private volatile ChatGPTReasoningSettings reasoningSettings;
    private volatile boolean webSearch;
    private volatile boolean fastMode;
    private volatile String lastServiceTier = "unknown";

    public ChatGPTLLMSite(String id, boolean enabled, Map<String, String> catalog) {
        this(id, enabled, catalog, true, "default");
    }

    public ChatGPTLLMSite(String id, boolean enabled, Map<String, String> catalog, boolean summary, String effort) {
        this(id, enabled, catalog, summary, effort, true);
    }

    public ChatGPTLLMSite(String id, boolean enabled, Map<String, String> catalog, boolean summary, String effort, boolean webSearch) {
        this(id, enabled, catalog, summary, effort, webSearch, false);
    }

    public ChatGPTLLMSite(String id, boolean enabled, Map<String, String> catalog, boolean summary, String effort,
                          boolean webSearch, boolean fastMode) {
        super(id, ICON, ENDPOINT, enabled, "", false, Map.of(), entries(catalog));
        this.models.clear();
        this.models.putAll(catalog);
        this.catalog = new LinkedHashMap<>(catalog);
        this.reasoningSettings = new ChatGPTReasoningSettings(summary, effort);
        this.webSearch = webSearch;
        this.fastMode = fastMode;
    }

    private static Map<String, ModelEntry> entries(Map<String, String> catalog) {
        Map<String, ModelEntry> entries = new LinkedHashMap<>();
        catalog.forEach((slug, label) -> entries.put(slug, new ModelEntry(slug)));
        return entries;
    }

    @Override public String getApiType() { return API_TYPE; }
    @Override public LLMClient client() { return new ChatGPTResponsesClient(LLM_HTTP_CLIENT, this); }
    @Override public String url() { return ENDPOINT; }
    @Override public String secretKey() { return ""; }
    @Override public Map<String, String> headers() { return Map.of(); }
    @Override public Map<String, String> models() { return catalog; }
    public ChatGPTReasoningSettings reasoningSettings() { return reasoningSettings; }
    public void setReasoningSettings(ChatGPTReasoningSettings settings) { reasoningSettings = settings; }
    public boolean webSearch() { return webSearch; }
    public void setWebSearch(boolean enabled) { webSearch = enabled; }
    public boolean fastMode() { return fastMode; }
    public void setFastMode(boolean enabled) { fastMode = enabled; }
    public String lastServiceTier() { return lastServiceTier; }
    public void recordServiceTier(String tier) {
        lastServiceTier = normalizeServiceTier(tier);
    }
    public static String normalizeServiceTier(String tier) {
        return tier != null && java.util.Set.of("fast", "priority", "default", "flex", "ultrafast").contains(tier) ? tier : "unknown";
    }
    public ChatGPTLLMSite withModels(Map<String, String> models) {
        return new ChatGPTLLMSite(id(), enabled(), models, reasoningSettings.summary(), reasoningSettings.effort(), webSearch, fastMode);
    }

    public static final class Serializer implements SerializableSite<ChatGPTLLMSite> {
        private static final Codec<ChatGPTLLMSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf(ID).forGetter(ChatGPTLLMSite::id),
                Codec.BOOL.fieldOf(ENABLED).forGetter(ChatGPTLLMSite::enabled),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("model_catalog", Map.of())
                        .forGetter(ChatGPTLLMSite::models),
                Codec.BOOL.optionalFieldOf("reasoning_summary", true).forGetter(site -> site.reasoningSettings().summary()),
                Codec.STRING.optionalFieldOf("reasoning_effort", "default").forGetter(site -> site.reasoningSettings().effort()),
                Codec.BOOL.optionalFieldOf("web_search", true).forGetter(ChatGPTLLMSite::webSearch),
                Codec.BOOL.optionalFieldOf("fast_mode", false).forGetter(ChatGPTLLMSite::fastMode)
        ).apply(instance, ChatGPTLLMSite::new));

        @Override public ChatGPTLLMSite defaultSite() { return new ChatGPTLLMSite(API_TYPE, false, Map.of()); }
        @Override public Codec<ChatGPTLLMSite> codec() { return CODEC; }
    }
}
