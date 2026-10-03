package com.wjx.touhou_aifun.mixin.client;

import com.wjx.touhou_aifun.client.gui.automation.GuiClientRuntime;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class GuiConnectionIsolationMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$isolateGuiPacket(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        if (GuiClientRuntime.intercept(packet)) ci.cancel();
    }
}
