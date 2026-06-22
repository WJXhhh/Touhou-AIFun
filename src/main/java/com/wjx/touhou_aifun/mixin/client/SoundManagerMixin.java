package com.wjx.touhou_aifun.mixin.client;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.wjx.touhou_aifun.client.sound.QueuedTTSPlaybackManager;

@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V",
            at = @At("HEAD"), cancellable = true)
    private void touhouStepFun$queueTtsAudio(SoundInstance sound, CallbackInfo ci) {
        if (QueuedTTSPlaybackManager.intercept((SoundManager) (Object) this, sound)) {
            ci.cancel();
        }
    }
}
