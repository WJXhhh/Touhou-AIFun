package com.wjx.touhou_aifun.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class TouhouAIFunConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.BooleanValue TTS_SENTENCE_STREAMING;
    public static final ForgeConfigSpec.BooleanValue TTS_EMOTION_CONTROL;
    public static final ForgeConfigSpec.BooleanValue TTS_EMOTION_IN_TEXT;
    public static final ForgeConfigSpec.BooleanValue LLM_STREAMING;
    public static final ForgeConfigSpec.ConfigValue<String> STT_SELECTED_SITE;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("tts");
        TTS_SENTENCE_STREAMING = builder
                .comment("Split TTS text by sentence and play each synthesized chunk immediately.")
                .define("sentenceStreaming", true);
        TTS_EMOTION_CONTROL = builder
                .comment("Enable emotion/style markers in TTS via English parentheses (开心)(笑).",
                        "Only takes effect when the TTS model supports it (stepaudio-2.5-tts or mimo-v2.5-tts).",
                        "Use （Chinese parentheses） for action descriptions that should be read as text.")
                .define("emotionControl", false);
        TTS_EMOTION_IN_TEXT = builder
                .comment("When emotion control is on, also show the emotion () brackets in Part 1 (the chat",
                        "bubble text), not only use them invisibly for TTS delivery.")
                .define("emotionInText", false);
        builder.pop();

        builder.push("llm");
        LLM_STREAMING = builder
                .comment("Use streaming (SSE) output for LLM providers. Tool/agent calls still work; ",
                        "completed sentences are forwarded to TTS as soon as they arrive.")
                .define("streaming", true);
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

    public static void setEmotionControl(boolean enabled) {
        TTS_EMOTION_CONTROL.set(enabled);
        TTS_EMOTION_CONTROL.save();
    }

    public static void setEmotionInText(boolean enabled) {
        TTS_EMOTION_IN_TEXT.set(enabled);
        TTS_EMOTION_IN_TEXT.save();
    }

    public static void setLlmStreaming(boolean enabled) {
        LLM_STREAMING.set(enabled);
        LLM_STREAMING.save();
    }

    public static void setSelectedSttSite(String siteId) {
        STT_SELECTED_SITE.set(siteId);
        STT_SELECTED_SITE.save();
    }
}
