package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Client -> server: fail a pending capture immediately instead of waiting for timeout. */
public record AIFunVisionCaptureFailureMessage(UUID requestId, int maidId, UUID maidUuid, String reason) {
    public static void encode(AIFunVisionCaptureFailureMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.requestId);
        buffer.writeVarInt(message.maidId);
        buffer.writeUUID(message.maidUuid);
        buffer.writeUtf(message.reason == null ? "capture_failed" : message.reason, 128);
    }

    public static AIFunVisionCaptureFailureMessage decode(FriendlyByteBuf buffer) {
        return new AIFunVisionCaptureFailureMessage(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(),
                buffer.readUtf(128));
    }

    public static void handle(AIFunVisionCaptureFailureMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> AIFunNetwork.acceptVisionCaptureFailure(sender, message));
        }
        context.setPacketHandled(true);
    }
}
