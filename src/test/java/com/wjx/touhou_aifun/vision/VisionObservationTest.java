package com.wjx.touhou_aifun.vision;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanRequest;
import com.wjx.touhou_aifun.vision.scan.EnvironmentScanResult;
import com.wjx.touhou_aifun.vision.scan.ImportantBlockHit;
import com.wjx.touhou_aifun.vision.scan.ScanDirection;
import com.wjx.touhou_aifun.vision.scan.ScanMode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisionObservationTest {
    @Test
    void unicodeResponseIsBoundedByUtf8BytesAndRemainsValidJson() {
        String large = "女仆看见复杂环境。".repeat(2_000);
        VisionObservation observation = new VisionObservation("ok", "test", large, large,
                large, "ok", "", 100, 99);

        String encoded = observation.toJson();
        JsonObject json = JsonParser.parseString(encoded).getAsJsonObject();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024);
        assertEquals("ok", json.get("status").getAsString());
        assertTrue(json.get("truncated").getAsBoolean());
    }

    @Test
    void oversizedSiteMetadataAlsoFallsBackWithinTheBoundary() {
        VisionObservation observation = new VisionObservation("ok", "站点".repeat(20_000), "scene", "answer",
                "{}", "ok", "", 100, 99);

        String encoded = observation.toJson();
        JsonObject json = JsonParser.parseString(encoded).getAsJsonObject();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024);
        assertTrue(json.get("truncated").getAsBoolean());
    }

    @Test
    void removesGroundingClaimsThatAreNotPresentAtTheScannedPosition() {
        EnvironmentScanResult scan = new EnvironmentScanResult(1, "minecraft:overworld",
                new EnvironmentScanRequest(ScanMode.BOTH, ScanDirection.ALL, 20, ""),
                10, 20, 1, List.of(), List.of(new ImportantBlockHit("minecraft:chest",
                4, 0, -7, 8.1, "front", Map.of("facing", "north"), "minecraft:chest")),
                List.of(), 0, 0, 0, Map.of(), 0, 0, Map.of(), new int[0],
                Map.of("front", 1), new int[]{4, 0, -7, 4, 0, -7}, Map.of(), new double[0], false);
        JsonObject observation = JsonParser.parseString("""
                {"grounded_matches":[
                  {"scan_kind":"block","registry_id":"minecraft:chest","relative_position":[4,0,-7]},
                  {"scan_kind":"block","registry_id":"minecraft:diamond_ore","relative_position":[4,0,-7]}
                ]}
                """).getAsJsonObject();

        VisionObservationManager.validateGroundedMatches(observation, scan);

        assertEquals(1, observation.getAsJsonArray("grounded_matches").size());
        assertTrue(observation.getAsJsonArray("uncertainties").get(0).getAsString().contains("removed 1"));
    }

    @Test
    void onlySuccessfulImageResultsAreEligibleForShortReuse() {
        assertTrue(VisionObservationManager.reusableVisualResult(
                "{\"status\":\"ok\",\"site_id\":\"qwen\",\"image_tick\":12}"));
        assertTrue(!VisionObservationManager.reusableVisualResult(
                "{\"status\":\"partial\",\"site_id\":\"qwen\",\"image_tick\":12}"));
        assertTrue(!VisionObservationManager.reusableVisualResult(
                "{\"status\":\"ok\",\"image_tick\":12}"));
    }

    @Test
    void providerFailureWithSuccessfulScanBecomesPartialInsteadOfFailed() {
        EnvironmentScanResult scan = new EnvironmentScanResult(1, "minecraft:overworld",
                new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 20, ""),
                10, 20, 0, List.of(), List.of(), List.of(), 0, 0, 0, Map.of(),
                0, 0, Map.of(), new int[0], Map.of(), new int[0], Map.of(), new double[0], false);

        JsonObject merged = JsonParser.parseString(VisionObservationManager.mergeScan(
                "{\"status\":\"failed\",\"site_id\":\"qwen\",\"error\":\"provider HTTP 429\"}",
                scan, "ok")).getAsJsonObject();

        assertEquals("partial", merged.get("status").getAsString());
        assertEquals("failed", merged.get("image_status").getAsString());
        assertEquals("ok", merged.get("scan_status").getAsString());
        assertTrue(merged.getAsJsonArray("uncertainties").get(0).getAsString().contains("server scan"));
    }

    @Test
    void combinedEmergencyEnvelopeCannotBeInflatedByProviderStrings() {
        JsonObject provider = new JsonObject();
        provider.addProperty("status", "failed");
        provider.addProperty("site_id", "恶意站点".repeat(10_000));
        provider.addProperty("error", "异常详情".repeat(10_000));

        String encoded = VisionObservationManager.boundCombined(provider, null);
        JsonObject json = JsonParser.parseString(encoded).getAsJsonObject();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024);
        assertTrue(json.get("truncated").getAsBoolean());
    }
}
