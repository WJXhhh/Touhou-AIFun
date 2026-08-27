package com.wjx.touhou_aifun.maid.management;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatSerializable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/** The per-maid, non-secret part of TLM's AI chat configuration. */
public record MaidAIConfigSnapshot(String llmSite, String llmModel, String ttsSite, String ttsModel,
                                   String ttsLanguage, String chatLanguage, String ownerName,
                                   String customSetting) {
    public static final int LLM = 1;
    public static final int TTS = 1 << 1;
    public static final int TTS_LANGUAGE = 1 << 2;
    public static final int CHAT_LANGUAGE = 1 << 3;
    public static final int OWNER_NAME = 1 << 4;
    public static final int CUSTOM_SETTING = 1 << 5;
    public static final int PUBLIC_MAID = 1 << 6;
    public static final int FRIENDLY_FIRE = 1 << 7;
    public static final int ALL = LLM | TTS | TTS_LANGUAGE | CHAT_LANGUAGE | OWNER_NAME
            | CUSTOM_SETTING | PUBLIC_MAID | FRIENDLY_FIRE;
    public static final MaidAIConfigSnapshot EMPTY = new MaidAIConfigSnapshot(
            "", "", "", "", "", "", "", "");

    public MaidAIConfigSnapshot {
        llmSite = safe(llmSite);
        llmModel = safe(llmModel);
        ttsSite = safe(ttsSite);
        ttsModel = safe(ttsModel);
        ttsLanguage = safe(ttsLanguage);
        chatLanguage = safe(chatLanguage);
        ownerName = safe(ownerName);
        customSetting = safe(customSetting);
    }

    public static MaidAIConfigSnapshot from(MaidAIChatSerializable source) {
        return new MaidAIConfigSnapshot(source.llmSite, source.llmModel, source.ttsSite, source.ttsModel,
                source.ttsLanguage, source.chatLanguage, source.ownerName, source.customSetting);
    }

    public void applyTo(MaidAIChatSerializable target) {
        applyTo(target, ALL);
    }

    public void applyTo(MaidAIChatSerializable target, int mask) {
        if ((mask & LLM) != 0) {
            target.llmSite = llmSite;
            target.llmModel = llmModel;
        }
        if ((mask & TTS) != 0) {
            target.ttsSite = ttsSite;
            target.ttsModel = ttsModel;
        }
        if ((mask & TTS_LANGUAGE) != 0) target.ttsLanguage = ttsLanguage;
        if ((mask & CHAT_LANGUAGE) != 0) target.chatLanguage = chatLanguage;
        if ((mask & OWNER_NAME) != 0) target.ownerName = ownerName;
        if ((mask & CUSTOM_SETTING) != 0) target.customSetting = customSetting;
    }

    public MaidAIConfigSnapshot merge(MaidAIConfigSnapshot patch, int mask) {
        return new MaidAIConfigSnapshot(
                (mask & LLM) != 0 ? patch.llmSite : llmSite,
                (mask & LLM) != 0 ? patch.llmModel : llmModel,
                (mask & TTS) != 0 ? patch.ttsSite : ttsSite,
                (mask & TTS) != 0 ? patch.ttsModel : ttsModel,
                (mask & TTS_LANGUAGE) != 0 ? patch.ttsLanguage : ttsLanguage,
                (mask & CHAT_LANGUAGE) != 0 ? patch.chatLanguage : chatLanguage,
                (mask & OWNER_NAME) != 0 ? patch.ownerName : ownerName,
                (mask & CUSTOM_SETTING) != 0 ? patch.customSetting : customSetting);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("LLMSite", llmSite);
        tag.putString("LLMModel", llmModel);
        tag.putString("TTSSite", ttsSite);
        tag.putString("TTSModel", ttsModel);
        tag.putString("TTSLanguage", ttsLanguage);
        tag.putString("ChatLanguage", chatLanguage);
        tag.putString("OwnerName", ownerName);
        tag.putString("CustomSetting", customSetting);
        return tag;
    }

    public static MaidAIConfigSnapshot fromTag(CompoundTag tag) {
        return new MaidAIConfigSnapshot(tag.getString("LLMSite"), tag.getString("LLMModel"),
                tag.getString("TTSSite"), tag.getString("TTSModel"), tag.getString("TTSLanguage"),
                tag.getString("ChatLanguage"), tag.getString("OwnerName"), tag.getString("CustomSetting"));
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeUtf(llmSite, 128);
        buffer.writeUtf(llmModel, 256);
        buffer.writeUtf(ttsSite, 128);
        buffer.writeUtf(ttsModel, 256);
        buffer.writeUtf(ttsLanguage, 16);
        buffer.writeUtf(chatLanguage, 16);
        buffer.writeUtf(ownerName, 128);
        buffer.writeUtf(customSetting, 4096);
    }

    public static MaidAIConfigSnapshot read(FriendlyByteBuf buffer) {
        return new MaidAIConfigSnapshot(buffer.readUtf(128), buffer.readUtf(256), buffer.readUtf(128),
                buffer.readUtf(256), buffer.readUtf(16), buffer.readUtf(16), buffer.readUtf(128),
                buffer.readUtf(4096));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
