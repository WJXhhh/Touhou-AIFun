package com.wjx.touhou_aifun.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryManager;
import com.wjx.touhou_aifun.chat.context.AIFunMemoryAccess;
import com.wjx.touhou_aifun.chat.context.MaidMemoryState;
import com.wjx.touhou_aifun.chat.context.MemoryStateCodec;
import com.wjx.touhou_aifun.chat.ChatFlowManager;
import com.wjx.touhou_aifun.compat.ai.openai.ReasoningContentCodec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Arrays;

/** Adds addon-owned context state without modifying the base mod's source or record codecs. */
@Mixin(value = MaidAIChatData.class, remap = false)
public abstract class MaidAIChatDataMixin implements AIFunMemoryAccess {
    @org.spongepowered.asm.mixin.Shadow
    protected String compressedSummary;
    @Unique
    private MaidMemoryState touhouAIFun$memoryState = new MaidMemoryState();
    @Unique
    private Tag touhouAIFun$memoryBackup;
    @Unique
    private Tag touhouAIFun$unsupportedPrimary;
    @Unique
    private long touhouAIFun$encodedRevision = Long.MIN_VALUE;
    @Unique
    private byte[] touhouAIFun$encodedCache;

    @Override
    public MaidMemoryState touhouAIFun$getMemoryState() {
        return touhouAIFun$memoryState;
    }

    @Override
    public void touhouAIFun$setMemoryState(MaidMemoryState state) {
        touhouAIFun$memoryState = state == null ? new MaidMemoryState() : state;
        touhouAIFun$encodedRevision = Long.MIN_VALUE;
        touhouAIFun$encodedCache = null;
    }

    @Inject(method = "readFromTag", at = @At("RETURN"))
    private void touhouAIFun$readMemory(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        touhouAIFun$memoryBackup = tag.contains(AIFunMemoryAccess.MEMORY_BACKUP_TAG)
                ? tag.get(AIFunMemoryAccess.MEMORY_BACKUP_TAG).copy() : null;
        touhouAIFun$unsupportedPrimary = null;
        if (tag.contains(AIFunMemoryAccess.MEMORY_TAG, Tag.TAG_BYTE_ARRAY)) {
            byte[] raw = tag.getByteArray(AIFunMemoryAccess.MEMORY_TAG);
            MemoryStateCodec.DecodeResult result = MemoryStateCodec.decodeBytesResult(raw);
            touhouAIFun$acceptDecoded(tag.get(AIFunMemoryAccess.MEMORY_TAG), result);
            if (result.status() == MemoryStateCodec.DecodeStatus.VALID) {
                touhouAIFun$encodedRevision = result.state().revision();
                touhouAIFun$encodedCache = Arrays.copyOf(raw, raw.length);
            }
        } else if (tag.contains(AIFunMemoryAccess.MEMORY_TAG, Tag.TAG_STRING)) {
            // One-way compatibility with the initial JSON-in-StringTag v1 implementation.
            MemoryStateCodec.DecodeResult result = MemoryStateCodec.decodeResult(
                    tag.getString(AIFunMemoryAccess.MEMORY_TAG));
            touhouAIFun$acceptDecoded(tag.get(AIFunMemoryAccess.MEMORY_TAG), result);
        }
    }

    @Inject(method = "writeToTag", at = @At("RETURN"))
    private void touhouAIFun$writeMemory(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        synchronized (touhouAIFun$memoryState) {
            if (touhouAIFun$unsupportedPrimary != null) {
                tag.put(AIFunMemoryAccess.MEMORY_TAG, touhouAIFun$unsupportedPrimary.copy());
            } else {
                if (touhouAIFun$encodedCache == null
                        || touhouAIFun$encodedRevision != touhouAIFun$memoryState.revision()) {
                    touhouAIFun$encodedCache = MemoryStateCodec.encodeBytes(touhouAIFun$memoryState);
                    touhouAIFun$encodedRevision = touhouAIFun$memoryState.revision();
                }
                tag.putByteArray(AIFunMemoryAccess.MEMORY_TAG,
                        Arrays.copyOf(touhouAIFun$encodedCache, touhouAIFun$encodedCache.length));
            }
            if (touhouAIFun$memoryBackup != null) {
                tag.put(AIFunMemoryAccess.MEMORY_BACKUP_TAG, touhouAIFun$memoryBackup.copy());
            }
        }
    }

    @Unique
    private void touhouAIFun$acceptDecoded(Tag original, MemoryStateCodec.DecodeResult result) {
        this.touhouAIFun$memoryState = result.state();
        this.touhouAIFun$encodedRevision = Long.MIN_VALUE;
        this.touhouAIFun$encodedCache = null;
        if (result.status() == MemoryStateCodec.DecodeStatus.UNSUPPORTED_SCHEMA) {
            this.touhouAIFun$unsupportedPrimary = original.copy();
            if (this.touhouAIFun$memoryBackup == null) this.touhouAIFun$memoryBackup = original.copy();
        } else if (result.status() == MemoryStateCodec.DecodeStatus.CORRUPT
                && this.touhouAIFun$memoryBackup == null) {
            this.touhouAIFun$memoryBackup = original.copy();
        }
    }

    @Inject(method = "writeToTag", at = @At("HEAD"))
    private void touhouAIFun$syncLegacySummary(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        if ((Object) this instanceof MaidAIChatManager manager
                && manager.getMaid().level() instanceof ServerLevel) {
            AIFunMemoryManager.ensureForPersistence(manager);
            this.compressedSummary = AIFunMemoryManager.displaySummary(manager);
        }
    }

    @Inject(method = "clearAllChatMemory", at = @At("HEAD"))
    private void touhouAIFun$clearMemory(CallbackInfo ci) {
        if ((Object) this instanceof MaidAIChatManager manager
                && manager.getMaid().level() instanceof ServerLevel) {
            ChatFlowManager.clearMaid(manager.getMaid());
            AIFunMemoryManager.cancelQueuedExtraction(manager.getMaid().getUUID());
        }
        this.touhouAIFun$clearMemoryState();
        this.touhouAIFun$memoryBackup = null;
        this.touhouAIFun$unsupportedPrimary = null;
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

    /** Legacy history is display/migration data now; keep provider envelopes out of saves and UI. */
    @ModifyVariable(method = "addAssistantHistory(Ljava/lang/String;)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 0)
    private String touhouAIFun$compactLegacyAssistant(String text) {
        String content = ReasoningContentCodec.decode(text).content();
        int separator = content.indexOf("---");
        return (separator >= 0 ? content.substring(0, separator) : content).trim();
    }

    /** The active callback retains the full result; legacy history only needs a bounded display copy. */
    @ModifyVariable(method = "addToolHistory", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String touhouAIFun$compactLegacyToolResult(String text) {
        if (text == null) return "";
        int count = text.codePointCount(0, text.length());
        if (count <= 512) return text;
        int headEnd = text.offsetByCodePoints(0, 240);
        int tailStart = text.offsetByCodePoints(0, count - 240);
        return text.substring(0, headEnd) + "\n...[tool result shortened for history]...\n"
                + text.substring(tailStart);
    }
}
