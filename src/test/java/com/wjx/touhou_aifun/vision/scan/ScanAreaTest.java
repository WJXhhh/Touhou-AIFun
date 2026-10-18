package com.wjx.touhou_aifun.vision.scan;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScanAreaTest {
    @Test
    void rejectsReversedOversizedAndOverflowingRegions() {
        assertThrows(IllegalArgumentException.class, () -> new ScanArea(1, 0, 0, 0, 0, 0, "world"));
        assertThrows(IllegalArgumentException.class, () -> new ScanArea(0, 0, 0, 7, 0, 0, "world"));
        assertThrows(IllegalArgumentException.class, () -> new ScanArea(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0, "world"));
        assertThrows(IllegalArgumentException.class, () -> new ScanArea(0, 0, 0, 0, 0, 0, "screen"));
        assertEquals(343, new ScanArea(-3, -3, -3, 3, 3, 3, "maid_relative").volume());
    }

    @Test
    void areaCodecSelectsPreciseBlocksAndRetainsPageArguments() {
        var json = JsonParser.parseString("""
                {"mode":"entities","direction":"back","area":{
                  "min_x":0,"min_y":1,"min_z":2,"max_x":1,"max_y":2,"max_z":3},
                  "offset":2,"limit":4}
                """);
        var request = EnvironmentScanRequest.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow();
        assertEquals("inspect_area", request.intent());
        assertEquals(ScanMode.BLOCKS, request.mode());
        assertEquals(ScanDirection.ALL, request.direction());
        assertEquals("maid_relative", request.area().coordinateSpace());
        assertEquals(8, request.area().volume());
        assertEquals(2, request.offset());
        assertEquals(4, request.limit());
        assertEquals(request, EnvironmentScanRequest.CODEC.parse(JsonOps.INSTANCE,
                EnvironmentScanRequest.CODEC.encodeStart(JsonOps.INSTANCE, request).result().orElseThrow()).result().orElseThrow());
    }

    @Test
    void existingRequestsStillDefaultToOverviewButInspectionNeedsBounds() {
        var request = EnvironmentScanRequest.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).result().orElseThrow();
        assertEquals(EnvironmentScanRequest.defaults(), request);
        assertThrows(IllegalArgumentException.class, () -> new EnvironmentScanRequest(
                ScanMode.BLOCKS, ScanDirection.ALL, 20, "", "inspect_area", "full"));
    }
}
