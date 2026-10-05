package com.wjx.touhou_aifun.maid.gui;

import com.google.gson.*;

/** Model projection only; authoritative slot observations remain complete on the server. */
public final class GuiSnapshotProjection {
    private JsonObject previous;
    private long revision;
    public long revision() { return revision; }
    /** Preserve every semantic slot and its permissions; pixels require an actual captured frame. */
    public static JsonObject taskView(JsonObject snapshot) {
        JsonObject out=snapshot.deepCopy();
        boolean pixels=out.has("frame_id") && out.get("frame_id").getAsLong()>0;
        for(String key:new String[]{"slots","backpack_slots"}) {
            JsonArray slots=out.getAsJsonArray(key);if(slots==null) continue;
            for(JsonElement value:slots) {
                JsonObject slot=value.getAsJsonObject();
                if(slot.has("count") && slot.get("count").getAsInt()==0) {slot.remove("name");slot.remove("fingerprint");}
                if(!pixels) {slot.remove("x");slot.remove("y");}
            }
        }
        if(!pixels && out.has("slots")) out.addProperty("slot_geometry","capture_required_for_visual_actions");
        return out;
    }
    public JsonObject project(JsonObject full, boolean complete, boolean closed) {
        JsonObject out = full.deepCopy();
        long base = revision++;
        out.addProperty("revision", revision);
        out.addProperty("snapshot_kind", complete || previous == null ? "full" : "delta");
        if (closed) {
            for (String key : new String[]{"slots", "backpack_slots", "controls", "data", "compatibility"}) out.remove(key);
            out.addProperty("snapshot_kind", "closed");
        } else if (!complete && previous != null) {
            out.addProperty("base_revision", base);
            for (String key : new String[]{"slots", "backpack_slots"}) {
                JsonArray delta = new JsonArray();
                JsonArray now = full.getAsJsonArray(key), before = previous.getAsJsonArray(key);
                if (now != null) for (int i = 0; i < now.size(); i++)
                    if (before == null || i >= before.size() || !now.get(i).equals(before.get(i))) delta.add(now.get(i));
                out.add(key, delta);
            }
            for (String key : new String[]{"controls", "compatibility", "title", "menu", "data"})
                if (java.util.Objects.equals(full.get(key), previous.get(key))) out.remove(key);
        }
        previous = full.deepCopy();
        return out;
    }
}
