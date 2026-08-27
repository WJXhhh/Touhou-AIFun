package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.maid.management.MaidAIConfigSnapshot;
import com.wjx.touhou_aifun.maid.management.MaidManagementService;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record AIFunMaidConfigSaveMessage(UUID maidId, MaidAIConfigSnapshot config,
                                         boolean publicMaid, boolean friendlyFireAllowed, int fieldMask) {
    public static void encode(AIFunMaidConfigSaveMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.maidId);
        message.config.write(buffer);
        buffer.writeBoolean(message.publicMaid);
        buffer.writeBoolean(message.friendlyFireAllowed);
        buffer.writeVarInt(message.fieldMask);
    }

    public static AIFunMaidConfigSaveMessage decode(FriendlyByteBuf buffer) {
        return new AIFunMaidConfigSaveMessage(buffer.readUUID(), MaidAIConfigSnapshot.read(buffer),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt());
    }

    public static void handle(AIFunMaidConfigSaveMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            String status = MaidManagementService.saveConfig(sender, message.maidId, message.config,
                    message.publicMaid, message.friendlyFireAllowed, message.fieldMask);
            AIFunNetwork.sendMaidListToPlayer(sender, status, false);
        });
        context.setPacketHandled(true);
    }
}
