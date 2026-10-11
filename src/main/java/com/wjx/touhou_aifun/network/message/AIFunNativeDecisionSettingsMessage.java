package com.wjx.touhou_aifun.network.message;

import com.wjx.touhou_aifun.chat.agent.decision.DecisionServiceSettings;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record AIFunNativeDecisionSettingsMessage(boolean save,String provider,boolean enabled,String endpoint,
                                                 String model,String key,boolean clearKey,int timeoutMs,double minConfidence) {
    @Override public String toString() {return "NativeDecisionSettingsMessage[save="+save+",provider="+provider+"]";}
    public static void encode(AIFunNativeDecisionSettingsMessage v,FriendlyByteBuf b) {
        b.writeBoolean(v.save);b.writeUtf(v.provider,32);b.writeBoolean(v.enabled);b.writeUtf(v.endpoint,1024);
        b.writeUtf(v.model,256);b.writeUtf(v.key,512);b.writeBoolean(v.clearKey);b.writeVarInt(v.timeoutMs);b.writeDouble(v.minConfidence);
    }
    public static AIFunNativeDecisionSettingsMessage decode(FriendlyByteBuf b) {
        return new AIFunNativeDecisionSettingsMessage(b.readBoolean(),b.readUtf(32),b.readBoolean(),b.readUtf(1024),b.readUtf(256),b.readUtf(512),b.readBoolean(),b.readVarInt(),b.readDouble());
    }
    public static void handle(AIFunNativeDecisionSettingsMessage v,Supplier<NetworkEvent.Context> supplier) {
        var context=supplier.get();var sender=context.getSender();
        if(sender!=null) context.enqueueWork(()-> {
            boolean allowed=com.github.tartaricacid.touhoulittlemaid.util.GameModeUtil.canEditSite(sender);String status="";
            if(v.save) {
                if(!allowed) status="permission_denied";
                else try {
                    DecisionServiceSettings.save(new DecisionServiceSettings.View(v.provider,v.enabled,v.endpoint,v.model,false,v.timeoutMs,v.minConfidence),v.key,v.clearKey);status="saved";
                } catch(IllegalArgumentException invalid) {status=invalid.getMessage();}
            }
            AIFunNetwork.sendNativeDecisionSettings(sender,v.provider,status);
        });context.setPacketHandled(true);
    }
}
