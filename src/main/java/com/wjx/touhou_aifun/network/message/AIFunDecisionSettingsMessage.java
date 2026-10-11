package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.chat.agent.AgentDecisionModels;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/** Request or save the server's task decision model. Site-edit permission is checked on the server. */
public record AIFunDecisionSettingsMessage(boolean save, String selection) {
    public static void encode(AIFunDecisionSettingsMessage value, FriendlyByteBuf buffer) {
        buffer.writeBoolean(value.save); buffer.writeUtf(value.selection, 1024);
    }
    public static AIFunDecisionSettingsMessage decode(FriendlyByteBuf buffer) {
        return new AIFunDecisionSettingsMessage(buffer.readBoolean(), buffer.readUtf(1024));
    }
    public static void handle(AIFunDecisionSettingsMessage value, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get(); var sender = context.getSender();
        if (sender != null) context.enqueueWork(() -> {
            boolean allowed = com.github.tartaricacid.touhoulittlemaid.util.GameModeUtil.canEditSite(sender);
            String status = "";
            if (value.save) {
                if (!allowed) status = "permission_denied";
                else if (!AgentDecisionModels.selectable(value.selection)) status = "model_unavailable";
                else { TouhouAIFunConfig.setDecisionModel(value.selection); status = "saved"; }
            }
            AIFunNetwork.sendDecisionSettingsToPlayer(sender, status);
        });
        context.setPacketHandled(true);
    }
}
