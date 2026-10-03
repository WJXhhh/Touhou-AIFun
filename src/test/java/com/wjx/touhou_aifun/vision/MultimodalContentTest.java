package com.wjx.touhou_aifun.vision;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultimodalContentTest {
    private Map<String, String> faces() {
        Map<String, String> images = new HashMap<>();
        for (String face : MultimodalContent.FACES) images.put(face, "data:image/jpeg;base64,AAAA");
        return images;
    }
    @Test void serializesEveryFaceInStableOrderForAllThreeProtocols() {
        for (var protocol : UnifiedModelCatalog.VisualProtocol.values()) {
            JsonObject message = MultimodalContent.user(protocol, "look", faces());
            JsonArray blocks = message.getAsJsonArray("content");
            assertEquals(13, blocks.size());
            for (int i = 0; i < 6; i++) {
                assertEquals("Cubemap face: " + MultimodalContent.FACES[i], blocks.get(1 + 2 * i).getAsJsonObject().get("text").getAsString());
                var image = blocks.get(2 + 2 * i).getAsJsonObject();
                String type = image.get("type").getAsString();
                if (protocol == UnifiedModelCatalog.VisualProtocol.ANTHROPIC) {
                    assertEquals("image", type);
                    assertEquals("AAAA", image.getAsJsonObject("source").get("data").getAsString());
                } else assertEquals(protocol == UnifiedModelCatalog.VisualProtocol.CHAT_COMPLETIONS ? "image_url" : "input_image", type);
            }
            JsonObject redacted = MultimodalContent.redacted(message);
            assertFalse(redacted.toString().contains("AAAA"));
            assertTrue(message.toString().contains("AAAA"));
        }
    }
    @Test void rejectsOnlyExplicitImageInputErrorsBeforeTextOutput() {
        assertTrue(MultimodalTurnContext.isImageRejection(400, "{\"error\":{\"message\":\"This model does not support image input\"}}"));
        assertTrue(MultimodalTurnContext.isImageRejection(200, "{\"type\":\"error\",\"error\":{\"message\":\"image_url unsupported\"}}"));
        assertFalse(MultimodalTurnContext.isImageRejection(200, "{\"choices\":[{\"content\":\"I do not support images\"}]}"));
        for (int status : new int[]{401, 403, 413, 429, 500}) assertFalse(MultimodalTurnContext.isImageRejection(status, "image unsupported"));
        assertFalse(MultimodalTurnContext.isImageRejection(400, "invalid tools: unsupported tool schema"));
        assertFalse(MultimodalTurnContext.isImageRejection(400, "unsupported image format"));
        assertFalse(MultimodalTurnContext.isImageRejection(400, "unsupported parameter image_url.detail"));
    }
    @Test void auxiliaryProtocolRequestsContainImagesWithoutAnyTools() {
        var site = new VisionSite("vision", "Vision", "custom", "https://example.invalid", "model", "", false);
        var request = new VisionRequest(site, null, "look", "", faces(), -1);
        for (var protocol : List.of(UnifiedModelCatalog.VisualProtocol.ANTHROPIC,
                UnifiedModelCatalog.VisualProtocol.RESPONSES, UnifiedModelCatalog.VisualProtocol.SUBSCRIPTION)) {
            JsonObject body = ProtocolVisionClient.body(request, protocol);
            assertFalse(body.has("tools"));
            assertTrue(body.toString().contains("AAAA"));
            assertEquals(protocol == UnifiedModelCatalog.VisualProtocol.SUBSCRIPTION, body.get("stream").getAsBoolean());
        }
    }
    @Test void requiresCompletedSubscriptionStreamAndPreservesFinalTextDeltas() {
        assertEquals("hello", ProtocolVisionClient.subscriptionText("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"));
        assertThrows(IllegalStateException.class, () -> ProtocolVisionClient.subscriptionText("data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n"));
        assertEquals("hello", ProtocolVisionClient.subscriptionText("data: {\"type\":\"response.output_text.done\",\n"
                + "data: \"text\":\"hello\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}"));
        assertThrows(IllegalStateException.class, () -> ProtocolVisionClient.subscriptionText("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\"}}"));
    }
}
