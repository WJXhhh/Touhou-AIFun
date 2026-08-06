package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.MaidMemoryState;
import com.wjx.touhou_aifun.chat.context.MemoryStateCodec;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Adds addon-owned context state without modifying the base mod's source or record codecs. */
@Mixin(value = MaidAIChatData.class, remap = false)
public abstract class MaidAIChatDataMixin implements AIFunMemoryAccess {
    @org.spongepowered.asm.mixin.Shadow
    protected String compressedSummary;
    @Unique
    private static final String touhouAIFun$MEMORY_TAG = "TouhouAIFunMemory";

    @Unique
    private MaidMemoryState touhouAIFun$memoryState = new MaidMemoryState();

    @Override
    public MaidMemoryState touhouAIFun$getMemoryState() {
        return touhouAIFun$memoryState;
    }

    @Override
    public void touhouAIFun$setMemoryState(MaidMemoryState state) {
        touhouAIFun$memoryState = state == null ? new MaidMemoryState() : state;
    }

    @Inject(method = "readFromTag", at = @At("RETURN"))
    private void touhouAIFun$readMemory(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        if (tag.contains(touhouAIFun$MEMORY_TAG)) {
            touhouAIFun$memoryState = MemoryStateCodec.decode(tag.getString(touhouAIFun$MEMORY_TAG));
        }
    }

    @Inject(method = "writeToTag", at = @At("RETURN"))
    private void touhouAIFun$writeMemory(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        synchronized (touhouAIFun$memoryState) {
            tag.putString(touhouAIFun$MEMORY_TAG, MemoryStateCodec.encode(touhouAIFun$memoryState));
        }
    }

    @Inject(method = "writeToTag", at = @At("HEAD"))
    private void touhouAIFun$syncLegacySummary(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        if ((Object) this instanceof MaidAIChatManager manager) {
            AIFunMemoryManager.ensureForPersistence(manager);
            this.compressedSummary = AIFunMemoryManager.displaySummary(manager);
        }
    }

    @Inject(method = "clearAllChatMemory", at = @At("HEAD"))
    private void touhouAIFun$clearMemory(CallbackInfo ci) {
        this.touhouAIFun$memoryState = new MaidMemoryState();
    }

    /** Do not let a superseded ordinary callback reintroduce assistant-A into legacy history. */
    @Inject(method = "addAssistantHistory(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$guardAssistantHistory(String text, CallbackInfo ci) {
        if ((Object) this instanceof MaidAIChatManager manager
                && !ChatFlowManager.allowHistoryWrite(manager.getMaid().getUUID())) {
            ci.cancel();
        }
    }

    @Inject(method = "addAssistantHistory(Ljava/lang/String;Ljava/util/List;)V", at = @At("HEAD"), cancellable = true)
    private void touhouAIFun$guardAssistantToolHistory(String text, List<?> toolCalls, CallbackInfo ci) {
        if ((Object) this instanceof MaidAIChatManager manager
                && !ChatFlowManager.allowHistoryWrite(manager.getMaid().getUUID())) {
            ci.cancel();
        }
    }
}
