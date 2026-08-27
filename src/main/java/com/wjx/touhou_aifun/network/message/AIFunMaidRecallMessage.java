package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.maid.management.MaidManagementService;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record AIFunMaidRecallMessage(UUID maidId) {
    public static void encode(AIFunMaidRecallMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.maidId);
    }

    public static AIFunMaidRecallMessage decode(FriendlyByteBuf buffer) {
        return new AIFunMaidRecallMessage(buffer.readUUID());
    }

    public static void handle(AIFunMaidRecallMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            String status = MaidManagementService.recall(sender, message.maidId);
            AIFunNetwork.sendMaidListToPlayer(sender, status, false);
        });
        context.setPacketHandled(true);
    }
}
