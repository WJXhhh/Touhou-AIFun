package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

/** A reference into the shared LLM catalog, never a second connection configuration. */
public record ModelRef(String siteId, String modelId) {
    public ModelRef {
        siteId = siteId == null ? "" : siteId.trim();
        modelId = modelId == null ? "" : modelId.trim();
    }

    public String encode() {
        JsonArray value = new JsonArray();
        value.add(siteId);
        value.add(modelId);
        return value.toString();
    }

    public static ModelRef decode(String value) {
        try {
            JsonArray array = JsonParser.parseString(value).getAsJsonArray();
            if (array.size() != 2) return null;
            ModelRef ref = new ModelRef(array.get(0).getAsString(), array.get(1).getAsString());
            return ref.siteId.isBlank() || ref.modelId.isBlank() ? null : ref;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
