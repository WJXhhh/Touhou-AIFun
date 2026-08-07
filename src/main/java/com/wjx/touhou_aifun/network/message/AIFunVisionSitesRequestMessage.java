package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.vision.AvailableVisionSites;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client asks the server for the authoritative visual settings/sites snapshot. */
public record AIFunVisionSitesRequestMessage() {
    public static void encode(AIFunVisionSitesRequestMessage message, FriendlyByteBuf buffer) {
    }

    public static AIFunVisionSitesRequestMessage decode(FriendlyByteBuf buffer) {
        return new AIFunVisionSitesRequestMessage();
    }

    public static void handle(AIFunVisionSitesRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> AIFunNetwork.sendVisionSitesToPlayer(sender));
        context.setPacketHandled(true);
    }
}
