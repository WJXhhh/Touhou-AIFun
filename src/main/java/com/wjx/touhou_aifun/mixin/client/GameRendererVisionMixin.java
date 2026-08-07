package com.wjx.touhou_aifun.mixin.client;

import com.wjx.touhou_aifun.client.vision.CubemapCapture;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Forces a true square 90-degree projection while the six cubemap faces are rendered. */
@Mixin(GameRenderer.class)
public abstract class GameRendererVisionMixin {
    @Inject(method = "getProjectionMatrix", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$cubemapProjection(double fov,
                                                CallbackInfoReturnable<Matrix4f> cir) {
        if (!CubemapCapture.isCubemapProjectionActive()) {
            return;
        }
        GameRenderer renderer = (GameRenderer) (Object) this;
        Matrix4f projection = new Matrix4f().perspective(
                (float) (Math.PI / 2.0), 1.0F, GameRenderer.PROJECTION_Z_NEAR,
                renderer.getRenderDistance() * 4.0F);
        cir.setReturnValue(projection);
    }
}
