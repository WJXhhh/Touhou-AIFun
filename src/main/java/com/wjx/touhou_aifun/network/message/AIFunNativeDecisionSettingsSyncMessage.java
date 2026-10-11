package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.chat.agent.decision.DecisionServiceSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.*;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

/** Server replies contain only a hasKey flag, never the credential. */
public record AIFunNativeDecisionSettingsSyncMessage(DecisionServiceSettings.View view,boolean readOnly,String status) {
    public static void encode(AIFunNativeDecisionSettingsSyncMessage v,FriendlyByteBuf b) {
        var s=v.view;b.writeUtf(s.provider(),32);b.writeBoolean(s.enabled());b.writeUtf(s.endpoint(),1024);b.writeUtf(s.model(),256);
        b.writeBoolean(s.hasKey());b.writeVarInt(s.timeoutMs());b.writeDouble(s.minConfidence());b.writeBoolean(v.readOnly);b.writeUtf(v.status,128);
    }
    public static AIFunNativeDecisionSettingsSyncMessage decode(FriendlyByteBuf b) {
        var s=new DecisionServiceSettings.View(b.readUtf(32),b.readBoolean(),b.readUtf(1024),b.readUtf(256),b.readBoolean(),b.readVarInt(),b.readDouble());
        return new AIFunNativeDecisionSettingsSyncMessage(s,b.readBoolean(),b.readUtf(128));
    }
    public static void handle(AIFunNativeDecisionSettingsSyncMessage v,Supplier<NetworkEvent.Context> supplier) {
        var context=supplier.get();if(context.getDirection().getReceptionSide().isClient()) context.enqueueWork(()->receive(v));context.setPacketHandled(true);
    }
    @OnlyIn(Dist.CLIENT) private static void receive(AIFunNativeDecisionSettingsSyncMessage v) {
        if(net.minecraft.client.Minecraft.getInstance().screen instanceof com.wjx.touhou_aifun.client.gui.DecisionServiceSettingsScreen screen) screen.refresh(v);
    }
}
