package com.wjx.touhou_aifun.client.voice;

import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, value = Dist.CLIENT)
public final class FreeChatClientEvents {
    private FreeChatClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FreeChatController.tick();
        }
    }
}
