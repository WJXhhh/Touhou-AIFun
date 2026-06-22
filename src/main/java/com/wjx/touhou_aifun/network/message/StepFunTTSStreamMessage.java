package com.wjx.touhou_aifun.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import com.wjx.touhou_aifun.client.sound.StepFunStreamSoundManager;

import java.util.UUID;
import java.util.function.Supplier;

public record StepFunTTSStreamMessage(UUID streamId, Action action, int maidId,
                                      int sampleRate, byte[] data) {
    public static StepFunTTSStreamMessage start(UUID streamId, int maidId, int sampleRate, byte[] data) {
        return new StepFunTTSStreamMessage(streamId, Action.START, maidId, sampleRate, data);
    }

    public static StepFunTTSStreamMessage data(UUID streamId, byte[] data) {
        return new StepFunTTSStreamMessage(streamId, Action.DATA, 0, 0, data);
    }

    public static StepFunTTSStreamMessage end(UUID streamId) {
        return new StepFunTTSStreamMessage(streamId, Action.END, 0, 0, new byte[0]);
    }

    public static void encode(StepFunTTSStreamMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.streamId);
        buffer.writeEnum(message.action);
        buffer.writeVarInt(message.maidId);
        buffer.writeVarInt(message.sampleRate);
        buffer.writeByteArray(message.data);
    }

    public static StepFunTTSStreamMessage decode(FriendlyByteBuf buffer) {
        return new StepFunTTSStreamMessage(buffer.readUUID(), buffer.readEnum(Action.class),
                buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray());
    }

    public static void handle(StepFunTTSStreamMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(message));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(StepFunTTSStreamMessage message) {
        StepFunStreamSoundManager.handle(message);
    }

    public enum Action {
        START,
        DATA,
        END
    }
}
