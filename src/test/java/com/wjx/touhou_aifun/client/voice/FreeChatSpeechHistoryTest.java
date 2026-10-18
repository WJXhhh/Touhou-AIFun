package com.wjx.touhou_aifun.client.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeChatSpeechHistoryTest {
    @Test
    void keepsOnlyTheNewestThreeEntries() {
        FreeChatSpeechHistory history = new FreeChatSpeechHistory();
        history.add("one", 1_000L);
        history.add("two", 1_100L);
        history.add("three", 1_200L);
        history.add("four", 1_300L);
        assertEquals(List.of("two", "three", "four"), history.recent(1_300L));
    }

    @Test
    void expiresEntriesAfterOneMinute() {
        FreeChatSpeechHistory history = new FreeChatSpeechHistory();
        history.add("old", 1_000L);
        history.add("new", 60_000L);
        assertEquals(List.of("new"), history.recent(61_500L));
    }

    @Test
    void truncatesVeryLongSpeechToTheConfiguredLimit() {
        FreeChatSpeechHistory history = new FreeChatSpeechHistory();
        history.add("灵".repeat(500), 1_000L);
        List<String> recent = history.recent(1_000L);
        assertEquals(1, recent.size());
        assertEquals(FreeChatSpeechHistory.MAX_CODE_POINTS,
                recent.get(0).codePointCount(0, recent.get(0).length()));
    }

    @Test
    void removeLastDropsAnEchoedUtterance() {
        FreeChatSpeechHistory history = new FreeChatSpeechHistory();
        history.add("real speech", 1_000L);
        history.add("maid echo", 1_100L);
        history.removeLast();
        assertEquals(List.of("real speech"), history.recent(1_100L));
    }

    @Test
    void clearRemovesEverything() {
        FreeChatSpeechHistory history = new FreeChatSpeechHistory();
        history.add("hello", 1_000L);
        history.clear();
        assertTrue(history.recent(1_000L).isEmpty());
    }
}
