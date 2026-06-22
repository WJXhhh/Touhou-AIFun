package com.wjx.touhou_aifun.compat.ai.tts.layout;

import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.layout.TTSSiteFormLayout;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetCapabilities;

public abstract class CustomVoiceTTSFormLayout extends TTSSiteFormLayout implements VoicePresetCapabilities {
    protected String customVoiceRefAudioPath;
    protected String customVoiceRefText;

    protected CustomVoiceTTSFormLayout(TTSSite sourceSite, String refAudioPath, String refText) {
        super(sourceSite);
        this.customVoiceRefAudioPath = refAudioPath;
        this.customVoiceRefText = refText;
    }

    @Override
    public boolean supportsReferenceVoice() {
        return true;
    }
}
