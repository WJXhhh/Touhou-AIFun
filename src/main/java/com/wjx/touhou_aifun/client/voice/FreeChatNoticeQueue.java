package com.wjx.touhou_aifun.client.voice;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure data model behind the free-chat HUD notice strip: at most three lines, a repeat of the
 * newest line refreshes it instead of stacking, and every line expires after
 * {@link #HOLD_MILLIS} + {@link #FADE_MILLIS}. Rendering lives in {@link FreeChatNotifier}; this
 * class intentionally has no Minecraft dependency so retention and fading stay unit-testable.
 */
public final class FreeChatNoticeQueue {
    public static final int MAX_LINES = 3;
    public static final long HOLD_MILLIS = 4500L;
    public static final long FADE_MILLIS = 700L;
    public static final int FULL_ALPHA = 255;

    public record Entry(String text, long at) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public void push(String text, long now) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!entries.isEmpty() && entries.get(entries.size() - 1).text().equals(text)) {
            entries.set(entries.size() - 1, new Entry(text, now));
            return;
        }
        entries.add(new Entry(text, now));
        while (entries.size() > MAX_LINES) {
            entries.remove(0);
        }
    }

    public void prune(long now) {
        entries.removeIf(entry -> alpha(entry, now) <= 0);
    }

    /** Oldest first; expired entries are pruned before the snapshot is returned. */
    public List<Entry> visible(long now) {
        prune(now);
        return List.copyOf(entries);
    }

    /** 255 while the line is held, then a linear fade to 0, then 0 for expired lines. */
    public static int alpha(Entry entry, long now) {
        long age = Math.max(0L, now - entry.at());
        if (age >= HOLD_MILLIS + FADE_MILLIS) {
            return 0;
        }
        if (age <= HOLD_MILLIS) {
            return FULL_ALPHA;
        }
        double remaining = 1.0D - (double) (age - HOLD_MILLIS) / (double) FADE_MILLIS;
        return (int) Math.round(FULL_ALPHA * Math.max(0.0D, Math.min(1.0D, remaining)));
    }

    public void clear() {
        entries.clear();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
