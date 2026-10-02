package com.wjx.touhou_aifun.network.message;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AIFunChatGPTActionMessageTest {
    @Test void savedReasoningPreferencesReachServerWithoutLosingExistingSettings() {
        var message = new AIFunChatGPTActionMessage(UUID.randomUUID(), AIFunChatGPTActionMessage.Action.SAVE, 1456,
                true, "account", false, "xhigh", false);
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            AIFunChatGPTActionMessage.encode(message, buffer);
            assertEquals(message, AIFunChatGPTActionMessage.decode(buffer));
        } finally { buffer.release(); }
    }
}
