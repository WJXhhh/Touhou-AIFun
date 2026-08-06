package com.wjx.touhou_aifun.compat.ai;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
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
                    + "Begin replies with one allowed `(emotion)` marker exactly as the output format requires.";
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
        String lang = locale.getDisplayLanguage();
        if (lang == null || lang.isEmpty() || lang.equals(tag)) {
            return null;
        }
        String country = locale.getDisplayCountry();
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

        String model = chatManager.getTTSModel();
        if (model == null) {
            return false;
        }
        // The "plan" site variants report apiType like "stepfun_plan" / "mimo_plan"; normalize that
        // suffix away so both the regular and plan sites are recognized. The model id may carry a
        // ":voice" suffix (e.g. "stepaudio-2.5-tts:yuanqishaonv"), which startsWith already tolerates.
        String apiType = ttsSite.getApiType();
        if (apiType.endsWith("_plan")) {
            apiType = apiType.substring(0, apiType.length() - "_plan".length());
        }
        return switch (apiType) {
            case "stepfun" -> model.startsWith("stepaudio-2.5-tts");
            case "mimo" -> model.startsWith("mimo-v2.5-tts");
            default -> false;
        };
    }

    /**
     * A deliberately short reminder appended as the final developer/system message on every normal
     * maid chat request. It is intentionally terse — the full contract (allowed markers, scope rules,
     * examples, expressiveness mandate) lives in the character system prompt; this only keeps the exact
     * output shape and a short "be expressive, re-mark" nudge close to the model's next response, since
     * a long trailing block tends to get skimmed.
     */
    @Nullable
    public static String turnReminder(EntityMaid maid) {
        MaidAIChatManager chatManager = maid.getAiChatManager();
        TTSSite site = chatManager.getTTSSite();
        boolean toggle = TouhouAIFunConfig.TTS_EMOTION_CONTROL.get();
        boolean supported = isSupported(maid);
        // Diagnostic: prints why emotion control is (in)active for this maid-chat request. Lets us see
        // the actual toggle / apiType / TTS model so a silent mismatch is obvious. Safe to remove later.
        TouhouAIFun.LOGGER.info("[emotion] toggle={} supported={} apiType={} ttsModel={} chatLang={} ttsLang={}",
                toggle, supported,
                site == null ? "null" : site.getApiType(),
                chatManager.getTTSModel(),
                chatManager.getChatLanguage(), chatManager.getTTSLanguage());
        if (!toggle || !supported) {
            return null;
        }

        if (chatManager.getChatLanguage().equals(chatManager.getTTSLanguage())) {
            // Same language: one reply only, no `---`, no duplicated copy. The display/TTS texts are
            // derived from this single body downstream.
            return """
                    Format reminder (skip while making tool calls): reply ONCE as one message — no `---`,
                    no duplicate. Open with a fitting `(emotion)` marker and RE-MARK whenever the mood
                    shifts: aim for 2+ different markers across a multi-sentence reply, and don't fall back
                    to `(平静)`. Singing must be `(唱歌)` as the ENTIRE reply.
                    """;
        }

        // Cross-language: the two sections DIFFER — Part 1 is the reply in the chat language, Part 2 is
        // its translation into the TTS language. Naming the TTS language keeps this reminder consistent
        // with the system-prompt contract instead of contradicting it.
        String ttsLanguageName = languageName(chatManager.getTTSLanguage());
        String ttsLang = ttsLanguageName != null ? ttsLanguageName : "the TTS language";

        if (TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get()) {
            return """
                    Format reminder (skip while making tool calls): output two sections split by a line of
                    only `---`. Part 1 = `(emotion)` + reply in the chat language; Part 2 = the SAME
                    `(emotion)` + its %s translation (translate, don't copy). RE-MARK on every mood shift
                    (aim for 2+ markers, in BOTH sections), don't fall back to `(平静)`. Singing must be
                    `(唱歌)` as the ENTIRE reply.
                    """.formatted(ttsLang);
        }

        return """
                Format reminder (skip while making tool calls): output two sections split by a line of
                only `---`. Part 1 = reply in the chat language with NO marker; Part 2 = `(emotion)` + its
                %s translation (translate, don't copy). RE-MARK on every mood shift in Part 2 (aim for 2+
                markers), don't fall back to `(平静)`. Singing must be `(唱歌)` as the ENTIRE reply.
                """.formatted(ttsLang);
    }
}
