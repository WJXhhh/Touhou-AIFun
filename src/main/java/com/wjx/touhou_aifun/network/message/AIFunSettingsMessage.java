package com.wjx.touhou_aifun.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import java.util.function.Supplier;

public record AIFunSettingsMessage(boolean sentenceStreaming, boolean llmStreaming,
                                   boolean emotionControl, boolean emotionInText) {
    public static void encode(AIFunSettingsMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.sentenceStreaming);
        buffer.writeBoolean(message.llmStreaming);
        buffer.writeBoolean(message.emotionControl);
        buffer.writeBoolean(message.emotionInText);
    }

    public static AIFunSettingsMessage decode(FriendlyByteBuf buffer) {
        boolean sentenceStreaming = buffer.readBoolean();
        boolean llmStreaming = buffer.readBoolean();
        boolean emotionControl = buffer.readBoolean();
        boolean emotionInText = buffer.readBoolean();
        return new AIFunSettingsMessage(sentenceStreaming, llmStreaming, emotionControl, emotionInText);
    }

    public static void handle(AIFunSettingsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null && context.getSender().hasPermissions(2)) {
                TouhouAIFunConfig.setSentenceStreaming(message.sentenceStreaming);
                TouhouAIFunConfig.setLlmStreaming(message.llmStreaming);
                TouhouAIFunConfig.setEmotionControl(message.emotionControl);
                TouhouAIFunConfig.setEmotionInText(message.emotionInText);
            }
        });
        context.setPacketHandled(true);
    }
}
