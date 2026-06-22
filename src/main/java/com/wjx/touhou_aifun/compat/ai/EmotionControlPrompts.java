package com.wjx.touhou_aifun.compat.ai;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.service.tts.TTSSite;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.wjx.touhou_aifun.TouhouAIFun;
import com.wjx.touhou_aifun.config.TouhouAIFunConfig;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class EmotionControlPrompts {
    private EmotionControlPrompts() {
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
     * maid chat request. The full contract remains in the character system prompt; this only keeps
     * the exact output shape close to the model's next response.
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
                    FINAL TEXT FORMAT REMINDER (ignore this while making tool calls):
                    Output your reply EXACTLY ONCE as ONE plain-text message.
                    Begin it with one allowed `(emotion)` marker, immediately followed by the reply.
                    A marker stays in effect for every following sentence until you write a new one, so re-mark
                    when the mood changes partway. If you sing `(唱歌)`, the ENTIRE reply must be ONLY the song —
                    no spoken lead-in or follow-up; say anything else in a separate later reply, never in the same one.
                    Do NOT output a `---` separator and do NOT repeat or duplicate the reply.
                    An explicitly requested style wins: singing=`(唱歌)`, loud crying=`(嚎啕大哭)`, sobbing=`(抽泣)`.
                    """;
        }

        // Cross-language: the two sections DIFFER — Part 1 is the reply in the chat language, Part 2 is
        // its translation into the TTS language. Naming the TTS language keeps this reminder consistent
        // with the system-prompt contract instead of contradicting it.
        String ttsLanguageName = languageName(chatManager.getTTSLanguage());
        String ttsLang = ttsLanguageName != null ? ttsLanguageName : "the TTS language";

        if (TouhouAIFunConfig.TTS_EMOTION_IN_TEXT.get()) {
            return """
                    FINAL TEXT FORMAT REMINDER (ignore this while making tool calls):
                    For the final text reply, compose the reply ONCE in the chat language, choose one allowed `(emotion)`, then output exactly:
                    (emotion)REPLY
                    ---
                    (emotion)TRANSLATION
                    `---` MUST be on its own line. Both sections start with the SAME `(emotion)`.
                    Part 1 is REPLY in the chat language; Part 2 is its faithful translation into %s — translate the meaning, do NOT copy Part 1.
                    A marker stays in effect for the following sentences until changed; if the mood changes partway
                    (e.g. from comforting to teasing), put a new marker at that point in BOTH sections.
                    A song `(唱歌)` must be the WHOLE reply — never mix singing with spoken lines in one message.
                    Do not add any preface, explanation, or follow-up to only one section.
                    An explicitly requested style wins: singing=`(唱歌)`, loud crying=`(嚎啕大哭)`, sobbing=`(抽泣)`.
                    """.formatted(ttsLang);
        }

        return """
                FINAL TEXT FORMAT REMINDER (ignore this while making tool calls):
                For the final text reply, compose the visible reply ONCE in the chat language, choose one allowed `(emotion)`, then output exactly:
                REPLY
                ---
                (emotion)TRANSLATION
                `---` MUST be on its own line. Part 1 has no marker; Part 2 starts with one `(emotion)`.
                Part 1 is REPLY in the chat language; Part 2 is its faithful translation into %s — translate the meaning, do NOT copy Part 1.
                In Part 2 a marker stays in effect for the following sentences until changed; if the mood changes
                partway (e.g. from comforting to teasing), put a new marker at that point in Part 2.
                A song `(唱歌)` must be the WHOLE reply — never mix singing with spoken lines in one message.
                Do not add any preface, explanation, or follow-up to only one section.
                An explicitly requested style wins: singing=`(唱歌)`, loud crying=`(嚎啕大哭)`, sobbing=`(抽泣)`.
                """.formatted(ttsLang);
    }
}
