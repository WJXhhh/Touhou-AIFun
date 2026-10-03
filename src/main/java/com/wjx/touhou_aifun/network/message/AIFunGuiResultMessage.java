package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.maid.gui.GuiClientBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

/** C2S images are chunked below the vanilla custom-payload size boundary. */
public record AIFunGuiResultMessage(UUID request, UUID session, UUID maid, String json, int index, int parts, byte[] data) {
    public static void encode(AIFunGuiResultMessage m, FriendlyByteBuf b) {
        b.writeUUID(m.request); b.writeUUID(m.session); b.writeUUID(m.maid); b.writeUtf(m.json, 8192); b.writeVarInt(m.index); b.writeVarInt(m.parts); b.writeByteArray(m.data);
    }
    public static AIFunGuiResultMessage decode(FriendlyByteBuf b) {
        return new AIFunGuiResultMessage(b.readUUID(), b.readUUID(), b.readUUID(), b.readUtf(8192), b.readVarInt(), b.readVarInt(), b.readByteArray(16 * 1024));
    }
    public static void handle(AIFunGuiResultMessage m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> GuiClientBridge.accept(ctx.get().getSender(), m)); ctx.get().setPacketHandled(true);
    }
}
