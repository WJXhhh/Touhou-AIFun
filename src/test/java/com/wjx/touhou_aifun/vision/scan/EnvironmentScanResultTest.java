package com.wjx.touhou_aifun.vision.scan;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentScanResultTest {
    @Test
    void allObservedModBlocksRemainRetrievableAcrossByteBoundedPagesAndSummaryFallback() {
        List<ScannedBlock> blocks = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            blocks.add(new ScannedBlock("example:machine_" + index, index, 1, 3, index + 1,
                    "block", Map.of("facing", "north", "mode", "working")));
        }
        EnvironmentScanResult result = result(List.of(), 100, 200, 100, 0)
                .origin(10, 64, 20).observedBlocks(blocks);
        var seen = new java.util.HashSet<String>();
        int offset = 0;
        do {
            var request = new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 20,
                    "", "overview", "summary", null, offset, 64, result.observationId());
            String encoded = result.project(request).toJson();
            assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 12 * 1024);
            var page = JsonParser.parseString(encoded).getAsJsonObject();
            assertEquals(200, page.get("observed_cell_count").getAsInt());
            assertEquals(result.observationId(), page.get("observation_id").getAsString());
            assertEquals(JsonParser.parseString("[10,64,20]"), page.get("maid_position_at_scan_start"));
            page.getAsJsonArray("observed_blocks").forEach(row -> assertTrue(seen.add(
                    row.getAsJsonObject().get("registry_id").getAsString())));
            if (!page.get("has_more").getAsBoolean()) break;
            int next = page.get("next_offset").getAsInt();
            assertTrue(next > offset);
            offset = next;
        } while (offset < 200);
        assertEquals(200, seen.size());
    }

    @Test
    void preciseAreaIncludesAirAndUnknownCellsAndSurvivesEmergencyEnvelope() {
        var area = new ScanArea(0, 1, 0, 0, 1, 2, "maid_relative");
        var request = new EnvironmentScanRequest(ScanMode.BLOCKS, ScanDirection.ALL, 20,
                "", "inspect_area", "summary", area, 0, 64, "");
        var result = new EnvironmentScanResult(42, "维度".repeat(10_000), request,
                0, 30, 0, List.of(), List.of(), List.of(), 0, 0, 0,
                Map.of(), 0, 0, Map.of(), new int[0], Map.of(), new int[0], Map.of(), new double[0], false)
                .observedBlocks(List.of(
                        new ScannedBlock("minecraft:air", 0, 1, 0, 1, "air", Map.of()),
                        new ScannedBlock("", 0, 1, 1, 2, "occluded", Map.of()),
                        new ScannedBlock("othermod:device", 0, 1, 2, 3, "block", Map.of("huge", "界".repeat(5000)))));
        String encoded = result.toJson();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 12 * 1024);
        var json = JsonParser.parseString(encoded).getAsJsonObject();
        assertEquals(3, json.getAsJsonArray("observed_blocks").size());
        assertTrue(json.get("area_enumeration_complete").getAsBoolean());
        assertEquals(0, json.get("expected_primary_rays").getAsInt());
        var machine = json.getAsJsonArray("observed_blocks").get(0).getAsJsonObject();
        assertEquals("othermod:device", machine.get("registry_id").getAsString());
        assertTrue(machine.get("state_omitted").getAsBoolean());
    }

    @Test
    void summaryPreservesOrdinaryModBlockAboveWorkstationWithoutImportantClassification() {
        SurfaceBlockHit workstation = new SurfaceBlockHit("minecraft:crafting_table", "front", OpacityClass.OPAQUE,
                20, 3, 3, List.of(new SurfaceBlockHit.Representative(0, 0, 3, 3, 0)));
        SurfaceBlockHit modBlock = new SurfaceBlockHit("example:custom_table", "front", OpacityClass.LOW,
                8, 3, 3, List.of(new SurfaceBlockHit.Representative(0, 1, 3, 3, 15)));
        EnvironmentScanResult result = result(List.of(workstation, modBlock), 100, 28, 28, 0)
                .origin(10, 64, 20).observedBlocks(List.of(
                        new ScannedBlock("minecraft:crafting_table", 0, 0, 3, 3, "block", Map.of()),
                        new ScannedBlock("example:custom_table", 0, 1, 3, 3, "block", Map.of())))
                .project(new EnvironmentScanRequest(ScanMode.BLOCKS,
                        ScanDirection.ALL, 20, "", "overview", "summary"));

        JsonObject json = JsonParser.parseString(result.toJson()).getAsJsonObject();
        assertEquals(0, json.getAsJsonArray("important_targets").size());
        var targets = json.getAsJsonArray("observed_blocks");
        assertEquals(2, targets.size());
        JsonObject table = targets.get(0).getAsJsonObject();
        assertEquals("example:custom_table", table.get("registry_id").getAsString());
        assertEquals(JsonParser.parseString("[0,1,3]"), table.get("relative_position"));
        assertEquals(JsonParser.parseString("[10,64,20]"), json.get("maid_position_at_scan_start"));
        assertTrue(!json.get("has_more").getAsBoolean());
    }

    @Test
    void serializesOneAggregateOnceAndUsesRayCoverage() {
        SurfaceBlockHit group = new SurfaceBlockHit("minecraft:glass", "front", OpacityClass.HIGH,
                10, 2.0, 7.5, List.of(
                new SurfaceBlockHit.Representative(0, 0, 2, 2.0, 14),
                new SurfaceBlockHit.Representative(1, 0, 4, 4.2, 12),
                new SurfaceBlockHit.Representative(-1, 1, 7, 7.5, 10)));
        EnvironmentScanResult result = result(List.of(group), 8, 10, 4, 0);

        JsonObject json = JsonParser.parseString(result.toJson()).getAsJsonObject();
        assertEquals(1, json.getAsJsonArray("surface_blocks").size());
        assertEquals(10, json.get("surface_hit_samples").getAsInt());
        assertEquals(4, json.get("surface_rays_with_hits").getAsInt());
        assertEquals(0.5, json.get("surface_coverage_estimate").getAsDouble());
        JsonObject serializedGroup = json.getAsJsonArray("surface_blocks").get(0).getAsJsonObject();
        assertEquals(10, serializedGroup.get("count").getAsInt());
        assertEquals(3, serializedGroup.getAsJsonArray("representatives").size());
    }

    @Test
    void oversizedUnicodeResultFallsBackToBoundedTruthfulEnvelope() {
        List<SurfaceBlockHit> groups = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            String id = "example:高频纹理方块_" + index + "_abcdefghijklmnopqrstuvwxyz";
            groups.add(new SurfaceBlockHit(id, "front", OpacityClass.MEDIUM, 10,
                    1.0, 20.0, List.of(
                    new SurfaceBlockHit.Representative(index, 0, 1, 1.0, 12),
                    new SurfaceBlockHit.Representative(index, 1, 2, 2.0, 8),
                    new SurfaceBlockHit.Representative(index, 2, 3, 3.0, 4))));
        }
        EnvironmentScanResult result = result(groups, 160, 1_200, 80, 7);

        String encoded = result.toJson();
        JsonObject json = JsonParser.parseString(encoded).getAsJsonObject();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024);
        assertTrue(json.get("truncated").getAsBoolean());
        assertEquals(127, json.get("surface_groups").getAsInt());
        assertEquals(1_200, json.get("surface_hit_samples").getAsInt());
        assertEquals(80, json.get("surface_rays_with_hits").getAsInt());
        assertTrue(json.has("surface_direction_samples"));
        assertTrue(json.has("surface_bounds"));
    }

    @Test
    void preservesImportantAndEntitySpatialSummariesWhenDetailsAreOmitted() {
        EnvironmentScanRequest request = new EnvironmentScanRequest(ScanMode.BOTH,
                ScanDirection.ALL, 20, "");
        EnvironmentScanResult result = new EnvironmentScanResult(42, "minecraft:overworld", request,
                10, 100, 2, List.of(), List.of(), List.of(), 0, 5, 9,
                Map.of("hostile|front", 9), 0, 0, Map.of(), new int[0],
                Map.of("front", 5), new int[]{-4, -2, 1, 8, 3, 12},
                Map.of("front", 7, "left", 2), new double[]{-5.5, -1, -4, 9, 2.5, 13}, true);

        JsonObject json = JsonParser.parseString(result.toJson()).getAsJsonObject();
        assertEquals(5, json.getAsJsonObject("important_direction_counts").get("front").getAsInt());
        assertEquals(2, json.getAsJsonObject("entity_direction_counts").get("left").getAsInt());
        assertTrue(json.has("important_bounds"));
        assertTrue(json.has("entity_bounds"));
        assertEquals(5, json.getAsJsonObject("omitted").get("important_blocks").getAsInt());
        assertEquals(9, json.getAsJsonObject("omitted").get("entities").getAsInt());
    }

    @Test
    void compactSummaryHasAnAbsoluteUtf8Boundary() {
        String huge = "超长模组维度".repeat(10_000);
        EnvironmentScanRequest request = new EnvironmentScanRequest(ScanMode.BLOCKS,
                ScanDirection.ALL, 20, "");
        EnvironmentScanResult result = new EnvironmentScanResult(42, huge, request,
                1, 1, 0, List.of(), List.of(), List.of(), 0, 0, 0,
                Map.of(huge, 1), 0, 0, Map.of(huge, 1), new int[0],
                Map.of(), new int[0], Map.of(), new double[0], true, List.of("hard_timeout"));

        String encoded = result.toCompactJson();
        JsonObject json = JsonParser.parseString(encoded).getAsJsonObject();
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 16 * 1024);
        assertTrue(json.get("truncated").getAsBoolean());
    }

    @Test
    void timeoutReasonAndUnprocessedRayCountAreExplicit() {
        EnvironmentScanRequest request = new EnvironmentScanRequest(ScanMode.BLOCKS,
                ScanDirection.ALL, 20, "");
        EnvironmentScanResult result = new EnvironmentScanResult(42, "minecraft:overworld", request,
                2_000, 20_000, 0, List.of(), List.of(), List.of(), 0, 0, 0,
                Map.of(), 0, 0, Map.of(), new int[0], Map.of(), new int[0], Map.of(),
                new double[0], true, List.of("hard_timeout"));

        JsonObject json = JsonParser.parseString(result.toJson()).getAsJsonObject();
        assertEquals(9_600, json.get("expected_primary_rays").getAsInt());
        assertEquals(7_600, json.get("unprocessed_primary_rays").getAsInt());
        assertEquals("hard_timeout", json.getAsJsonArray("truncation_reasons").get(0).getAsString());
    }

    private static EnvironmentScanResult result(List<SurfaceBlockHit> surfaces, int primaryRays, int samples,
                                                int raysWithHits, int omittedGroups) {
        EnvironmentScanRequest request = new EnvironmentScanRequest(ScanMode.BLOCKS,
                ScanDirection.ALL, 20, "测试");
        return new EnvironmentScanResult(42, "minecraft:overworld", request,
                primaryRays, 500, 2, surfaces, List.of(), List.of(), omittedGroups,
                0, 0, Map.of(), samples, raysWithHits, Map.of("front", samples),
                new int[]{-20, -3, -20, 20, 5, 20}, Map.of(), new int[0], Map.of(),
                new double[0], omittedGroups > 0);
    }
}
