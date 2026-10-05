package com.wjx.touhou_aifun.chat;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Compares completed batch outcomes, ignoring ordering of JSON keys and transient scan metadata. */
public final class ToolBatchProgress {
    private static final Set<String> VOLATILE = Set.of("game_tick", "scan_tick", "scan_ticks", "timestamp",
            "captured_at", "capture_tick", "image_tick", "image_start_tick", "observation_id",
            "elapsed_ms", "elapsed_ticks", "duration_ms", "request_id");
    private String signature;
    private List<String> previousResults;
    private final List<String> results = new ArrayList<>();

    public boolean begin(String nextSignature) {
        boolean changed = nextSignature.equals(signature) && previousResults != null
                && !results.isEmpty() && !results.equals(previousResults);
        previousResults = nextSignature.equals(signature) ? List.copyOf(results) : null;
        signature = nextSignature;
        results.clear();
        return changed;
    }

    public void result(String value) {
        String normalized = value == null ? "" : value;
        try { normalized = canonical(JsonParser.parseString(normalized)).toString(); }
        catch (JsonParseException ignored) { /* Plain text outcomes are also valid. */ }
        try {
            results.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8))));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static JsonElement canonical(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject copy = new JsonObject();
            new TreeSet<>(value.getAsJsonObject().keySet()).stream().filter(key -> !VOLATILE.contains(key))
                    .forEach(key -> copy.add(key, canonical(value.getAsJsonObject().get(key))));
            return copy;
        }
        if (value.isJsonArray()) {
            JsonArray copy = new JsonArray();
            value.getAsJsonArray().forEach(element -> copy.add(canonical(element)));
            return copy;
        }
        return value;
    }
}
