package com.wjx.touhou_aifun.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIFunMaidSpeechStopMessageTest {
    @Test
    void maidIdSurvivesTheRoundTrip() {
        var message = new AIFunMaidSpeechStopMessage(4821);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AIFunMaidSpeechStopMessage.encode(message, buffer);
            assertEquals(message, AIFunMaidSpeechStopMessage.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
