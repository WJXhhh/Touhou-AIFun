package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.client.vision.CubemapCapture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Server -> owner client: capture the six camera faces for one observation. */
public record AIFunVisionCaptureRequestMessage(UUID requestId, int maidId, UUID maidUuid) {
    public static void encode(AIFunVisionCaptureRequestMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.requestId);
        buffer.writeVarInt(message.maidId);
        buffer.writeUUID(message.maidUuid);
    }

    public static AIFunVisionCaptureRequestMessage decode(FriendlyByteBuf buffer) {
        return new AIFunVisionCaptureRequestMessage(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID());
    }

    public static void handle(AIFunVisionCaptureRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(message));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(AIFunVisionCaptureRequestMessage message) {
        CubemapCapture.captureAndSend(message);
    }
}
