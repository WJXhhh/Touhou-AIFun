package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.maid.management.MaidManagementService;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record AIFunMaidOpenBehaviorMessage(UUID maidId) {
    public static void encode(AIFunMaidOpenBehaviorMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.maidId);
    }

    public static AIFunMaidOpenBehaviorMessage decode(FriendlyByteBuf buffer) {
        return new AIFunMaidOpenBehaviorMessage(buffer.readUUID());
    }

    public static void handle(AIFunMaidOpenBehaviorMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            String status = MaidManagementService.openBehavior(sender, message.maidId);
            // A successful call sends TLM's native open-menu packet. Only failures need to refresh
            // the still-open management screen with an explanatory status.
            if (!"behavior_opened".equals(status)) {
                AIFunNetwork.sendMaidListToPlayer(sender, status, false);
            }
        });
        context.setPacketHandled(true);
    }
}
