package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LeanToolResultTest {
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
