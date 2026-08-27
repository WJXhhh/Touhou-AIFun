package com.wjx.touhou_aifun.maid.management;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID)
public final class MaidManagementEvents {
    private MaidManagementEvents() {
    }

    @SubscribeEvent
    public static void onMaidJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof EntityMaid maid)
                || maid.getOwnerUUID() == null) {
            return;
        }
        MaidManagementData data = MaidManagementData.get(level.getServer());
        data.applyPending(maid);
        data.snapshot(maid);
    }

    @SubscribeEvent
    public static void onMaidLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof EntityMaid maid)
                || !maid.isAlive() || maid.getOwnerUUID() == null) {
            return;
        }
        MaidManagementData.get(level.getServer()).snapshot(maid);
    }

    @SubscribeEvent
    public static void onMaidDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid) || !(maid.level() instanceof ServerLevel level)) {
            return;
        }
        MaidManagementData.get(level.getServer()).remove(maid);
    }
}
