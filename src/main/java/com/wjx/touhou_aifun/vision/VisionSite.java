package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.net.URI;

/** Legacy migration DTO and transient model view; live connections belong to the LLM catalog. */
public final class VisionSite {
    private final String id;
    private String displayName;
    private String provider;
    private String endpoint;
    private String model;
    private String apiKey;
    private boolean apiKeyPresent;
    private boolean thinking;
    private transient com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite source;
    private VisionCapabilityMode capability = VisionCapabilityMode.AUTO;
    private boolean imageSupported;
    private boolean enabled = true;
    private Boolean connectionAvailable;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public VisionSite(String id, String displayName, String provider, String endpoint, String model,
                      String apiKey, boolean thinking) {
        this.id = safe(id);
        this.displayName = safe(displayName);
        this.provider = safe(provider);
        this.endpoint = safe(endpoint);
        this.model = safe(model);
        this.apiKey = apiKey == null ? "" : apiKey;
        this.apiKeyPresent = !this.apiKey.isBlank();
        this.thinking = thinking;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String provider() { return provider; }
    public String endpoint() { return endpoint; }
    public String model() { return model; }
    public String apiKey() { return apiKey; }
    public boolean apiKeyPresent() { return apiKeyPresent || !apiKey.isBlank(); }
    public boolean thinking() { return thinking; }
    public com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite source() { return source; }
    public VisionCapabilityMode capability() { return capability; }
    public boolean imageSupported() { return imageSupported; }
    public boolean enabled() { return enabled; }
    public void setSource(com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite value) {
        source = value;
        enabled = value.enabled();
        apiKeyPresent = UnifiedModelCatalog.authAvailable(value);
    }
    public void setCapability(VisionCapabilityMode value, boolean supported) { capability = value; imageSupported = supported; }
    public Map<String, String> headers() { return Map.copyOf(headers); }

    public boolean hasValidHttpEndpoint() {
        if (connectionAvailable != null) return connectionAvailable;
        try {
            URI uri = URI.create(endpoint);
            return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                    || "http".equalsIgnoreCase(uri.getScheme()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public void setDisplayName(String value) { displayName = safe(value); }
    public void setProvider(String value) { provider = safe(value); }
    public void setEndpoint(String value) { endpoint = safe(value); }
    public void setModel(String value) { model = safe(value); }
    public void setApiKey(String value) {
        apiKey = value == null ? "" : value;
        apiKeyPresent = !apiKey.isBlank();
    }
    public void setThinking(boolean value) { thinking = value; }
    public void setHeader(String key, String value) {
        if (key != null && !key.isBlank()) headers.put(key, value == null ? "" : value);
    }

    public JsonObject toJson() {
        return toJson(true);
    }

    public JsonObject toJson(boolean includeApiKey) {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        object.addProperty("display_name", displayName);
        object.addProperty("provider", provider);
        if (includeApiKey) object.addProperty("endpoint", endpoint);
        else object.addProperty("connection_available", hasValidHttpEndpoint());
        object.addProperty("model", model);
        if (includeApiKey) {
            object.addProperty("api_key", apiKey);
        } else {
            object.addProperty("api_key_present", apiKeyPresent());
        }
        object.addProperty("thinking", thinking);
        object.addProperty("enabled", enabled);
        object.addProperty("image_capability", capability.name());
        object.addProperty("image_supported", imageSupported);
        JsonObject headerObject = new JsonObject();
        // Custom headers may contain Authorization, cookies or provider-specific tokens. They are
        // server secrets just like api_key and are never included in the client snapshot.
        if (includeApiKey) headers.forEach(headerObject::addProperty);
        object.add("headers", headerObject);
        return object;
    }

    public static VisionSite fromJson(JsonObject object) {
        String id = string(object, "id", "custom");
        VisionSite site = new VisionSite(id, string(object, "display_name", id),
                string(object, "provider", "custom"), string(object, "endpoint", ""),
                string(object, "model", ""), string(object, "api_key", ""),
                object.has("thinking") && object.get("thinking").getAsBoolean());
        if (object.has("api_key_present") && object.get("api_key_present").getAsBoolean()) {
            site.apiKeyPresent = true;
        }
        if (object.has("enabled")) site.enabled = object.get("enabled").getAsBoolean();
        if (object.has("connection_available")) site.connectionAvailable = object.get("connection_available").getAsBoolean();
        if (object.has("image_supported")) site.imageSupported = object.get("image_supported").getAsBoolean();
        try { if (object.has("image_capability")) site.capability = VisionCapabilityMode.valueOf(object.get("image_capability").getAsString()); }
        catch (IllegalArgumentException ignored) { }
        if (object.has("headers") && object.get("headers").isJsonObject()) {
            object.getAsJsonObject("headers").entrySet().forEach(entry -> site.setHeader(entry.getKey(),
                    entry.getValue().getAsString()));
        }
        return site;
    }

    private static String string(JsonObject object, String name, String fallback) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
