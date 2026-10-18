package com.wjx.touhou_aifun.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suppresses the same chat line being posted to the owner twice for one reply.
 *
 * <p>{@code ChatBubbleManager.addLLMChatText} does two things: it replaces the maid's overhead bubble
 * <em>and</em> posts a {@code <maid name> text} line into the owner's chat. Several flows legitimately
 * add the bubble early (so the text appears immediately, before TTS finishes) and then the TTS callback
 * adds it again on success/failure with the same text and bubble id. The second call used to post a
 * second, identical chat line.</p>
 *
 * <p>The identity of one bubble update is {@code (maid, waitingChatBubbleId, text)}:
 * <ul>
 *   <li>a positive bubble id belongs to one reply, so the same text+id cannot legitimately repeat;</li>
 *   <li>announcements use the fixed id {@code -1}, so only the short-window text match is available.</li>
 * </ul>
 * Backed by a bounded in-memory map with time-based pruning; it never keeps a strong reference to the
 * maid beyond its UUID.</p>
 */
public final class ChatLineDedup {
    /** A reply bubble id is unique per reply; one small guard window is plenty. */
    private static final long REPLY_WINDOW_MILLIS = 60_000;
    /** Announcements share id -1, so only suppress a rapid repeat of the exact same line. */
    private static final long ANNOUNCEMENT_WINDOW_MILLIS = 15_000;
    private static final int MAX_ENTRIES = 512;
    private static final Map<String, Long> RECENT = new ConcurrentHashMap<>();

    private ChatLineDedup() {
    }

    /**
     * Claims one bubble update. Returns {@code false} when the exact same update was already applied
     * within the guard window, in which case the caller must skip {@code addLLMChatText}.
     */
    public static boolean claim(UUID maidId, long bubbleId, String text) {
        return claimAt(maidId, bubbleId, text, System.currentTimeMillis());
    }

    static boolean claimAt(UUID maidId, long bubbleId, String text, long nowMillis) {
        String key = key(maidId, bubbleId, text);
        long window = bubbleId >= 0 ? REPLY_WINDOW_MILLIS : ANNOUNCEMENT_WINDOW_MILLIS;
        Long previous = RECENT.get(key);
        if (previous != null && nowMillis >= previous && nowMillis - previous < window) {
            return false;
        }
        if (RECENT.size() >= MAX_ENTRIES) {
            prune(nowMillis);
        }
        RECENT.put(key, nowMillis);
        return true;
    }

    private static String key(UUID maidId, long bubbleId, String text) {
        return (maidId == null ? "" : maidId.toString()) + '|' + bubbleId + '|' + (text == null ? "" : text);
    }

    private static void prune(long nowMillis) {
        RECENT.entrySet().removeIf(entry -> nowMillis - entry.getValue() >= REPLY_WINDOW_MILLIS);
        if (RECENT.size() >= MAX_ENTRIES) {
            RECENT.clear();
        }
    }

    /** Test hook only. */
    static void clearForTests() {
        RECENT.clear();
    }
}
