package com.wjx.touhou_aifun.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.maid.action.MaidEatFoodActionManager;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Forge-side ownership boundary for static chat, tool and extraction runtime state. */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChatRuntimeLifecycle {
    private static int cacheTicks;
    private ChatRuntimeLifecycle() {
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof EntityMaid maid) {
            MaidEatFoodActionManager.cancelForMaid(maid.getUUID(), "The maid left the level.");
            com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager.cancel(maid.getUUID(), "maid_left_level");
            ChatFlowManager.forgetMaid(maid.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && ++cacheTicks >= 200) {
            cacheTicks = 0;
            com.wjx.touhou_aifun.vision.ObservationSnapshotCache.INSTANCE.prune();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        cacheTicks = 0;
        MaidEatFoodActionManager.clearAll();
        com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager.clearAll();
        ChatFlowManager.clearAllRuntimeState();
    }
}
