package com.wjx.touhou_aifun.compat.ai;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import com.wjx.touhou_aifun.compat.ai.tts.VoicePresetSpec;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class EmotionControlPrompts {
    private EmotionControlPrompts() {
    }

    /** The effective emotion output mode for a maid, derived from the toggles plus TTS-model support. */
    private enum EmotionMode {
        /** No markers: replies are plain text. */
        OFF,
        /** Markers required for TTS delivery but stripped from the visible chat text. */
        HIDDEN,
        /** Markers required and also shown in the chat text. */
        VISIBLE
    }

    /**
     * Last emotion mode each maid was told about. Used to detect when the setting changed between
     * conversations so the next turn can announce the switch (the history still holds old-format replies).
     */
    private static final Map<UUID, EmotionMode> LAST_SEEN_MODE = new ConcurrentHashMap<>();

    private static EmotionMode currentMode(EntityMaid maid) {
        // An unsupported TTS model makes markers a no-op, so it is effectively OFF.
        if (!TouhouAIFunConfig.TTS_EMOTION_CONTROL.get() || !isSupported(maid)) {
            return EmotionMode.OFF;
        }
        return TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get() ? EmotionMode.VISIBLE : EmotionMode.HIDDEN;
    }

    /**
     * A one-shot system/developer notice emitted on the first chat turn after the emotion setting
     * changed for this maid, or {@code null} if nothing changed (including the very first turn). The
     * full output contract still lives in the character system prompt; this only flags the transition
     * prominently so the model does not keep copying the old-format replies in the history.
     * <p>
     * Has the side effect of recording the maid's current mode, so it must be called exactly once per
     * ordinary chat turn.
     */
    @Nullable
    public static String changeNotice(EntityMaid maid) {
        EmotionMode now = currentMode(maid);
        EmotionMode prev = LAST_SEEN_MODE.put(maid.getUUID(), now);
        if (prev == null || prev == now) {
            return null;
        }

        String change = switch (now) {
            case OFF -> "Emotion `(emotion)` markers are now DISABLED. Do NOT begin replies with any "
                    + "`(emotion)` marker and do NOT add markers anywhere — write the reply as plain text.";
            case HIDDEN -> "Emotion `(emotion)` markers are now ENABLED (used for spoken TTS delivery). "
                    + "Mark only the sections specified by the current output contract; hidden mode does not mark the visible cross-language section.";
            case VISIBLE -> "Emotion `(emotion)` markers are now ENABLED and are shown in the chat text. "
                    + "Begin replies with one allowed `(emotion)` marker exactly as the output format requires.";
        };

        return """
                ⚠️ TTS EMOTION FORMAT SETTING JUST CHANGED (since your previous reply):
                %s
                The assistant replies already in the conversation history were produced under the OLD
                setting and are NOT valid format examples. From this reply onward, follow the current
                Output Format Requirements / response contract exactly; this overrides any conflicting
                format in the history.
                """.formatted(change);
    }

    /**
     * Convert a language code like {@code zh_cn} to a human-readable name like {@code Chinese (China)},
     * or {@code null} if the code is unknown. Shared with the system-prompt builder so the per-turn
     * reminder names the TTS language the same way the contract does.
     */
    @Nullable
    public static String languageName(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String[] parts = code.split("_");
        String tag = parts[0] + (parts.length >= 2 ? "-" + parts[1].toUpperCase(Locale.ENGLISH) : "");
        Locale locale = Locale.forLanguageTag(tag);
        String lang = locale.getDisplayLanguage(Locale.ENGLISH);
        if (lang == null || lang.isEmpty() || lang.equals(tag)) {
            return null;
        }
        String country = locale.getDisplayCountry(Locale.ENGLISH);
        if (country != null && !country.isEmpty()) {
            return "%s (%s)".formatted(lang, country);
        }
        return lang;
    }

    public static boolean isSupported(EntityMaid maid) {
        MaidAIChatManager chatManager = maid.getAiChatManager();
        TTSSite ttsSite = chatManager.getTTSSite();
        if (ttsSite == null || ttsSite.getApiType() == null) {
            return false;
        }

        return isSupported(ttsSite.getApiType(), chatManager.getTTSModel());
    }

    static boolean isSupported(String apiType, String storedModel) {
        if (apiType == null || storedModel == null) {
            return false;
        }
        String model = runtimeModel(storedModel);
        // The "plan" site variants report apiType like "stepfun_plan" / "mimo_plan"; normalize that
        // suffix away so both the regular and plan sites are recognized. runtimeModel decodes
        // instruction/reference presets and removes the ":voice" suffix before matching.
        if (apiType.endsWith("_plan")) {
            apiType = apiType.substring(0, apiType.length() - "_plan".length());
        }
        return switch (apiType) {
            case "stepfun" -> model.equals("stepaudio-2.5-tts") || model.equals("stepaudio-3-tts");
            case "mimo" -> model.equals("mimo-v2.5-tts");
            default -> false;
        };
    }

    public static boolean isStepAudio3(EntityMaid maid) {
        MaidAIChatManager manager = maid.getAiChatManager();
        TTSSite site = manager.getTTSSite();
        return site != null && "stepfun".equals(site.getApiType())
                && runtimeModel(manager.getTTSModel()).equals("stepaudio-3-tts");
    }

    private static String runtimeModel(String storedModel) {
        String value = VoicePresetSpec.decode(storedModel).runtimeValue();
        int separator = value.indexOf(':');
        return separator < 0 ? value : value.substring(0, separator);
    }

    /** Keep the current output shape close to every ordinary chat reply, including emotion OFF. */
    public static String turnReminder(EntityMaid maid) {
        MaidAIChatManager manager = maid.getAiChatManager();
        boolean emotion = TouhouAIFunConfig.TTS_EMOTION_CONTROL.get() && isSupported(maid);
        return ReplyPromptBuilder.reminder(manager.getChatLanguage(), manager.getTTSLanguage(),
                emotion, TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get());
    }
}
