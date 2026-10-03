package com.wjx.touhou_aifun.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.wjx.touhou_aifun.compat.ai.chatgpt.ChatGPTLLMSite;
import com.wjx.touhou_aifun.compat.ai.opencodego.OpenCodeGoShared;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves both chat and vision against TLM's live server-owned LLM catalog. */
public final class UnifiedModelCatalog {
    private static ModelMetadataStore metadata = new ModelMetadataStore();
    private static final Set<String> REJECTED_IMAGES = ConcurrentHashMap.newKeySet();
    private static boolean initialized;
    private UnifiedModelCatalog() { }

    static Path directory() { return FMLPaths.CONFIGDIR.get().resolve("touhou_little_maid/sites"); }
    public static String loadStatus() { return initialized ? "" : "migration_failed"; }

    /** Must run after AvailableSites.readSites, not in the addon constructor. */
    public static synchronized void initialize() {
        try {
            metadata = ModelMetadataStore.read(directory().resolve("aifun_model_metadata.json"));
            String selection = LegacyVisionMigration.migrate(directory(), AvailableSites.LLM_SITES, metadata,
                    TouhouAIFunConfig.VISION_SELECTED_SITE.get());
            if (!selection.equals(TouhouAIFunConfig.VISION_SELECTED_SITE.get())) {
                TouhouAIFunConfig.setSelectedVisionSite(selection);
            }
            initialized = true;
            REJECTED_IMAGES.clear();
        } catch (Exception exception) {
            initialized = false;
            com.wjx.touhou_aifun.TouhouAIFun.LOGGER.error("Unable to migrate/load shared model metadata; legacy files retained", exception);
        }
    }

    public static LLMSite site(ModelRef ref) {
        if (ref == null) return null;
        LLMSite site = AvailableSites.LLM_SITES.get(ref.siteId());
        return site instanceof LLMOpenAISite openAI && openAI.models().containsKey(ref.modelId()) ? site : null;
    }

    public static synchronized ModelMetadataStore.Metadata metadata(ModelRef ref) {
        var data = metadata.get(ref);
        if (!data.providerContext().isBlank() && !data.providerContext().equals(connectionFingerprint(ref, site(ref)))) {
            return new ModelMetadataStore.Metadata(data.capability(), null, data.visualAdapter(), data.visualThinking(), data.displayName(), data.providerContext());
        }
        return data;
    }

    public static synchronized boolean setCapability(ModelRef ref, VisionCapabilityMode mode) {
        if (!initialized || site(ref) == null) return false;
        var old = metadata.get(ref);
        metadata.put(ref, old.withCapability(mode));
        try {
            metadata.save(directory().resolve("aifun_model_metadata.json"));
            REJECTED_IMAGES.clear();
            return true;
        } catch (Exception exception) {
            metadata.put(ref, old);
            com.wjx.touhou_aifun.TouhouAIFun.LOGGER.error("Unable to save model image capability", exception);
            return false;
        }
    }

    /** Can also be populated by a provider catalog that explicitly reports input modalities. */
    public static synchronized void providerCapability(ModelRef ref, boolean supported) {
        var old = metadata.get(ref);
        metadata.put(ref, new ModelMetadataStore.Metadata(old.capability(), supported,
                old.visualAdapter(), old.visualThinking(), old.displayName(), connectionFingerprint(ref, site(ref))));
    }

    public static synchronized void recordProviderCapabilities(String siteId, Map<String, Boolean> capabilities) {
        capabilities.forEach((model, supported) -> {
            var ref = new ModelRef(siteId, model);
            if (site(ref) != null) providerCapability(ref, supported);
        });
        if (!initialized || capabilities.isEmpty()) return;
        try { metadata.save(directory().resolve("aifun_model_metadata.json")); }
        catch (Exception error) { com.wjx.touhou_aifun.TouhouAIFun.LOGGER.warn("Unable to persist provider image modalities", error); }
    }

    public static boolean supportsImages(ModelRef ref) {
        LLMSite site = site(ref);
        return site instanceof LLMOpenAISite && ModelImageCapabilities.supports(metadata(ref), site.getApiType(), site.url(), ref.modelId())
                && !REJECTED_IMAGES.contains(fingerprint(ref));
    }

