package com.wjx.touhou_aifun.compat.ai;

import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EmotionControlPromptsTest {
    @Test
    void addingGlobalInstructionDoesNotDisableStepAudioEmotionControl() {
        String preset = VoicePresetSpec.direct("stepaudio-3-tts:yuanqishaonv")
                .withInstruction("温柔自然，语速适中").encode();
        assertTrue(EmotionControlPrompts.isSupported("stepfun", preset));
    }

    @Test
    void clonedVoiceUsesResolvedSynthesisModel() {
        String preset = VoicePresetSpec.reference("voice.wav", "参考文本", "stepaudio-3-tts:voice-custom")
                .withInstruction("轻声").encode();
        assertTrue(EmotionControlPrompts.isSupported("stepfun", preset));
        assertFalse(EmotionControlPrompts.isSupported("stepfun",
                VoicePresetSpec.reference("voice.wav", "参考文本", "").encode()));
    }

    @Test
    void planAndMimoPresetsKeepEmotionSupport() {
        assertTrue(EmotionControlPrompts.isSupported("stepfun_plan",
                VoicePresetSpec.direct("stepaudio-2.5-tts:cixingnansheng").withStreaming(true).encode()));
        assertTrue(EmotionControlPrompts.isSupported("mimo_plan",
                VoicePresetSpec.direct("mimo-v2.5-tts:default_zh").withInstruction("自然").encode()));
    }

    @Test
    void unsupportedModelsAndLookalikeNamesDoNotReceiveMarkers() {
        assertFalse(EmotionControlPrompts.isSupported("stepfun", "step-tts-mini:cixingnansheng"));
        assertFalse(EmotionControlPrompts.isSupported("stepfun", "stepaudio-3-tts-unknown:voice"));
        assertFalse(EmotionControlPrompts.isSupported("aliyun", "stepaudio-3-tts:voice"));
        assertFalse(EmotionControlPrompts.isSupported(null, "stepaudio-3-tts:voice"));
        assertFalse(EmotionControlPrompts.isSupported("stepfun", null));
    }
}
