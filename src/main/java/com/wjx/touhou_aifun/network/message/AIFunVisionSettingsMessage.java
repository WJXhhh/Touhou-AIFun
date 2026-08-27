package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AIFunVisionSettingsMessage(boolean visionEnabled, boolean shallowScanEnabled, String selectedSite) {
    public static void encode(AIFunVisionSettingsMessage message, FriendlyByteBuf buffer) {
        buffer.writeBoolean(message.visionEnabled);
        buffer.writeBoolean(message.shallowScanEnabled);
        buffer.writeUtf(message.selectedSite == null ? "" : message.selectedSite, 128);
    }

    public static AIFunVisionSettingsMessage decode(FriendlyByteBuf buffer) {
        return new AIFunVisionSettingsMessage(buffer.readBoolean(), buffer.readBoolean(), buffer.readUtf(128));
    }

    public static void handle(AIFunVisionSettingsMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> {
            if (sender.hasPermissions(2)) {
                TouhouAIFunConfig.setVisionEnabled(message.visionEnabled);
                TouhouAIFunConfig.setShallowScanEnabled(message.shallowScanEnabled);
                TouhouAIFunConfig.setSelectedVisionSite(message.selectedSite);
            }
            AIFunNetwork.sendVisionSitesToPlayer(sender,
                    sender.hasPermissions(2) ? "settings_saved" : "permission_denied");
        });
        context.setPacketHandled(true);
    }
}
