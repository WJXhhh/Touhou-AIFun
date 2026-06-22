package com.wjx.touhou_aifun.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;

import java.util.function.Supplier;

public record AIFunSettingsMessage(boolean sentenceStreaming) {
    public static void encode(AIFunSettingsMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.sentenceStreaming);
    }

    public static AIFunSettingsMessage decode(FriendlyByteBuf buffer) {
        return new AIFunSettingsMessage(buffer.readBoolean());
    }

    public static void handle(AIFunSettingsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (context.getSender() != null && context.getSender().hasPermissions(2)) {
                TouhouAIFunConfig.setSentenceStreaming(message.sentenceStreaming);
            }
        });
        context.setPacketHandled(true);
    }
}
