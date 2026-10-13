package com.wjx.touhou_aifun.chat.agent.decision;

import com.google.gson.*;
import java.util.*;

/** Compact only repeated empty-slot data. Complete snapshots remain on the server for freshness checks. */
public final class DecisionStateProjection {
    private DecisionStateProjection() { }
    public static JsonObject modelView(JsonObject live) {
        var out=live.deepCopy();
        out.remove("binding_proof");
        out.remove("skill_catalog");
        var position=new JsonArray();
        for(String axis:List.of("x","y","z")) if(out.has(axis)) position.add(out.remove(axis).getAsDouble()/4);
        if(position.size()==3) out.add("position_rounded_to_quarter_block",position);
        for(String key:List.of("targets","action_targets")) if(out.has(key)) for(var value:out.getAsJsonArray(key)) {
                var target=value.getAsJsonObject();
                // Closed-container/server NBT is freshness evidence, not an observed menu.
                target.remove("items");target.remove("sign");
            }
        if(out.has("gui")) {
            var gui=out.getAsJsonObject("gui");
            gui.remove("backpack_slots"); // The live inventory and menu already describe the same backpack.
            for(String key:List.of("frame_id","layout","waited_ticks","slot_geometry")) gui.remove(key);
            if(gui.has("slots")) gui.add("slots",compactSlots(gui.getAsJsonArray("slots"),"slot"));
        }
        return out;
    }
    static JsonObject compactSlots(JsonArray slots,String indexKey) {
        var out=new JsonObject();out.addProperty("slot_count",slots.size());
        var occupied=new JsonArray();out.add("occupied",occupied);
        var emptyGroups=new LinkedHashMap<String,JsonObject>();
        for(int i=0;i<slots.size();i++) {
            var row=slots.get(i).getAsJsonObject().deepCopy();if(!row.has(indexKey)) row.addProperty(indexKey,i);
            if(!row.has("count") || row.get("count").getAsInt()!=0) {occupied.add(row);continue;}
            var index=row.remove(indexKey);
            var inventorySlot=row.get("inventory_slot");var backpackSlot=row.get("backpack_slot");
            for(String key:List.of("item","count","name","fingerprint","inventory_slot","backpack_slot")) row.remove(key);
            String signature=row.toString();var group=emptyGroups.get(signature);
            if(group==null) {group=row;group.add("indices",new JsonArray());emptyGroups.put(signature,group);}
            group.getAsJsonArray("indices").add(index);
            if(inventorySlot!=null) {
                if(!group.has("inventory_slots")) group.add("inventory_slots",new JsonArray());group.getAsJsonArray("inventory_slots").add(inventorySlot);
            }
            if(backpackSlot!=null) {
                if(!group.has("backpack_slots")) group.add("backpack_slots",new JsonArray());group.getAsJsonArray("backpack_slots").add(backpackSlot);
            }
        }
        var empty=new JsonArray();emptyGroups.values().forEach(empty::add);out.add("empty_groups",empty);return out;
    }
    /** Historical menu observations cannot supply current slots; the live menu is authoritative. */
    public static JsonArray observations(Map<String,JsonObject> observations) {
        var out=new JsonArray();
        observations.forEach((tool,result)-> {
            if(Set.of("open_gui","inspect_gui","gui_action","gui_batch","close_gui").contains(tool)) return;
            var record=new JsonObject();record.addProperty("tool",tool);record.addProperty("historical",true);record.add("result",result.deepCopy());out.add(record);
        });return out;
    }
}
