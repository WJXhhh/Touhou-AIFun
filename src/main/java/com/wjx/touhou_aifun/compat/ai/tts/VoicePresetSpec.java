package com.wjx.touhou_aifun.compat.ai.tts;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public record VoicePresetSpec(Mode mode, String value, String referenceText, String resolvedValue,
                              boolean streaming, String instruction) {
    private static final String PREFIX = "__touhou_stepfun_voice_v1__";

    public VoicePresetSpec {
        value = StringUtils.defaultString(value);
        referenceText = StringUtils.defaultString(referenceText);
        resolvedValue = StringUtils.defaultString(resolvedValue);
        instruction = StringUtils.defaultString(instruction);
    }

    public static VoicePresetSpec direct(String value) {
        return new VoicePresetSpec(Mode.DIRECT_ID, value, StringUtils.EMPTY, StringUtils.EMPTY,
                false, StringUtils.EMPTY);
    }

    public static VoicePresetSpec design(String prompt) {
        return new VoicePresetSpec(Mode.VOICE_DESIGN, prompt, StringUtils.EMPTY, StringUtils.EMPTY,
                false, StringUtils.EMPTY);
    }

    public static VoicePresetSpec reference(String audioPath, String referenceText, String resolvedValue) {
        return new VoicePresetSpec(Mode.REFERENCE_SAMPLE, audioPath, referenceText, resolvedValue,
                false, StringUtils.EMPTY);
    }

    public static VoicePresetSpec decode(String storedValue) {
        if (StringUtils.isBlank(storedValue) || !storedValue.startsWith(PREFIX)) {
            return direct(storedValue);
        }
        try {
            String encoded = storedValue.substring(PREFIX.length());
            String jsonText = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();
            Mode mode = Mode.valueOf(json.get("mode").getAsString());
            return new VoicePresetSpec(mode, getString(json, "value"),
                    getString(json, "reference_text"), getString(json, "resolved_value"),
                    json.has("streaming") && json.get("streaming").getAsBoolean(),
                    getString(json, "instruction"));
        } catch (Exception ignored) {
            return direct(storedValue);
        }
    }

    public String encode() {
        if (this.mode == Mode.DIRECT_ID && !this.streaming && StringUtils.isBlank(this.instruction)) {
            return this.value;
        }
        JsonObject json = new JsonObject();
        json.addProperty("mode", this.mode.name());
        json.addProperty("value", this.value);
        if (StringUtils.isNotBlank(this.referenceText)) {
            json.addProperty("reference_text", this.referenceText);
        }
        if (StringUtils.isNotBlank(this.resolvedValue)) {
            json.addProperty("resolved_value", this.resolvedValue);
        }
        if (this.streaming) {
            json.addProperty("streaming", true);
        }
        if (StringUtils.isNotBlank(this.instruction)) {
            json.addProperty("instruction", this.instruction);
        }
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.toString().getBytes(StandardCharsets.UTF_8));
        return PREFIX + encoded;
    }

    public VoicePresetSpec withResolvedValue(String value) {
        return new VoicePresetSpec(this.mode, this.value, this.referenceText, value,
                this.streaming, this.instruction);
    }

    public VoicePresetSpec withStreaming(boolean streaming) {
        return new VoicePresetSpec(this.mode, this.value, this.referenceText, this.resolvedValue,
                streaming, this.instruction);
    }

    public VoicePresetSpec withInstruction(String instruction) {
        return new VoicePresetSpec(this.mode, this.value, this.referenceText, this.resolvedValue,
                this.streaming, instruction);
    }

    public String runtimeValue() {
        return StringUtils.isNotBlank(this.resolvedValue) ? this.resolvedValue : this.value;
    }

    public boolean isReference() {
        return this.mode == Mode.REFERENCE_SAMPLE;
    }

    public boolean isDesign() {
        return this.mode == Mode.VOICE_DESIGN;
    }

    private static String getString(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : StringUtils.EMPTY;
    }

    public enum Mode {
        DIRECT_ID,
        VOICE_DESIGN,
        REFERENCE_SAMPLE
    }
}
