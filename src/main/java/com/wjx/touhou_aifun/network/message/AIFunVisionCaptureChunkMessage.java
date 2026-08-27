package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Client -> server chunk for an in-memory JPEG face. */
public record AIFunVisionCaptureChunkMessage(UUID requestId, int maidId, UUID maidUuid, String face, int chunkIndex,
                                              int chunkCount, byte[] data, float captureYaw,
                                              long captureStartTick, long captureEndTick) {
    public static void encode(AIFunVisionCaptureChunkMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.requestId);
        buffer.writeVarInt(message.maidId);
        buffer.writeUUID(message.maidUuid);
        buffer.writeUtf(message.face, 32);
        buffer.writeVarInt(message.chunkIndex);
        buffer.writeVarInt(message.chunkCount);
        buffer.writeByteArray(message.data == null ? new byte[0] : message.data);
        buffer.writeFloat(message.captureYaw);
        buffer.writeLong(message.captureStartTick);
        buffer.writeLong(message.captureEndTick);
    }

    public static AIFunVisionCaptureChunkMessage decode(FriendlyByteBuf buffer) {
        return new AIFunVisionCaptureChunkMessage(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(), buffer.readUtf(32),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(32 * 1024), buffer.readFloat(),
                buffer.readLong(), buffer.readLong());
    }

    public static void handle(AIFunVisionCaptureChunkMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> AIFunNetwork.acceptVisionCaptureChunk(sender, message));
        }
        context.setPacketHandled(true);
    }
}
