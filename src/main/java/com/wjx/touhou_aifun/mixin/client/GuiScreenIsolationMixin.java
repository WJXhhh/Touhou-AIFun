package com.wjx.touhou_aifun.mixin.client;

import com.wjx.touhou_aifun.client.gui.automation.GuiClientRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class GuiScreenIsolationMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$isolateGuiScreen(Screen screen, CallbackInfo ci) {
        if (GuiClientRuntime.isolated()) { GuiClientRuntime.replaceScreen(screen); ci.cancel(); }
    }
}
