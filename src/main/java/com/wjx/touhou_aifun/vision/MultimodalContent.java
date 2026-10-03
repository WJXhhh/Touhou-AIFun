package com.wjx.touhou_aifun.vision;

import com.google.gson.*;
import java.util.Map;

/** Common face labels with protocol-specific image blocks. Never serialize images into history. */
public final class MultimodalContent {
    public static final String[] FACES = {"front", "right", "back", "left", "up", "down"};
    public static final String[] IMAGE_LABELS = {"front", "right", "back", "left", "up", "down", "gui"};
    private MultimodalContent() { }

    public static JsonArray content(UnifiedModelCatalog.VisualProtocol protocol, String prompt, Map<String, String> images) {
        JsonArray result = new JsonArray();
        result.add(text(protocol, prompt));
        for (String face : IMAGE_LABELS) {
            String data = images.get(face);
            if (data == null || data.isBlank()) continue;
            result.add(text(protocol, face.equals("gui") ? "GUI screenshot" : "Cubemap face: " + face));
            JsonObject image = new JsonObject();
            if (protocol == UnifiedModelCatalog.VisualProtocol.ANTHROPIC) {
                image.addProperty("type", "image");
                JsonObject source = new JsonObject();
                int comma = data.indexOf(',');
                if (!data.startsWith("data:image/jpeg;base64,") || comma < 0) throw new IllegalArgumentException("Expected JPEG data URL");
                source.addProperty("type", "base64");
                source.addProperty("media_type", "image/jpeg");
                source.addProperty("data", data.substring(comma + 1));
                image.add("source", source);
            } else if (protocol == UnifiedModelCatalog.VisualProtocol.CHAT_COMPLETIONS) {
                image.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", data);
                image.add("image_url", url);
            } else {
                image.addProperty("type", "input_image");
                image.addProperty("image_url", data);
            }
            result.add(image);
        }
        return result;
    }

    private static JsonObject text(UnifiedModelCatalog.VisualProtocol protocol, String value) {
        JsonObject block = new JsonObject();
        block.addProperty("type", protocol == UnifiedModelCatalog.VisualProtocol.RESPONSES
                || protocol == UnifiedModelCatalog.VisualProtocol.SUBSCRIPTION ? "input_text" : "text");
        block.addProperty("text", value);
        return block;
    }

    public static JsonObject user(UnifiedModelCatalog.VisualProtocol protocol, String prompt, Map<String, String> images) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.add("content", content(protocol, prompt, images));
        return message;
    }

    public static JsonObject redacted(JsonObject body) {
        JsonObject copy = body.deepCopy();
        redact(copy);
        return copy;
    }
    private static void redact(JsonElement value) {
        if (value.isJsonArray()) { for (JsonElement entry : value.getAsJsonArray()) redact(entry); }
        else if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            for (var entry : object.entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()
                        && (entry.getValue().getAsString().startsWith("data:image/") || ("data".equals(entry.getKey()) && object.has("media_type")))) {
                    entry.setValue(new JsonPrimitive("[in-memory image omitted]"));
                } else redact(entry.getValue());
            }
        }
    }
}
