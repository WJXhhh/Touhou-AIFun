package com.wjx.touhou_aifun.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AIFunFreeChatUtteranceMessageTest {
    @Test
    void utteranceSurvivesTheRoundTrip() {
        AIFunFreeChatUtteranceMessage message = new AIFunFreeChatUtteranceMessage(
                42L, List.of(101, 202), "灵梦，过来一下", "candidate 0 maid 灵梦",
                List.of("上一句", "再上一句"), "zh_cn", 1234);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AIFunFreeChatUtteranceMessage.encode(message, buffer);
            AIFunFreeChatUtteranceMessage decoded = AIFunFreeChatUtteranceMessage.decode(buffer);
            assertEquals(message.sequence(), decoded.sequence());
            assertEquals(message.candidateMaidIds(), decoded.candidateMaidIds());
            assertEquals(message.transcript(), decoded.transcript());
            assertEquals(message.playerScene(), decoded.playerScene());
            assertEquals(message.recentUtterances(), decoded.recentUtterances());
            assertEquals(message.clientLanguage(), decoded.clientLanguage());
            assertEquals(message.speechMillis(), decoded.speechMillis());
        } finally {
            buffer.release();
        }
    }

    @Test
    void toStringDoesNotLeakSpeechOrSceneText() {
        AIFunFreeChatUtteranceMessage message = new AIFunFreeChatUtteranceMessage(
                1L, List.of(1), "secret transcript", "secret scene", List.of("secret history"),
                "en_us", 100);
        String text = message.toString();
        assertFalse(text.contains("secret"));
        assertFalse(text.contains("灵梦"));
    }

    @Test
    void candidateAndRecentListsAreBoundedOnConstruction() {
        List<Integer> ids = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        AIFunFreeChatUtteranceMessage message = new AIFunFreeChatUtteranceMessage(
                1L, ids, "hello", "scene",
                List.of("a", "b", "c", "d", "e"), "en_us", 100);
        assertEquals(8, message.candidateMaidIds().size());
        assertEquals(3, message.recentUtterances().size());
    }
}
