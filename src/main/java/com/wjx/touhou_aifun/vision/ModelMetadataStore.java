package com.wjx.touhou_aifun.vision;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Addon metadata only. Connection parameters and model lists belong exclusively to llm.json. */
public final class ModelMetadataStore {
    public record Metadata(VisionCapabilityMode capability, Boolean providerSupportsImages,
                           String visualAdapter, boolean visualThinking, String displayName, String providerContext) {
        public Metadata(VisionCapabilityMode capability, Boolean providerSupportsImages, String visualAdapter,
                        boolean visualThinking, String displayName) {
            this(capability, providerSupportsImages, visualAdapter, visualThinking, displayName, "");
        }
        public static final Metadata DEFAULT = new Metadata(VisionCapabilityMode.AUTO, null, "", false, "");
        public Metadata withCapability(VisionCapabilityMode mode) {
            return new Metadata(mode, providerSupportsImages, visualAdapter, visualThinking, displayName, providerContext);
        }
    }

    private final Map<ModelRef, Metadata> entries = new LinkedHashMap<>();
    private boolean migrated;
    private String migratedSelection = "";

    public Metadata get(ModelRef ref) { return entries.getOrDefault(ref, Metadata.DEFAULT); }
    public void put(ModelRef ref, Metadata metadata) { entries.put(ref, metadata); }
    public boolean migrated() { return migrated; }
    public String migratedSelection() { return migratedSelection; }
    public void markMigrated(String selection) { migrated = true; migratedSelection = selection; }

    public static ModelMetadataStore read(Path path) throws IOException {
        ModelMetadataStore store = new ModelMetadataStore();
        if (!Files.exists(path)) return store;
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        store.migrated = root.has("vision_migrated") && root.get("vision_migrated").getAsBoolean();
        store.migratedSelection = string(root, "migrated_selection");
        if (root.has("models")) for (var entry : root.getAsJsonObject("models").entrySet()) {
            ModelRef ref = ModelRef.decode(entry.getKey());
            if (ref == null || !entry.getValue().isJsonObject()) continue;
            JsonObject object = entry.getValue().getAsJsonObject();
            VisionCapabilityMode mode;
            try { mode = VisionCapabilityMode.valueOf(string(object, "image_capability")); }
            catch (IllegalArgumentException ignored) { mode = VisionCapabilityMode.AUTO; }
            Boolean provider = object.has("provider_image_input") ? object.get("provider_image_input").getAsBoolean() : null;
            store.put(ref, new Metadata(mode, provider, string(object, "visual_adapter"),
                    object.has("visual_thinking") && object.get("visual_thinking").getAsBoolean(), string(object, "display_name"),
                    string(object, "provider_context")));
        }
        return store;
    }

    public void save(Path path) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.addProperty("vision_migrated", migrated);
        root.addProperty("migrated_selection", migratedSelection);
        JsonObject models = new JsonObject();
        entries.forEach((ref, data) -> {
            JsonObject object = new JsonObject();
            object.addProperty("image_capability", data.capability.name());
            if (data.providerSupportsImages != null) object.addProperty("provider_image_input", data.providerSupportsImages);
            if (!data.providerContext.isBlank()) object.addProperty("provider_context", data.providerContext);
            object.addProperty("visual_adapter", data.visualAdapter);
            object.addProperty("visual_thinking", data.visualThinking);
            object.addProperty("display_name", data.displayName);
            models.add(ref.encode(), object);
        });
        root.add("models", models);
        atomicWrite(path, new GsonBuilder().setPrettyPrinting().create().toJson(root));
    }

    static void atomicWrite(Path target, String contents) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, contents, StandardCharsets.UTF_8);
        moveIntoPlace(temporary, target);
    }

    static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }
}
