package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** A user-configurable visual model endpoint, independent from the base mod's ServiceType enum. */
public final class VisionSite {
    private final String id;
    private String displayName;
    private String provider;
    private String endpoint;
    private String model;
    private boolean enabled;
    private String apiKey;
    private boolean thinking;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public VisionSite(String id, String displayName, String provider, String endpoint, String model,
                      boolean enabled, String apiKey, boolean thinking) {
        this.id = safe(id);
        this.displayName = safe(displayName);
        this.provider = safe(provider);
        this.endpoint = safe(endpoint);
        this.model = safe(model);
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.thinking = thinking;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String provider() { return provider; }
    public String endpoint() { return endpoint; }
    public String model() { return model; }
    public boolean enabled() { return enabled; }
    public String apiKey() { return apiKey; }
    public boolean thinking() { return thinking; }
    public Map<String, String> headers() { return Map.copyOf(headers); }

    public void setDisplayName(String value) { displayName = safe(value); }
    public void setProvider(String value) { provider = safe(value); }
    public void setEndpoint(String value) { endpoint = safe(value); }
    public void setModel(String value) { model = safe(value); }
    public void setEnabled(boolean value) { enabled = value; }
    public void setApiKey(String value) { apiKey = value == null ? "" : value; }
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
        object.addProperty("endpoint", endpoint);
        object.addProperty("model", model);
        object.addProperty("enabled", enabled);
        if (includeApiKey) {
            object.addProperty("api_key", apiKey);
        } else if (!apiKey.isBlank()) {
            object.addProperty("api_key_present", true);
        }
        object.addProperty("thinking", thinking);
        JsonObject headerObject = new JsonObject();
        headers.forEach(headerObject::addProperty);
        object.add("headers", headerObject);
        return object;
    }

    public static VisionSite fromJson(JsonObject object) {
        String id = string(object, "id", "custom");
        VisionSite site = new VisionSite(id, string(object, "display_name", id),
                string(object, "provider", "custom"), string(object, "endpoint", ""),
                string(object, "model", ""), object.has("enabled") && object.get("enabled").getAsBoolean(),
                string(object, "api_key", ""), object.has("thinking") && object.get("thinking").getAsBoolean());
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
