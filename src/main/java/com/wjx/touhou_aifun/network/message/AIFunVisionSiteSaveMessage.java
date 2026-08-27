package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.network.AIFunNetwork;
import com.wjx.touhou_aifun.vision.AvailableVisionSites;
import com.wjx.touhou_aifun.vision.VisionSite;
import com.google.gson.JsonParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AIFunVisionSiteSaveMessage(Action action, String siteId, String siteJson) {
    public enum Action { UPSERT, DELETE, CLEAR_API_KEY }

    public static void encode(AIFunVisionSiteSaveMessage message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.action);
        buffer.writeUtf(message.siteId == null ? "" : message.siteId, 128);
        buffer.writeUtf(message.siteJson == null ? "" : message.siteJson, 16 * 1024);
    }

    public static AIFunVisionSiteSaveMessage decode(FriendlyByteBuf buffer) {
        return new AIFunVisionSiteSaveMessage(buffer.readEnum(Action.class), buffer.readUtf(128),
                buffer.readUtf(16 * 1024));
    }

    public static void handle(AIFunVisionSiteSaveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> {
            String status = "permission_denied";
            if (sender.hasPermissions(2)) {
                AvailableVisionSites.ensureLoaded();
                if (message.action == Action.DELETE) {
                    AvailableVisionSites.remove(message.siteId);
                    status = "site_deleted";
                } else if (message.action == Action.CLEAR_API_KEY) {
                    VisionSite existing = AvailableVisionSites.get(message.siteId);
                    if (existing != null) {
                        existing.setApiKey("");
                        AvailableVisionSites.upsert(existing);
                        status = "api_key_cleared";
                    } else {
                        status = "site_not_found";
                    }
                } else {
                    status = "invalid_site_payload";
                    try {
                        var parsed = JsonParser.parseString(message.siteJson);
                        if (parsed.isJsonObject()) {
                            VisionSite incoming = VisionSite.fromJson(parsed.getAsJsonObject());
                            if (!message.siteId.isBlank() && message.siteId.equals(incoming.id())) {
                                AvailableVisionSites.upsertPreservingSecret(incoming);
                                status = "site_saved";
                            } else {
                                status = "site_id_mismatch";
                            }
                        }
                    } catch (Exception ignored) {
                        // Malformed client payload is ignored; the next sync restores server state.
                    }
                }
            }
            AIFunNetwork.sendVisionSitesToPlayer(sender, status);
        });
        context.setPacketHandled(true);
    }
}
