package com.wjx.touhou_aifun.network.message;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.maid.PublicMaidAccess;
import com.wjx.touhou_aifun.maid.PublicMaidData;
import com.wjx.touhou_aifun.maid.management.MaidManagementData;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record AIFunMaidAccessMessage(int maidId, boolean publicMaid, boolean friendlyFireAllowed) {
    public static void encode(AIFunMaidAccessMessage message, FriendlyByteBuf buffer) {
        buffer.writeVarInt(message.maidId);
        buffer.writeBoolean(message.publicMaid);
        buffer.writeBoolean(message.friendlyFireAllowed);
    }

    public static AIFunMaidAccessMessage decode(FriendlyByteBuf buffer) {
        return new AIFunMaidAccessMessage(buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
    }

    public static void handle(AIFunMaidAccessMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            Entity entity = sender.level().getEntity(message.maidId);
            if (!(entity instanceof EntityMaid maid)
                    || !maid.isAlive()
                    || maid.distanceTo(sender) >= 8.0F
                    || !PublicMaidAccess.isActualOwner(maid, sender)) {
                return;
            }
            PublicMaidData data = PublicMaidAccess.data(maid);
            data.touhouAIFun$setPublicMaid(message.publicMaid);
            data.touhouAIFun$setFriendlyFireAllowed(message.friendlyFireAllowed);
            MaidManagementData.get(sender.server).snapshot(maid);
        });
        context.setPacketHandled(true);
    }
}
