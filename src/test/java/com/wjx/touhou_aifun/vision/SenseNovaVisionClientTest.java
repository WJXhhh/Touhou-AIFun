package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SenseNovaVisionClientTest {
    private final VisionSite site = new VisionSite("sensenova", "SenseNova", "sensenova",
            "https://token.sensenova.cn/v1/chat/completions", "sensenova-6.7-flash-lite", "secret", false);

    @Test
    void factoryUsesDedicatedSenseNovaAdapter() {
        assertInstanceOf(SenseNovaVisionClient.class, VisionClient.forSite(site));
    }

    @Test
    void requestUsesSenseNovaTokenOpenAIShape() {
        Map<String, String> images = new LinkedHashMap<>();
        for (String face : OpenAICompatibleVisionClient.CUBEMAP_FACES) {
            images.put(face, "data:image/jpeg;base64," + face + "Payload");
        }
        VisionRequest request = new VisionRequest(site, null, "check chest", "", images, 12, 13);
        JsonObject body = new SenseNovaVisionClient(site).requestBody(request);

        assertFalse(body.has("max_tokens"));
        assertFalse(body.has("max_new_tokens"));
        assertFalse(body.has("thinking"));
        assertEquals("none", body.get("reasoning_effort").getAsString());
        assertEquals(1, body.get("n").getAsInt());

        JsonArray content = body.getAsJsonArray("messages").get(0).getAsJsonObject().getAsJsonArray("content");
        int imageCount = 0;
        for (int index = 0; index < content.size(); index++) {
            JsonObject part = content.get(index).getAsJsonObject();
            if (!"image_url".equals(part.get("type").getAsString())) continue;
            imageCount++;
            String payload = part.getAsJsonObject("image_url").get("url").getAsString();
            assertTrue(payload.startsWith("data:image/jpeg;base64,"));
            assertTrue(payload.endsWith("Payload"));
            assertFalse(part.has("image_base64"));
        }
        assertEquals(6, imageCount);
    }

    @Test
    void parsesWrappedNonStreamingResponse() {
        String response = """
                {"choices":[{"message":{"role":"assistant","content":"{\\"scene_summary\\":\\"room\\",\\"answer_to_focus\\":\\"chest\\"}"}}]}
                """;
        VisionObservation result = new SenseNovaVisionClient(site).parse(200, response, 44, 42);

        assertEquals("ok", result.status());
        assertEquals("room", result.sceneSummary());
        assertEquals("chest", result.answerToFocus());
        assertEquals(44, result.imageTick());
        assertEquals(42, result.scanTick());
    }

    @Test
    void emptyProviderMessageIsFailure() {
        VisionObservation result = new SenseNovaVisionClient(site).parse(
                200, "{\"choices\":[{\"message\":{\"content\":\"\"}}]}", 44, 42);
        assertEquals("failed", result.status());
        assertTrue(result.error().contains("empty"));
    }

    @Test
    void nonSuccessResponsePreservesBoundedProviderReason() {
        VisionObservation result = new SenseNovaVisionClient(site).parse(403,
                "{\"error\":{\"message\":\"model permission denied\"}}", 44, 42);

        assertEquals("failed", result.status());
        assertEquals("provider HTTP 403: model permission denied", result.error());
    }

    @Test
    void plainTextGatewayDenialIsAlsoDiagnosable() {
        assertEquals("provider HTTP 403: request blocked by gateway",
                OpenAICompatibleVisionClient.httpError(403, "request blocked by gateway\n"));
    }

    @Test
    void parsesObjectMessageContentAsWellAsDocumentedStringShape() {
        VisionObservation result = new SenseNovaVisionClient(site).parse(200,
                "{\"choices\":[{\"message\":{\"content\":\"room\"}}]}", 44, 42);
        assertEquals("ok", result.status());
        assertEquals("room", result.sceneSummary());
    }

    @Test
    void promptUsesFrozenCaptureYawAndTickRange() {
        VisionRequest request = new VisionRequest(site, null, "moving target", "", Map.of(),
                10, 20, 25, 90.0F);

        String prompt = OpenAICompatibleVisionClient.prompt(request);

        assertTrue(prompt.contains("front=west"));
        assertTrue(prompt.contains("game tick 20 through 25"));
    }
}
