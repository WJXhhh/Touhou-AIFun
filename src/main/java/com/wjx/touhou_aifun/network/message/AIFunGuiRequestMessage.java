package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.client.gui.automation.GuiClientRuntime;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.*;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

public record AIFunGuiRequestMessage(UUID request, UUID session, UUID maid, String operation, String json, byte[] mirror) {
    public static void encode(AIFunGuiRequestMessage m, FriendlyByteBuf b) {
        b.writeUUID(m.request); b.writeUUID(m.session); b.writeUUID(m.maid); b.writeUtf(m.operation, 32); b.writeUtf(m.json, 8192); b.writeByteArray(m.mirror);
    }
    public static AIFunGuiRequestMessage decode(FriendlyByteBuf b) {
        return new AIFunGuiRequestMessage(b.readUUID(), b.readUUID(), b.readUUID(), b.readUtf(32), b.readUtf(8192), b.readByteArray(900 * 1024));
    }
    public static void handle(AIFunGuiRequestMessage m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> client(m)); ctx.get().setPacketHandled(true);
    }
    @OnlyIn(Dist.CLIENT) private static void client(AIFunGuiRequestMessage m) { GuiClientRuntime.handle(m); }
}
