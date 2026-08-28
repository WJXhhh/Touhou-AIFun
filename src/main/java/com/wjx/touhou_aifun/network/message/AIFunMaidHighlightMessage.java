package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.maid.management.MaidManagementService;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record AIFunMaidHighlightMessage(UUID maidId) {
    public static void encode(AIFunMaidHighlightMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.maidId);
    }

    public static AIFunMaidHighlightMessage decode(FriendlyByteBuf buffer) {
        return new AIFunMaidHighlightMessage(buffer.readUUID());
    }

    public static void handle(AIFunMaidHighlightMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            String status = MaidManagementService.highlight(sender, message.maidId);
            AIFunNetwork.sendMaidListToPlayer(sender, status, false);
        });
        context.setPacketHandled(true);
    }
}
