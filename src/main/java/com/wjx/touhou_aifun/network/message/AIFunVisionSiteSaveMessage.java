package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.vision.AvailableVisionSites;
import com.wjx.touhou_aifun.vision.VisionSite;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AIFunVisionSiteSaveMessage(Action action, String siteId, boolean enabled, String siteJson) {
    public enum Action { UPSERT, DELETE, TOGGLE }

    public static void encode(AIFunVisionSiteSaveMessage message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.action);
        buffer.writeUtf(message.siteId == null ? "" : message.siteId, 128);
        buffer.writeBoolean(message.enabled);
        buffer.writeUtf(message.siteJson == null ? "" : message.siteJson, 16 * 1024);
    }

    public static AIFunVisionSiteSaveMessage decode(FriendlyByteBuf buffer) {
        return new AIFunVisionSiteSaveMessage(buffer.readEnum(Action.class), buffer.readUtf(128),
                buffer.readBoolean(), buffer.readUtf(16 * 1024));
    }

    public static void handle(AIFunVisionSiteSaveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> {
            if (sender.hasPermissions(2)) {
                AvailableVisionSites.ensureLoaded();
                if (message.action == Action.DELETE) {
                    AvailableVisionSites.remove(message.siteId);
                } else if (message.action == Action.TOGGLE) {
                    VisionSite site = AvailableVisionSites.get(message.siteId);
                    if (site != null) {
                        site.setEnabled(message.enabled);
                        AvailableVisionSites.upsert(site);
                    }
                } else {
                    try {
                        if (JsonParser.parseString(message.siteJson).isJsonObject()) {
                            AvailableVisionSites.upsertPreservingSecret(
                                    VisionSite.fromJson(JsonParser.parseString(message.siteJson).getAsJsonObject()));
                        }
                    } catch (Exception ignored) {
                        // Malformed client payload is ignored; the next sync restores server state.
                    }
                }
            }
            AIFunNetwork.sendVisionSitesToPlayer(sender);
        });
        context.setPacketHandled(true);
    }
}
