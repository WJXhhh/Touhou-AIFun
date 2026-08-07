package com.wjx.touhou_aifun.vision;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Structured envelope returned to the main LLM after the visual provider call. */
public record VisionObservation(String status, String siteId, String sceneSummary, String answerToFocus,
                                String rawText, String scanStatus, String error, long imageTick, long scanTick) {
    public static VisionObservation failed(String siteId, String error, long scanTick) {
        return new VisionObservation("failed", siteId, "", "", "", "not_run", error, -1, scanTick);
    }

    public String toJson() {
        JsonObject result = new JsonObject();
        result.addProperty("status", status);
        result.addProperty("site_id", siteId == null ? "" : siteId);
        result.addProperty("scene_summary", sceneSummary == null ? "" : sceneSummary);
        result.addProperty("answer_to_focus", answerToFocus == null ? "" : answerToFocus);
        result.addProperty("scan_status", scanStatus == null ? "not_run" : scanStatus);
        result.addProperty("image_tick", imageTick);
        result.addProperty("scan_tick", scanTick);
        if (rawText != null && !rawText.isBlank()) result.addProperty("raw_model_text", rawText);
        if (error != null && !error.isBlank()) result.addProperty("error", error);
        if (rawText != null) {
            try {
                JsonObject structured = JsonParser.parseString(stripJsonFence(rawText)).getAsJsonObject();
                for (String key : new String[]{"grounded_matches", "directions", "visible_text", "hazards", "uncertainties"}) {
                    if (structured.has(key)) result.add(key, structured.get(key));
                }
            } catch (Exception ignored) {
                // The raw text is still included above when a model ignores the JSON instruction.
            }
        }
        String json = new GsonBuilder().disableHtmlEscaping().create().toJson(result);
        if (json.length() <= 16 * 1024) return json;
        JsonObject compact = new JsonObject();
        compact.addProperty("status", status);
        compact.addProperty("site_id", siteId == null ? "" : siteId);
        compact.addProperty("scene_summary", truncate(sceneSummary, 4096));
        compact.addProperty("answer_to_focus", truncate(answerToFocus, 2048));
        compact.addProperty("scan_status", scanStatus == null ? "not_run" : scanStatus);
        compact.addProperty("image_tick", imageTick);
        compact.addProperty("scan_tick", scanTick);
        compact.addProperty("truncated", true);
        compact.addProperty("uncertainty", "visual provider response exceeded 16 KiB; raw text omitted");
        return new GsonBuilder().disableHtmlEscaping().create().toJson(compact);
    }

    private static String stripJsonFence(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```") && text.endsWith("```")) {
            int newline = text.indexOf('\n');
            if (newline >= 0) text = text.substring(newline + 1, text.length() - 3).trim();
        }
        return text;
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
