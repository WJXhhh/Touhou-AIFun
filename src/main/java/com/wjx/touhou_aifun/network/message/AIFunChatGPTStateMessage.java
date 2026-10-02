package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.client.gui.ChatGPTSubscriptionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** Public account/model state only; the session credential store is never sent. */
public record AIFunChatGPTStateMessage(UUID screenId, String metadata, String status, String authorizationUrl,
                                      boolean busy, boolean editable, boolean saved) {
    public static void encode(AIFunChatGPTStateMessage message, FriendlyByteBuf buffer) {
        buffer.writeUUID(message.screenId()); buffer.writeUtf(message.metadata(), 65536);
        buffer.writeUtf(message.status(), 2048); buffer.writeUtf(message.authorizationUrl(), 8192);
        buffer.writeBoolean(message.busy()); buffer.writeBoolean(message.editable()); buffer.writeBoolean(message.saved());
    }
    public static AIFunChatGPTStateMessage decode(FriendlyByteBuf buffer) {
        return new AIFunChatGPTStateMessage(buffer.readUUID(), buffer.readUtf(65536), buffer.readUtf(2048), buffer.readUtf(8192),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
    }
    public static void handle(AIFunChatGPTStateMessage message, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> handleClient(message));
        context.setPacketHandled(true);
    }
    @OnlyIn(Dist.CLIENT) private static void handleClient(AIFunChatGPTStateMessage message) {
        if (Minecraft.getInstance().screen instanceof ChatGPTSubscriptionScreen screen) screen.refreshFromServer(message);
    }
}
