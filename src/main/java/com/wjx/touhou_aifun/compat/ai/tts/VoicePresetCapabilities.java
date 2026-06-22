package com.wjx.touhou_aifun.compat.ai.tts;

public interface VoicePresetCapabilities {
    default boolean supportsVoiceDesign() {
        return false;
    }

    default boolean supportsReferenceVoice() {
        return false;
    }

    default boolean supportsStreamingOutput() {
        return false;
    }

    default boolean supportsSynthesisInstruction() {
        return false;
    }
}
