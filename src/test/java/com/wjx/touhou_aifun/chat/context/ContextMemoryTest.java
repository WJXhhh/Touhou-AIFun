package com.wjx.touhou_aifun.chat.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextMemoryTest {
    @Test
    void tokenEstimatorChargesProtocolAndCjkEmoji() {
        assertEquals(11, ContextTokenEstimator.estimate("abcd"));
        assertTrue(ContextTokenEstimator.estimate("你好😀") > ContextTokenEstimator.estimate("abcd"));
    }

    @Test
    void retrieverPrefersStrongOldMatchOverUnrelatedRecentEpisode() {
        MemoryEpisode oldMatch = new MemoryEpisode("old", "玩家喜欢红茶，并在 mod:item_chest 中保存红茶。",
                List.of("红茶", "mod:item_chest"), 1, 1, 1, List.of(1L));
        MemoryEpisode recentNoise = new MemoryEpisode("noise", "天气晴朗，玩家在广场散步。",
                List.of("天气"), 0, 99, 99, List.of(99L));

        List<MemoryEpisode> result = LocalMemoryRetriever.topEpisodes("红茶", List.of(recentNoise, oldMatch),
                List.of(), 100, 6);
        assertEquals(List.of("old"), result.stream().map(MemoryEpisode::id).toList());
    }

    @Test
    void interruptedTurnNeverLooksCompleted() {
        ConversationTurn turn = new ConversationTurn(7, "先做 A", 10);
        turn.interrupt();
        assertEquals(ConversationTurn.Status.INTERRUPTED, turn.status());
        assertFalse(turn.status() == ConversationTurn.Status.COMPLETED);
    }

    @Test
    void emptyExtractionDeltaIsRejected() {
        MemoryExtractionDelta delta = new MemoryExtractionDelta();
        assertFalse(delta.hasChanges());
        delta.setEpisode("一个结果", List.of("结果"), 2);
        assertTrue(delta.hasChanges());
    }

    @Test
    void memoryStateRoundTripsThroughAddonCodec() {
        MaidMemoryState state = new MaidMemoryState();
        long id = state.nextTurnId();
        ConversationTurn turn = new ConversationTurn(id, "用户喜欢红茶", 42);
        turn.complete("记住了");
        state.turns().add(turn);
        state.facts().add(new MemoryFact("fact-local", "preference", "用户喜欢红茶", 3, 42, List.of(id)));
        String encoded = MemoryStateCodec.encode(state);
        MaidMemoryState decoded = MemoryStateCodec.decode(encoded);
        assertEquals(1, decoded.turns().size());
        assertEquals("用户喜欢红茶", decoded.facts().get(0).text());
        assertEquals(MaidMemoryState.SCHEMA_VERSION, decoded.schemaVersion());
    }

}
