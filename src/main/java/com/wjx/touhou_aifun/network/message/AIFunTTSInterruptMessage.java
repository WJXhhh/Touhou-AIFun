package com.wjx.touhou_aifun.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import com.wjx.touhou_aifun.client.sound.AIFunStreamSoundManager;
import com.wjx.touhou_aifun.client.sound.QueuedTTSPlaybackManager;

import java.util.function.Supplier;

/**
 * Server -> client: stop any TTS audio currently queued/playing for the maid, so a newer reply can
 * take over immediately.
 */
public record AIFunTTSInterruptMessage(int maidId) {
    public static void encode(AIFunTTSInterruptMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.maidId);
    }

    public static AIFunTTSInterruptMessage decode(FriendlyByteBuf buffer) {
        return new AIFunTTSInterruptMessage(buffer.readVarInt());
    }

    public static void handle(AIFunTTSInterruptMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(message));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(AIFunTTSInterruptMessage message) {
        QueuedTTSPlaybackManager.interrupt();
        AIFunStreamSoundManager.interrupt();
    }
}
