package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.vision.ClientVisionSitesSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AIFunVisionSitesSyncMessage(String sitesJson, boolean visionEnabled,
                                          boolean shallowScanEnabled, String selectedSite,
                                          boolean insufficientPermissions, String operationStatus) {
    public static void encode(AIFunVisionSitesSyncMessage message, FriendlyByteBuf buffer) {
        buffer.writeUtf(message.sitesJson == null ? "[]" : message.sitesJson, 64 * 1024);
        buffer.writeBoolean(message.visionEnabled);
        buffer.writeBoolean(message.shallowScanEnabled);
        buffer.writeUtf(message.selectedSite == null ? "" : message.selectedSite, 128);
        buffer.writeBoolean(message.insufficientPermissions);
        buffer.writeUtf(message.operationStatus == null ? "" : message.operationStatus, 128);
    }

    public static AIFunVisionSitesSyncMessage decode(FriendlyByteBuf buffer) {
        return new AIFunVisionSitesSyncMessage(buffer.readUtf(64 * 1024), buffer.readBoolean(),
                buffer.readBoolean(), buffer.readUtf(128), buffer.readBoolean(), buffer.readUtf(128));
    }

    public static void handle(AIFunVisionSitesSyncMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(message));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(AIFunVisionSitesSyncMessage message) {
        ClientVisionSitesSnapshot.replaceFromJson(message.sitesJson);
        if (Minecraft.getInstance().screen instanceof com.wjx.touhou_aifun.client.gui.VisionSettingsScreen screen) {
            screen.refreshFromServer(message);
        }
    }
}
