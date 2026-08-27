package com.wjx.touhou_aifun.network.message;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.site.ClientAvailableSitesSync;
import com.wjx.touhou_aifun.client.gui.MaidManagementScreen;
import com.wjx.touhou_aifun.maid.management.MaidManagementEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record AIFunMaidListSyncMessage(List<MaidManagementEntry> entries, String operationStatus,
                                       boolean openScreen) {
    private static final int MAX_ENTRIES = 4096;

    public AIFunMaidListSyncMessage {
        entries = List.copyOf(entries);
        operationStatus = operationStatus == null ? "" : operationStatus;
    }

    public static void encode(AIFunMaidListSyncMessage message, FriendlyByteBuf buffer) {
        int size = Math.min(message.entries.size(), MAX_ENTRIES);
        buffer.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            message.entries.get(i).write(buffer);
        }
        buffer.writeUtf(message.operationStatus, 128);
        buffer.writeBoolean(message.openScreen);
        ClientAvailableSitesSync.writeToNetwork(buffer);
    }

    public static AIFunMaidListSyncMessage decode(FriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new IllegalArgumentException("Invalid maid management entry count: " + size);
        }
        List<MaidManagementEntry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(MaidManagementEntry.read(buffer));
        }
        String status = buffer.readUtf(128);
        boolean openScreen = buffer.readBoolean();
        ClientAvailableSitesSync.readFromNetwork(buffer);
        return new AIFunMaidListSyncMessage(entries, status, openScreen);
    }

    public static void handle(AIFunMaidListSyncMessage message,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        if (context.getDirection().getReceptionSide().isClient()) {
            context.enqueueWork(() -> handleClient(message));
        }
        context.setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void handleClient(AIFunMaidListSyncMessage message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof MaidManagementScreen screen) {
            screen.refreshFromServer(message.entries, message.operationStatus);
        } else if (message.openScreen) {
            minecraft.setScreen(new MaidManagementScreen(null, message.entries, message.operationStatus));
        }
    }
}
