package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.maid.management.MaidManagementService;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record AIFunMaidBatchConfigMessage(UUID sourceMaidId) {
    public static void encode(AIFunMaidBatchConfigMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.sourceMaidId);
    }

    public static AIFunMaidBatchConfigMessage decode(FriendlyByteBuf buffer) {
        return new AIFunMaidBatchConfigMessage(buffer.readUUID());
    }

    public static void handle(AIFunMaidBatchConfigMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            String status = MaidManagementService.copyConfigToAll(sender, message.sourceMaidId);
            AIFunNetwork.sendMaidListToPlayer(sender, status, false);
        });
        context.setPacketHandled(true);
    }
}
