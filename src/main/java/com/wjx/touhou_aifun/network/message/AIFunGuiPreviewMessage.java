package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.maid.gui.GuiClientBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

public record AIFunGuiPreviewMessage(UUID maid, boolean stop) {
    public static void encode(AIFunGuiPreviewMessage m, FriendlyByteBuf b) { b.writeUUID(m.maid); b.writeBoolean(m.stop); }
    public static AIFunGuiPreviewMessage decode(FriendlyByteBuf b) { return new AIFunGuiPreviewMessage(b.readUUID(), b.readBoolean()); }
    public static void handle(AIFunGuiPreviewMessage m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> GuiClientBridge.preview(ctx.get().getSender(), m.maid, m.stop)); ctx.get().setPacketHandled(true);
    }
}
