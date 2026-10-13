package com.wjx.touhou_aifun.chat.agent;

import com.google.gson.*;
import java.util.*;

/** Keep evidence fields, not arbitrary head/tail fragments of inventory JSON. */
public final class ToolResultProjection {
    private static final Set<String> VOLATILE = Set.of("session_id", "revision", "base_revision", "frame_id", "layout", "game_tick", "scan_tick", "completed_tick", "observation_id", "elapsed_ms");
    private ToolResultProjection() { }
    public static String project(String raw, String reference) {
        try {
            JsonObject value=JsonParser.parseString(raw).getAsJsonObject();
            if(value.has("important_blocks") && value.has("maid_position_at_scan_start")) {
                JsonObject scan=new JsonObject();
                for(String key:List.of("status","error","game_tick","completed_tick","dimension","intent","coverage","coordinate_space","maid_position_at_scan_start","coordinate_notice","important_blocks","entities","sign_texts","truncated","omitted_sign_texts","omitted_important_target_details","omitted","unprocessed_primary_rays"))
                    if(value.has(key))scan.add(key,value.get(key));
                if(value.has("surface_blocks")) {
                    JsonArray surfaces=new JsonArray();for(var block:value.getAsJsonArray("surface_blocks")) {
                        JsonObject summary=new JsonObject();for(String key:List.of("registry_id","direction","count","nearest_distance","farthest_distance"))
                            if(block.getAsJsonObject().has(key))summary.add(key,block.getAsJsonObject().get(key));surfaces.add(summary);
                    }scan.add("surface_summary",surfaces);
                }
                scan.addProperty("result_ref",reference);
                scan.addProperty("terrain_details_omitted",true);scan.addProperty("notice","Important target coordinates, signs and entities are retained. Read result_ref only when omitted terrain samples are needed; target coordinates above can be used directly.");
                return scan.toString();
            }
            if(value.has("snapshot_kind") && (!value.has("layout") || value.get("layout").getAsString().isBlank())) {
                JsonObject compact=compactMenu(value);compact.addProperty("result_ref",reference);return compact.toString();
            }
            if(value.has("snapshot_kind") && Set.of("full","container_inspection").contains(value.get("snapshot_kind").getAsString()) && raw.length()<=65536
                    && com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(raw)<=16384) {
                value.addProperty("result_ref",reference);return value.toString();
            }
        } catch(RuntimeException ignored) { }
        if (raw.length() <= 8192) {
            try {
                JsonObject value = JsonParser.parseString(raw).getAsJsonObject();
                value.addProperty("result_ref", reference); return value.toString();
            } catch (RuntimeException ignored) { return raw + "\nresult_ref=" + reference; }
        }
        try {
            JsonObject value = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject out = new JsonObject();
            for (String key : List.of("status", "error", "completion_error", "completion_requested", "cleanup", "session_id", "revision", "target_position", "moved_count", "moved_item", "to_maid", "dimension", "action_results", "delivered_count", "goal_count", "goal_item", "dropped_count", "carried", "process", "sign_texts", "truncated"))
                if (value.has(key)) out.add(key, value.get(key));
            out.addProperty("result_ref", reference); out.addProperty("details_omitted", true);
            out.addProperty("notice", "Read referenced details before choosing slots or coordinates; omitted fields do not mean empty or absent.");
            return out.toString();
        } catch (RuntimeException ignored) {
            return AgentTaskState.bounded(raw, 2048) + "\n[truncated; read_task_result reference=" + reference + "]";
        }
    }
    /** Remove duplicate/default decoration, while retaining every observed semantic slot and action receipt. */
    private static JsonObject compactMenu(JsonObject value) {
        JsonObject out=value.deepCopy();
        if(out.has("active_gui"))out.add("active_gui",compactMenu(out.getAsJsonObject("active_gui")));
        if(out.has("action_results")) {
            JsonArray actions=new JsonArray();for(var entry:out.getAsJsonArray("action_results")) {
                JsonObject action=entry.getAsJsonObject().deepCopy();
                for(String key:List.of("layout","frame_id","compatibility","title","menu","wait_policy"))action.remove(key);
                actions.add(action);
            }out.add("action_results",actions);
        }
        if(out.has("slots")) {
            JsonArray slots=new JsonArray();for(var entry:out.getAsJsonArray("slots")) {
                var slot=entry.getAsJsonObject().deepCopy();
                if(slot.has("count") && slot.get("count").getAsInt()==0){slot.remove("name");slot.remove("fingerprint");}
                slots.add(slot);
            }out.add("slots",slots);
        }
        if(out.has("backpack_slots") && out.has("slots")) {
            JsonArray backpack=new JsonArray();for(var entry:out.getAsJsonArray("backpack_slots")) {
                var slot=entry.getAsJsonObject().deepCopy();slot.remove("name");if(slot.get("count").getAsInt()==0)slot.remove("fingerprint");backpack.add(slot);
            }out.add("backpack_slots",backpack);
        }
        for(String key:List.of("data","controls"))if(out.has(key) && out.get(key).isJsonArray() && out.getAsJsonArray(key).isEmpty())out.remove(key);
        if(out.has("process") && "UNKNOWN".equals(out.getAsJsonObject("process").get("status").getAsString())) {
            var process=new JsonObject();process.addProperty("status","UNKNOWN");process.addProperty("reliable",false);out.add("process",process);
        }
        return out;
    }
    public static String progress(String raw) {
        try { return stable(JsonParser.parseString(raw)).toString(); }
        catch (RuntimeException ignored) { return raw; }
    }
    /** Checkpoints retain domain facts and references, not duplicate menu or panorama snapshots. */
    public static String domainSummary(String raw) {
        try {
            JsonObject value = JsonParser.parseString(raw).getAsJsonObject(), out = new JsonObject();
            for (String key : List.of("status", "error", "result_ref", "source_result_ref", "details_omitted", "truncated",
                    "dimension", "target_position", "maid_position_at_scan_start", "coordinate_space", "coverage",
                    "external_transfer", "moved_item", "moved_count", "to_maid", "delivered_count", "dropped_count",
                    "goal_item", "goal_count", "item", "count", "position", "distance", "completion_verified",
                    "verified_net_count", "kind", "menu", "last_action", "executed_count", "failed_index", "carried", "containers", "completion_requested", "completion_error"))
                if (value.has(key)) out.add(key, value.get(key));
            if (value.has("action_results")) {
                JsonArray actions = new JsonArray();
                for (JsonElement result : value.getAsJsonArray("action_results")) {
                    JsonObject step = JsonParser.parseString(domainSummary(result.toString())).getAsJsonObject();
                    step.remove("snapshot_details"); actions.add(step);
                }
                out.add("action_results", actions);
            }
            out.addProperty("snapshot_details", "Historical summary only. Read the result reference for omitted evidence; observe again before mutations.");
            return out.toString();
        } catch (RuntimeException ignored) { return AgentTaskState.bounded(raw, 512); }
    }
    private static JsonElement stable(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject out = new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).stream().filter(k -> !VOLATILE.contains(k))
                    .forEach(k -> out.add(k, stable(value.getAsJsonObject().get(k))));
            return out;
        }
        if (value.isJsonArray()) { JsonArray out = new JsonArray(); value.getAsJsonArray().forEach(v -> out.add(stable(v))); return out; }
        return value;
    }
}
