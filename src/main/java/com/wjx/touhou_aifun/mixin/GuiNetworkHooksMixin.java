package com.wjx.touhou_aifun.mixin;

import com.wjx.touhou_aifun.maid.gui.MaidGuiActor;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraftforge.network.NetworkHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Consumer;

@Mixin(value = NetworkHooks.class, remap = false)
public abstract class GuiNetworkHooksMixin {
    @Inject(method = "openScreen(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/MenuProvider;Ljava/util/function/Consumer;)V",
            at = @At("HEAD"), cancellable = true)
    private static void touhouAIFun$openMaidMenu(ServerPlayer player, MenuProvider provider,
                                               Consumer<FriendlyByteBuf> writer, CallbackInfo ci) {
        if (player instanceof MaidGuiActor actor) { actor.open(provider, writer); ci.cancel(); }
    }
}
