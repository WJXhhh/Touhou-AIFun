package com.wjx.touhou_aifun.client;

import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.network.AIFunNetwork;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, value = Dist.CLIENT)
public final class MaidManagementKeyHandler {
    private MaidManagementKeyHandler() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        while (MaidManagementKeyMappings.OPEN.consumeClick()) {
            if (minecraft.player != null && minecraft.level != null && minecraft.screen == null
                    && minecraft.getOverlay() == null) {
                AIFunNetwork.requestMaidList(true);
            }
        }
    }
}
