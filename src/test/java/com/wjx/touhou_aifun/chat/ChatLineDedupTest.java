package com.wjx.touhou_aifun.chat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLineDedupTest {
    @AfterEach
    void cleanup() {
        ChatLineDedup.clearForTests();
    }

    @Test
    void duplicateReplyBubbleUpdateIsSuppressedButOtherUpdatesPass() {
        UUID maid = UUID.randomUUID();
        assertTrue(ChatLineDedup.claimAt(maid, 7L, "完成啦", 1_000));
        // TTS callback repeats the exact same reply bubble update.
        assertFalse(ChatLineDedup.claimAt(maid, 7L, "完成啦", 2_000));
        // A different reply has a different bubble id; identical words are still new content.
        assertTrue(ChatLineDedup.claimAt(maid, 8L, "完成啦", 2_000));
        // Same bubble id with new text is a real update (live bubble -> final text).
        assertTrue(ChatLineDedup.claimAt(maid, 7L, "另一句", 2_000));
        // The reply guard window expires eventually.
        assertTrue(ChatLineDedup.claimAt(maid, 7L, "完成啦", 62_000));
    }

    @Test
    void announcementUsesShortTextWindow() {
        UUID maid = UUID.randomUUID();
        assertTrue(ChatLineDedup.claimAt(maid, -1L, "东西都放回去了～", 10_000));
        // The announcement TTS callback repeats the same line shortly afterwards.
        assertFalse(ChatLineDedup.claimAt(maid, -1L, "东西都放回去了～", 12_000));
        // A later task may legitimately phrase the exact same line again.
        assertTrue(ChatLineDedup.claimAt(maid, -1L, "东西都放回去了～", 26_000));
    }

    @Test
    void differentMaidsAreIndependent() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertTrue(ChatLineDedup.claimAt(first, -1L, "好呀", 1_000));
        assertTrue(ChatLineDedup.claimAt(second, -1L, "好呀", 1_000));
        assertFalse(ChatLineDedup.claimAt(first, -1L, "好呀", 2_000));
    }

    @Test
    void nullTextIsHandled() {
        UUID maid = UUID.randomUUID();
        assertTrue(ChatLineDedup.claimAt(maid, -1L, null, 1_000));
        assertFalse(ChatLineDedup.claimAt(maid, -1L, null, 2_000));
    }
}
