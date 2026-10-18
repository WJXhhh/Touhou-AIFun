package com.wjx.touhou_aifun.chat;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.maid.action.MaidEatFoodActionManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
            com.wjx.touhou_aifun.maid.action.MaidOwnerDropAction.cancelForMaid(maid.getUUID());
            MaidEatFoodActionManager.cancelForMaid(maid.getUUID(), "The maid left the level.");
            com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager.cancel(maid.getUUID(), "maid_left_level");
            ChatFlowManager.forgetMaid(maid.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            com.wjx.touhou_aifun.chat.freechat.FreeChatGate.clearPlayer(player.getUUID());
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
        com.wjx.touhou_aifun.maid.action.MaidOwnerDropAction.clearAll();
        MaidEatFoodActionManager.clearAll();
        com.wjx.touhou_aifun.maid.gui.MaidGuiSessionManager.clearAll();
        com.wjx.touhou_aifun.chat.freechat.FreeChatGate.clearAll();
        com.wjx.touhou_aifun.chat.freechat.FreeChatContext.clearAll();
        ChatFlowManager.clearAllRuntimeState();
    }
}
