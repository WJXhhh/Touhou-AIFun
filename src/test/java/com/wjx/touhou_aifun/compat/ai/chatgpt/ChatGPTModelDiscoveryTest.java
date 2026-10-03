package com.wjx.touhou_aifun.compat.ai.chatgpt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChatGPTModelDiscoveryTest {
    @Test void preservesExplicitImageCapabilitiesWithoutInventingThemForProbedModels() throws Exception {
        var result = ChatGPTModelDiscovery.discover(json("""
                {"models":[{"slug":"image-model","visibility":"list","input_modalities":["text","image"]},
                {"slug":"text-model","visibility":"list","input_modalities":["text"]}]}
                """), model -> new ChatGPTModelDiscovery.Check(true, "verified"));
        assertEquals(java.util.Map.of("image-model", true, "text-model", false), result.imageCapabilities());
        assertFalse(result.imageCapabilities().containsKey("gpt-6.1-sol"));
    }
    @Test void preservesServerCatalogAndOnlyAddsVerifiedMissingModels() throws Exception {
        List<String> probed = new ArrayList<>();
        var result = ChatGPTModelDiscovery.discover(json("""
                {"models":[{"slug":"gpt-6-astra","display_name":"Astra","visibility":"list"},
                {"slug":"gpt-6-sol","display_name":"Server Sol","visibility":"list"},
                {"slug":"internal-model","visibility":"hide"}]}
                """), model -> {
            probed.add(model);
            return new ChatGPTModelDiscovery.Check(model.equals("gpt-6.1-sol"), "probe result");
        });
        assertEquals(List.of("gpt-6-astra", "gpt-6-sol", "gpt-6.1-sol"), new ArrayList<>(result.models().keySet()));
        assertEquals("Server Sol", result.models().get("gpt-6-sol"));
        assertEquals(List.of("gpt-6.1-sol", "gpt-6-luna"), probed);
        assertFalse(result.models().containsKey("internal-model"));
        assertFalse(result.models().containsKey("gpt-6-luna"));
        assertFalse(result.checks().get("gpt-6-luna").usable());
    }

    @Test void aProbeFailureDoesNotDiscardOfficialOrOtherVerifiedModels() throws Exception {
        var result = ChatGPTModelDiscovery.discover(json("{\"models\":[{\"slug\":\"official\",\"visibility\":\"list\"}]}"), model -> {
            if (model.equals("gpt-6.1-sol")) throw new IllegalStateException("HTTP 403 (unsupported_model)");
            return new ChatGPTModelDiscovery.Check(true, "success");
        });
        assertTrue(result.models().containsKey("official"));
        assertEquals(3, result.models().size());
        assertTrue(result.checks().get("gpt-6.1-sol").detail().contains("403"));
    }

    @Test void emptyOfficialCatalogCanStillDiscoverUsableModels() throws Exception {
        var result = ChatGPTModelDiscovery.discover(json("{\"models\":[]}"), model -> new ChatGPTModelDiscovery.Check(true, "success"));
        assertEquals(3, result.models().size());
    }

    @Test void streamedTextAloneOrHttp200CannotAuthorizeAddition() {
        String partial = frame("{\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}");
        assertFalse(ChatGPTModelDiscovery.inspect(200, partial).usable());
        assertFalse(ChatGPTModelDiscovery.inspect(200, partial + frame("{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"usage_limit\"}}}")).usable());
        assertFalse(ChatGPTModelDiscovery.inspect(200, partial + frame("{\"type\":\"response.incomplete\"}")).usable());
        var result = ChatGPTModelDiscovery.inspect(200, partial + frame("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}"));
        assertTrue(result.usable());
    }

    @Test void terminalTextIsAcceptedWithoutDeltasAndWithoutTrailingBlankLine() {
        var result = ChatGPTModelDiscovery.inspect(200, "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"OK\"}]}]}}");
        assertTrue(result.usable());
        assertFalse(ChatGPTModelDiscovery.inspect(200, frame("{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}")).usable());
    }

    @Test void onlyErrorCodesReachDiagnostics() {
        var result = ChatGPTModelDiscovery.inspect(404, "{\"error\":{\"code\":\"model_not_found\",\"message\":\"access-token-secret\"}}");
        assertFalse(result.usable());
        assertEquals("HTTP 404 (model_not_found)", result.detail());
        assertFalse(result.detail().contains("secret"));
    }

    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static String frame(String value) { return "data: " + value + "\n\n"; }
}
