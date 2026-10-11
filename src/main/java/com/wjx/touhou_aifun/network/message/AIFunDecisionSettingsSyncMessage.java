package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.chat.agent.AgentDecisionModels;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;
import java.util.List;
import java.util.function.Supplier;

/** Only model references/display names/availability cross the wire; no connection credentials. */
public record AIFunDecisionSettingsSyncMessage(String selection, List<AgentDecisionModels.Option> models,
                                               boolean readOnly, String status) {
    public static void encode(AIFunDecisionSettingsSyncMessage value, FriendlyByteBuf buffer) {
        buffer.writeUtf(value.selection, 1024);
        buffer.writeCollection(value.models, (buf, row) -> {
            buf.writeUtf(row.id(), 1024); buf.writeUtf(row.label(), 256); buf.writeBoolean(row.usable());
        });
        buffer.writeBoolean(value.readOnly); buffer.writeUtf(value.status, 128);
    }
    public static AIFunDecisionSettingsSyncMessage decode(FriendlyByteBuf buffer) {
        String selection = buffer.readUtf(1024);
        int count = buffer.readVarInt();
        if (count < 0 || count > 1024) throw new IllegalArgumentException("decision_model_catalog_limit");
        var models = new java.util.ArrayList<AgentDecisionModels.Option>();
        for (int i = 0; i < count; i++) models.add(new AgentDecisionModels.Option(buffer.readUtf(1024), buffer.readUtf(256), buffer.readBoolean()));
        return new AIFunDecisionSettingsSyncMessage(selection, List.copyOf(models), buffer.readBoolean(), buffer.readUtf(128));
    }
    public static void handle(AIFunDecisionSettingsSyncMessage value, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        if (context.getDirection().getReceptionSide().isClient()) context.enqueueWork(() -> receive(value));
        context.setPacketHandled(true);
    }
    @OnlyIn(Dist.CLIENT) private static void receive(AIFunDecisionSettingsSyncMessage value) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof com.wjx.touhou_aifun.client.gui.AgentDecisionSettingsScreen screen)
            screen.refresh(value);
    }
}
