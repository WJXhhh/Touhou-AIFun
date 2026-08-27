package com.wjx.touhou_aifun.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Allows a render-thread-only, try/finally protected swap to an off-screen framebuffer. */
@Mixin(Minecraft.class)
public interface MinecraftRenderTargetAccessor {
    @Mutable
    @Accessor("mainRenderTarget")
    void touhouAIFun$setMainRenderTarget(RenderTarget target);
}
