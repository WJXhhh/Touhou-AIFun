package com.wjx.touhou_aifun.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializableSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAISite;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Idempotent import. Marker is committed only after the complete LLM file round-trips. */
public final class LegacyVisionMigration {
    private LegacyVisionMigration() { }

    static String migrate(Path directory, Map<String, LLMSite> sites, ModelMetadataStore metadata,
                          String selected) throws Exception {
        if (metadata.migrated()) {
            return ModelRef.decode(selected) == null && !selected.isBlank() ? metadata.migratedSelection() : selected;
        }
        Path legacy = directory.resolve("vision.json");
        if (!Files.exists(legacy)) return selected;
        var parsed = JsonParser.parseString(Files.readString(legacy, StandardCharsets.UTF_8));
        JsonArray array = parsed.isJsonArray() ? parsed.getAsJsonArray() : parsed.getAsJsonObject().getAsJsonArray("sites");
        if (array == null) throw new IllegalArgumentException("Legacy visual sites are not an array");
        if (!Files.exists(directory.resolve("vision.json.pre-unification.bak"))) {
            Files.copy(legacy, directory.resolve("vision.json.pre-unification.bak"));
        }
        Path llm = directory.resolve("llm.json");
        if (Files.exists(llm) && !Files.exists(directory.resolve("llm.json.pre-unification.bak"))) {
            Files.copy(llm, directory.resolve("llm.json.pre-unification.bak"));
        }
        Map<String, LLMSite> imported = new LinkedHashMap<>(sites);
        String newSelection = ModelRef.decode(selected) == null ? "" : selected;
        String firstUsable = "";
        for (var element : array) {
            VisionSite old = VisionSite.fromJson(element.getAsJsonObject());
            if (old.id().isBlank() || old.model().isBlank()) continue;
            LLMSite matching = imported.values().stream().filter(site -> matches(site, old)).findFirst().orElse(null);
            if (matching == null) {
                String base = "vision_" + old.id();
                String id = base;
                for (int suffix = 2; imported.containsKey(id); suffix++) id = base + "_" + suffix;
                matching = new ReasoningCompatOpenAISite(id, SerializableSite.defaultIcon("openai"), old.endpoint(),
                        !old.apiKey().isBlank() && old.hasValidHttpEndpoint(), old.apiKey(), false, old.headers(),
                        List.of(new LLMOpenAISite.ModelEntry(old.model())));
                imported.put(id, matching);
            } else if (matching instanceof LLMOpenAISite openAI && !openAI.models().containsKey(old.model())) {
                // Copy rather than mutating the live catalog before persistence succeeds.
                var serializer = com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister.getLLMSerializer(matching.getApiType());
                var encoded = serializer.codec().encodeStart(com.mojang.serialization.JsonOps.INSTANCE, matching).getOrThrow(false, ignored -> { });
                var models = encoded.getAsJsonObject().getAsJsonArray("models");
                models.add(old.model());
                matching = serializer.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow(false, ignored -> { });
                imported.put(matching.id(), matching);
            }
            ModelRef ref = new ModelRef(matching.id(), old.model());
            var previous = metadata.get(ref);
            metadata.put(ref, new ModelMetadataStore.Metadata(previous.capability(), true, old.provider(), old.thinking(), old.displayName(),
                    UnifiedModelCatalog.connectionFingerprint(ref, matching)));
            if (firstUsable.isBlank() && matching.enabled()) firstUsable = ref.encode();
            if (old.id().equals(selected)) newSelection = ref.encode();
        }
        if (selected.isBlank()) newSelection = firstUsable;
        Path temporary = directory.resolve("llm.json.migration.tmp");
        LLMSite.writeSites(temporary, imported);
        Map<String, LLMSite> roundTrip = LLMSite.readSites(temporary);
        if (!roundTrip.keySet().equals(imported.keySet())) throw new IllegalStateException("Migrated LLM catalog failed round-trip validation");
        for (var entry : imported.entrySet()) {
            LLMSite restored = roundTrip.get(entry.getKey());
            if ((restored instanceof com.github.tartaricacid.touhoulittlemaid.ai.service.SupportModelSelect a
                        && entry.getValue() instanceof com.github.tartaricacid.touhoulittlemaid.ai.service.SupportModelSelect b
                        && !a.models().keySet().equals(b.models().keySet()))
                    || !restored.url().equals(entry.getValue().url())
                    || (restored instanceof LLMOpenAISite a && entry.getValue() instanceof LLMOpenAISite b
                        && (!a.secretKey().equals(b.secretKey()) || !a.headers().equals(b.headers())))) {
                throw new IllegalStateException("Migrated LLM connection failed validation: " + entry.getKey());
            }
        }
        ModelMetadataStore.moveIntoPlace(temporary, llm);
        metadata.markMigrated(newSelection);
        metadata.save(directory.resolve("aifun_model_metadata.json"));
        sites.clear();
        sites.putAll(imported);
        return newSelection;
    }

    static boolean matches(LLMSite site, VisionSite old) {
        return site instanceof LLMOpenAISite openAI
                && UnifiedModelCatalog.protocol(site, old.model()) == UnifiedModelCatalog.VisualProtocol.CHAT_COMPLETIONS
                && site.url().replaceAll("/+$", "").equals(old.endpoint().replaceAll("/+$", ""))
                && openAI.secretKey().equals(old.apiKey()) && openAI.headers().equals(old.headers());
    }
}
