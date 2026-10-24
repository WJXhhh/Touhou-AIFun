package com.wjx.touhou_aifun.chat.agent.build;

import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.maid.action.block.MaidBlockActionManager;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Clears per-server static build state so a client/test world restart cannot leak runs. */
@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID)
public final class BuildLifecycleEvents {
    private BuildLifecycleEvents() {
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        BuildQueueRunner.cancelAll();
        BuildQueueManager.clearAll();
        BuildQueueData.clearAll();
        MaidBlockActionManager.clearAll();
    }
}
