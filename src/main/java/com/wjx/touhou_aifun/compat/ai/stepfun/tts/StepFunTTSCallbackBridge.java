package com.wjx.touhou_aifun.compat.ai.stepfun.tts;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.TTSCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import com.wjx.touhou_aifun.TouhouStepFun;

import java.lang.reflect.Field;

final class StepFunTTSCallbackBridge {
    private static final Field CHAT_TEXT = findField("chatText");
    private static final Field WAITING_CHAT_BUBBLE_ID = findField("waitingChatBubbleId");

    private StepFunTTSCallbackBridge() {
    }

    static void complete(TTSCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (!(maid.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        try {
            String chatText = (String) CHAT_TEXT.get(callback);
            long waitingChatBubbleId = WAITING_CHAT_BUBBLE_ID.getLong(callback);
            MinecraftServer server = serverLevel.getServer();
            server.submit(() -> maid.getChatBubbleManager().addLLMChatText(chatText, waitingChatBubbleId));
        } catch (ReflectiveOperationException e) {
            TouhouStepFun.LOGGER.error("Failed to complete streamed TTS chat bubble", e);
        }
    }

    private static Field findField(String name) {
        try {
            Field field = TTSCallback.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
