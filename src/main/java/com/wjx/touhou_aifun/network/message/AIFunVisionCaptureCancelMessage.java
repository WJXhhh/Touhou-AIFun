package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.client.vision.CubemapCapture;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Server -> owner client: stop one exact queued/rendering/encoding capture. */
public record AIFunVisionCaptureCancelMessage(UUID requestId, UUID maidUuid) {
    public static void encode(AIFunVisionCaptureCancelMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.requestId);
        buffer.writeUUID(message.maidUuid);
    }

    public static AIFunVisionCaptureCancelMessage decode(FriendlyByteBuf buffer) {
        return new AIFunVisionCaptureCancelMessage(buffer.readUUID(), buffer.readUUID());
    }

    public static void handle(AIFunVisionCaptureCancelMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(message));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(AIFunVisionCaptureCancelMessage message) {
        CubemapCapture.cancelRequest(message.requestId, message.maidUuid);
    }
}
