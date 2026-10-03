package com.wjx.touhou_aifun.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.service.SerializerRegister;
import com.github.tartaricacid.touhoulittlemaid.ai.service.ServiceType;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAISite;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LegacyVisionMigrationTest {
    @TempDir Path directory;
    @BeforeEach void serializers() {
        SerializerRegister.LLM_SERIALIZER = new HashMap<>();
        new SerializerRegister().register(ServiceType.LLM, "openai", new ReasoningCompatOpenAISite.Serializer());
    }
    private LLMOpenAISite site(String id, String key) {
        return new ReasoningCompatOpenAISite(id, new net.minecraft.resources.ResourceLocation("touhou_little_maid", "textures/gui/ai_chat/openai.png"),
                "https://example.invalid/v1/chat/completions", true, key, false, Map.of("X-Custom", "header"), List.of(new LLMOpenAISite.ModelEntry("text")));
    }
    private void legacy() throws Exception {
        Files.writeString(directory.resolve("vision.json"), """
                [{"id":"custom","display_name":"Custom vision","provider":"custom",
                  "endpoint":"https://example.invalid/v1/chat/completions","model":"vision-model","api_key":"vision-secret",
                  "thinking":true,"headers":{"X-Custom":"header"}}]
                """);
    }
    @Test void matchingConnectionReusesSiteAndAddsModelWithoutChangingExistingModel() throws Exception {
        legacy();
        Map<String, LLMSite> sites = new LinkedHashMap<>(Map.of("existing", site("existing", "vision-secret")));
        var metadata = new ModelMetadataStore();
        String selected = LegacyVisionMigration.migrate(directory, sites, metadata, "custom");
        assertEquals(new ModelRef("existing", "vision-model"), ModelRef.decode(selected));
        assertEquals(1, sites.size());
        assertTrue(((LLMOpenAISite) sites.get("existing")).models().containsKey("text"));
        assertTrue(((LLMOpenAISite) sites.get("existing")).models().containsKey("vision-model"));
        assertTrue(metadata.get(ModelRef.decode(selected)).visualThinking());
        assertTrue(Files.exists(directory.resolve("vision.json.pre-unification.bak")));
        String again = LegacyVisionMigration.migrate(directory, sites, ModelMetadataStore.read(directory.resolve("aifun_model_metadata.json")), selected);
        assertEquals(selected, again);
        assertEquals(1, sites.size());
    }
    @Test void differentSecretAndOccupiedIdCreateSeparateSiteAndNeverOverwriteExistingSecret() throws Exception {
        legacy();
        Map<String, LLMSite> sites = new LinkedHashMap<>(Map.of("vision_custom", site("vision_custom", "chat-secret")));
        String selected = LegacyVisionMigration.migrate(directory, sites, new ModelMetadataStore(), "custom");
        assertEquals("vision_custom_2", ModelRef.decode(selected).siteId());
        assertEquals("chat-secret", ((LLMOpenAISite) sites.get("vision_custom")).secretKey());
        assertEquals("vision-secret", ((LLMOpenAISite) sites.get("vision_custom_2")).secretKey());
    }
    @Test void interruptedAfterLlmCommitBeforeMarkerIsIdempotentOnRetry() throws Exception {
        legacy();
        var metadata = new ModelMetadataStore();
        Map<String, LLMSite> sites = new LinkedHashMap<>();
        String selected = LegacyVisionMigration.migrate(directory, sites, metadata, "custom");
        Files.delete(directory.resolve("aifun_model_metadata.json"));
        var reloaded = new LinkedHashMap<>(LLMSite.readSites(directory.resolve("llm.json")));
        assertEquals(selected, LegacyVisionMigration.migrate(directory, reloaded, new ModelMetadataStore(), "custom"));
        assertEquals(1, reloaded.size());
    }
    @Test void invalidLegacyFileDoesNotChangeLiveSitesOrWriteCompletionMarker() throws Exception {
        Files.writeString(directory.resolve("vision.json"), "broken-json");
        Map<String, LLMSite> sites = new LinkedHashMap<>(Map.of("existing", site("existing", "secret")));
        assertThrows(Exception.class, () -> LegacyVisionMigration.migrate(directory, sites, new ModelMetadataStore(), "custom"));
        assertEquals(1, sites.size());
        assertFalse(Files.exists(directory.resolve("aifun_model_metadata.json")));
    }
}
