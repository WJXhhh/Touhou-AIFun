package com.wjx.touhou_aifun.compat.ai.opencodego;

import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Set;

/** Shared OpenCode Go provider metadata and model-to-protocol routing. */
public final class OpenCodeGoShared {
    public static final String API_TYPE = "opencode_go";
    public static final String DEFAULT_URL = "https://opencode.ai/zen/go";
    public static final String MUSE_SPARK_1_2_CONTRIBUTOR = "muse-spark-1.2-contributor";
    public static final ResourceLocation ICON = new ResourceLocation(
            "touhou_little_maid", "textures/gui/ai_chat/openrouter.png");

    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String RESPONSES_PATH = "/v1/responses";
    private static final String MODELS_PATH = "/v1/models";
    private static final Set<String> KNOWN_SUFFIXES = Set.of(
            CHAT_COMPLETIONS_PATH, MESSAGES_PATH, RESPONSES_PATH, MODELS_PATH);

    private OpenCodeGoShared() {
    }

    /** Required by Go on main and auxiliary calls. Identify this client honestly. */
    public static java.util.Map<String, String> requestHeaders(java.util.Map<String, String> configured, java.util.UUID conversation) {
        var result = new java.util.LinkedHashMap<>(configured);
        if (result.keySet().stream().noneMatch(key -> key.equalsIgnoreCase("x-opencode-session"))) {
            result.put("x-opencode-session", conversation == null ? java.util.UUID.randomUUID().toString() : conversation.toString());
        }
        if (result.keySet().stream().noneMatch(key -> key.equalsIgnoreCase("User-Agent"))) {
            result.put("User-Agent", "Touhou-AIFun/Minecraft-1.20.1");
        }
        return result;
    }

    /** OpenCode Go currently exposes MiniMax and Qwen models through Anthropic Messages. */
    public static boolean usesAnthropicMessages(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("minimax-") || normalized.startsWith("qwen");
    }

    /** Models documented by OpenCode Go as using the OpenAI Responses API. */
    public static boolean usesResponses(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("muse-spark-");
    }

    public static String chatCompletionsEndpoint(String configuredUrl) {
        return endpoint(configuredUrl, CHAT_COMPLETIONS_PATH);
    }

    public static String messagesEndpoint(String configuredUrl) {
        return endpoint(configuredUrl, MESSAGES_PATH);
    }

    public static String responsesEndpoint(String configuredUrl) {
        return endpoint(configuredUrl, RESPONSES_PATH);
    }

    static String endpoint(String configuredUrl, String path) {
        String base = configuredUrl == null ? "" : configuredUrl.trim().replaceAll("/+$", "");
        for (String suffix : KNOWN_SUFFIXES) {
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
                break;
            }
        }
        return base + path;
    }
}
