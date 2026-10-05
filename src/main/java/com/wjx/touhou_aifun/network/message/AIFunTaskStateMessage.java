package com.wjx.touhou_aifun.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.api.distmarker.*;
import java.util.UUID;
import java.util.function.Supplier;

public record AIFunTaskStateMessage(UUID maid, String json) {
    public static void encode(AIFunTaskStateMessage m, FriendlyByteBuf b) { b.writeUUID(m.maid); b.writeUtf(m.json,8192); }
    public static AIFunTaskStateMessage decode(FriendlyByteBuf b) { return new AIFunTaskStateMessage(b.readUUID(),b.readUtf(8192)); }
    public static void handle(AIFunTaskStateMessage m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> client(m)); ctx.get().setPacketHandled(true);
    }
    @OnlyIn(Dist.CLIENT) private static void client(AIFunTaskStateMessage m) {
        com.wjx.touhou_aifun.client.gui.automation.GuiOperationPreviewScreen.updateTask(m.maid,
                com.google.gson.JsonParser.parseString(m.json).getAsJsonObject());
    }
}
