package com.wjx.touhou_aifun.client.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeChatNoticeQueueTest {
    @Test
    void keepsOnlyTheNewestThreeLines() {
        FreeChatNoticeQueue queue = new FreeChatNoticeQueue();
        queue.push("one", 1_000L);
        queue.push("two", 1_100L);
        queue.push("three", 1_200L);
        queue.push("four", 1_300L);
        assertEquals(List.of("two", "three", "four"),
                queue.visible(1_300L).stream().map(FreeChatNoticeQueue.Entry::text).toList());
    }

    @Test
    void repeatingTheNewestLineRefreshesItInsteadOfStacking() {
        FreeChatNoticeQueue queue = new FreeChatNoticeQueue();
        queue.push("low_confidence", 1_000L);
        queue.push("low_confidence", 4_000L);
        List<FreeChatNoticeQueue.Entry> visible = queue.visible(4_000L);
        assertEquals(1, visible.size());
        assertEquals(4_000L, visible.get(0).at());
    }

    @Test
    void repeatedTextOnlyRefreshesWhenItIsTheNewestLine() {
        FreeChatNoticeQueue queue = new FreeChatNoticeQueue();
        queue.push("ignored", 1_000L);
        queue.push("heard", 1_100L);
        queue.push("ignored", 1_200L);
        List<FreeChatNoticeQueue.Entry> visible = queue.visible(1_200L);
        assertEquals(3, visible.size());
        assertEquals(List.of("ignored", "heard", "ignored"),
                visible.stream().map(FreeChatNoticeQueue.Entry::text).toList());
    }

    @Test
    void expiredLinesArePruned() {
        FreeChatNoticeQueue queue = new FreeChatNoticeQueue();
        queue.push("old", 1_000L);
        long expiredAt = 1_000L + FreeChatNoticeQueue.HOLD_MILLIS + FreeChatNoticeQueue.FADE_MILLIS;
        assertTrue(queue.visible(expiredAt).isEmpty());
        assertTrue(queue.isEmpty());
    }

    @Test
    void alphaHoldsThenFadesToZero() {
        FreeChatNoticeQueue.Entry entry = new FreeChatNoticeQueue.Entry("line", 1_000L);
        assertEquals(FreeChatNoticeQueue.FULL_ALPHA, FreeChatNoticeQueue.alpha(entry, 1_000L));
        assertEquals(FreeChatNoticeQueue.FULL_ALPHA,
                FreeChatNoticeQueue.alpha(entry, 1_000L + FreeChatNoticeQueue.HOLD_MILLIS));
        int midway = FreeChatNoticeQueue.alpha(entry,
                1_000L + FreeChatNoticeQueue.HOLD_MILLIS + FreeChatNoticeQueue.FADE_MILLIS / 2);
        assertTrue(midway > 0 && midway < FreeChatNoticeQueue.FULL_ALPHA,
                "halfway through the fade the alpha should be between 0 and 255, was " + midway);
        assertEquals(0, FreeChatNoticeQueue.alpha(entry,
                1_000L + FreeChatNoticeQueue.HOLD_MILLIS + FreeChatNoticeQueue.FADE_MILLIS));
    }

    @Test
    void alphaStartsAtFullOnTheFirstFrame() {
        FreeChatNoticeQueue.Entry entry = new FreeChatNoticeQueue.Entry("line", 5_000L);
        assertEquals(FreeChatNoticeQueue.FULL_ALPHA, FreeChatNoticeQueue.alpha(entry, 5_000L));
    }

    @Test
    void visibleReturnsOldestFirst() {
        FreeChatNoticeQueue queue = new FreeChatNoticeQueue();
        queue.push("first", 1_000L);
        queue.push("second", 1_100L);
        assertEquals(List.of("first", "second"),
                queue.visible(1_100L).stream().map(FreeChatNoticeQueue.Entry::text).toList());
    }

    @Test
    void blankTextIsIgnored() {
        FreeChatNoticeQueue queue = new FreeChatNoticeQueue();
        queue.push("", 1_000L);
        queue.push("   ", 1_000L);
        queue.push(null, 1_000L);
        assertTrue(queue.isEmpty());
    }

    @Test
    void clearRemovesEverything() {
        FreeChatNoticeQueue queue = new FreeChatNoticeQueue();
        queue.push("line", 1_000L);
        queue.clear();
        assertTrue(queue.isEmpty());
        assertFalse(queue.visible(1_000L).iterator().hasNext());
    }
}
