package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.SyncMaidAIDataMessage;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryAccess;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the full server-only memory payload out of TLM's settings-screen synchronization packet. */
@Mixin(value = SyncMaidAIDataMessage.class, remap = false)
public abstract class SyncMaidAIDataMessageMixin {
    @Inject(method = "<init>(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;"
            + "Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("RETURN"))
    private void touhouAIFun$stripServerMemory(EntityMaid maid, ServerPlayer player, CallbackInfo ci) {
        ((SyncMaidAIDataMessage) (Object) this).configData().remove(AIFunMemoryAccess.MEMORY_TAG);
        ((SyncMaidAIDataMessage) (Object) this).configData().remove(AIFunMemoryAccess.MEMORY_BACKUP_TAG);
    }
}
