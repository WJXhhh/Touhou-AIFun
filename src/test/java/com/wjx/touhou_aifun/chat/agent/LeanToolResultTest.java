package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeanToolResultTest {
    @Test void ownerEmcAndActualPartialExchangeSurviveCheckpointSummaries() {
        var raw = JsonParser.parseString("""
                {"snapshot_kind":"delta","session_id":"live","adapter_state":{"adapter":"projecte_transmutation","account":"owner","emc":"99999999999999999999999"},
                 "requested_count":70,"exchanged_count":64,"exchanged_item":"projecte:alchemical_coal","emc_before":"10000","emc_after":"1808","emc_spent":"8192",
                 "error":"projecte_inventory_changed_partial_exchange","deposited_item":"minecraft:diamond","deposited_count":2,"emc_gained":"16384","learned":true}
                """).getAsJsonObject();
        var summary = JsonParser.parseString(ToolResultProjection.domainSummary(ToolResultProjection.project(raw.toString(), "emc-ref"))).getAsJsonObject();
        for (String key : new String[]{"adapter_state", "requested_count", "exchanged_count", "exchanged_item", "emc_before", "emc_after", "emc_spent", "error", "deposited_item", "deposited_count", "emc_gained", "learned"})
            assertEquals(raw.get(key), summary.get(key));
    }
    @Test void recipeLookupAndCraftingReceiptsSurviveContextProjection() {
        var query = JsonParser.parseString("{\"source\":\"live_server_recipe_manager\",\"recipes\":[{\"recipe_id\":\"mod:real\"}],\"has_more\":true,\"next_offset\":1}").getAsJsonObject();
        query.addProperty("ingredient_data", "large recipe context ".repeat(600));
        var projected = JsonParser.parseString(ToolResultProjection.project(query.toString(), "recipes-ref")).getAsJsonObject();
        assertEquals(query.get("recipes"), projected.get("recipes"));
        assertEquals(1, projected.get("next_offset").getAsInt());
        var receipt = JsonParser.parseString(ToolResultProjection.domainSummary("{\"recipe_id\":\"mod:real\",\"requested_count\":6,\"crafted_count\":8,\"crafts_executed\":2,\"surplus_count\":2,\"crafted_item\":\"mod:output\"}")).getAsJsonObject();
        assertEquals(8, receipt.get("crafted_count").getAsInt());
        assertEquals(2, receipt.get("surplus_count").getAsInt());
        assertEquals("mod:real", receipt.get("recipe_id").getAsString());
    }

    @Test void preciseScanPagesSurviveAgentContextCompactionWithContinuationAndUnknownCells() {
        var scan = JsonParser.parseString("""
                {"status":"ok","dimension":"minecraft:overworld","maid_position_at_scan_start":[10,64,20],
                 "observation_id":"saved-scan","observed_cell_count":40,"offset":0,"has_more":true,"next_offset":2,
                 "area":{"min_x":0,"min_y":1,"min_z":0,"max_x":1,"max_y":1,"max_z":0},
                 "area_enumeration_complete":true,"area_visibility_complete":false,
                 "observed_blocks":[{"registry_id":"example:device","relative_position":[0,1,0],"status":"block"},
                                    {"relative_position":[1,1,0],"status":"occluded"}]}
                """).getAsJsonObject();
        scan.addProperty("large_unused_field", "omitted".repeat(2000));
        var projected = JsonParser.parseString(ToolResultProjection.project(scan.toString(), "scan-ref")).getAsJsonObject();
        for (String key : new String[]{"observed_blocks", "observation_id", "next_offset", "has_more", "offset",
                "area", "area_enumeration_complete", "area_visibility_complete", "maid_position_at_scan_start"}) {
            assertEquals(scan.get(key), projected.get(key));
        }
        assertFalse(projected.has("large_unused_field"));
    }

    @Test void bigScanKeepsUsableCoordinatesSignsCoverageAndEntitiesWithoutTerrainSamples() {
        var scan=JsonParser.parseString("{\"status\":\"ok\",\"coordinate_space\":\"world\",\"coverage\":\"partial\",\"maid_position_at_scan_start\":[0,64,0],\"important_blocks\":[{\"position\":[1,64,2],\"registry_id\":\"minecraft:chest\"}],\"sign_texts\":[{\"text\":\"owner source\"}],\"entities\":[{\"uuid\":\"actual\"}],\"truncated\":true,\"unprocessed_primary_rays\":4,\"surface_blocks\":[{\"registry_id\":\"minecraft:stone\",\"count\":200}]}").getAsJsonObject();
        scan.getAsJsonArray("surface_blocks").get(0).getAsJsonObject().addProperty("representatives","terrain".repeat(4000));
        var projected=JsonParser.parseString(ToolResultProjection.project(scan.toString(),"raw-scan")).getAsJsonObject();
        for(String k:new String[]{"important_blocks","sign_texts","entities","coverage","coordinate_space","truncated","unprocessed_primary_rays"})assertEquals(scan.get(k),projected.get(k));
        assertEquals("raw-scan",projected.get("result_ref").getAsString());assertTrue(projected.toString().length()<scan.toString().length()/4);
    }
    @Test void menuCompactionKeepsEverySlotPermissionAndActualFailedActionReceipt() {
        var raw=JsonParser.parseString("{\"snapshot_kind\":\"full\",\"session_id\":\"current\",\"revision\":7,\"slots\":[{\"slot\":0,\"count\":0,\"name\":\"\",\"fingerprint\":\"empty\",\"enabled\":false,\"may_take\":false},{\"slot\":27,\"count\":2,\"fingerprint\":\"NBT\",\"backpack_slot\":0}],\"action_results\":[{\"error\":\"partial\",\"moved_count\":1,\"external_transfer\":true,\"carried\":{\"count\":1},\"process\":{\"status\":\"BLOCKED\"}}]}").getAsJsonObject();
        var value=JsonParser.parseString(ToolResultProjection.project(raw.toString(),"raw-menu")).getAsJsonObject();
        assertEquals(2,value.getAsJsonArray("slots").size());assertFalse(value.getAsJsonArray("slots").get(0).getAsJsonObject().get("may_take").getAsBoolean());assertEquals(raw.getAsJsonArray("action_results"),value.getAsJsonArray("action_results"));assertEquals("NBT",value.getAsJsonArray("slots").get(1).getAsJsonObject().get("fingerprint").getAsString());
    }
}
