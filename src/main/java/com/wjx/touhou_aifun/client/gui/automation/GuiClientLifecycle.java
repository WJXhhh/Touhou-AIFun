package com.wjx.touhou_aifun.client.gui.automation;

import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, value = Dist.CLIENT)
public final class GuiClientLifecycle {
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { GuiClientRuntime.clear(); }
}
