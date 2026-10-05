package com.wjx.touhou_aifun.chat.agent;

import java.util.*;

/** Bounded, execution-local raw evidence; references never cause a tool to run again. */
public final class TaskResultStore {
    private final LinkedHashMap<String, String> values = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> expired = new LinkedHashMap<>();
    private final int capacity;
    private int chars;
    private long sequence;
    private String prefix = UUID.randomUUID().toString().substring(0, 12);
    private Runnable changed = () -> { };
    public record State(int version, int capacity, String prefix, long sequence,
                        Map<String, String> values, Map<String, String> expired) { }
    public TaskResultStore() { this(512 * 1024); }
    public TaskResultStore(int capacity) { this.capacity = capacity; }
    public TaskResultStore(State state) {
        this(state.capacity());
        if (state.version() != 1 || capacity < 1 || capacity > 512 * 1024 || state.prefix() == null
                || state.sequence() < 0 || state.values() == null || state.values().size() > 128
                || state.expired() == null || state.expired().size() > 128) throw new IllegalArgumentException("invalid_result_store");
        prefix = state.prefix(); sequence = state.sequence();
        state.values().forEach((id, value) -> { values.put(id, value); chars += value.length(); });
        if (chars > capacity) throw new IllegalArgumentException("result_store_over_budget");
        state.expired().forEach((id, value) -> expired.put(id, AgentTaskState.bounded(value, 256)));
    }
    public synchronized State snapshot() {
        return new State(1, capacity, prefix, sequence, new LinkedHashMap<>(values), new LinkedHashMap<>(expired));
    }
    public synchronized void onChange(Runnable listener) { changed = Objects.requireNonNull(listener); }
    public synchronized boolean contains(String id) { return values.containsKey(id) || expired.containsKey(id); }
    public synchronized String put(String value) {
        String id = "result_" + prefix + "_" + (++sequence);
        if (value.length() > capacity) { expire(id, value); changed.run(); return id; }
        values.put(id, value); chars += value.length();
        while (chars > capacity || values.size() > 128) {
            var first = values.entrySet().iterator(); var entry = first.next(); chars -= entry.getValue().length();
            expire(entry.getKey(), entry.getValue()); first.remove();
        }
        changed.run(); return id;
    }
    private void expire(String id, String value) {
        expired.put(id, AgentTaskState.bounded(value, 256));
        while (expired.size() > 128) expired.remove(expired.keySet().iterator().next());
    }
    public synchronized String read(String id, int offset) {
        String value = values.get(id);
        if (value == null) return "result_expired: raw evidence is unavailable; this does not authorize replaying actions.\nsummary="
                + expired.getOrDefault(id, "No retained summary; reference is unknown or its summary also expired.");
        return page(value, offset);
    }
    public static String page(String value, int offset) {
        int length = value.codePointCount(0, value.length());
        if (offset < 0 || offset > length) return "invalid_offset";
        int end = Math.min(length, offset + 4096);
        while (end > offset && com.wjx.touhou_aifun.chat.context.ContextTokenEstimator.estimate(
                value.substring(value.offsetByCodePoints(0, offset), value.offsetByCodePoints(0, end))) > 1950)
            end = offset + Math.max(1, (end - offset) * 3 / 4);
        return "offset=" + offset + " next_offset=" + end + " complete=" + (end == length) + "\n"
                + value.substring(value.offsetByCodePoints(0, offset), value.offsetByCodePoints(0, end));
    }
}
