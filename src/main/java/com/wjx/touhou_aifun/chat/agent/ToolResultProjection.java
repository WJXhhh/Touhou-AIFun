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
