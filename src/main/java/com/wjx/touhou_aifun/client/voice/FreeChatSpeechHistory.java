package com.wjx.touhou_aifun.client.voice;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Short, in-memory history of the local player's ambient speech. It is sent with the next free-chat
 * utterance so the decision service can judge whether a conversation with another player is about
 * the maid. It is never persisted and never written to logs.
 */
public final class FreeChatSpeechHistory {
    static final int MAX_ENTRIES = 3;
    static final int MAX_CODE_POINTS = 200;
    static final long TTL_MILLIS = 60_000L;

    private final Deque<Entry> entries = new ArrayDeque<>();

    public synchronized void add(String text) {
        add(text, System.currentTimeMillis());
    }

    synchronized void add(String text, long nowMillis) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return;
        }
        prune(nowMillis);
        entries.addLast(new Entry(normalized, nowMillis));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    /** Removes the newest entry; used when the server says it was the maid's own TTS echo. */
    public synchronized void removeLast() {
        entries.pollLast();
    }

    /** Oldest to newest, excluding the utterance currently being sent. */
    public synchronized List<String> recent() {
        return recent(System.currentTimeMillis());
    }

    synchronized List<String> recent(long nowMillis) {
        prune(nowMillis);
        List<String> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            result.add(entry.text());
        }
        return List.copyOf(result);
    }

    public synchronized void clear() {
        entries.clear();
    }

    private void prune(long nowMillis) {
        while (!entries.isEmpty() && nowMillis - entries.peekFirst().atMillis() > TTL_MILLIS) {
            entries.removeFirst();
        }
    }

    static String normalize(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        String collapsed = text.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").trim();
        int codePoints = collapsed.codePointCount(0, collapsed.length());
        if (codePoints <= MAX_CODE_POINTS) {
            return collapsed;
        }
        int end = collapsed.offsetByCodePoints(0, MAX_CODE_POINTS);
        return collapsed.substring(0, end).trim();
    }

    private record Entry(String text, long atMillis) {
    }
}
