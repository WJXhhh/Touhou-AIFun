package team.cfpa.touhoustepfun.client.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.apache.commons.lang3.StringUtils;
import team.cfpa.touhoustepfun.compat.ai.tts.VoicePresetSpec;

import java.util.EnumMap;

public final class VoicePresetRowState {
    private final EnumMap<VoicePresetSpec.Mode, String> values = new EnumMap<>(VoicePresetSpec.Mode.class);
    private VoicePresetSpec.Mode mode;
    private String referenceText;
    private String resolvedValue;
    private String resolvedReferencePath;
    private String resolvedReferenceText;
    private boolean streaming;
    private String instruction;

    private VoicePresetRowState(VoicePresetSpec spec) {
        this.mode = spec.mode();
        this.values.put(VoicePresetSpec.Mode.DIRECT_ID, StringUtils.EMPTY);
        this.values.put(VoicePresetSpec.Mode.VOICE_DESIGN, StringUtils.EMPTY);
        this.values.put(VoicePresetSpec.Mode.REFERENCE_SAMPLE, StringUtils.EMPTY);
        this.values.put(spec.mode(), spec.value());
        this.referenceText = spec.referenceText();
        this.resolvedValue = spec.resolvedValue();
        this.resolvedReferencePath = spec.value();
        this.resolvedReferenceText = spec.referenceText();
        this.streaming = spec.streaming();
        this.instruction = spec.instruction();
    }

    public static VoicePresetRowState fromStoredValue(String value) {
        return new VoicePresetRowState(VoicePresetSpec.decode(value));
    }

    public VoicePresetSpec.Mode mode() {
        return mode;
    }

    public void setMode(VoicePresetSpec.Mode mode) {
        this.mode = mode;
    }

    public String value(VoicePresetSpec.Mode mode) {
        return values.get(mode);
    }

    public String referenceText() {
        return referenceText;
    }

    public boolean streaming() {
        return streaming;
    }

    public void toggleStreaming() {
        this.streaming = !this.streaming;
    }

    public String instruction() {
        return instruction;
    }

    public void setInstruction(String instruction) {
        this.instruction = StringUtils.defaultString(instruction);
    }

    public void updateReference(String audioPath, String referenceText) {
        String normalizedPath = StringUtils.defaultString(audioPath);
        String normalizedText = StringUtils.defaultString(referenceText);
        if (!StringUtils.equals(this.values.get(VoicePresetSpec.Mode.REFERENCE_SAMPLE), normalizedPath)
                || !StringUtils.equals(this.referenceText, normalizedText)) {
            this.resolvedValue = StringUtils.EMPTY;
        }
        this.values.put(VoicePresetSpec.Mode.REFERENCE_SAMPLE, normalizedPath);
        this.referenceText = normalizedText;
    }

    public void syncVisibleValue(String value) {
        this.values.put(this.mode, StringUtils.defaultString(value));
    }

    public void applyToBox(EditBox box) {
        box.setValue(this.values.get(this.mode));
        box.setSuggestion(switch (this.mode) {
            case DIRECT_ID -> Component.translatable("gui.touhou_aifun.voice_mode.id.hint").getString();
            case VOICE_DESIGN -> Component.translatable("gui.touhou_aifun.voice_mode.design.hint").getString();
            case REFERENCE_SAMPLE -> Component.translatable("gui.touhou_aifun.voice_mode.reference.hint").getString();
        });
    }

    public VoicePresetSpec toSpec() {
        String value = StringUtils.trimToEmpty(this.values.get(this.mode));
        VoicePresetSpec spec = switch (this.mode) {
            case DIRECT_ID -> VoicePresetSpec.direct(value);
            case VOICE_DESIGN -> VoicePresetSpec.design(value);
            case REFERENCE_SAMPLE -> {
                String resolved = StringUtils.equals(value, this.resolvedReferencePath)
                        && StringUtils.equals(this.referenceText, this.resolvedReferenceText)
                        ? this.resolvedValue : StringUtils.EMPTY;
                yield VoicePresetSpec.reference(value, this.referenceText, resolved);
            }
        };
        return spec.withStreaming(this.streaming).withInstruction(this.instruction);
    }
}
