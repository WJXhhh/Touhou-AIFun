package com.wjx.touhou_aifun.mixin.client;

import com.wjx.touhou_aifun.client.vision.CubemapCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Drives the private capture target with an undistorted 90-degree cubemap projection. */
@Mixin(value = GameRenderer.class, priority = 900)
public abstract class GameRendererVisionMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void touhouAIFun$captureOffscreen(float partialTick, long finishTimeNano,
                                               boolean renderLevel, CallbackInfo ci) {
        if (renderLevel) {
            CubemapCapture.captureOffscreenFrame(partialTick, finishTimeNano);
        }
    }

    @Inject(method = "getProjectionMatrix", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$cubemapProjection(double fov,
                                                CallbackInfoReturnable<Matrix4f> cir) {
        if (!CubemapCapture.isCubemapProjectionActive()) {
            return;
        }
        GameRenderer renderer = (GameRenderer) (Object) this;
        var target = Minecraft.getInstance().getMainRenderTarget();
        float aspect = target.width > 0 && target.height > 0
                ? (float) target.width / (float) target.height : 1.0F;
        // Keep the rendered image geometrically correct at the real framebuffer aspect ratio.
        // The shorter axis is exactly 90 degrees; the longer axis deliberately contains extra
        // field of view which CubemapCapture removes with a centered square crop.
        float verticalFov = aspect >= 1.0F
                ? (float) (Math.PI / 2.0)
                : (float) (2.0 * Math.atan(1.0 / aspect));
        Matrix4f projection = new Matrix4f().perspective(
                verticalFov, aspect, GameRenderer.PROJECTION_Z_NEAR,
                renderer.getRenderDistance() * 4.0F);
        cir.setReturnValue(projection);
    }
}
