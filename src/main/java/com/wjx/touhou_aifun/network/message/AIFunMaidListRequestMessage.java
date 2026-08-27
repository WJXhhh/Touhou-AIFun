package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AIFunMaidListRequestMessage(boolean openScreen) {
    public static void encode(AIFunMaidListRequestMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.openScreen);
    }

    public static AIFunMaidListRequestMessage decode(FriendlyByteBuf buffer) {
        return new AIFunMaidListRequestMessage(buffer.readBoolean());
    }

    public static void handle(AIFunMaidListRequestMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null) {
                AIFunNetwork.sendMaidListToPlayer(sender, "", message.openScreen);
            }
        });
        context.setPacketHandled(true);
    }
}
