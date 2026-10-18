package com.wjx.touhou_aifun.network.message;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Client -&gt; server: the player just pressed the STT key and started recording, so the maid must
 * stop speaking now instead of waiting for the transcribed text to open a new chat turn.
 * <p>
 * The client already dropped its local playback; this request bumps the server-side TTS generation
 * so sentences that are still being synthesized or sent for the old reply are discarded too.
 * It only silences speech: the reply text is still finished and kept as assistant history.
 */
public record AIFunMaidSpeechStopMessage(int maidId) {
    public static void encode(AIFunMaidSpeechStopMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.maidId);
    }

    public static AIFunMaidSpeechStopMessage decode(FriendlyByteBuf buffer) {
        return new AIFunMaidSpeechStopMessage(buffer.readVarInt());
    }

    public static void handle(AIFunMaidSpeechStopMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            if (!(sender.level().getEntity(message.maidId) instanceof EntityMaid maid)) {
                return;
            }
            // Voice chat is owner-only in TLM, so only the owner may silence this maid.
            if (!maid.isOwnedBy(sender)) {
                return;
            }
            ChatFlowManager.interruptSpeech(maid.getUUID());
            AIFunNetwork.sendInterruptTts(maid);
        });
        context.setPacketHandled(true);
    }
}
