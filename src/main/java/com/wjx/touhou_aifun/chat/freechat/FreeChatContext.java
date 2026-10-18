package com.wjx.touhou_aifun.chat.freechat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Carries a free-chat turn's transient player-view/decision context through TLM's optional
 * asynchronous history-summary hop into {@code normalChat}. It is intentionally separate from
 * {@link com.wjx.touhou_aifun.chat.ChatSpeakerContext} so ordinary text/STT turns never acquire
 * ambient-voice wording.
 */
public final class FreeChatContext {
    private static final int MAX_PENDING_PER_MAID = 4;
    private static final long MAX_AGE_MILLIS = 60_000L;
    private static final Map<UUID, ArrayDeque<Pending>> PENDING = new HashMap<>();

    private FreeChatContext() {
    }

    public static synchronized void remember(EntityMaid maid, String message, String ambientFacts) {
        if (maid == null || message == null || ambientFacts == null || ambientFacts.isBlank()) {
            return;
        }
        UUID maidId = maid.getUUID();
        ArrayDeque<Pending> queue = PENDING.computeIfAbsent(maidId, ignored -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        prune(queue, now);
        while (queue.size() >= MAX_PENDING_PER_MAID) {
            queue.removeFirst();
        }
        queue.addLast(new Pending(message, ambientFacts, now));
    }

    @Nullable
    public static synchronized String take(EntityMaid maid, String message) {
        if (maid == null || message == null) {
            return null;
        }
        ArrayDeque<Pending> queue = PENDING.get(maid.getUUID());
        if (queue == null) {
            return null;
        }
        prune(queue, System.currentTimeMillis());
        Pending match = removeMatching(queue, message, true);
        if (match == null) {
            match = removeMatching(queue, message, false);
        }
        if (queue.isEmpty()) {
            PENDING.remove(maid.getUUID());
        }
        return match == null ? null : match.facts();
    }

    public static synchronized void clearMaid(UUID maidId) {
        if (maidId != null) {
            PENDING.remove(maidId);
        }
    }

    public static synchronized void clearAll() {
        PENDING.clear();
    }

    /** Inserts the ambient facts inside the latest context block, matching ChatSpeakerContext. */
    public static String attach(String messageWithContext, @Nullable String ambientFacts) {
        if (messageWithContext == null || ambientFacts == null || ambientFacts.isBlank()) {
            return messageWithContext;
        }
        int contextEnd = messageWithContext.indexOf("</context>");
        if (contextEnd < 0) {
            return "<context>\n" + ambientFacts + "</context>\n" + messageWithContext;
        }
        return messageWithContext.substring(0, contextEnd) + ambientFacts
                + messageWithContext.substring(contextEnd);
    }

    private static Pending removeMatching(ArrayDeque<Pending> queue, String message, boolean identityOnly) {
        Iterator<Pending> iterator = queue.iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next();
            boolean matches = identityOnly ? pending.message() == message
                    : Objects.equals(pending.message(), message);
            if (matches) {
                iterator.remove();
                return pending;
            }
        }
        return null;
    }

    private static void prune(ArrayDeque<Pending> queue, long now) {
        while (!queue.isEmpty() && now - queue.peekFirst().createdAtMillis() > MAX_AGE_MILLIS) {
            queue.removeFirst();
        }
    }

    private record Pending(String message, String facts, long createdAtMillis) {
    }
}
