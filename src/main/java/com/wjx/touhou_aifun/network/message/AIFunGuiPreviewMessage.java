package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.maid.gui.GuiClientBridge;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

public record AIFunGuiPreviewMessage(UUID maid, String action) {
    public AIFunGuiPreviewMessage(UUID maid, boolean stop) { this(maid, stop ? "stop" : "status"); }
    public static void encode(AIFunGuiPreviewMessage m, FriendlyByteBuf b) { b.writeUUID(m.maid); b.writeUtf(m.action, 16); }
    public static AIFunGuiPreviewMessage decode(FriendlyByteBuf b) { return new AIFunGuiPreviewMessage(b.readUUID(), b.readUtf(16)); }
    public static void handle(AIFunGuiPreviewMessage m, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> GuiClientBridge.preview(ctx.get().getSender(), m.maid, m.action)); ctx.get().setPacketHandled(true);
    }
}
