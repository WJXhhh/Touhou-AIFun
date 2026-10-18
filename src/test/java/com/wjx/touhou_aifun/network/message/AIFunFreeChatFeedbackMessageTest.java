package com.wjx.touhou_aifun.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIFunFreeChatFeedbackMessageTest {
    @Test
    void feedbackSurvivesTheRoundTrip() {
        AIFunFreeChatFeedbackMessage message = new AIFunFreeChatFeedbackMessage("not_related", 77);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AIFunFreeChatFeedbackMessage.encode(message, buffer);
            assertEquals(message, AIFunFreeChatFeedbackMessage.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
