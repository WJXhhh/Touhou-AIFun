package com.wjx.touhou_aifun.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatFlowManagerSpeechTest {
    @Test
    void interruptingSpeechAdvancesTheTtsGenerationSoOlderAudioIsDropped() {
        UUID maid = UUID.randomUUID();
        int before = ChatFlowManager.ttsGeneration(maid);
        ChatFlowManager.interruptSpeech(maid);
        assertEquals(before + 1, ChatFlowManager.ttsGeneration(maid));
    }

    @Test
    void interruptingSpeechTwiceKeepsAdvancingSoAnInFlightReplyCannotTakeOver() {
        UUID maid = UUID.randomUUID();
        ChatFlowManager.interruptSpeech(maid);
        int afterFirst = ChatFlowManager.ttsGeneration(maid);
        ChatFlowManager.interruptSpeech(maid);
        assertEquals(afterFirst + 1, ChatFlowManager.ttsGeneration(maid));
    }
}
