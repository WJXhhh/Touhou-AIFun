package com.wjx.touhou_aifun.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class TouhouAIFunConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue TTS_SENTENCE_STREAMING;
    public static final ForgeConfigSpec.ConfigValue<String> STT_SELECTED_SITE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("tts");
        TTS_SENTENCE_STREAMING = builder
                .comment("Split TTS text by sentence and play each synthesized chunk immediately.")
                .define("sentenceStreaming", true);
        builder.pop();

        builder.push("stt");
        STT_SELECTED_SITE = builder
                .comment("Selected enabled STT site id. Empty means the first enabled site.")
                .define("selectedSite", "");
        builder.pop();
        SPEC = builder.build();
    }

    private TouhouAIFunConfig() {
    }

    public static void setSentenceStreaming(boolean enabled) {
        TTS_SENTENCE_STREAMING.set(enabled);
        TTS_SENTENCE_STREAMING.save();
    }

    public static void setSelectedSttSite(String siteId) {
        STT_SELECTED_SITE.set(siteId);
        STT_SELECTED_SITE.save();
    }
}
