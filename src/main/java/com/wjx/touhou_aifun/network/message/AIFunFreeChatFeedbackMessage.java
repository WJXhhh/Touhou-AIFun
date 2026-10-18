package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.client.voice.FreeChatController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server -> client free-chat outcome. It carries a fixed reason code, never free text. */
public record AIFunFreeChatFeedbackMessage(String status, int maidId) {
    public AIFunFreeChatFeedbackMessage {
        status = status == null ? "" : (status.length() <= 32 ? status : status.substring(0, 32));
    }

    public static void encode(AIFunFreeChatFeedbackMessage message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.status, 32);
        buffer.writeVarInt(message.maidId);
    }

    public static AIFunFreeChatFeedbackMessage decode(FriendlyByteBuf buffer) {
        return new AIFunFreeChatFeedbackMessage(buffer.readUtf(32), buffer.readVarInt());
    }

    public static void handle(AIFunFreeChatFeedbackMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> receive(message));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void receive(AIFunFreeChatFeedbackMessage message) {
        FreeChatController.onFeedback(message.status, message.maidId);
    }

    @Override
    public String toString() {
        return "FreeChatFeedback[status=" + status + ", maidId=" + maidId + "]";
    }
}
