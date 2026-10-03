package com.wjx.touhou_aifun.vision;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.AvailableSites;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMSite;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.openai.LLMOpenAISite;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningCompatOpenAISite;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedModelCatalogTest {
    @Test void opencodeSessionAndClientIdentityApplyToBothRoutesWithoutReplacingCustomHeaders() {
        var conversation = UUID.randomUUID();
        var headers = com.wjx.touhou_aifun.compat.ai.opencodego.OpenCodeGoShared.requestHeaders(Map.of(), conversation);
        assertEquals(conversation.toString(), headers.get("x-opencode-session"));
        assertTrue(headers.get("User-Agent").startsWith("Touhou-AIFun/"));
        var configured = Map.of("X-OpenCode-Session", "configured-session", "User-Agent", "configured-client");
        assertEquals(configured, com.wjx.touhou_aifun.compat.ai.opencodego.OpenCodeGoShared.requestHeaders(configured, conversation));
    }
    private Map<String, LLMSite> saved;
    @BeforeEach void prepare() {
        saved = new LinkedHashMap<>(AvailableSites.LLM_SITES);
        AvailableSites.LLM_SITES.clear();
        UnifiedModelCatalog.clearSessionRejections();
    }
    @AfterEach void restore() {
        AvailableSites.LLM_SITES.clear(); AvailableSites.LLM_SITES.putAll(saved);
        UnifiedModelCatalog.clearSessionRejections();
    }
    private LLMOpenAISite site(String id, String endpoint, String model) {
        var site = new ReasoningCompatOpenAISite(id, new net.minecraft.resources.ResourceLocation("test", "icon"),
                endpoint, true, "test-secret", false, new HashMap<>(), List.of(new LLMOpenAISite.ModelEntry(model)));
        AvailableSites.LLM_SITES.put(id, site);
        return site;
    }
    @Test void removalOrDisablingInvalidatesExactReferenceWithoutChoosingAnotherModel() {
        var site = site("shared", "https://api.openai.com/v1/chat/completions", "gpt-4o");
        site.addModel("gpt-4.1");
        var ref = new ModelRef("shared", "gpt-4o");
        assertNotNull(AvailableVisionSites.chooseSelected(UnifiedModelCatalog.views(), ref.encode()));
        site.setEnabled(false);
        assertNull(AvailableVisionSites.chooseSelected(UnifiedModelCatalog.views(), ref.encode()));
        site.setEnabled(true); site.removeModel("gpt-4o");
        assertNull(UnifiedModelCatalog.site(ref));
        assertNull(AvailableVisionSites.chooseSelected(UnifiedModelCatalog.views(), ref.encode()));
        AvailableSites.LLM_SITES.clear();
        assertNull(UnifiedModelCatalog.site(new ModelRef("shared", "gpt-4.1")));
    }
    @Test void imageRejectionIsScopedToConnectionAndClearsWhenCredentialsOrHeadersChange() {
        var site = site("negative", "https://api.openai.com/v1/chat/completions", "gpt-4o");
        var ref = new ModelRef(site.id(), "gpt-4o");
        assertTrue(UnifiedModelCatalog.supportsImages(ref));
        UnifiedModelCatalog.rejectImages(ref);
        assertFalse(UnifiedModelCatalog.supportsImages(ref));
        site.setSecretKey("replacement-secret");
        assertTrue(UnifiedModelCatalog.supportsImages(ref));
        UnifiedModelCatalog.rejectImages(ref);
        site.headers().put("X-Token", "header-secret");
        assertTrue(UnifiedModelCatalog.supportsImages(ref));
        assertFalse(UnifiedModelCatalog.connectionFingerprint(ref, site).contains("secret"));
    }
    @Test void providerMetadataDoesNotFollowSameNamedModelToAnotherGateway() {
        var site = site("provider", "https://first.invalid/v1/chat/completions", "custom");
        var ref = new ModelRef(site.id(), "custom");
        UnifiedModelCatalog.providerCapability(ref, true);
        assertTrue(UnifiedModelCatalog.supportsImages(ref));
        site.setUrl("https://second.invalid/v1/chat/completions");
        assertFalse(UnifiedModelCatalog.supportsImages(ref));
    }
    @Test void malformedConnectionCannotBreakPublicCatalogAndResponsesUsesCorrectClient() {
        var invalid = site("invalid", "not a URL", "unknown");
        assertFalse(UnifiedModelCatalog.usable(new ModelRef(invalid.id(), "unknown")));
        assertEquals(1, UnifiedModelCatalog.views().size());
        var responses = site("responses", "https://api.openai.com/v1/responses", "gpt-4o");
        assertEquals(UnifiedModelCatalog.VisualProtocol.RESPONSES, UnifiedModelCatalog.protocol(responses, "gpt-4o"));
        assertInstanceOf(com.wjx.touhou_aifun.compat.ai.openai.OpenAIResponsesCompatLLMClient.class, responses.client());
    }
}
