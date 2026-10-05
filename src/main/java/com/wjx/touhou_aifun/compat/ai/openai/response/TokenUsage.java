package com.wjx.touhou_aifun.compat.ai.openai.response;

import com.google.gson.JsonObject;

/** Numeric usage only. Missing cache fields are unknown, not a cache miss. */
public record TokenUsage(int input_tokens, int output_tokens, int total_tokens,
                         Integer cached_input_tokens, Integer uncached_input_tokens,
                         Integer cache_write_input_tokens, Integer reasoning_tokens) {
    public static JsonObject anthropic(JsonObject raw) {
        if (raw.has("prompt_tokens")) return raw.deepCopy(); // Already normalized streamed response.
        JsonObject normalized = raw.deepCopy();
        // Native Messages input_tokens excludes cache reads and writes. Include them in context/quota.
        int input = value(raw, "input_tokens") + value(raw, "cache_read_input_tokens")
                + value(raw, "cache_creation_input_tokens");
        int output = value(raw, "output_tokens");
        normalized.addProperty("prompt_tokens", input);
        normalized.addProperty("completion_tokens", output);
        normalized.addProperty("total_tokens", input + output);
        return normalized;
    }

    public static JsonObject responses(JsonObject raw) {
        JsonObject normalized = raw.deepCopy();
        int input = value(raw, "input_tokens"), output = value(raw, "output_tokens");
        normalized.addProperty("prompt_tokens", input);
        normalized.addProperty("completion_tokens", output);
        normalized.addProperty("total_tokens", raw.has("total_tokens") ? value(raw, "total_tokens") : input + output);
        return normalized;
    }

    public static TokenUsage read(JsonObject normalized) {
        int input = value(normalized, "prompt_tokens"), output = value(normalized, "completion_tokens");
        Integer cached = number(normalized, "prompt_cache_hit_tokens");
        if (cached == null) cached = number(normalized, "cache_read_input_tokens");
        if (cached == null) cached = nested(normalized, "prompt_tokens_details", "cached_tokens");
        if (cached == null) cached = nested(normalized, "input_tokens_details", "cached_tokens");
        Integer write = number(normalized, "cache_creation_input_tokens");
        Integer uncached = number(normalized, "prompt_cache_miss_tokens");
        if (uncached == null && cached != null && cached + (write == null ? 0 : write) <= input)
            uncached = input - cached - (write == null ? 0 : write);
        Integer reasoning = nested(normalized, "completion_tokens_details", "reasoning_tokens");
        if (reasoning == null) reasoning = nested(normalized, "output_tokens_details", "reasoning_tokens");
        return new TokenUsage(input, output, normalized.has("total_tokens") ? value(normalized,"total_tokens") : input + output,
                cached, uncached, write, reasoning);
    }

    private static Integer nested(JsonObject source, String object, String key) {
        return source.has(object) && source.get(object).isJsonObject() ? number(source.getAsJsonObject(object), key) : null;
    }
    private static int value(JsonObject source, String key) { Integer n = number(source, key); return n == null ? 0 : n; }
    private static Integer number(JsonObject source, String key) {
        try {
            if (!source.has(key) || !source.get(key).isJsonPrimitive() || !source.getAsJsonPrimitive(key).isNumber()) return null;
            int n = source.get(key).getAsInt(); return n < 0 ? null : n;
        } catch (RuntimeException ignored) { return null; }
    }
}