    public static boolean usable(ModelRef ref) {
        LLMSite site = site(ref);
        if (!(site instanceof LLMOpenAISite openAI) || !site.enabled()) return false;
        if (!authAvailable(site)) return false;
        try {
            var uri = java.net.URI.create(site.url());
            return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (RuntimeException ignored) { return false; }
    }

    public static void rejectImages(ModelRef ref) { REJECTED_IMAGES.add(fingerprint(ref)); }
    static void rejectImages(String connection) { REJECTED_IMAGES.add(connection); }
    public static void clearSessionRejections() { REJECTED_IMAGES.clear(); }

    static String fingerprint(ModelRef ref) {
        LLMSite site = site(ref);
        return connectionFingerprint(ref, site) + "|" + metadata(ref).capability();
    }

    static String connectionFingerprint(ModelRef ref, LLMSite site) {
        if (!(site instanceof LLMOpenAISite openAI) || ref == null) return "";
        // A digest scopes capability observations to this connection without retaining credentials.
        var data = new com.google.gson.JsonArray();
        data.add(ref.encode()); data.add(site.getApiType()); data.add(site.url()); data.add(openAI.secretKey()); data.add(openAI.hasThinkingField());
        if (site instanceof ChatGPTLLMSite) data.add(com.wjx.touhou_aifun.compat.ai.chatgpt.ChatGPTSession.capabilityContext());
        var headers = new com.google.gson.JsonObject();
        new TreeMap<>(openAI.headers()).forEach(headers::addProperty); data.add(headers);
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static boolean authAvailable(LLMSite site) {
        if (site instanceof ChatGPTLLMSite) {
            return com.wjx.touhou_aifun.compat.ai.chatgpt.ChatGPTSession.authenticationAvailable();
        }
        if (!(site instanceof LLMOpenAISite openAI)) return false;
        if (!openAI.secretKey().isBlank() || openAI.headers().entrySet().stream().anyMatch(entry ->
                !entry.getValue().isBlank() && (entry.getKey().equalsIgnoreCase("Authorization")
                || entry.getKey().toLowerCase(Locale.ROOT).matches("cookie|.*(?:key|token|secret).*")))) return true;
        try {
            String host = java.net.URI.create(site.url()).getHost();
            return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        } catch (RuntimeException ignored) { return false; }
    }

    public static VisualProtocol protocol(LLMSite site, String model) {
        if (site instanceof ChatGPTLLMSite) return VisualProtocol.SUBSCRIPTION;
        if ("anthropic".equals(site.getApiType())) return VisualProtocol.ANTHROPIC;
        if (OpenCodeGoShared.API_TYPE.equals(site.getApiType())) {
            if (OpenCodeGoShared.usesResponses(model)) return VisualProtocol.RESPONSES;
            if (OpenCodeGoShared.usesAnthropicMessages(model)) return VisualProtocol.ANTHROPIC;
        }
        if (site.url().replaceAll("/+$", "").endsWith("/responses")) return VisualProtocol.RESPONSES;
        return VisualProtocol.CHAT_COMPLETIONS;
    }

    static Map<String, String> requestHeaders(VisionSite view, java.util.UUID maid) {
        return view.source() != null && OpenCodeGoShared.API_TYPE.equals(view.source().getApiType())
                ? OpenCodeGoShared.requestHeaders(view.headers(), maid) : view.headers();
    }

    public enum VisualProtocol { CHAT_COMPLETIONS, ANTHROPIC, RESPONSES, SUBSCRIPTION }

    public static String endpoint(LLMSite site, String model) {
        var protocol = protocol(site, model);
        if (OpenCodeGoShared.API_TYPE.equals(site.getApiType())) {
            return switch (protocol) {
                case ANTHROPIC -> OpenCodeGoShared.messagesEndpoint(site.url());
                case RESPONSES -> OpenCodeGoShared.responsesEndpoint(site.url());
                default -> OpenCodeGoShared.chatCompletionsEndpoint(site.url());
            };
        }
        if (protocol == VisualProtocol.ANTHROPIC) {
            String base = site.url().replaceAll("/+$", "");
            return base.endsWith("/v1/messages") ? base : base + "/v1/messages";
        }
        // MiMo and StepFun clients may resolve plan credentials to their dedicated endpoint.
        if (site.getApiType().startsWith("mimo")) {
            return com.wjx.touhou_aifun.compat.ai.mimo.MimoEndpointResolver.resolve(site.url(), ((LLMOpenAISite) site).secretKey()).toString();
        }
        return site.url();
    }

    public static List<VisionSite> views() {
        List<VisionSite> result = new ArrayList<>();
        AvailableSites.LLM_SITES.forEach((id, site) -> {
            if (!(site instanceof LLMOpenAISite openAI)) return;
            openAI.models().forEach((model, label) -> {
                ModelRef ref = new ModelRef(id, model);
                var data = metadata(ref);
                String provider = data.visualAdapter().isBlank() ? provider(site) : data.visualAdapter();
                String display = data.displayName().isBlank() ? id : data.displayName();
                String target;
                try { target = endpoint(site, model); }
                catch (RuntimeException ignored) { target = ""; }
                VisionSite view = new VisionSite(ref.encode(), display + " / " + label, provider,
                        target, model, openAI.secretKey(), data.visualThinking());
                openAI.headers().forEach(view::setHeader);
                view.setSource(site);
                view.setCapability(data.capability(), supportsImages(ref));
                result.add(view);
            });
        });
        return List.copyOf(result);
    }

    private static String provider(LLMSite site) {
        String host;
        try { host = java.net.URI.create(site.url()).getHost(); }
        catch (RuntimeException ignored) { return site.getApiType(); }
        if ("dashscope.aliyuncs.com".equals(host) || "dashscope-intl.aliyuncs.com".equals(host)) return "qwen";
        if ("open.bigmodel.cn".equals(host)) return "zhipu";
        if ("token.sensenova.cn".equals(host)) return "sensenova";
        return site.getApiType();
    }
}
