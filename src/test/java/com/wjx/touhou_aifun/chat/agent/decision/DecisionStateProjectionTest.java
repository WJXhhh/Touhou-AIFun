package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecisionStateProjectionTest {
    private static JsonObject json(String s){return JsonParser.parseString(s).getAsJsonObject();}
    @Test void compressesEmptySlotsWithoutDroppingOccupiedEvidenceOrPermissions() {
        var live=json("{\"x\":250,\"y\":-236,\"z\":434,\"gui\":{\"revision\":3,\"backpack_slots\":[],\"slots\":[]},\"targets\":[]}");
        var slots=live.getAsJsonObject("gui").getAsJsonArray("slots");
        for(int i=0;i<512;i++) {
            var row=json("{\"item\":\"\",\"count\":0,\"name\":\"\",\"enabled\":true,\"may_take\":true,\"maid_inventory\":false}");row.addProperty("slot",i);slots.add(row);
        }
        slots.set(7,json("{\"slot\":7,\"item\":\"minecraft:diamond\",\"count\":2,\"name\":\"Custom item\",\"fingerprint\":\"abc\",\"enabled\":false,\"may_take\":false}"));
        var before=live.deepCopy();var projected=DecisionStateProjection.modelView(live);
        assertEquals(before,live);assertTrue(projected.toString().length()<live.toString().length()/8);
        var compact=projected.getAsJsonObject("gui").getAsJsonObject("slots");
        assertEquals(512,compact.get("slot_count").getAsInt());assertEquals(slots.get(7),compact.getAsJsonArray("occupied").get(0));
        assertEquals(511,compact.getAsJsonArray("empty_groups").get(0).getAsJsonObject().getAsJsonArray("indices").size());
        assertEquals(62.5,projected.getAsJsonArray("position_rounded_to_quarter_block").get(0).getAsDouble());
    }
    @Test void keepsSlotMappingAndHistoricalScanWhileExcludingOldMenus() {
        var slots=new JsonArray();slots.add(json("{\"slot\":28,\"inventory_slot\":1,\"backpack_slot\":0,\"count\":0,\"maid_storage\":\"backpack\"}"));
        var group=DecisionStateProjection.compactSlots(slots,"slot").getAsJsonArray("empty_groups").get(0).getAsJsonObject();
        assertEquals(28,group.getAsJsonArray("indices").get(0).getAsInt());assertEquals(0,group.getAsJsonArray("backpack_slots").get(0).getAsInt());
        var historical=DecisionStateProjection.observations(Map.of("scan_surroundings",json("{\"sign_texts\":[\"iron\"]}"),"inspect_gui",json("{\"session_id\":\"old\"}")));
        assertEquals(1,historical.size());assertTrue(historical.get(0).getAsJsonObject().getAsJsonObject("result").has("sign_texts"));
    }
    @Test void privateClosedContainerEvidenceNeverBecomesAnObservedModelInventory(){
        var live=json("{\"binding_proof\":{\"secret\":true},\"targets\":[{\"position\":[1,2,3],\"items\":[{\"item\":\"minecraft:diamond\",\"count\":64}]}],\"action_targets\":[{\"position\":[1,2,3],\"sign\":\"private NBT\"}]}");var projected=DecisionStateProjection.modelView(live);assertFalse(projected.has("binding_proof"));assertFalse(projected.toString().contains("diamond"));assertFalse(projected.toString().contains("private NBT"));assertTrue(live.toString().contains("diamond"));
    }
}
