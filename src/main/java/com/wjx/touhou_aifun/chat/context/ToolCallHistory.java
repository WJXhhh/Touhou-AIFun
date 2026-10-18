package com.wjx.touhou_aifun.chat.context;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure helpers for the bounded recent-tool-call history. No Minecraft types, so the ring-buffer,
 * summary projection and paging rules are unit-testable.
 */
public final class ToolCallHistory {
    public static final int MAX_RECORDS = 100;
    public static final int SUMMARY_CODE_POINTS = 100;
    private static final int PAGE_DEFAULT = 10;
    private static final int PAGE_MAX = 50;

    private ToolCallHistory() {
    }

    /** Append and keep the newest {@link #MAX_RECORDS} entries. */
    public static void append(List<ToolCallRecord> records, ToolCallRecord record) {
        if (records == null || record == null) return;
        records.add(record);
        while (records.size() > MAX_RECORDS) records.remove(0);
    }

    public static int pageSize(int requested) {
        if (requested <= 0) return PAGE_DEFAULT;
        return Math.min(requested, PAGE_MAX);
    }

    /** Newest-first page; {@code offset} counts back from the newest record. */
    public static List<ToolCallRecord> page(List<ToolCallRecord> records, int offset, int limit,
                                            String toolFilter) {
        if (records == null || records.isEmpty()) return List.of();
        String filter = toolFilter == null ? "" : toolFilter.trim().toLowerCase(Locale.ROOT);
        List<ToolCallRecord> newestFirst = new ArrayList<>();
        for (int i = records.size() - 1; i >= 0; i--) {
            ToolCallRecord record = records.get(i);
            if (!filter.isBlank() && !record.tool().toLowerCase(Locale.ROOT).contains(filter)) continue;
            newestFirst.add(record);
        }
        int from = Math.max(0, offset);
        if (from >= newestFirst.size()) return List.of();
        int to = Math.min(newestFirst.size(), from + pageSize(limit));
        return List.copyOf(newestFirst.subList(from, to));
    }

    /**
     * Compact one tool result into at most {@link #SUMMARY_CODE_POINTS} code points, preferring the
     * fields that matter for later questions (status, target, coordinates, counts) over raw JSON.
     */
    public static String summarize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String text = raw.trim();
        try {
            JsonObject value = JsonParser.parseString(text).getAsJsonObject();
            StringBuilder out = new StringBuilder();
            appendField(out, value, "status");
            appendField(out, value, "error");
            appendField(out, value, "intent");
            appendField(out, value, "target");
            appendField(out, value, "actual_block");
            appendField(out, value, "target_position");
            appendField(out, value, "maid_position_at_scan_start");
            appendField(out, value, "moved_item");
            appendField(out, value, "moved_count");
            appendField(out, value, "to_maid");
            appendField(out, value, "delivered_count");
            appendField(out, value, "goal_item");
            appendField(out, value, "goal_count");
            appendField(out, value, "important_blocks");
            appendField(out, value, "observed_cell_count");
            appendField(out, value, "result_ref");
            if (out.isEmpty()) {
                // Fall back to the whole compact JSON with volatile whitespace removed.
                out.append(text.replaceAll("\\s+", " "));
            }
            return truncate(out.toString(), SUMMARY_CODE_POINTS);
        } catch (RuntimeException ignored) {
            return truncate(text.replaceAll("\\s+", " "), SUMMARY_CODE_POINTS);
        }
    }

    /** Short status derived from the projected summary for the persisted record. */
    public static String statusOf(String raw) {
        if (raw == null || raw.isBlank()) return "empty";
        String text = raw.trim();
        try {
            JsonObject value = JsonParser.parseString(text).getAsJsonObject();
            for (String key : List.of("status", "error", "completion_error")) {
                if (value.has(key) && value.get(key).isJsonPrimitive()) {
                    return truncate(value.get(key).getAsString(), 40);
                }
            }
            if (value.has("important_blocks") || value.has("observed_blocks")) return "observed";
        } catch (RuntimeException ignored) {
            // Plain-text tool result.
        }
        return text.length() > 40 ? "ok" : truncate(text, 40);
    }

    private static void appendField(StringBuilder out, JsonObject value, String key) {
        JsonElement element = value.get(key);
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive()) {
            if (!out.isEmpty()) out.append(' ');
            out.append(key).append('=').append(element.getAsString());
        } else if (element.isJsonArray()) {
            if (!out.isEmpty()) out.append(' ');
            out.append(key).append("[]=").append(element.getAsJsonArray().size());
        } else if (element.isJsonObject()) {
            if (!out.isEmpty()) out.append(' ');
            out.append(key).append("{}");
        }
    }

    static String truncate(String text, int maxCodePoints) {
        String value = text == null ? "" : text.trim();
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        int keep = Math.max(0, maxCodePoints - 3);
        return value.substring(0, value.offsetByCodePoints(0, keep)) + "...";
    }
}
