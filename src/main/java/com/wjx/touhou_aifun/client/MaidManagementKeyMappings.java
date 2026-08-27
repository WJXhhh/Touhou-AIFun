package com.wjx.touhou_aifun.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wjx.touhou_aifun.TouhouAIFun;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = TouhouAIFun.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MaidManagementKeyMappings {
    public static final KeyMapping OPEN = new KeyMapping("key.touhou_aifun.maid_management",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.touhou_aifun");

    private MaidManagementKeyMappings() {
    }

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }
}
