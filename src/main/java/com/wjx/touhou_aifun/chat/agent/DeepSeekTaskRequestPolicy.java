package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.JsonObject;
import java.net.URI;
import java.util.Set;

/** Only known official DeepSeek models accept this provider-specific wire policy. */
public final class DeepSeekTaskRequestPolicy {
    private DeepSeekTaskRequestPolicy() { }
    public static void apply(JsonObject body, String endpoint, String model, boolean background, boolean anthropic, String effort) {
        if (!background || !Set.of("low", "high", "max").contains(effort)
                || !(model.equals("deepseek-flash") || model.startsWith("deepseek-v4"))) return;
        try { if (!"api.deepseek.com".equalsIgnoreCase(URI.create(endpoint).getHost())) return; }
        catch (IllegalArgumentException invalid) { return; }
        if (body.has("thinking") && body.getAsJsonObject("thinking").has("type")
                && "disabled".equals(body.getAsJsonObject("thinking").get("type").getAsString())) return;
        if (anthropic) {
            JsonObject config = body.has("output_config") ? body.getAsJsonObject("output_config") : new JsonObject();
            config.addProperty("effort", effort); body.add("output_config", config);
        } else body.addProperty("reasoning_effort", effort);
    }
}
